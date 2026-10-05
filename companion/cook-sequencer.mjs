const MINUTE_MS = 60_000;
const APPLIANCES = [
  ['duxtop', 'Duxtop'], ['ninja', 'Ninja'], ['stove', 'Stove'],
  ['oven', 'Oven'], ['microwave', 'Microwave'], ['air fryer', 'Air fryer'],
];

function appliance(label) {
  const head = (label ?? '').split('·')[0].trim().toLowerCase();
  const name = APPLIANCES.find(([prefix]) => head.startsWith(prefix))?.[1];
  // As in build.py, the stove can hold several pans. Counters do not lock.
  return name === 'Stove' || !head || /^(counter|worktop|prep|cutting board)\b/.test(head) ? null : name ?? head;
}

function nonnegative(value, name) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new RangeError(`${name} must be a finite nonnegative number`);
  }
  return value;
}

function timing(step, index) {
  for (const key of ['minutes', 'passive_minutes', 'start_minute', 'timer_minutes']) {
    if (step[key] != null) nonnegative(step[key], `steps[${index}].${key}`);
  }
  const passive = step.passive_minutes ?? 0;
  if (passive > (step.minutes ?? 0)) {
    throw new RangeError(`steps[${index}].passive_minutes exceeds minutes`);
  }
  if (step.equipment != null && typeof step.equipment !== 'string') {
    throw new TypeError(`steps[${index}].equipment must be a string`);
  }
  return {
    start: step.start_minute ?? null,
    minutes: step.minutes ?? null,
    passive,
    active: (step.minutes ?? 0) - passive,
    appliance: appliance(step.equipment),
  };
}

function overlaps(earlier, later) {
  if (earlier.start === null || earlier.minutes === null ||
      later.start === null || later.minutes === null || earlier.passive === 0) return false;
  const passiveStart = earlier.start + earlier.active;
  // Shared start times are used by current recipes for parallel prep. Serialize
  // their setup first, then place the later start inside the passive window.
  const laterStart = later.start === earlier.start ? passiveStart : later.start;
  return laterStart >= passiveStart && laterStart < earlier.start + earlier.minutes;
}

/**
 * Pure cook sequencing, with no clock, I/O or mutations.
 *
 * sequenceCook(steps, session, currentTime)
 * - All times are numeric milliseconds (epoch or a session-relative clock).
 *   Recipe durations/start_minute remain minutes; no fixed start-time gates.
 * - session is indexed like steps. Missing/null entries mean not started;
 *   otherwise use {status:'not_started'}, {status:'started', startedAt:T},
 *   or {status:'done', doneAt:T, startedAt?:T}.
 * - For a passive step, `start` confirms the hands-on setup is complete and
 *   starts its passive_minutes countdown. For an entirely hands-on step,
 *   `start` keeps it in now until an explicit finish (time alone never finishes it).
 * - Outputs use zero-based step indices: now/next are indices or null;
 *   running is [{step, endsAt}] sorted by deadline then index; waitingOn has
 *   the same shape or is null. next previews the first other unstarted step,
 *   including a blocked one; when now is null it previews the first unstarted.
 * - Expired passive timers unblock dependencies without modifying session or
 *   claiming that the cook confirmed doneness. Finish early cancels the timer.
 * - timer_minutes is only a reminder, not evidence of passive duration. A
 *   short reminder cannot release an appliance before passive_minutes elapses.
 * - Every earlier step is a dependency unless its timeline explicitly permits
 *   overlap. Overlapping unstarted steps can be bypassed when appliance-blocked.
 *   Missing timeline fields preserve written order without parallelism.
 */
export function sequenceCook(steps, session = [], currentTime = 0) {
  if (!Array.isArray(steps) || !Array.isArray(session)) {
    throw new TypeError('steps and session must be arrays');
  }
  nonnegative(currentTime, 'currentTime');
  if (session.length > steps.length) throw new RangeError('session has extra steps');
  const timings = steps.map((step, index) => {
    if (!step || typeof step !== 'object' || Array.isArray(step)) {
      throw new TypeError(`steps[${index}] must be an object`);
    }
    return timing(step, index);
  });
  const states = steps.map((_, index) => {
    const state = session[index] ?? {status: 'not_started'};
    if (!['not_started', 'started', 'done'].includes(state.status)) {
      throw new TypeError(`session[${index}] has an unknown status`);
    }
    for (const key of ['startedAt', 'doneAt']) {
      if (state[key] != null) {
        nonnegative(state[key], `session[${index}].${key}`);
        if (state[key] > currentTime) throw new RangeError(`${key} is in the future`);
      }
    }
    if (state.status === 'started' && state.startedAt == null) {
      throw new TypeError('started steps require startedAt');
    }
    if (state.status === 'done' && state.doneAt == null) {
      throw new TypeError('done steps require doneAt');
    }
    if (state.startedAt != null && state.doneAt != null && state.doneAt < state.startedAt) {
      throw new RangeError('doneAt precedes startedAt');
    }
    const endsAt = state.status === 'started' && timings[index].passive > 0
      ? state.startedAt + timings[index].passive * MINUTE_MS : null;
    return {status: state.status, endsAt,
      resolved: state.status === 'done' || (endsAt !== null && endsAt <= currentTime)};
  });
  const running = states.flatMap((state, step) =>
    state.endsAt !== null && !state.resolved ? [{step, endsAt: state.endsAt}] : [])
    .sort((a, b) => a.endsAt - b.endsAt || a.step - b.step);
  const pending = states.flatMap((state, index) =>
    state.status === 'not_started' ? [index] : []);
  const active = states.findIndex(state => state.status === 'started' && state.endsAt === null);

  const blockers = index => {
    const blocked = [];
    for (let prior = 0; prior < index; prior++) {
      if (!states[prior].resolved && !overlaps(timings[prior], timings[index])) blocked.push(prior);
    }
    if (timings[index].appliance !== null) {
      for (const timer of running) {
        if (timings[timer.step].appliance === timings[index].appliance &&
            !blocked.includes(timer.step)) blocked.push(timer.step);
      }
    }
    return blocked;
  };
  const eligible = active < 0 ? pending.find(index => blockers(index).length === 0) : active;
  const now = eligible ?? null;
  const next = pending.find(index => index !== now) ?? null;
  // A non-blocking, shorter timer must not be named instead of the timer that
  // actually unlocks work. With no pending work, wait for the final timers.
  const blockingSteps = new Set(pending.flatMap(blockers));
  const waitingOn = now === null
    ? running.find(timer => pending.length === 0 || blockingSteps.has(timer.step)) ?? null
    : null;
  return {now, running, next, waitingOn};
}
