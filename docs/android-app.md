# Android application

Meal Garden is a Kotlin/Jetpack Compose client with a Node laptop companion. The current app version is `0.1.1` (`versionCode 23`), application ID and namespace `app.mealgarden`. The neutral identity requires a new install and one pairing; older installed builds continue to use their existing companion tokens. No app is installed on a physical phone as part of repository preparation.

Install companion dependencies with Node 24 or later:

```sh
npm ci --prefix companion --ignore-scripts
MEAL_HOST=0.0.0.0 node companion/server.mjs
```

Without configuration, this starts a temporary copy of `sample-household/` and prints its location. Set `MEAL_HOME` for a private data home or `MEAL_DATA_DIR` for one household; see [households and data](households-and-data.md). `MEAL_PORT` defaults to 4783 and `MEAL_HOST` to loopback. The desktop launcher opens setup and starts the companion if necessary; use a trusted network or encrypted tailnet for phone access, with no public port forwarding.

Open `http://localhost:4783/setup` on the laptop. Each household has its own section, code and locally rendered QR. In the phone Connection screen scan the QR or enter the address and eight-digit code. Codes expire after ten minutes and are single-use. Pairing stores an encrypted token in Android Keystore; the server stores its hash in that household's devices table. Names, timezone and shopping labels arrive from the server. A household without shopping has no sync, purchases or connection-check jobs.

The companion checks Codex subscription authentication when a provider starts. No API key is required; inherited API key values are cleared for its child process. External integrations require an explicitly configured household and its existing authorized account workflow. Secrets, cookies and sign-in links must never be stored in food records.

The client supports a food timeline, recipe browsing, guided cooking with timers, grouped pantry assumption review, observed-food additions, portion reports, linked food-log nutrition, goal views, shopping decisions, receipt history, conversations and screen feedback. It caches snapshots and persists outbox writes for offline operation. A recipe or schedule does not imply cooking, intake or stock. Unknown quantities and nutrition remain unknown or estimated with evidence.

Cook mode shows amounts, settings and doneness cues at the current step. The Kotlin sequencer mirrors the companion sequencer and uses shared vectors in JVM tests. Passive timing can yield to another hands-on task without asserting the food is done. Portion destinations record actual reports. Homemade gram balances require a measured batch weight for mixed destinations; estimated equal portion weights remain assumptions.

The household's `.runtime/` contains the SQLite database, content-addressed media and uploads. Household `data/` holds file records such as screen feedback and cooking reports. APK builds live in the data home's `builds/`. `/lab` serves fictional public prototypes from the code folder; `/lab/private/` serves private workspace prototypes on the laptop and tailnet only.

For persistent macOS operation, use a LaunchAgent labelled `app.mealgarden.companion` that runs Node with this repository's `companion/server.mjs` and `MEAL_HOME` pointing to the private data home. Use `app.mealgarden.backup` for a separately configured private backup job. Machine paths and credentials belong in the installed private configuration, not a public plist. Check `/health` and launchd logs after relocation.

Build requirements are JDK 17, Android SDK platform 36, build-tools 35.0.0 and Gradle 8.14.3. `scripts/bootstrap-android.sh` obtains the build toolchain in ignored `.tools/`; it does not download models. Preserve the existing signing key in its private secret location. A distributable release will need a dedicated signing process.

```sh
node --test tests/*.test.mjs
python3 scripts/build.py
MEAL_BUILD_DIR=/path/to/private-builds scripts/build-android.sh
```

The bundled `snapshot.json` is generated from the sample unless `MEAL_DATA_DIR` selects a real household. It is a build intermediate. The default output is `MEAL_HOME/builds` when set, otherwise a temporary builds folder announced by the script. `MEAL_BUILD_DIR` overrides the destination. Build archives include the APK, checksum and UI source. Development keys and release artifacts stay outside public history.

Only one process should run Gradle at a time. Build `testDebugUnitTest assembleDebug assembleDebugAndroidTest`, install debug and test APKs on an emulator, and run:

```sh
adb -s emulator-5556 shell am instrument -w app.mealgarden.test/androidx.test.runner.AndroidJUnitRunner
```

Select `LiveConnectionTest` explicitly for pairing/chat verification; the controlled test companion uses a temporary copied household and a synthetic provider. Reverse the emulator port 14783 to the test server port 14783. A real subscription smoke test is a separate explicit operation. Never point tests at live food records. Emulator success does not verify physical camera optics, microphone accuracy, notification delivery after reboot or real external account persistence.

The provider boundary, job audit and validated dynamic tools are described in [architecture](architecture.md). Official protocol reference: [Codex app-server](https://developers.openai.com/codex/app-server/).
