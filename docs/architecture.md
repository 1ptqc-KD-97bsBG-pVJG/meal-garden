# Meal Garden architecture

Meal Garden is a native Android application and a Node laptop companion for the food lifecycle: recipes, plans, acquisitions, pantry observations, cooking, batches, intake and nutrition. It is an experimental personal project in active development.

Code lives in this repository. Household records live outside it; see [households and data](households-and-data.md). A companion serves several isolated gardens, each with a household root, SQLite store, job queue, food graph and replaceable provider. Devices authenticate to exactly one garden. Pairing codes identify the garden on the laptop; authenticated phone requests cannot select another household.

Recipes, plans, kitchen configuration and profiles remain JSON files. Only ready recipes with structured ingredients and executable steps drive shopping demand and cooking cards. `scripts/build.py` builds derived files in the household's `generated/` directory. Generated indexes and cards are rebuildable; canonical recipes must be edited instead of their rendered copies.

Each garden's `.runtime/garden.sqlite` stores food-domain records, evidence events, content-addressed media, conversations, jobs and paired-device hashes. Products, purchases, pantry items and movements, batches, intake, assumptions, preferences and reactions form the [food graph](food-graph-2026-10-04.md). Operational conversations and jobs are evidence, not an independent pantry truth. A planned meal establishes intent, never cooking or consumption.

The domain layer owns arithmetic, validation, atomic transactions, idempotency and audit events. Context is compiled from household records for each task; model responses propose judgments through bounded domain operations. The current provider uses Codex app-server in the household directory. Providers start lazily. For data-home gardens, named native Codex permissions deny reads across the data home and allow only the current household. Single-root runs selecting a household under that layout infer the same boundary. Multi-garden runs also disable global plugins, apps, browser/computer tools and MCP servers because these could bypass filesystem permissions; configured external shopping jobs reject clearly until household-scoped tools exist. Single-household runs retain their established browser workflow. Hosted multi-user service still requires per-user account and process isolation.

The HTTP server provides pairing, authenticated snapshots and operations. The setup page is laptop-only. Design prototypes and APK downloads are restricted to the laptop, tailnet or the appropriate authorization path. Public prototypes come from this code folder; private prototypes come from the data home's workspace. Household uploads and media resolve in that garden's runtime, not another garden's files.

The Android app caches snapshots and outbox records for offline operation. Health views retain ranges, uncertain evidence and unset targets. Cook mode keeps amounts and appliance instructions at the point of use. Feedback and interaction timing record observations without an inference call.

External shopping tasks use explicitly configured household settings, inspect current external state, preserve unrelated rows and checkmarks, act once, and verify the result. Local generation is never evidence of an external sync. No shopping connection is invented for a household without shopping settings.

The [core design](core-design-2026-09-29.md) describes the working agent/domain boundary. The [Android guide](android-app.md) covers setup, builds and validation. Local inference, partner linking and notification policies remain future work.
