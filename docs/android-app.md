# Android application

Meal Garden is a Kotlin/Jetpack Compose client with a Node laptop companion. The current app version is `0.2.0` (`versionCode 24`), application ID and namespace `app.mealgarden`. The redesign keeps the neutral package identity and the existing snapshot, outbox, pairing and food graph engine. Installation on a physical phone is a separate action.

Install companion dependencies with Node 24 or later:

```sh
npm ci --prefix companion --ignore-scripts
MEAL_HOST=0.0.0.0 node companion/server.mjs
```

Without configuration, this starts a temporary copy of `sample-household/` and prints its location. Set `MEAL_HOME` for a private data home or `MEAL_DATA_DIR` for one household; see [households and data](households-and-data.md). `MEAL_PORT` defaults to 4783 and `MEAL_HOST` to loopback. The desktop launcher opens setup and starts the companion if necessary; use a trusted network or encrypted tailnet for phone access, with no public port forwarding.

Open `http://localhost:4783/setup` on the laptop. Each household has its own section, code and locally rendered QR. In the phone Connection screen scan the QR or enter the address and eight-digit code. Codes expire after ten minutes and are single-use. Pairing stores an encrypted token in Android Keystore; the server stores its hash in that household's devices table. Names, timezone and shopping labels arrive from the server. A household without shopping has no sync, purchases or connection-check jobs.

The companion checks Codex subscription authentication when a provider starts. No API key is required; inherited API key values are cleared for its child process. External integrations require an explicitly configured household and its existing authorized account workflow. Secrets, cookies and sign-in links must never be stored in food records.

The native shell has Today, Kitchen, Capture, Recipes and More. The note pencil and active timer strip remain available across screens. More contains Your app, Activity, Receipts, Preferences, Ask and Connection; Ask also opens full conversation history. Debug builds expose a component gallery for checking the shared paper surfaces, compact type, food illustrations and controls.

Today shows logged plates, one dinner suggestion, eating ranges, identified foods and weekly variety. Repeated dishes stack and nearby drinks attach to a plate; supplements remain in the log. These are projections of recorded captures. A recipe or schedule does not imply cooking, intake, leftovers or stock. Unknown quantities and nutrients remain unknown, and nutrition ranges retain their coverage and uncertainty.

Kitchen draws the fridge, drawers, freezer, pantry, counter and available appliances from pantry and household data. It groups quantities rather than duplicating items. Zone filters, a state key and inline item panels support condition, amount, location and receipt review while keeping the drawing accessible. Assumptions remain reviewable; a kitchen check offers a single Looks right action and individual corrections. A pantry with unknown contents stays unknown.

Recipes group ready dishes by meal type, with candidates and incomplete records behind a separate link. Only ready recipes with structured amounts and executable steps can start cooking. Saved variants switch as complete recipe records before a cook starts; local substitutions retain their own switches. Cook mode shows the full current instruction, amounts, appliance settings and doneness cues. The Kotlin sequencer mirrors the companion sequencer and uses shared vectors in JVM tests. Passive timing can yield to another hands-on task without asserting that food is done. The kitchen highlights the current equipment, or an actual running appliance while a prep step has none, and shows its matching timer. Portion destinations accept taps to add and minus controls to remove. Reports record what the user confirms; measured batch weight supports mixed destinations, while estimated equal portion weights remain assumptions.

Capture uses the native camera or gallery and supports several photos per entry, text-only logs, linked batch and product details, rating and one interpretation question at a time. Optional positive weight is saved as weight evidence in the capture note; blank or zero means no weight was given. Your app can show the weight field upfront and can add sequencer-driven appliance lanes or suggested cooking questions. Turning off the question suggestions keeps the question button available. Nutrition, food timeline, basket, weekly variety and shopping timeline modules also control their optional screen content. These display choices do not rewrite food evidence or household targets.

Cooking timers retain their existing scheduling, alarm, chime, vibration and snooze behavior. Starting a hands-off timer leaves it in the shared strip and lets the sequencer show the next available action. The full alarm view offers Done, +1 min and +5 min. Timer visibility does not depend on optional display modules.

The household's `.runtime/` contains the SQLite database, content-addressed media and uploads. Household `data/` holds file records such as screen feedback and cooking reports. APK builds live in the data home's `builds/`. `/lab` serves fictional public prototypes from the code folder; `/lab/private/` serves private workspace prototypes on the laptop and tailnet only.

For persistent macOS operation, use a LaunchAgent labelled `app.mealgarden.companion` that runs Node with this repository's `companion/server.mjs` and `MEAL_HOME` pointing to the private data home. Use `app.mealgarden.backup` for a separately configured private backup job. Machine paths and credentials belong in the installed private configuration, not a public plist. Check `/health` and launchd logs after relocation.

Build requirements are JDK 17, Android SDK platform 36, build-tools 35.0.0 and Gradle 8.14.3. `scripts/bootstrap-android.sh` obtains the build toolchain in ignored `.tools/`; it does not download models. Preserve the existing signing key in its private secret location. A distributable release will need a dedicated signing process.

```sh
node --test tests/*.test.mjs
python3 scripts/build.py
MEAL_BUILD_DIR=/path/to/private-builds scripts/build-android.sh
```

The bundled `snapshot.json` is generated from the sample unless `MEAL_DATA_DIR` selects a real household. It is a build intermediate. The default output is `MEAL_HOME/builds` when set, otherwise a temporary builds folder announced by the script. `MEAL_BUILD_DIR` overrides the destination. Build archives include the APK, checksum and UI source. Development keys and release artifacts stay outside public history.

Only one process should run Gradle at a time. Build `testDebugUnitTest assembleDebug assembleDebugAndroidTest`, install debug and test APKs on a dedicated emulator, and run the default suite with the companion-dependent classes excluded:

```sh
adb -s emulator-5556 shell am instrument -w \
  -e notClass app.mealgarden.LiveConnectionTest,app.mealgarden.CompanionPreviewTest \
  app.mealgarden.test/androidx.test.runner.AndroidJUnitRunner
```

The Gradle runner configuration excludes `LiveConnectionTest` and `CompanionPreviewTest` by default. These classes require an explicitly started test companion. A real subscription smoke test is a separate explicit operation.

For a reproducible interface walkthrough, start a second companion on port 14783 from the code folder. This example uses a synthetic provider and the companion's temporary copy of `sample-household/`; it does not start a model or use an external account:

```sh
MEAL_HOME= MEAL_DATA_DIR= node --input-type=module <<'JS'
import { EventEmitter } from 'node:events';
import { createCompanion } from './companion/server.mjs';
class PreviewProvider extends EventEmitter {
  async thread() { return 'preview-thread'; }
  async start() {
    setTimeout(() => {
      this.emit('event', { method: 'item/completed', params: {
        threadId: 'preview-thread', item: {
          id: 'preview-message', type: 'agentMessage', text: 'Kitchen connected.'
        }
      }});
      this.emit('event', { method: 'turn/completed', params: {
        threadId: 'preview-thread', turn: { status: 'completed' }
      }});
    }, 100);
    return { turn: { id: 'preview-turn' } };
  }
  respond() {}
  close() {}
}
const app = createCompanion({ provider: new PreviewProvider() });
app.server.listen(14783, '127.0.0.1');
process.on('SIGINT', () => { app.close(); process.exit(0); });
JS
```

For a walkthrough of another household, replace the default sample with a verified disposable copy using `createCompanion({root: '/path/to/copied-household', provider: new PreviewProvider()})`. Take a consistent SQLite backup and copy the corresponding media; never serve the live records to a test. Keep screenshots of private records outside the code folder.

Reverse the loopback port and clear only the dedicated emulator's app state before each phase:

```sh
adb -s emulator-5556 reverse tcp:14783 tcp:14783
adb -s emulator-5556 shell pm clear app.mealgarden
adb -s emulator-5556 shell am instrument -w \
  -e class app.mealgarden.CompanionPreviewTest \
  -e notClass app.mealgarden.LiveConnectionTest \
  -e copiedCompanion true -e phase 1 \
  -e endpoint http://127.0.0.1:14783 \
  app.mealgarden.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 exec-out run-as app.mealgarden \
  tar -C files/preview-screenshots -cf - . > /path/to/private-artifacts/phase1.tar
```

Repeat with phases 1–8: components; shell; Today and health; Kitchen; recipes and cooking; capture and food log; More; notes and gallery. The test requires `copiedCompanion=true`, the fixed loopback endpoint, an unpaired app and no pending food, goal or note writes. It pairs the test device, opens the relevant views and records screenshots without saving food observations. It can start local cooking timers, and photo-dependent views require photos in the copied data. Pairing and telemetry stay within the copied companion. Discarded drafts are removed and the test pairing token is cleared at the end.

Select `LiveConnectionTest` explicitly with `-e class app.mealgarden.LiveConnectionTest -e notClass app.mealgarden.CompanionPreviewTest -e temporaryCompanionPort 14783` for synthetic pairing and chat verification against the same loopback companion. Emulator checks do not establish physical camera optics, microphone accuracy, notification delivery after reboot or real external account persistence.

The provider boundary, job audit and validated dynamic tools are described in [architecture](architecture.md). Official protocol reference: [Codex app-server](https://developers.openai.com/codex/app-server/).
