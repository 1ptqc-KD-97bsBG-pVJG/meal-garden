# Cooking page QA

Validate source content hash and renderer version; timestamps alone do not establish freshness. Use scripts/build.py to regenerate cards. Run browser checks for timer, checkoff, reset and responsive layout when changing behavior.

## Required Page Checks

- Valid `<!doctype html>`, `<title>`, and viewport meta tag.
- Self-contained CSS in the page; no external scripts, fonts, or network assets unless explicitly requested. Inline JavaScript is allowed when it powers local page behavior such as timers.
- Readable mobile layout with no table-dependent cooking instructions.
- Touch-friendly checkboxes for ingredient/components and cooking steps.
- Make the full safe surface of every ingredient and cooking-step card clickable, not only the checkbox. Timer controls, links, and other nested interactive controls must remain independent and must not toggle the task card.
- Use a task-first visual hierarchy in every checklist row:
  - Ingredient rows headline the ingredient or component name; quantities, prep state, and qualifiers are secondary description text.
  - Cooking rows headline the action being taken; start time, duration, method details, and completion cues are secondary description text.
  - Never use a raw amount or timestamp as the row headline when an ingredient or action name is available.
- Each checkbox must represent an action that can be checked immediately after doing it. Do not make one checkbox cover a long-running wait plus later follow-up work.
- Long-running work should be split into "start timer", "work while timer runs", and "return when timer ends" sections.
- Timer controls should be real buttons/countdowns, not static text such as "timer started".
- Timer controls inside checkbox labels must not accidentally toggle the parent checkbox when tapped.
- Timer completion should attempt sound and vibration when the browser/phone allows it, while clearly explaining that the page should stay open for alerts.
- Pages with checkboxes should include a sticky progress dock that remains visible while scrolling, updates when boxes are checked, and highlights the next unchecked action.
- When updating an older phone page, compare its progress dock against `templates/phone-recipe-page.html`; do not preserve an older dock implementation just because it already has a progress bar.
- Progress dock text should be descriptive. Prefer explicit task labels such as "Gather carrots" or "Start quinoa simmer" over raw quantities such as "1-2 medium".
- Progress dock timer text should update live while timers are running, not only when checkboxes change.
- Pages with checkboxes or timers should persist local state with best-effort browser storage: checked boxes, completed timers, and active timer end-times. If storage is blocked by the viewer, the page should say progress is session-only.
- Persistent state must be local to the page/device. Do not imply it writes back to project Markdown or syncs across devices unless a real sync mechanism exists.
- Parallel work should be explicit: tell the cook what to do during simmering, baking, resting, chilling, or cooling time.
- Put skip/branch paths before the main flow when the cook may already have major components prepared.
- Include executable quantities and settings for core components: liquid ratios, oven or air-fryer temperature, oil/seasoning amounts, flip/shake cues, and yield where relevant.
- Make hot-versus-cool handling explicit when warm components are served over greens or other delicate ingredients.
- Visible source/provenance panel:
  - Source type: reusable recipe or active-plan one-off.
  - Source file link.
  - Reusable recipe status.

