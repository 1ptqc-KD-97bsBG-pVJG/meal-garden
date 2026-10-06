# Meal Garden

A native Android app and laptop companion for recipes, meal plans, pantry evidence, cooking, food logs and nutrition. This is a personal project in active development. There is no license yet.

Code and personal records are separate. This repository contains a generic sample household, tests, technical documentation, food and cooking icons and a [fictional interface prototype](design-lab/index.html). Household data belongs in a private data home outside this folder.

With Node 24+ and Python 3:

```sh
npm ci --prefix companion --ignore-scripts
python3 scripts/build.py
node --test tests/*.test.mjs
MEAL_HOST=0.0.0.0 node companion/server.mjs
```

Use this phone-serving command on a trusted network or encrypted tailnet. The companion copies `sample-household/` to a temporary folder and announces it. Open `http://localhost:4783/setup` on the laptop. Install an APK you build yourself, then scan that household's QR from the phone Connection screen. Codex subscription sign-in is needed for assistant jobs; browsing the sample does not require a model call.

For private use, set `MEAL_HOME` to your data home and create a household with `scripts/add-household.mjs`. `MEAL_DATA_DIR` selects one household for scripts or tests. The sample has no shopping integration. See [households and data](docs/households-and-data.md) for configuration, pairing and backup boundaries.

Build the Android app with `scripts/bootstrap-android.sh` followed by `scripts/build-android.sh`. The default bundled snapshot uses sample records; build outputs stay outside this repository. Version `0.2.1` uses the neutral app ID `app.mealgarden`. Updates from `0.1.1` retain the existing pairing; builds from an older app identity need a new install and pairing.

[Architecture](docs/architecture.md) · [Android development](docs/android-app.md) · [Food graph](docs/food-graph-2026-10-04.md) · [Engineering rules](AGENTS.md)
