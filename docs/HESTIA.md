# Hestia (smart home)

Hestia ("Keeper of the hearth") is Zeus's smart-home screen. It covers smart lights and plugs, for example AiDot (Linkind and similar).
AiDot has no public API, so the devices are linked into the Google Home app. Zeus then reaches them through the
**Google Home APIs for Android**: the Device and Structure APIs, with no commissioning.

## SDK

| What | Value | Source |
|---|---|---|
| Release | Home APIs **1.11** (GA), 2026-09-23 | https://developers.home.google.com/apis/android/release-notes |
| Artifacts | `com.google.android.gms:play-services-home:17.1.0`, `com.google.android.gms:play-services-home-types:17.1.0` | `home.android.sdk_1_11_0.zip` in the public bucket `gs://home_sdk_android` (https://storage.googleapis.com/home_sdk_android/home.android.sdk_1_11_0.zip). The release notes link it at https://console.cloud.google.com/storage/browser/home_sdk_android?project=home-api-public-beta |
| Hosting | Not on Google's Maven (Google Maven only has the old `play-services-home` 16.0.0). Not committed. To build the real backend, unzip the SDK (check the sha256 below) and copy its Maven tree so `app/libs/home-sdk/com/google/android/gms/play-services-home/17.1.0/` exists; `settings.gradle.kts` then adds it as a local Maven repo limited to those two modules and the build compiles `app/src/homeSdk`. Without it the build compiles the stub in `app/src/homeStub` (Hestia shows "not connected"), which is what CI does. | https://developers.home.google.com/apis/android/sdk ("download and host the libraries locally") |
| zip sha256 | `5a75ecfffecdcf87c660c3a9c67924baa05466b1eaeb952e7219d29abaf98e04` (192 MB, javadoc jars not vendored) | |
| AAR sha256 | home `f4024b0f…4a81e`, types `44bb79c1…7b97520` | |
| Google Play services | 26.36.25 or later for 1.11 (`GoogleHomeBackend.MIN_GMS`; the SDK's own internal floor is 244400000) | release notes |
| Kotlin | 1.11 is built with Kotlin 2.4.0. Zeus compiles with 2.2.21 and `-Xskip-metadata-version-check`, the workaround Google gives. | release notes, 1.11 "Important updates" |
| Coroutines | Since 1.10.1 the app must name `kotlinx-coroutines-core/-android/-play-services` itself, or you get a blocking runtime error. Pinned at 1.11.0, the same as Google's sample app. | release notes; https://github.com/google-home/google-home-api-sample-app-android (`gradle/libs.versions.toml`) |
| minSdk | AAR manifest minSdk 24. Zeus's minSdk is 31. Test phones must run Android 10 or later. | AAR manifest; SDK page |

OAuth client (Cloud project `your-gcp-project`): Google Auth Platform External, in Testing,
test user owner@example.com, no scopes. Android OAuth client for `dev.phonerecall` + the debug SHA-1, client ID
`YOUR_ANDROID_OAUTH_CLIENT_ID.apps.googleusercontent.com` (public, not a secret). It is **not in the code**:
Android clients are matched by package + SHA-1, and `HomeConfig.serverClientId` is only for apps that want a server auth
code, which Zeus doesn't. The Home Developer Console project "Zeus1" exists with no integrations; it isn't needed for
personal test use.

Allowlist: none. 1.10 and later are GA, and the zip downloads without signing in. **Testing-mode OAuth:** the consent screen
works only for test users on the Cloud project (`your-gcp-project`), and the app must be registered in the Home Developer Console.

API used (checked with `javap` against the 17.1.0 classes, and against Google's sample app):
- `Home.getClient(context, HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = FactoryRegistry(traits, types)))`. Create one client per process.
- `registerActivityResultCallerForPermissions(activity)` is called in `MainActivity.onCreate` (through `HestiaCore.attach`).
  Then `hasPermissions()` (a Flow of `PermissionsState`) and `requestPermissions(ForcePermissionFlow.FORCE_LAUNCH)`.
- `structures()`, `rooms()`, `devices()` and `structure.automations()` all return `HomeObjectsFlow` (a live `Set`). `device.types()` is live per device.
- Traits: `OnOff` (`onOff`, `on()`, `off()`), `LevelControl` (`currentLevel` 0 to 254, `moveToLevelWithOnOff`) and
  `ColorControl` (`colorTemperatureMireds`, `colorTempPhysicalMin/MaxMireds`, `currentHue/Saturation` 0 to 254,
  `colorCapabilities`, `moveToColorTemperature`, `moveToHueAndSaturation`). `DoorLock` is registered only so that
  anything with a lock trait is classified as a lock.

## Network

The Home APIs work over IPC to Google Play services. The consent screen and the Home graph run in Play services' own
process. A string scan of both AARs' classes turned up only OAuth scope URIs and a `home.google.com/connections/link`
URL (the cloud account-linking web broker, which Zeus doesn't use). The scan found no HttpURLConnection, OkHttp or socket use.
**So no host was added** to NetGate or `network_security_config.xml`, and the base config still trusts no CA.
This is verified at the source level only. If the phone ever shows a TLS failure from `com.google.home`, the audit log's blocked-request line will name the host.

## What is built

- `hestia/Model.kt` holds the plain data: `DeviceKind` (with `forbidden` set for LOCK, GARAGE, SECURITY, CAMERA and DOORBELL), `Trait`, `HomeDevice`,
  `HomeSnapshot`, `Link` (Starting / NeedsPermission / Connected / NotConnected(reason)), `HomeCommand`, the `HomeBackend`
  seam, and the optional `Whereabouts` interface.
- `hestia/GoogleHome.kt` is the real backend. `hestia/Hestia.kt` has `HestiaCore`, the process singleton: screen taps go to `tap`, and
  confirmed proposals go to `control`/`restore`. The same file has `FakeHomeBackend` for tests and shots.
- `hestia/Rules.kt` holds the pure rules: device lookup, validation, unit conversion, and the `home_status` JSON.
- `hestia/HestiaScreen.kt` replaces the phase 6 placeholder. It shows rooms as device tiles: tap a tile to switch it, or use Adjust for brightness,
  white and color. Below that is a read-only automations list, then the connect flow and status. Forbidden kinds show as read-only tiles.
- `hestia/Cards.kt` builds the Olympus "Home" card. "3 lights on, you're away" appears when Odyssey's current stay is more than 200 m from Home.
  "N lights still on" appears from 23:00 to 05:00. The VM sets `HestiaCore.whereabouts` from `OdysseyStore`. Hestia never imports Odyssey.

## Live state

Each device's `device.types()` flow is collected for as long as access is granted. Its trait attributes are live, as in
Google's sample app, so a change from the Google Home app or a wall switch updates the snapshot, the screen and the
Olympus card with no refresh. `devices()`, `rooms()`, `structures()` and `automations()` are live sets as well.

## Verify from adb (debug builds only)

`DebugCommandReceiver` (in `app/src/debug`, so it is not in release builds) needs `android.permission.DUMP`, which only
the adb shell holds. It is read only: there is no control command.

```
adb shell am broadcast -a dev.phonerecall.DEBUG --es cmd hestia_status -n dev.phonerecall/.DebugCommandReceiver
adb logcat -d -s ZeusHestia
```

It logs one JSON line: `connected`, `reason`, `structures`, `structure_names`, `rooms`, and `devices` (name, room, type,
online, on, level). It has no ids and no tokens. If Zeus wasn't running, the first broadcast may show `Connecting to Google Home`, so send it again a few seconds later.

## AI: proposal only

- `home_status` (read): lists rooms, devices with ids, state, away, and automations.
- `home_control` (write) only proposes. The validator chooses the step kind from the device: a light becomes `LIGHTS`, and a plug or switch
  becomes `HOME_CONTROL`. Both are tier 2. `LIGHTS` is the only one that can go on auto-approve, it is off by default,
  and the model can't name it directly. `HOME_CONTROL` is never auto-eligible.
- Forbidden kinds are refused at proposal time, again at confirm (revalidation), in `HestiaCore.tap`/`control`, and in the fake.
- Proposals expire after 10 minutes. Undo restores the previous on/off state, brightness or white.

## Home routines (`Routines.kt`, `RoutineSpec.kt`, `RoutinesAndroid.kt`)

Chat-first ("describe it to Athena and she sets it up"). One list of WHEN / IF / THEN routines,
whether they came from a preset, his words or one of Athena's suggestions.

- **Making one.** He says it in any words or Hinglish. Athena calls `read_routines`, then `propose_routine`; the phone
  checks it (`RoutineSpec.check`) and parks ONE `SET_ROUTINE` card with the routine in one sentence ("When you leave
  home: turn off the Bedroom lights and every plug and switch, except Computer."). Live after his Confirm; Undo puts the
  old one back. Changing ("make that only after 10 PM") and deleting go the same way, by id.
- **WHEN**: leave or arrive (home, work, or a named place), sunset or sunrise (offset), a time, looks asleep, alarm
  dismissed, car connects or disconnects. Not built yet: charging, unplugged, calendar event start or end.
- **IF**: home or away, dark or light, weekdays, weekend or days, after or before a time, a device on or off.
- **THEN**: a lights block (rooms, named devices, every plug and switch for off, except, his learned usual set), one
  device (on, off, brightness, white, color), music, an alarm, a card. A scene is a few device steps.
- **Never**: locks and every forbidden kind (refused by the spec, by the confirm's revalidation and by the Fates
  validator and `HestiaCore` underneath); messages, calls and payments. A device marked **Never automate** (his
  computer; a device named like computer/PC/desktop is pre-tagged once) only when he says so for that routine.
- **Can't yet**: an unknown or unbuilt WHEN, IF, THEN or device kind comes back telling Athena to call
  `ask_prometheus` (kind assess); Prometheus checks the code and, if feasible, parks a Build card (promclio).
- **Running**: through `Fates.own` as his standing commands (validated, audited, Undo), one notification with Undo per
  run, Clio logs runs and skips with reasons, run history (last 10) in Hestia. Quiet rules: no arrival run when already
  there (arrived in the last 8 h) or back within 5 minutes; asleep at home, a leave or arrival is a GPS jump; nothing
  that turns lights on or plays music runs while he looks asleep (except on his alarm); a clock routine runs once a day.
- **Presets**: leaving home (Bedroom lights, every plug and switch off), home after dark (usual arrival lights, until
  learned the living room), getting dark while home, looks asleep (past Hypnos's bedtime or 11:30 PM, never before
  10 PM, screen off 20 min, nothing playing: every light off).
- **Learning**: his tile taps (`HestiaCore.tapped`) and the moments (`leave:home`, `arrive:home`, `sun:set`, `bedtime`)
  feed the learned-shortcuts evidence; Athena can offer a routine from it, and his Yes makes one (`hr-` id). The
  routines' own steps stay out of the evidence (`ShortcutsCore.quietly`).
- **Screens**: Hestia lists them (on/off, sentence, last run; open one for its runs, Undo, Edit by chat, Delete) plus the
  Never automate picks; Horologion has a "Home routines" row each (on/off). The clock is one inexact alarm
  (`RoutineReceiver`): the next sun or set time, tonight's bedtime, then every 15 minutes until 5 AM.

## What the owner has to do

1. In the Google Home app: Devices, Add, Works with Google Home, AiDot. Sign in with the AiDot account and put the lights in rooms.
2. The OAuth consent screen in Testing with the owner's account as a test user, and the Android OAuth client above.
3. Home Developer Console: no step needed for personal test use. Register only if the consent screen refuses.
4. On the phone: update Google Play services to 26.36.25 or later. Install the debug build, open Hestia, tap Connect Google Home,
   and pick the home and devices to share.
5. Routines: check which plug is the computer under Hestia, Routines, Never automate (a device named like "computer" or "PC" is pre-tagged).
