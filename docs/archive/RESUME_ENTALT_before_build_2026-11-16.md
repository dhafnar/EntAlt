# Resume EntAlt implementation

Checkpoint: **2026-11-16**, saved at the user's explicit request to pause and resume later. All changes are saved in this workspace. The latest core JVM check finished successfully; no task-owned build, test, or generation process remains running. This directory is **not a Git repository**, so no commit was created.

## Authorized task and decisions

Build the native Android application in [ANDROID_APP_PLAN.md](ANDROID_APP_PLAN.md), not just another plan. Application name **EntAlt**, namespace/application ID `si.entalt.app`.

- Dark Material Design 3 by default: charcoal surfaces, mint/teal controls, warm activity accents.
- Intentional phone, tablet, and tiny wall-panel layouts, including short landscape and square windows.
- Persistent username/password sign-in with secure storage, direct Entia APIs, room navigation, and device control.
- The working Home Assistant integration in `reference/home-assistant-entia/` and its notes take precedence over supplementary/outdated Swagger in `docs/api/entia/`. Preserve original reference code/assets.
- Use the proven keyless login and metadata, `/rt` integer writes, full REST refresh, and WebSocket behavior.
- Reuse installed SDK/JDK/Gradle tools. No credentials or signing keys in source/logs. No live Entia credentials have been supplied; use mocks and clearly labeled demo data.
- **No computer-use/UI automation and no sub-agent delegation.** Use files, terminal, and connected IDE tools. Development instrumentation/ADB checks are separate, but need an available environment.
- The user explicitly paused this session. Resume only on their next instruction.

## MCP connection — update after restart

The user supplied Android Studio's actual endpoints:

```text
http://127.0.0.1:64343/stream
http://127.0.0.1:64343/sse
```

The old `idea` MCP connection used **64342**, belonging to IntelliJ IDEA. In this session the user ran:

```sh
codex mcp add idea --url http://127.0.0.1:64343/stream
```

They confirmed: **“Added global MCP server 'idea'.”** Configuration is saved, but this running chat continued using its existing connection. Rechecks, including a `build_project` call after edits, still listed only ThymeleafDemo, DispozicijaWorking, smallProjects, and NrzFhirValidation. **No EntAlt Android build was performed by that tool.**

On resume, recheck `get_project_modules` with `/Users/davorhafnar/Dev/Other/EntApp/android` (or the root EntApp path if that is what Android Studio opened). The client restart should load the updated configuration; verify rather than assume. Do not ask for the URL again unless the endpoint changes. The user has Codex ACP too, but that is separate from this chat's IDE MCP connection.

User-facing setup was clarified: JetBrains' **MCP Server** page has Enable MCP Server and Copy HTTP Stream/SSE Config; the **Add via JSON** page is Android Studio/Gemini's MCP client configuration, not its IDE server.

## Current implementation

```text
android/                         Android/Compose project, full app build still unverified
scripts/check-core.py            Repeatable offline core Kotlin/JUnit check
android/app/src/test/...         DomainTest, ApiTest, RepositoryTest
store/brand/                     Generated icon master and provenance
reference/home-assistant-entia/   Preserved protocol reference and tests
docs/api/entia/                  Preserved Swagger assets and extracted OpenAPI
```

Toolchain pins: AGP 8.10.1, Kotlin 2.1.21, Gradle wrapper 8.14, Compose BOM 2025.05.01, SDK 36, minimum SDK 26, DataStore 1.1.7. **Dependency resolution and an actual Android build remain unverified.** Wrapper JAR matches the installed distribution's original, SHA-256 `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`.

Already present:

- Gradle scripts, version catalog, wrapper, manifest, backup exclusions, dark/light/dynamic themes, adaptive launcher icon, ignored `local.properties`.
- `domain/Home.kt`: hierarchy normalization, device capabilities, half-degree temperatures, blind inversion and tilt state, climate activity/valve matching.
- `domain/Updates.kt`: tolerant raw-value WebSocket event parsing, immutable updates, timestamp-aware snapshot reconciliation, command validation.
- `data/EntiaApi.kt`: exact keyless login metadata, bearer tokens, shared bounded 401 renewal, `/flat`, `/flat/device`, `/rt`, cancellable HTTP, no automatic transport replay, shared bounded WebSocket transport and one auth renewal on rejection.
- `data/CredentialStore.kt`: Keystore AES-GCM and atomic encrypted account file in `noBackupFilesDir`.
- `data/Contracts.kt`, `AppPreferences.kt`, `EntiaRepository.kt`: injectable storage/gateway contracts, DataStore preferences, serialized session/repository state, restoration, candidate sign-in, local logout, foreground socket/polling, refresh coalescing, command outcomes, remembered fan speed.
- `DemoHome.kt`, Application, Activity/ViewModel, Compose sign-in/Rooms/settings/pickers/connection/adaptive controls. Synthetic demo commands never create a live client or persist credentials.

### Changes completed in this session

- Added **`ui/DeviceControls.kt`**, completing the previously missing `DeviceControl` and `ChoiceRow` references. Includes light power; sensor temperature; blind open/close/position/estimated tilt; climate current/target, half-degree buttons and Off/Heat/Cool; ventilation power/remembered speed/preset; unsupported/unavailable states; command outcome labels.
- Added full-window scrollable numeric editors with sliders, +/- buttons, Apply/Cancel and explicit Back, suitable for short/square panels. These source layouts **have not been rendered or visually verified**.
- Added English and Slovenian resource files covering **107 referenced string IDs**. Backend/repository error messages, fallback hierarchy labels, and demo names still contain English literals; localization is not complete.
- Added sign-in username/password autofill semantics and navigation reset when account/demo identity changes. Password remains `remember` only, never saved UI state.
- Added an opaque SHA-256 account owner tag to preferences. Restoring another account discards room/startup/speed preferences even if the previous preference reset/save failed or the process stopped between credential/preference writes. Username/password are not stored in DataStore.
- Credential save/session installation now uses a bounded, noncancellable commit section. A preference save failure after successful credential persistence no longer abandons the validated candidate. Overlapping form submissions cannot interrupt a disk commit. Logout still invalidates immediately and serializes a guaranteed cleanup after an in-progress save.
- Kept failed startup account-load cleanup under the account lock, preventing late startup cleanup from deleting a newer saved record. Logout storage failures remain visible.
- Accepted commands now wait for a preexisting refresh to finish, then request a fresh snapshot begun after acceptance. No PUT replay was added.
- Only awaiting commands can become observed; a matching event while still sending cannot confirm an unaccepted write. HTTP 5xx/408 outcomes remain unknown instead of being treated as definite rejection. Removed/unavailable controls terminate pending commands.
- Blind commands are disabled while movement is reported. Unrelated events/pre-write snapshots cannot reset a pending blind direction/base; an unconfirmed timeout invalidates the derived tilt. UI says **Target observed**, not completed physical movement.
- Added a transport lifecycle lock so closing a client cannot race the registration/enqueue of new HTTP calls/sockets or leave a newly assigned token after close. Core code compiles; actual WebSocket callback races still need dedicated testing.
- Added `scripts/check-core.py` and 21 JUnit tests (details below).

## Verification actually performed

**Passed on the final saved core sources:**

```sh
python3 scripts/check-core.py
# kotlinc-jvm 2.4.0 (JRE 21.0.10)
# JUnit 4.13.2 — OK (21 tests)
```

The script uses Android Studio's bundled Kotlin/compiler/runtime libraries, OkHttp 4.12.0, Okio 3.17.0 and cached Hamcrest, with JVM target 17. It compiles `domain/*.kt`, `Contracts.kt`, `EntiaApi.kt`, `EntiaRepository.kt`, `DemoHome.kt`, and the tests. **It does not compile Compose, Activity/Application, CredentialStore, AppPreferences, or Android resources. Bundled versions differ from app pins; this is not a substitute for Gradle.**

Coverage includes integral/string raw values, hierarchy conflicts/unavailable/empty rooms, telemetry valve matching and ambiguity, timestamp reconciliation, tilt direction/half-even/clamping, write validation, preference ownership, exact login and `/rt` payload, 12 concurrent 401 responses sharing renewal, bounded repeated rejection, sanitized uncertain write without replay, candidate persistence failure, preference failure after commit, canceled startup candidate, post-write fresh snapshot ordering, one foreground socket, logout ignoring late events, failed account clearing, unknown 5xx outcome, demo isolation/speed memory, logout during atomic save, and unrelated blind events during pending writes.

API tests use OkHttp interceptors, not network sockets. Repository socket tests use a fake gateway. **The actual WebSocket transport, Android Keystore/DataStore, process/reboot restore and live Entia behavior remain untested.**

Also passed:

- Resource XML parsing, duplicate/missing key checks, matching English/Slovenian keys: 107 referenced IDs covered.
- Installed SDK `aapt2 compile` for all app resources.
- `aapt2 link` of resources and a temporary manifest package against SDK 36, min 26/target 36. Output `android/core-check/build/resource-check.ap_` is a **resource-only package, not an application APK**.
- Previous wrapper `sh -n` verification remains valid.

Generated verification outputs live under ignored `android/core-check/build/`. No APK exists from these checks. No emulator, Compose preview, screenshot, instrumentation, lint, physical-panel or live-account validation has been performed.

## Next steps after resume

1. Recheck the **updated Android Studio MCP connection**. Use it to resolve/sync and build `android/`.
2. Run the actual project checks from `android/`:

   ```sh
   ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   ```

   Resolve concrete Kotlin/Compose/Android/Gradle diagnostics. The source-only UI still needs compilation. Do not claim an APK until assembled.
3. Review remaining concurrency and behavior: real WebSocket bounded buffering/auth recovery/cancellation; startup/load/save/clear failures; preferences failure recovery; timestamp/event races; long/removed/unavailable blind targets and external movement. Current 21 tests are useful but not exhaustive.
4. Finish localization of repository/API errors and fallback/demo labels; review TalkBack/autofill, 200% font scale, scroll/navigation restoration, and every touch target. Mode controls with null readings and full-screen editor enablement merit compile/interaction review.
5. Add/run meaningful Compose navigation and Android persistence/instrumentation checks once an execution environment is available. Use the plan's screen matrix: tiny 240×240/320×320, short landscape, phone and tablet. No UI automation of the user's desktop.
6. Render and visually polish demo UI; produce genuine phone/tablet/panel screenshots. Do not label promotional artwork as screenshots or claim physical-panel support without hardware checks.
7. Finish requested store assets: export correctly sized Play icon and create a coordinated feature graphic. Only `store/brand/entalt-icon-master.png`, its provenance, and native vector launcher resources exist so far.
8. Update README with actual build results and deliver debug APK/assets, with honest live-account/hardware limits.

## Environment and restrictions

- Workspace `/Users/davorhafnar/Dev/Other/EntApp`; Android project is its `android/` subdirectory.
- Android Studio `/Users/davorhafnar/Applications/Android Studio.app`, AI-262.9437.185.2621.16467767 / AndroidStudio2026.2.1 at earlier checkpoint.
- SDK `/Users/davorhafnar/Library/Android/sdk`; platforms 35/36, build tools 35.0.0/35.0.1/36.0.0/37.0.0, Android 36 images. AVDs `Medium_Phone_API_36` and `Small_Phone`.
- Installed JDKs 17/21/24. Cached Gradle 8.14: `/Users/davorhafnar/.gradle/wrapper/dists/gradle-8.14-bin/38aieal9i53h9rfe7vjup95b9/gradle-8.14/bin/gradle`.
- Earlier terminal Gradle attempts failed creating a local lock socket (`SocketException: Operation not permitted`); HTTPS DNS and adb startup were also restricted. Do not repeat unchanged restrictions or ask for forbidden escalation. Use the newly configured IDE connection after restart.
- No AGP/Compose dependencies were available in the ordinary Gradle cache when checked. Bundle-based core verification was used successfully.
- Default `python3` is **3.9.21**, without `tomllib`; the checker uses only compatible standard-library features. Do not assume Python 3.11+.

## Resume prompt

> Continue EntAlt from docs/RESUME_ENTALT.md. I updated the global idea MCP URL to Android Studio at http://127.0.0.1:64343/stream. Recheck the connection, compile/fix the new device controls and resources, run the full Android build/tests/lint, and continue the recorded implementation. No computer-use automation or sub-agent delegation.
