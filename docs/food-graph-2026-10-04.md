# Food graph

The food graph links acquisitions, pantry lots, cooks, consumption and evidence. It lives in each household's `garden.sqlite`; recipes, plans and profiles remain files. The [DBML](food-graph.dbml) documents the conceptual schema; executable migrations and invariants live in `companion/store.mjs` and `companion/graph.mjs`.

| Record | Meaning |
| --- | --- |
| Product | Packaged, generic or homemade food, units, matching information and sourced nutrition |
| Purchase | Product, receipt, date, quantity and price |
| Pantry item | One lot at one location, linked to acquisition or preparation |
| Movement | Signed delta or observed count, with confidence and originating evidence |
| Batch | Actual recipe or informal cook, ingredient usage, yield, destination and nutrition snapshot |
| Intake | Food-log consumption linked to a batch or product |
| Assumption | Inferred statement, evidence and review status |
| Reaction | Attributed outcome, rating or aspect of a meal |
| Preference | Stated or inferred constraint, taste, portion, routine, process or goal |

Identity comes from `household.json`: household id and person id. Existing databases retain their identity rows; new stores seed from the household configuration. Person fields, profile paths and Android event actors derive from the configured person. Every authenticated HTTP operation uses the bearer token's garden.

A lot's balance is its latest count plus subsequent deltas. Without an opening amount or count, the balance is unknown; an unknown-sized use also prevents a false exact balance. Confidence reflects the relevant evidence. Amounts use grams, millilitres or count, with nutrition basis explicit. A transfer creates paired movements between lots. A correction reverses the mistaken movement without destroying its evidence.

Batch and intake operations run in transactions with idempotency keys. Reinterpreting a food-log entry replaces the earlier interpretation and reverses its movements so ingredients are not deducted twice. Homemade nutrition is a snapshot of the linked ingredients and yield. Computed describes the method; estimated quantities still imply uncertainty. Mixed destination balances need a measured net batch weight for meaningful grams.

Receipt import creates linked products, purchases and pantry lots. A reported cook can create an assumed batch, but a scheduled cook cannot. User counts supersede estimates. Assumptions appear in grouped review; confirmation and correction preserve provenance. Reactions are observations, while preferences are conclusions that can be superseded.

Nutrition uses label evidence first, free food databases where available, and bounded model or web lookup for unresolved products. Generic food estimates remain labelled. Each lookup records method, elapsed time and token use. A configured lookup is not proof that a physical package has been identified accurately.

Context packets include matching pantry products, recent batches, intake, open assumptions and hard constraints appropriate to the task. Agents have lookup and validated write tools. Arithmetic is done in domain code; the agent judges ambiguous descriptions and reports exactly what remains missing.

Known limits include restaurant meals, unweighed food, incomplete historical batch ingredients and unknown pantry openings. These retain unknown or assumed confidence. Full event replay, partner linking, notifications and a hosted multi-user service are future work, not implied by the current household fields.
