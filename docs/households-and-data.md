# Households and data

Code and household records have separate homes. Set `MEAL_HOME` to a private data home:

```text
<data home>/
  households/<id>/
    household.json  assistant-notes.md
    profile/ recipes/ data/ generated/ docs/
    .runtime/          SQLite, media, uploads and attachments
  workspace/           private development instructions and material
  builds/              APK and release archives
```

The data home can have its own private Git repository for readable household records and project material. Ignore `.runtime/` and `builds/` there. Never push that private repository. Database and media backups are separate from Git.

`MEAL_DATA_DIR` selects one household for scripts or a single-household companion. `createCompanion({root})` keeps this single-household interface for tests. With neither setting, the companion copies `sample-household/` to a temporary directory and announces its location. The sample contains generic recipes, a small illustrative plan, an unconfirmed inventory, a profile and kitchen; it has no shopping integration. Building without `MEAL_DATA_DIR` uses the sample.

Create another household with:

```sh
MEAL_HOME=/path/to/private-data node scripts/add-household.mjs second "Second kitchen" "Alex"
```

The command refuses an existing id. Review the new `household.json`, profile and equipment. Personal records start empty. Restart the companion to discover the new household. Open `http://localhost:4783/setup` on the laptop for that kitchen's pairing code or QR, then scan or enter it in the phone's Connection screen. A code pairs one device to one household. Do not share the code publicly.

Each household has its own devices table, jobs, messages, evidence, runtime media and provider. A bearer token is resolved across gardens; subsequent routes use only that garden. The phone never chooses a household id in a route. Household names arrive in pairing and snapshots. A phone can be re-paired to switch households; partner switching and permissions are not implemented.

`household.json` has `schema_version`, `id`, `name`, `person` (`id`, `name`, optional `pronouns`), `timezone`, optional `shopping` and optional `assistant_notes`. Identity determines `profile/<person.id>.json` and `<person.id>:android` actors. Missing configuration gets safe test defaults (`home`, `owner`, `You`, UTC). Missing pronouns use they. Shopping settings describe an explicitly supported service, list and store; absence disables sync, purchases and connection jobs. Private notes extend the generic contract. Paths must stay inside the household. Named provider permissions restrict household filesystem access, including symlink and canonical aliases. Multi-household runs disable global external tools; shopping sync, connection checks and purchase refresh reject until scoped integrations are provided, even when shopping settings exist. A one-household run retains the configured browser workflow.

Back up code history, the private data home and build artifacts to a trusted destination. Use SQLite's `.backup` API for consistent database copies, then verify `pragma integrity_check`, row counts and media checksums. Stop writers and checkpoint before a filesystem relocation. Copy and verify before removing originals; preserve device hashes so existing app connections survive. Test restores against copies, never rerun one-time import scripts against live records. Keep signing keys in the existing secret store and back them up separately through a private process.

For a later many-user service, a household registry would replace scanning folders. Storage could remain one database per household or become a shared database keyed by `household_id`; media would move to object storage. Per-user sign-in and explicit membership permissions would replace laptop codes, and providers would need enforced tenant isolation. These are future deployment options, not current capabilities.
