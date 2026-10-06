import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { iconFor } from '../design-lab/icons/match.js';

const root = new URL('../', import.meta.url);
const catalog = JSON.parse(await readFile(new URL('design-lab/icons/icons.json', root), 'utf8'));
const sprite = await readFile(new URL('design-lab/icons/food-icons.svg', root), 'utf8');
const specificIds = new Set(catalog.icons.filter(icon => !icon.fallback).map(icon => icon.id));
const categoryNames = ['vegetable','fruit','grain','legume','protein','dairy','sauce','nut-seed','drink','sweet','supplement','unknown'];

test('longest whole phrase wins, including sauces and green onions', () => {
  for (const [name, id] of [
    ['fresh green onions', 'food-green-onion'],
    ['rice vinegar', 'food-vinegar'],
    ['low-sodium soy sauce with garlic', 'food-soy-sauce'],
    ['Prego tomato sauce with olive oil and garlic', 'food-tomato-sauce'],
    ['extra-virgin olive oil', 'food-olive-oil'],
    ['fresh-ground peanut butter (likely peanuts only)', 'food-peanut-butter'],
    ['Milk or unsweetened soy milk', 'food-soy-milk'],
    ['pumpkin and sunflower seeds', 'food-pumpkin-seed'],
    ['preferred sesame or bowl dipping sauce', 'food-dressing'],
    ['Chocolate-frosted Boston cream donut', 'food-donut'],
    ['Banana, chia, walnut, and peanut-butter yogurt bowl', 'food-yogurt-bowl'],
  ]) assert.equal(iconFor(name), id, name);
});

test('matching normalizes case, punctuation, accents and whitespace', () => {
  assert.equal(iconFor('  BABY\nSPINACH  '), 'food-spinach');
  assert.equal(iconFor('Crimini–mushrooms'), 'food-mushroom');
  assert.equal(iconFor('Néstlé milk chocolate morsels'), 'food-chocolate');
  assert.equal(iconFor('iced white chocolate almond-milk matcha'), 'food-matcha');
});

test('category fallback follows specific food matches; no substrings', () => {
  assert.equal(iconFor('fruit snack with banana'), 'food-banana');
  assert.equal(iconFor('unlisted fruit'), 'food-fruit');
  assert.equal(iconFor('unlisted bean'), 'food-legume');
  assert.equal(iconFor('unlisted supplement'), 'food-supplement');
  for (const value of ['riced', 'iceberg', 'nutmeg', 'salted', 'milkweed', '', '???', null, undefined, 42, {}]) {
    assert.equal(iconFor(value), 'food-unknown', String(value));
  }
  for (const category of categoryNames) assert.equal(iconFor(category), catalog.categoryFallbacks[category], category);
});

test('every catalog entry has a unique 24px symbol and at most three palette colors', () => {
  const entries = [...catalog.icons, ...catalog.activities];
  const ids = entries.map(icon => icon.id);
  assert.equal(new Set(ids).size, ids.length);
  assert.deepEqual(Object.keys(catalog.categoryFallbacks), categoryNames);
  const symbols = [...sprite.matchAll(/<symbol id="([^"]+)" viewBox="0 0 24 24">([\s\S]*?)<\/symbol>/g)];
  assert.deepEqual(new Set(symbols.map(match => match[1])), new Set(ids));
  const palette = new Set(['#2C2721','#F7F1E6','#2F5A3F','#7FA36B','#E0A034','#D9772F','#C24E3A','#7FA7C4','#6B6155','#A2968A']);
  for (const [,id,body] of symbols) {
    const colors = new Set(body.match(/#[\da-f]{6}/gi));
    assert.ok(colors.size >= 2 && colors.size <= 3, `${id}: ${colors.size} colors`);
    for (const color of colors) assert.ok(palette.has(color), `${id}: ${color}`);
    const darkColors = new Set(body.replaceAll('var(--food-icon-ink, #2C2721)', '#F7F1E6').match(/#[\da-f]{6}/gi));
    assert.ok(darkColors.size <= 3, `${id}: ${darkColors.size} dark-background colors`);
  }
  for (const comment of sprite.matchAll(/<!--([\s\S]*?)-->/g)) assert.doesNotMatch(comment[1], /--/, 'valid XML comment');
  assert.doesNotMatch(sprite, /<image|<script|<foreignObject|href=|url\(/i);
  for (const icon of catalog.icons) {
    assert.ok(icon.matchWords.length, icon.id);
    for (const word of icon.matchWords) assert.equal(word, word.toLowerCase(), icon.id);
  }
  assert.deepEqual(catalog.activities.map(icon => icon.id), ['act-heat','act-chop','act-mix','act-wait','act-boil','act-roast','act-microwave','act-press','act-assemble','act-wash']);
});

test('sample recipe ingredients resolve to a specific food or an explicit category fallback', async () => {
  const folders = await readdir(new URL('sample-household/recipes/', root), { withFileTypes: true });
  let ready = 0, ingredients = 0;
  for (const folder of folders.filter(folder => folder.isDirectory())) {
    const recipe = JSON.parse(await readFile(new URL(`sample-household/recipes/${folder.name}/recipe.json`, root), 'utf8'));
    if (recipe.readiness !== 'ready') continue;
    ready++;
    for (const ingredient of recipe.ingredients) {
      ingredients++;
      const fallback = {Water: "food-drink", Apple: "food-fruit", "Lemon juice": "food-drink"};
      if (Object.hasOwn(fallback, ingredient.name)) assert.equal(iconFor(ingredient.name), fallback[ingredient.name], ingredient.name);
      else assert.ok(specificIds.has(iconFor(ingredient.name)), `${folder.name}: ${ingredient.name}`);
    }
  }
  assert.ok(ready > 0 && ingredients > 0);
});

test('sample pantry and food-log names resolve to specific foods or their declared catalog fallback', async () => {
  const data = JSON.parse(await readFile(new URL('design-lab/concepts/12-unified-v2/data.json', root), 'utf8'));
  // Fictional foods without their own drawing use an appropriate category.
  const fallback = {
    Hummus: 'food-legume', Seitan: 'food-protein', 'Pea soup': 'food-legume',
    'Baked squash': 'food-vegetable', Grapes: 'food-fruit', Pears: 'food-fruit',
    Fennel: 'food-vegetable', Leeks: 'food-vegetable', Lemons: 'food-fruit', Radishes: 'food-vegetable',
    'Frozen sweetcorn': 'food-corn', 'Rye rolls': 'food-grain', Cornmeal: 'food-grain',
    Tahini: 'food-nut-seed', Apples: 'food-fruit', Apple: 'food-fruit',
  };
  fallback['Sunflower seeds'] = 'food-nut-seed';
  fallback['Mint tea'] = 'food-drink';
  const check = name => Object.hasOwn(fallback, name) ? assert.equal(iconFor(name), fallback[name], name) : assert.ok(specificIds.has(iconFor(name)), name);
  for (const item of data.kitchenReset.items) {
    for (const name of [item.name, item.fullName].filter(Boolean)) check(name);
  }
  for (const row of data.foodLog) {
    check(row.title);
    for (const item of row.items) check(item.name);
  }
});


test('invented dishes choose a meaningful food/category without substring collisions', () => {
  for (const [name, expected] of [
    ['Cloud pear snack', 'food-fruit'], ['Sunrise toast triangles', 'food-grain'],
    ['Silver quinoa bake', 'food-quinoa'], ['Blue banana pudding', 'food-banana'],
    ['Acorn snack pouch', 'food-nut-seed'], ['Golden smoothie', 'food-drink'],
    ['Fennel moon soup', 'food-vegetable'], ['Amber seitan strips', 'food-protein'],
    ['Cloud kefir pot', 'food-dairy'], ['Twilight cookie', 'food-sweet'],
    ['Pearlescent milkweed', 'food-unknown'], ['Orbital mystery', 'food-unknown'],
    ['Unknown pear', 'food-fruit'], ['Unknown tea', 'food-drink'], ['Unknown snack', 'food-nut-seed'],
  ]) assert.equal(iconFor(name), expected, name);
});

test('Android catalog is generated from the exact shared matcher metadata', async () => {
  const { execFileSync } = await import('node:child_process');
  execFileSync(process.execPath, ['scripts/generate-food-icons.mjs', '--check'], { cwd: new URL('../', import.meta.url) });
});


test('native food and activity drawings stay faithful to the shared SVG source', async () => {
  const { execFileSync } = await import('node:child_process');
  execFileSync('python3', ['scripts/generate-food-drawables.py', '--check'], { cwd: new URL('../', import.meta.url) });
});
