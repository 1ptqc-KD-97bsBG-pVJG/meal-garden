# Core design

Working direction: structured records, compiled task context and a replaceable agent loop. The filename preserves the design document's identity; this public edition describes system behavior rather than a person's historical decisions.

The deterministic domain layer owns facts and invariants. An agent interprets captures, reconciles uncertain evidence, develops recipes, plans meals and proposes corrections. The provider is replaceable; the current implementation uses Codex app-server. No local model or alternative runtime is required to run the sample.

Each household has its own data root and SQLite database. Food events preserve attribution, effective time, recorded time and supporting evidence. Media is content-addressed. Conversations, queued jobs, questions and task outcomes support operations without becoming canonical pantry facts. Files remain canonical for recipes, plans, profile and kitchen configuration.

Tasks declare their purpose and permitted effects. Before a call, the companion compiles the relevant profile, hard constraints, pantry, batches, intake, assumptions and recipe records. Standing instructions combine the generic recipe and agent contract with household identity, pronouns, shopping configuration and an optional private notes file. Missing pronouns default to they. The provider's working directory is the household folder.

Read before assuming: use compiled context and domain lookup tools first. Missing information remains unknown or becomes an explicit assumption with evidence. Estimates must not be presented as confirmed measurements. Repeated observations can inform preferences, but inferred preferences remain distinguishable from stated constraints.

Domain writes are validated, transactional and idempotent. Corrections append reversing or compensating events instead of erasing history. A retry must not deduct the same ingredient twice. Exact inverses are preferred where safe; later dependent changes may require a reviewed compensating repair.

Recipe identity, source method, readiness and testing history remain separate. Imported candidates preserve source evidence until normalized. One-cook substitutions create variants; lasting changes require an intended revision. Executable steps contain quantities, heat settings, timing, doneness cues, capacity and storage guidance at the point of use.

The phone captures observations and queues offline work. Intent and reality remain separate: schedules do not prove cooking, leftovers or intake. Health calculations expose nutrition sources and uncertainty. Goal ordering and numeric targets come from household evidence, never a universal energy prescription.

External effects follow read, act, verify. Preserve unrelated shopping rows and checked items. Authentication, cookies, sign-in links and incidental secrets do not belong in household records or the repository. Credentials remain managed by their existing tools.

Data remains inspectable and portable. Statistical calibration, richer workflow orchestration, local inference, notifications and shared-household permissions can be added after measured product use identifies a need. They are not selected merely because the architecture leaves room for them.
