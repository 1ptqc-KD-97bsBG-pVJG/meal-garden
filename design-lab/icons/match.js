// Punctuation is a separator: “extra-firm” and “extra firm” are equivalent.
// The factory takes already-loaded metadata; matching itself performs no I/O.
function createIconMatcher(catalog) {
  const normalize = value => typeof value === 'string'
    ? value.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/\s+/g, ' ')
    : '';
  const entries = fallback => catalog.icons
    .filter(icon => icon.id !== catalog.categoryFallbacks.unknown && Boolean(icon.fallback) === fallback)
    .flatMap(icon => icon.matchWords.map(word => ({ id: icon.id, word: normalize(word), weak: (icon.weakMatchWords ?? []).includes(word) })))
    .sort((a, b) => Number(a.weak) - Number(b.weak) || b.word.length - a.word.length);
  const specific = entries(false), categories = entries(true);
  const unknown = catalog.categoryFallbacks.unknown;

  /** Longest whole-word/phrase alias wins: specific, category, unknown.
   * Weak snack aliases follow other categories; generic unknown is last.
   * Equal-length ties follow the stable catalog order. */
  return function iconFor(name) {
    const words = ` ${normalize(name)} `;
    return specific.find(entry => words.includes(` ${entry.word} `))?.id
      ?? categories.find(entry => words.includes(` ${entry.word} `))?.id
      ?? unknown;
  };
}

// Node: import { iconFor } from './match.js' (or require it).
// Browser: load this classic script, fetch icons.json, then call the factory.
if (typeof module !== 'undefined' && module.exports) {
  exports.createIconMatcher = createIconMatcher;
  exports.iconFor = createIconMatcher(require('./icons.json'));
} else {
  globalThis.FoodIcons = Object.freeze({ createIconMatcher });
}
