import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {sequenceCook} from '../companion/cook-sequencer.mjs';

const vectors = JSON.parse(fs.readFileSync(new URL('./fixtures/cook-sequencer-vectors.json', import.meta.url)));
const minute = 60_000;
const freeze = value => {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freeze);
    Object.freeze(value);
  }
  return value;
};

for (const vector of vectors) {
  test(`shared vector: ${vector.name}`, () => {
    let clock = vector.initialTime ?? 0;
    const session = vector.steps.map(() => ({status: 'not_started'}));
    const steps = freeze(structuredClone(vector.steps));
    for (const [actionIndex, action] of vector.actions.entries()) {
      const context = `${vector.name}, action ${actionIndex}: ${JSON.stringify(action)}`;
      if (action.type === 'advance') {
        assert.ok(action.time >= clock, context);
        clock = action.time;
      } else if (action.type === 'start') {
        assert.equal(sequenceCook(steps, session, clock).now, action.step, context);
        assert.equal(session[action.step].status, 'not_started', context);
        session[action.step] = {status: 'started', startedAt: clock};
      } else if (action.type === 'finish') {
        assert.equal(session[action.step].status, 'started', context);
        session[action.step] = {...session[action.step], status: 'done', doneAt: clock};
      } else {
        assert.fail(`Unknown action type: ${action.type}`);
      }
      const snapshot = freeze(structuredClone(session));
      const actual = sequenceCook(steps, snapshot, clock);
      assert.deepEqual(actual, action.expected, context);
      assert.deepEqual(sequenceCook(steps, snapshot, clock), actual, 'deterministic');
      assert.deepEqual(snapshot, session, 'session is untouched');
      assert.deepEqual(steps, vector.steps, 'recipe is untouched');
      if (actual.now !== null) assert.equal(actual.waitingOn, null, 'never wait during hands-on work');
    }
  });
}

function simulate(steps) {
  const session = steps.map(() => ({status: 'not_started'}));
  const starts = [], parallel = [];
  let clock = 0;
  const finishExpired = () => {
    for (const [index, state] of session.entries()) {
      if (state.status === 'started' && (steps[index].passive_minutes ?? 0) > 0 &&
          state.startedAt + steps[index].passive_minutes * minute <= clock) {
        session[index] = {...state, status: 'done', doneAt: clock};
      }
    }
  };
  for (let turns = 0; session.some(state => state.status !== 'done'); turns++) {
    assert.ok(turns < steps.length * 6 + 1, 'bounded simulation cannot deadlock');
    const output = sequenceCook(steps, session, clock);
    if (output.now !== null) {
      const index = output.now;
      if (session[index].status === 'not_started') {
        if (output.running.length) parallel.push({step: index, during: output.running.map(t => t.step)});
        starts.push(index);
        session[index] = {status: 'started', startedAt: clock};
      } else {
        assert.equal(session[index].status, 'started');
        assert.equal(steps[index].passive_minutes ?? 0, 0, 'passive work is never current hands-on');
        clock += (steps[index].minutes ?? 0) * minute;
        session[index] = {...session[index], status: 'done', doneAt: clock};
        finishExpired();
      }
    } else {
      assert.ok(output.waitingOn, 'unfinished recipe must offer work or a timer');
      assert.ok(output.waitingOn.endsAt > clock, 'waiting must advance time');
      clock = output.waitingOn.endsAt;
      finishExpired();
    }
  }
  assert.equal(starts.length, steps.length);
  assert.equal(new Set(starts).size, steps.length, 'each step starts exactly once, as returned by now');
  assert.deepEqual(sequenceCook(steps, session, clock), {now: null, running: [], next: null, waitingOn: null});
  return {starts, parallel};
}

const recipesRoot = new URL('../sample-household/recipes/', import.meta.url);
const readyRecipes = fs.readdirSync(recipesRoot, {withFileTypes: true})
  .filter(entry => entry.isDirectory())
  .flatMap(entry => {
    const filename = new URL(`${entry.name}/recipe.json`, recipesRoot);
    if (!fs.existsSync(filename)) return [];
    const recipe = JSON.parse(fs.readFileSync(filename));
    return recipe.readiness === 'ready' ? [recipe] : [];
  }).sort((a, b) => a.id.localeCompare(b.id));

test('the start-to-finish suite discovers ready recipes instead of keeping a duplicate catalog', () => {
  assert.ok(readyRecipes.length > 0);
});

for (const recipe of readyRecipes) {
  test(`ready recipe completes without deadlock: ${recipe.id}`, t => {
    const {starts, parallel} = simulate(freeze(structuredClone(recipe.steps)));
    t.diagnostic(parallel.length ? `Parallel: ${parallel.map(p => recipe.steps[p.step].title).join('; ')}` : 'Parallel: none');
    if (!recipe.steps.some(step => step.start_minute != null && step.minutes != null && step.passive_minutes > 0)) {
      assert.deepEqual(starts, recipe.steps.map((_, index) => index), 'thin data follows written order');
      assert.deepEqual(parallel, []);
    }
  });
}

test('time before the first state change is equivalent to a shifted session clock', () => {
  const steps = vectors[0].steps;
  const session = [{status: 'started', startedAt: 0}];
  const offset = 1_800_000_000_000;
  const expected = sequenceCook(steps, session, 30_000);
  const shifted = sequenceCook(steps, [{status: 'started', startedAt: offset}], offset + 30_000);
  assert.deepEqual(shifted, {...expected,
    running: expected.running.map(t => ({...t, endsAt: t.endsAt + offset}))});
});

test('all exclusive appliances and aliases hold their lock until finish or expiry', () => {
  for (const [first, second] of [
    ['Duxtop 9120MC', 'duxtop'], ['Ninja DT551', 'Ninja'], ['Air fryer', 'air fryer'],
    ['Oven · 425°F', 'oven'], ['Microwave · high', 'microwave'],
  ]) {
    const steps = [
      {title: 'Heat', equipment: first, start_minute: 0, minutes: 5, passive_minutes: 4},
      {title: 'Heat again', equipment: second, start_minute: 1, minutes: 1},
    ];
    const session = [{status: 'started', startedAt: 0}];
    assert.deepEqual(sequenceCook(steps, session, 0), {
      now: null, running: [{step: 0, endsAt: 240000}], next: 1, waitingOn: {step: 0, endsAt: 240000},
    }, first);
    assert.equal(sequenceCook(steps, session, 240000).now, 1, first);
    assert.equal(sequenceCook(steps, [{status: 'done', doneAt: 1000}], 1000).now, 1, first);
  }
});

test('malformed timing and session data fails explicitly instead of producing a bad wait', () => {
  const step = {title: 'Cook', minutes: 5, passive_minutes: 4, start_minute: 0};
  for (const bad of [
    {...step, minutes: -1}, {...step, passive_minutes: 6}, {...step, start_minute: NaN},
    {...step, minutes: '5'}, {...step, equipment: []}, {passive_minutes: 1},
  ]) assert.throws(() => sequenceCook([bad], [], 0), {name: /RangeError|TypeError/});
  for (const session of [
    [{status: 'mystery'}], [{status: 'started'}], [{status: 'done'}],
    [{status: 'started', startedAt: 1}], [{status: 'done', doneAt: -1}],
    [{status: 'done', startedAt: 2, doneAt: 1}], [{status: 'not_started'}, null],
  ]) assert.throws(() => sequenceCook([step], session, session[0]?.startedAt === 2 ? 3 : 0));
  assert.throws(() => sequenceCook([step], [], Infinity));
  assert.throws(() => sequenceCook({}, [], 0));
});

test('returned objects do not share mutable state between calls', () => {
  const steps = vectors[0].steps;
  const session = [{status: 'started', startedAt: 0}];
  const output = sequenceCook(steps, session, 0);
  output.running[0].endsAt = 0;
  assert.equal(sequenceCook(steps, session, 0).running[0].endsAt, 1440000);
});
