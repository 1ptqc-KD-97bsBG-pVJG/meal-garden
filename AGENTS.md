# Meal Garden engineering

Read README.md, docs/architecture.md and docs/households-and-data.md for entry points. For agent/runtime work read docs/core-design-2026-09-29.md and docs/food-graph-2026-10-04.md.

Owner-specific instructions live in the household folder and, for project work, in `<data home>/workspace/`. Code must use household.json for identity, person, pronouns, timezone, store and list settings. Do not import another household's preferences or targets.

- recipes/*/recipe.json in a household are canonical recipe sources. Only readiness=ready with structured ingredients and executable steps may drive automated shopping or current cards. Candidates preserve evidence until reviewed and normalized.
- Plans record intent. Never infer cooked status, intake or leftovers from a schedule. An unconfirmed inventory with empty lots does not prove an empty kitchen.
- Preserve dates, attribution, confidence and the distinction between testing goals and actual observations. Runtime messages/jobs are evidence, not canonical pantry facts.
- Run python3 scripts/build.py after recipe, plan or template changes, with MEAL_DATA_DIR selecting the relevant copied household. generated/ is rebuildable; edit canonical records.
- Temporary substitutions belong in meal-specific variants. Lasting changes require an intended revision. Rebuild affected demand/cards and verify any requested external sync.
- Report actual external outcomes accurately; never claim sync from local generation. Preserve unrelated shopping rows and checkmarks. Do not store credentials, cookies or sign-in links in files.
- Tests and development use temporary copies. Never rerun one-time migration/setup scripts on live state. Storage changes preserve working data, use verified backups and pass relevant validation.
- Run node --test tests/*.test.mjs and scripts/build-android.sh for relevant changes. Only one process runs Gradle at a time. Physical phone installation is a separate user action.
- Keep personal records, local machine paths, screenshots, private project material and build artifacts outside the code folder. Verify the tracked tree with scripts/check-public.mjs and a private terms file before publication.
- Do not install or download local models without a new request. Never publish this repository or its private data without explicit authorization.
