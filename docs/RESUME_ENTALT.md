# Resume EntAlt implementation

Checkpoint: **2026-11-24**. The Android app compiles, assembles `0.12.0` optimized and debug APKs, and passes all 69 JVM unit tests (`ApiTest` 7, `DomainTest` 21, `HueApiTest` 3, `RepositoryTest` 30, `WidgetTest` 3, `SocketTransportTest` 5), `lintDebug` & `lintOptimized` (0 errors), with 342 matched English and Slovenian string resources.

Read [ANDROID_APP_PLAN.md](ANDROID_APP_PLAN.md) for intended behavior.

## Current 0.12.0 handoff

- **Deliverable**: `output/apk/EntAlt-0.12.0-optimized.apk` (`versionCode = 16`, `versionName = "0.12.0"`, SHA-256 `2de09bf7b6c2d30c1bdc1e0c67e9fe23d5e0a13beec255092fb1b14ac4955a2c`). Previous `0.11.1` artifacts were removed.
- **Multi-Hue on One Entia Outlet (Smart Mains + Sibling State Preservation)**:
  - Added optional `StandaloneHueLight.entiaDeviceId: Long? = null` (`Contracts.kt`, `AppPreferences.kt`) plus `Preferences.linkedEntiaSwitchId(deviceId)` and `Preferences.allHueTargetsOnEntiaSwitch(entiaId)`.
  - Added optional physical Entia switch picker in `AddStandaloneHueLightDialog` (`HueControls.kt`) and `DeviceEditor.kt` for standalone Hue lights, and wired `ResolvedLightState` (`resolveLightState`) across `EntiaRepository.kt`, `HueControls.kt`, `DeviceControls.kt`, `PanelControls.kt`, `EntAltApp.kt`, and `EntAltWidgetProvider.kt`.
  - When turning on or adjusting a Hue light whose physical Entia switch is OFF, `EntiaRepository` and `EntAltWidgetProvider` restore the Entia switch (`Attr.LIGHT = 1`), snapshot the prior state of all sibling Hue targets sharing that Entia outlet, and actively re-assert sibling states (sending `setPower(..., false)` for OFF siblings and restoring prior ON siblings) both immediately and after `hueMainsBootDelayMillis` (`1200L`) so 230V mains restoration never leaves other bulbs on the same outlet turned on.
- **480×480 Clock View — 50% Bigger Temperatures & Week Slider**:
  - Increased indoor/outdoor temperature font size by 50% (`readingSize = (effectiveDim * 0.1125f).sp`, up from `0.075f`) in `RoomDisplay.kt` with single-line overflow scaling guard.
  - Redesigned `DayOfWeekIndicators` (`RoomDisplay.kt`) into a low-contrast horizontal week slider with a continuous track, filled elapsed-week bar from Monday to today, subdued past nodes, elongated pill thumb for today (`circleSize * 1.42f`), and dim future stops.
- **Manually Selectable App Language (`"system"`, `"en"`, `"sl"`)**:
  - Added installation-wide `Preferences.language` (`Contracts.kt`, `AppPreferences.kt`), `resolveAppLocale` / `withAppLanguage` (`AndroidText.kt`), `LocalizedContextWrapper` in `EntAltTheme` (`Theme.kt`), and `EntiaRepository.setLanguage` with live re-translation of Demo and REST snapshots plus widget cache localization.
  - Added `Language` (`System` / `English` / `Slovenščina`) selector in phone/tablet `Settings` (`EntAltApp.kt`) and `480×480` `PanelSettings` (`PanelSettings.kt`).

## Previous 0.11.1 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.11.1-optimized.apk` (`versionCode = 15`, `versionName = "0.11.1"`, SHA-256 `ffb9c2c76371b7157ea9ad15b3265e3ad0073147ef38d6ed529e67773250da2f`). Previous `0.11.0` artifacts were removed.
- **Standalone Hue Light Management Moved Strictly to Settings**:
  - Removed the `+ Add Hue light` buttons from `RoomControls` in `EntAltApp.kt`.
  - Kept standalone Hue management inside `HueBridgeSettingsCard` (`HueControls.kt`) in Settings and `PanelHueSettings`: once Hue is enabled, users can view/edit/remove existing standalone Hue lights and tap **Add standalone Hue light** (`AddStandaloneHueLightDialog`) to pick the target room, one or more Hue bulbs/rooms/zones, name, and icon.
- **Main-Thread & Concurrency Unresponsiveness Fixes**:
  - Wrapped `preferences.load()`, `preferences.save(...)`, and `preferences.clearAccount()` in `withContext(io)` in `EntiaRepository.kt`, and moved `parseHome(...)` (including `Html.fromHtml` label decoding) and `hueGatewayFactory(...)` onto `withContext(io)`.
  - Cached `hueTrustManager`, `hueSslContext`, and `baseHueClient` lazily in `HueApi.Companion` (`HueApi.kt`) so `localBridgeClient(expectedHost)` shares the OkHttp pool/dispatcher and TLS context instead of re-initializing `KeyStore`/`TrustManagerFactory`/`SSLContext` per instance.
  - Added `hueRefreshJob` deduplication in `EntiaRepository.kt` and updated `command(...)` so tapping controls while `!socketConnected` does not cancel active workers or trigger redundant full refreshes when `restReachable` is true.
- **Desktop Widget Visual Overhaul & All 12 Light Icons**:
  - Added vector drawables `ic_widget_light_bulb.xml` through `ic_widget_light_outdoor.xml` for all 12 `LightIcon`s, persisted `lightIcon` in `CachedDeviceState` (`WidgetConfig.kt`), and mapped each light's icon in `EntAltWidgetProvider.kt` and `EntAltWidgetConfigureActivity.kt`.
  - Redesigned `entalt_widget_root.xml` (transparent outer container) and `entalt_widget_cell_sm/md/lg/xl.xml` into Material You rounded tiles (top row with icon + status, bottom-left device title, and subtle dashed ghost `+ Add` tiles for unassigned slots).

## Previous 0.11.0 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.11.0-optimized.apk` (`versionCode = 14`, `versionName = "0.11.0"`, SHA-256 `0e025892b7594f8edafb4f0f73981b4456de336df85bee26db7d49fec24c3e1d`). Previous `0.10.1` artifacts were removed.
- **Standalone Philips Hue Lights in Rooms**:
  - Added `StandaloneHueLight` (`id: Long`, `roomId: Long`, `name: String`, `icon: LightIcon`, `targets: List<HueTargetRef>`) and `Preferences.standaloneHueLights` (`Contracts.kt`, `AppPreferences.kt`), using negative synthetic device IDs (`-1001L`, `-1002L`, ...) and `Device.isStandaloneHue = true`.
  - `Home.withLocalOverrides(...)` (`Home.kt`) injects standalone Hue lights into `Home.devices` and their target `Room.deviceIds`, and `Preferences.hueBindingFor(deviceId)` resolves Hue bindings uniformly for both Entia-backed and standalone Hue lights.
  - Added **Add Hue light** (`+ Hue`) in room views and `AddStandaloneHueLightDialog` (`HueControls.kt`, `EntAltApp.kt`) for both phone/tablet and `480×480` square panel layouts.
  - `HueControlWindow` omits `MainsRelayCard` ("Physical Entia switch") when `device.isStandaloneHue` is true, `DeviceEditor` edits or removes standalone Hue lights directly, and `EntiaRepository` / `EntAltWidgetProvider` control standalone Hue targets directly over Hue CLIP v2 without calling `EntiaApi`.
- **Persistent Login & Automatic Re-Authentication**:
  - `EntiaApi` (`EntiaApi.kt`) now clears its cached bearer token and re-authenticates via `POST /login` using stored `Credentials` whenever a REST request returns `401` or `403`, retrying the request automatically.
  - `EntiaRepository.restoreAccount` (`EntiaRepository.kt`) keeps the user session active immediately when saved `Credentials` exist in `CredentialStore` (even when starting offline) and automatically re-authenticates on WebSocket `AuthFailure` or REST `HTTP 401`/`403` without signing the user out; the user remains signed in until explicitly tapping **Log out** or **Replace sign-in details**.

## Previous 0.10.1 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.10.1-optimized.apk` (`versionCode = 13`, `versionName = "0.10.1"`, SHA-256 `4e2fa4a8f71b7a5516bdf98511f54002de58b16e85b2fa816b3c118922f77116`). Previous `0.10.0` artifacts were removed.
- **Multi-Bulb Hue Outlets & Simplified Wording**:
  - Updated `HueBinding` (`Contracts.kt`, `AppPreferences.kt`) to store a list of `HueTargetRef`s per Entia light (`targets: List<HueTargetRef>`), backwards-compatible with `0.10.0` single-target bindings.
  - `DeviceEditor.kt` now allows multi-selecting multiple Hue bulbs/rooms/zones for a single Entia outlet.
  - `EntiaRepository.kt`, `HueControls.kt`, and `EntAltWidgetProvider.kt` toggle/control all linked bulbs in parallel by default, and `HueControlWindow` adds a top selector (`All (N) | Bulb 1 | Bulb 2 ...`) when multiple bulbs share an Entia switch so users can control all bulbs together or an individual bulb.
  - Simplified `"230V Mains Relay"` labels to **`Physical Entia switch`** (`Fizično stikalo Entia`) in `strings.xml` and `values-sl/strings.xml`.

## Previous 0.10.0 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.10.0-optimized.apk` (`versionCode = 12`, `versionName = "0.10.0"`, SHA-256 `46b496785f65db9679ed32383b2d18465746ab68e6d1e2f7b4f9a2213ddae8fb`). Previous `0.9.2` artifacts were removed.
- **Philips Hue Integration (CLIP API v2) & Smart Mains Guard**:
  - Added `HueApi.kt` (`HueApi` & `HueDiscovery`): isolated HTTPS `OkHttpClient` trusting local Hue Bridge self-signed / Philips Hue Root CA certificates with strict host verification; mDNS (`_hue._tcp.`) + `https://discovery.meethue.com` discovery + manual IP fallback; Link-button pairing (`POST /api`); CLIP v2 `light`, `room`, `zone`, and `grouped_light` discovery and `PUT` control (`on`, `dimming.brightness`, `color_temperature.mirek`, `color.xy`).
  - Added `CredentialStore.saveHue` / `loadHue` / `clearHue` encrypting the Hue Bridge IP and `hue-application-key` in Android Keystore (`AES/GCM/NoPadding`) in `noBackupFilesDir`.
  - Added `Preferences.hueEnabled` (installation-wide) and `Preferences.hueBindings: Map<Long, HueBinding>` (account-scoped) in `Contracts.kt` and `AppPreferences.kt`.
  - Added Settings toggle & Bridge pairing UI in `EntAltApp.kt`, `PanelSettings.kt`, and `HueControls.kt`, plus per-light **"Managed by Philips Hue"** toggle and target picker in `DeviceEditor.kt`.
  - Implemented **Smart Mains Guard** in `EntiaRepository.kt`, `DeviceControls.kt` (`LightControlPill`), and `EntAltWidgetProvider.kt`: tapping a Hue-managed light toggles Hue while automatically restoring Entia 230V mains power (`Attr.LIGHT = 1`) if it was `0`, and keeping 230V mains on when turning Hue off.
  - Added `HueControlWindow` in `HueControls.kt` (opened from the tune button on Hue-managed `LightControlPill`s) with Brightness (`1–100%`), White color temperature (`153–500 mirek` / `2200K–6500K`), Color XY presets, and explicit **230V Mains Relay (Entia)** cut/restore control for both phone/tablet and `480×480` square panels.

## Previous 0.9.2 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.9.2-optimized.apk` (`versionCode = 11`, `versionName = "0.9.2"`, SHA-256 `1a25b8d50812a5db9d548290f8a50b5d5263025ac149c453e2642d19ec95ecc4`). Previous `0.9.1` artifacts were removed.
- **UI & Settings Refinements**:
  - Removed the vertical background fill from `ShadeControlTile`, keeping `WindowBlindGraphic` and percentage text.
  - Removed `"Readings or settings need attention"` and `"Last known readings"` banners from room controls; toolbar shows `EntAlt` on phone/tablet room views instead of repeating the selected tab name.
  - Elaborated **Hide cooling** in Settings (`hide_cooling_description` / `cooling_controls`) and added removal confirmation (`confirm_remove_device_title` / `confirm_remove_device_message`) in `DeviceEditor` for both phone and `480×480` panel layouts.
- **Android Desktop Widget**:
  - Added `EntAltWidgetProvider`, `EntAltWidgetConfigureActivity`, `WidgetStore`, and Material You (`values-v31/colors.xml`) + dark fallback layouts (`entalt_widget_root.xml`, `entalt_widget_cell_*.xml`, `entalt_widget_info.xml`).
  - Supports user-defined `1..4 × 1..4` grid (default `2×2`) with configurable fixed-size buttons (`Small 72×58`, `Medium 92×72`, `Large 112×86`, `Extra large 132×98`, plus custom dp width/height on Android 12+) for lights, blinds, climate, and ventilation.

## Previous 0.9.1 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.9.1-optimized.apk` (`versionCode = 10`, `versionName = "0.9.1"`, SHA-256 `29ec2caabd375faf971d337ba434442f4d0d2ae1a22d1dc338b52a1da2435a0b`). Previous `0.9.0` artifacts were removed.
- **Defaults & Settings**:
  - `dynamicColor` ("Use device colors") defaults to `true` in `Preferences` and `AppPreferences`.
  - Added `hideCooling` preference and settings controls in both `Settings` (Switch) and `PanelSettings` (`Cooling controls` submenu) to hide the `Cool` HVAC mode button in `TemperatureControlStrip` and `PanelClimate`.
  - Settings screen (`EntAltApp.kt`) now uses a compact `SingleChoiceSegmentedButtonRow` for Dark/Light/System theme selection, left-aligned Startup room button with trailing chevron, and hides the Settings cog icon from `AppToolbar` while in Settings/Connection/Replace.
- **Room Navigation & Lights**:
  - Removed the toolbar room pulldown (`titleAction` / `ExpandMore`) from `AppToolbar`; room tabs and horizontal pager swipes navigate rooms.
  - Expanded `LightIcon` from 6 to 12 icons (`FloorLamp`, `WallLamp`, `Chandelier`, `Downlight`, `Nightlight`, `Outdoor`) and updated `LightControlPill` active icon tint to `MaterialTheme.colorScheme.onPrimaryContainer` for reliable contrast on `primaryContainer` under dynamic colors.
- **Top-Down Window Blind Tile & Simplified Blind Menu**:
  - `ShadeControlTile` uses a top-down fill (`Alignment.TopCenter`) and a Material You `WindowBlindGraphic` (frame, mullions, sill, top-down slats) instead of a left-to-right bar.
  - Blind Control Window (`showBlindWindow`) uses a vertical top-down pill slider (`VerticalBlindPillSlider` with `Icons.Outlined.Blinds` top / `Icons.Outlined.BlindsClosed` bottom), `1%` click and hold-to-repeat `-` / `+` buttons (`RepeatStepperButton`), and two per-device persisted save slots (`Preferences.blindPresets` / `BlindPresetSlot`) for one-click presets (tap empty slot or hold saved slot to save current position; tap saved slot to apply). Adapts vertically on phone and side-by-side on `480×480` (`maxHeight < 520.dp`) with verified unclipped `480×480` screenshots in `android/build/panel-qa-latest/`.

## Previous 0.9.0 handoff (historical)

- **Deliverable**: `output/apk/EntAlt-0.9.0-optimized.apk` (`versionCode = 9`, `versionName = "0.9.0"`, SHA-256 `9c21390a43d2b872e15496b40332b3ad91c4c10d5e39b0febc59c1b59f22792c`). Previous `0.8.0` artifacts were removed.
- **Safe Vertical Blind Controller & Confirmation**:
  - Room-level blind tiles (`ShadeControlTile`) have no drag or tap-to-toggle actuation; tapping only opens the dedicated Blind Control Window so swiping/scrolling can never accidentally move blinds.
  - Uses `0%` = fully open and `100%` = fully closed everywhere.
  - The Blind Control Window presents a vertical top-down blind graphic/slider (`0%` top, `100%` bottom) and `-` / `+` buttons that send `Attr.POSITION` immediately (on step or pointer release), with no `Apply` button.
  - `Open` (`0%`) and `Close` (`100%`) open an additional modal Confirmation Window before sending any command.
- **1:1 (`480 × 480`) Clock View & Resolution Rules**:
  - The full-screen clock face (`RoomDisplay`) is strictly active **only** on `1:1` (`widthPx == heightPx`, e.g. `480 × 480`) resolutions. Non-1:1 resolutions (regular phones/tablets) have no clock view, no clock button, and no clock/display settings.
  - The clock view displays a single bound room's indoor temperature and outdoor temperature alongside the enlarged 24-hour clock (`0.35f` of viewport width) and subtle ~50% greyed-out day-of-week circles (`0.65.dp` thin weekend border), with no focus or room toggle on the clock view and no `"Last known reading"` text.
  - Elements across `RoomDisplay`, `RoomControls`, `HomeVentilation`, and `PanelMenu` use `Arrangement.SpaceEvenly` / centered layouts, enlarged typography, and large `76 × 56 dp` tonal pill Back buttons.
- **Controls, Keyboard & Connection Reset**:
  - Fixed `TemperatureControlStrip` `-`/`+` commands to send half-degree raw steps (`raw ± 1` in `20..60`), added `Off / Heat / Cool` mode controls, and wired up `ValueEditor` (tap) and `DeviceEditor` (long-press).
  - Separated Lights (teal primary) and Blinds (warm-sand secondary with slider thumb edge) with a horizontal section divider.
  - Fixed `ShadeControlTile` horizontal drag math with a float accumulator and isolated `HorizontalPager` scrolling while dragging blinds.
  - Removed the duplicate power switch from `VentilationControl` so `0 / 1 / 2 / 3` is the single speed/off control alongside `Bypass / Heat recovery`.
  - Fixed keyboard entry on `480 × 480` (`SignIn` and `DeviceEditor`) using physical window size detection so IME appearance never resets the focused text field.
  - Control buttons remain clickable when disconnected (`restReachable == false`), and pressing any control button (or tapping the clock face/menu) when disconnected immediately fires `resetConnection()` in `EntiaRepository` to restart workers/re-authenticate and execute the command.
- **Localized Outside Sensor Default Naming**:
  - Added `TextKey.OutsideSensor` / `R.string.text_outside_sensor` (`Outside sensor` in English, `Zunanje tipalo` in Slovenian).
  - Unlabeled, unassigned, or generic outside sensors (`Attr.OUTSIDE` `1104`, or `Attr.TEMPERATURE` `801` with explicit outside server labels) resolve to the active language default in `parseHome` (`Home.kt`) and via `controlDisplayName` in Compose controls (`TemperatureSensorTile`, `PanelDeviceControl`, `ControlPicker`), while preserving specific custom server labels and user overrides saved via `DeviceEditor`.

## Previous 0.6.0 handoff (historical)

The latest user restored the **clock-first** face, kept ventilation, requested usable small-screen controls with no whole-page scrolling, permitted emulator use as needed, and changed the Android identity to **`si.hafnar.entalt`**. They then explicitly said to **skip the smallest size and build immediately** because they had to stop. This overrides the older request to complete that size and pause further emulator work.

- Restored the centered near-black clock face: large white HH:mm clock, weekday/date beneath, secondary indoor/outdoor readings, subtle digit motion. Removed the permanent room-navigation footer added in 0.5.0. Climate, Ventilation, Rooms and Settings remain in the tap menu. Original `display_focus` preference/default Clock is restored; temperature focus remains selectable.
- Compact mode now includes short windows below 480 dp. `PanelControls.kt` supplies dedicated compact climate, ventilation, blind, light and sensor layouts. Room/device navigation stays in a fixed 48 dp footer; room controls have no whole-page scroll. Climate has direct +/- and a target editor; mode opens separately. Ventilation has Off/1/2/3 and airflow-mode selection, with per-unit selection only when needed. Value editors keep Back/Apply visible and use a value row plus slider.
- `PanelMenu.kt` pages rooms, device selection, settings and choices to keep actions within the viewport. `PanelSettings.kt` replaces the long settings form on panels. Device editing has compact name/icon/remove pages and a fixed Save action. Compact login uses side-by-side fields where possible or a username/password step on very narrow, short screens. The concise unofficial agreement is split across two pages on short windows; explicit acceptance is still required. Normal navigation retains a labeled Clock return button. System bars hide on short panel windows to preserve usable pixels.
- Namespace, application ID, all source/test package directories, Proguard Page identity and offline checker migrated to **si.hafnar.entalt** (no `.app` suffix). Android treats this as a separate installation: old app data remains with `si.entalt.app`; the new app requires its own agreement/login. The old installed app was not uninstalled. Test-only `PanelQaActivity` is in debug sources and is verified absent from the optimized manifest.

**Verification:** optimized assembly and 45 JVM tests pass. `lintOptimized` completes successfully, but the shared Gradle lint worker logged stale migrated-jar cache exceptions, so this is not a clean custom-Compose-lint result; use a fresh Gradle worker for a future lint rerun. Signature/certificate match verified; new package ID/version and non-debuggable APK verified. Resources remain matched in both languages.

**UI evidence:** `PanelLayoutTest` uses a debug-only host, in-memory preferences/accounts and synthetic demo commands (no production credential or acceptance writes). The first ForcedSize approach was replaced after screenshots showed Android dialogs retaining the larger physical window and the artificial locale override failing to reach them. Tests now change the actual emulator framebuffer/density, actual font scale and app locale. The real-window pass completed **19/20**, with only the 240×180 menu failing; the source menu was then fixed. The next recheck passed its first six cases before being stopped at the user's explicit request to skip the smallest and build immediately. Do not claim the entire latest UI matrix passed. The 240×180 case was removed from the future matrix per that request. Retained cases are 533×400 and 320×240, English plus Slovenian with 1.3/1.5 text scale. Rendered screenshots in `android/build/panel-qa/` were inspected for clock, climate, ventilation and value editor. The physical wall panel and live Entia account remain untested.

Deliverable: **output/apk/EntAlt-0.6.0-optimized.apk**, version code 6, development signed, R8/resource shrinking, 1766227 bytes (1.68 MiB). SHA-256 `b2bbbe2e864eb7b7a60b3ba3f1aed8a313a1ab272b50208d5620293ccdabcbf1`. Prior 0.5.0 packaged APK/checksum removed as requested. User requested immediate delivery; no further UI suite should run unless requested. Display restored to 800×600 at 240 dpi, font scale 1.0. Check `android/build/low-res-reload.json` for final install/launch status.

## Previous 0.5.0 handoff (historical)

Latest steering: compact screens should prioritize ventilation/climate over lights, then the user requested **temperature room by room** because the layout was too tall, and an obvious return to the clock face from anywhere in normal navigation. These changes are implemented.

- The near-black full-screen face now defaults to **Room temperature** focus. A compact white 24-hour clock and weekday/date share a horizontal line, with one centered room name/large measured temperature and smaller outdoor reading below. Subtle digit/reading motion remains. A reserved solid footer has 48 dp Previous/Next room buttons and a room-picker action; it navigates only rooms with available indoor readings. The content uses height-aware sizing and scroll fallback for enlarged fonts/very short windows. Settings still offers Clock focus. `AppPreferences` uses the new `room_display_focus` key, so this redesign resets the old focus once to temperature; subsequent choices persist.
- Tap the face for a short two-column menu: **Room climate** (or controls for a sensor-only room), **Ventilation** when supported, **Rooms**, **Settings**, Close. Ventilation remains home-level/per-unit. No permanent settings/branding/socket badge was added to the face.
- From the face, the room picker places rooms with temperatures first. Selecting one returns to its temperature face; other supported rooms remain accessible and open ordinary controls. Control lists now put climate and temperature readings before blinds/lights. Compact ventilation omits the explanatory scope paragraph to save height.
- Every normal toolbar has a labeled **Clock** button that returns directly to the full-screen face from Rooms, Settings, Ventilation, Connection, control selection or room controls. It also provides an entry to the face from explicitly selected standard layouts. The first-use agreement and sign-in gate are still required; no acceptance or login UI was automated.

Deliverable: **`output/apk/EntAlt-0.5.0-optimized.apk`**, version code 5, **1,726,575 bytes (1.65 MiB)**, SHA-256 `5636f28e5dcbcef26f1a2a6c8fa3fd234d7b0ccd3445b99490ea0e5990c87b98`. Signature verified against 0.4.0; the old packaged APK/checksum were removed as requested. **45 optimized JVM tests and lint pass**, build status `android/build/optimized-check.json`, exit 0. Both languages retain 201 matching string keys. No automated UI interaction, live account or physical panel validation was performed.

The user authorized rebuild/rerun. Installation/relaunch in the existing 800×600/240 dpi emulator succeeded: `android/build/low-res-reload.json` reports ready, app PID 5970; its log records installation Success and the MainActivity launch. The emulator itself was not rebooted. Agreement/account/settings data are preserved with `install -r`; demo entry may require manual selection after process restart.

## Previous 0.4.0 handoff (historical)

The user's subsequent manual review requested a centered Scandinavian/minimalist always-on display matching the black enclosure, white 24-hour clock without AM/PM, larger weekday plus date, no persistent EntAlt branding/navigation/settings icons, subtle Material motion, an automatic default on small 4:3 panels, and better small-window agreement/login layouts. These changes are implemented.

- `RoomDisplay.kt`: near-black solid background, centered content group, warm-white clock, enlarged weekday/date, secondary temperatures. Tap anywhere to reveal a short neutral Settings/Rooms/Close menu. No persistent EntAlt label or controls. Clock uses `HH:mm`; changed digits have a fixed-width 220 ms fade/small vertical transition, and temperature changes crossfade. No continuous idle animation; updates remain minute-based.
- Layout `auto` defaults to this face for approximately 4:3 windows up to 1280×960 physical pixels (rotation-independent; ratio tolerance 0.08). `domain/DisplayLayout.kt` holds the tested detector. The user can choose **Standard controls**, Compact panel or Room display explicitly in Settings. Existing explicit choices take precedence.
- Agreement: compact typography, scrollable copy and a separate always-reachable action row. Sign-in: compact spacing/headings on short screens, horizontal fields/actions when wide enough, vertical fallback otherwise, scrollable IME-safe content and working Next/Done focus. Password state remains unsaved.
- The user explicitly requested **rebuild and rerun**. The optimized 0.4.0 APK was packaged, prior 0.3.0 package/checksum removed, and the app was successfully updated/relaunched in the existing emulator without restarting the AVD or clearing app data. `android/build/low-res-reload.json` reports ready, app PID 5531; the log records installation Success and the MainActivity launch. Saved agreement and settings are preserved; a demo session may require choosing Demo again after process restart.

Deliverable: **`output/apk/EntAlt-0.4.0-optimized.apk`**, version code 4, approximately **1.65 MiB**, signature verified against the previous development-signed APK. SHA-256 is alongside it. **45 optimized JVM tests and lint pass** (`optimized-check.json`, exit 0); resources match in both languages. The new detector has regression coverage. No automated UI gestures or acceptance actions were performed; the user's reported clipping/visual layout must be checked manually after the update. Earlier UI-suite failures remain documented below.

## Previous 0.3.0 handoff (historical)

The timed pause ended when the user explicitly said **resume** at about 13:41 Europe/Ljubljana on 2026-11-16. All queued clock/local-customization requests are now implemented.

Deliverable: **`output/apk/EntAlt-0.3.0-optimized.apk`**, version code 3, **1,662,979 bytes (1.59 MiB)**. SHA-256 is in its sibling `.sha256`. The prior 0.2.0 packaged APK and checksum were deleted as requested. Generic filename, no device-model tag. The optimized APK is signed with the same development key as earlier builds and can update them.

- **Settings → Display focus** chooses Clock (default) or Room temperature. Clock is dominant with the weekday directly below; temperature readings are secondary in clock focus. The preference persists locally, including in demo mode.
- Routine socket state is quiet: the always-present Live/REST banner and bright Wi-Fi indicator are gone. Connection details remain behind a neutral Info action and Settings → Connection. The socket is still used for attribute events, with the existing five-minute REST/reconnect/foreground/manual/post-write reconciliation. A socket loss alone does not mark REST offline or block commands; real data/settings failures and uncertain command outcomes remain visible.
- Long-press a device card/header to rename, choose one of six light icons, or remove/hide it locally. Icons: Bulb, Ceiling, Pendant, Table lamp, Spotlight, LED strip. **Settings → Hidden devices → Show** restores it. There are no Entia writes for customization. Account-owned overrides apply after REST and survive events; original server names/readings remain available. Hidden devices and newly empty rooms are excluded consistently, including room-display temperature selection. Real-account overrides are cleared with sign-out/account removal, matching existing account-isolation policy. Demo uses a distinct local owner and persists its preferences without credentials/network access.
- Added an `optimized` Gradle build type with release settings, R8 minification, resource shrinking and no debug tooling; it is development-signed. `proguard-rules.pro` preserves the Page enum's Bundle identity across builds.

**Passed:** `:app:assembleOptimized :app:testOptimizedUnitTest :app:lintOptimized`, 44 tests (Domain 16, API 4, Repository 19, socket 5), APK signature/certificate comparison. Debug build, 44 debug JVM tests, lint, and test-APK compilation also passed before the optimized variant was added. See `android/build/optimized-check.json` (exit 0) and `.log`.

The user explicitly requested a visible low-resolution 4:3 emulator after finishing. The approximation was revised from 320×240 to **800×600 at 240 dpi** based on their feedback that the photographed unit is probably higher resolution. Exact hardware pixels/density remain unknown. No emulator was connected before this launch. `android/core-check/build/open-low-res.py` starts one visible emulator with isolated `android/build/low-res-emulator-data`, sets the framebuffer/density, installs the optimized APK, launches MainActivity and checks the process. Status/logs: `android/build/low-res-emulator.json`, `low-res-emulator.log`, `low-res-app.log`. Final status is **ready**: emulator PID **35104**, serial **emulator-5554**, configured 800×600/240 dpi, app PID **2664** at handoff. Installation returned Success and MainActivity remained running after startup. The first immediate emulator ADB screen-timeout warnings were transient during boot; app installation/launch completed afterward. The first-use agreement must be accepted manually; do not automate agreement or interact with controls on the user's behalf.

No automated UI suite was rerun. New layouts/gestures, physical panel behavior, live-account operation and process/reboot persistence remain unverified; the previous 0.1.0 UI-test failures still need diagnosis.

## Authorization and constraints

- Native Android app in `android/`, app name **EntAlt**, namespace `si.entalt.app`.
- Dark Material Design 3; phone, tablet, short landscape, and tiny panel layouts.
- The preserved `reference/home-assistant-entia/` integration is the primary protocol contract. Swagger is supplementary.
- No live credentials have been supplied. Demo data and all test credentials are synthetic. Do not claim live-account or physical-device validation.
- **No computer-use/UI automation and no sub-agent delegation.** Files, terminal, and connected IDE tools are authorized. Development instrumentation/ADB checks were allowed by the earlier checkpoint; the user subsequently questioned the repeated launches and asked for a visible emulator to try manually. Further automatic UI runs should wait until this manual session is over.
- This directory is not a Git repository.

## Earlier emulator handoff (historical, before 0.3.0)

The visible emulator was opened through the IDE terminal and uses isolated test userdata:

- AVD template: `Small_Phone`, Android 16/API 36, 720 × 1280 physical pixels, 320 dpi.
- Serial: `emulator-5554`; emulator process at handoff: **29107**.
- Data: `android/build/test-emulator-data/`; existing AVD userdata was not wiped.
- EntAlt debug APK installed and `.MainActivity` launched.
- Sign-in screen offers **Explore the demo home** for simulated controls.
- Launcher status: `android/build/visible-emulator.json`; launch/install log: `android/build/visible-app-install.log`.
- A temporary launcher is saved in ignored `android/core-check/build/open-app.py`. It opens one visible emulator and installs/launches the APK; it does not interact with the app UI.

The initial headless emulator had `RUNNING_LOCKED` user storage and blocked instrumentation startup. It was stopped. A second headless emulator using fresh isolated userdata reached `RUNNING_UNLOCKED` and ran the tests. It was then stopped for the user's visible/manual session. The 21 test cases opened test activities repeatedly; there were **two headless emulator boots**, followed by **one visible emulator boot** at the user's request.

## IDE and build environment

The updated `idea` MCP endpoint works at `http://127.0.0.1:64343/stream`. It now finds the EntApp root, but `get_project_modules` still reports only an `EntApp` Python module. IDE `build_project` success is therefore not proof of an Android build. The actual Gradle build was run successfully through `execute_terminal_command`, from `android/`.

For normal Android Studio Run/Sync, open **`android/`** as the Gradle project. SDK/JDK/Gradle paths and version pins are unchanged from the previous checkpoint. Local sandboxed terminal sockets remain restricted; use the working IDE terminal when necessary. Do not request forbidden escalation or repeatedly retry the same sandbox restriction.

Long IDE terminal calls can time out and terminate the Gradle client. A repeatable runner now survives this:

```sh
python3 scripts/check-android.py --background --name build-check
```

This defaults to `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. It writes atomic status/exit-code JSON and a full log under `android/build/`. Custom tasks can be passed as positional arguments. Avoid simultaneous runs with the same name. The current **build-check.json reports exit_code 0**. No build/test process remains running; the visible emulator is intentionally left open for the user.

## Latest user requests: visibility and installable APK

The user requested an installable APK, then required filtering before delivery:

- Display a device/card only when it exposes a supported status reading or action; keep read-only temperatures.
- Hide unsupported placeholders, unavailable records, and cards with no usable readings/actions.
- Show a room only when at least one visible device remains. Apply the same policy to counts, floor filters, pickers, preferred/last room restoration, and Previous/Next.
- Source snapshots retain hidden records, including valves used to infer climate activity. Incoming events/snapshots recompute visibility and clear a selected room that becomes unusable.
- These changes are implemented and covered by five new domain/repository tests. The build, **39 JVM tests**, and lint pass; no emulator instrumentation was restarted for this change.
- An installable test APK is packaged under `output/apk/`, with installation notes and SHA-256. It contains the visibility changes. It remains a test build; prior UI failures and live-account limitations are unresolved.
- The user's current visible emulator session retains the earlier APK; it was not force-stopped or reinstalled during this change.

## 0.2.0 update: shared ventilation, display mode, agreement

The current deliverable is **`output/apk/EntAlt-0.2.0-debug.apk`**, a universal build, version code 2. Its signature was verified; SHA-256 is saved alongside it. At the user's explicit request, the old 0.1.0 packaged APK and checksum were deleted, and the build name has no phone-model reference. The running emulator was not reinstalled or restarted.

User requirements added in this update:

- Check whether recuperation belongs to rooms or the home. The reference `climate.py` creates one HRV entity per device with attribute 3702 and writes speed 3702/preset 3701 to that device ID. It does not establish how many units exist in a real account. Ventilation is now accessed from **Home controls**, with each discovered unit kept separate. Room controls/counts/navigation omit its global-only card; original room metadata is preserved.
- Smaller cards where appropriate: light tiles toggle directly and temperature tiles are compact. Phones can show two simple cards per row, with larger cards spanning more columns; larger fonts return to one column. Climate/ventilation choices use wrapping chips. Previous/Next now occupy a solid tonal surface with filled buttons, in reserved layout space.
- A calm **Room display** mode for a very small **4:3 wall unit**. The user provided a photo of its original dark thermostat screen; exact pixel resolution and Android density remain unknown. `RoomDisplay.kt` uses a large measured indoor temperature, device clock and localized weekday, plus Entia outside temperature (attribute 1104, raw divided by two). Small landscape windows use two columns. There are source previews for **320×240 and 240×180 dp**, but they have not been rendered. Missing readings show a dash; conflicting outside readings are not arbitrarily combined.
- Enable it under **Settings → Layout → Room display**. A separate **Room on display** picker persists an account-specific room. Automatic prefers room labels containing `Living room` or `Dnevna`, then the first room with an indoor reading. Full-screen mode hides system bars transiently and keeps the screen on; both are restored when leaving the mode. Own Rooms/Settings buttons stay reachable. The clock ticks only while the lifecycle is started.
- **One-time explicit agreement**: a scrollable English/Slovenian first-use screen says this is unofficial/independent, not affiliated with or endorsed by Entia, and used at the user's own risk. It requires **I understand and agree**, or Exit. Acceptance is saved in DataStore per installation, survives account changes/logout/updates, and resets with uninstall/app-data removal. The repository defers credential loading and API client creation until successful persistence. Save failure keeps the gate closed and permits retry.

Validation for this update: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` passed, **39 JVM tests** passed, resources match in both languages, and APK v2 signature verification passed. New tests cover home-level/multiple ventilation units, actual vs target indoor readings, outdoor scaling/ambiguity, living-room preference/account isolation, agreement before credential/network access, saved agreement across logout/new repository, and failed-save retry. The test APK was compiled, **not executed**. Prior UI failures, new layout visual review, physical wall-panel compatibility, and live-account verification remain outstanding.

## Changes completed after resuming

- Fixed real Android compilation errors: disambiguated `super<Application>.onCreate()` and captured the outer window width before nested layout scopes.
- Fixed Android 8.0 lint compatibility by moving `windowLightNavigationBar` into a v27 theme overlay with a shared base theme. Removed an obsolete v26 launcher folder qualifier and switched Back icons to their mirrored variants.
- Added Android-free `TextCatalog`/`TextKey` injection and Android resource binding. Repository errors, HTTP permission/rate-limit messages, fallback hierarchy labels, and demo labels now use English/Slovenian resources. There are **175 matching string keys** in each language; all source references are covered. Internal parser/API exceptions remain English but are not surfaced verbatim by the repository.
- Preserved Rooms filter/scroll state when opening a room; scoped room view state to account/room and retained navigation across saved-state restoration. A removed floor no longer hides all rooms behind an obsolete filter.
- Changed compact Room pages to scroll the picker, control card, and Previous/Next actions together. Rooms headings/floor chips also scroll with the grid. The compact demo banner stays on one line.
- Gave power switches device-specific accessibility labels. Numeric editors disable Apply if their required live reading disappears.
- Fixed demo blind commands leaving derived tilt permanently pending; simulated observations now complete their tilt state. A JVM regression test covers position → tilt → position → tilt.
- Added configurable WebSocket endpoint injection (production default unchanged), plus actual OkHttp/MockWebServer handshake/frame/cancellation/auth/buffer tests on localhost.
- Added isolated Android Keystore tests using separate fixture file/key names, and a six-window Compose instrumentation matrix.
- Exported opaque `store/entalt-play-icon.png` (**512 × 512**) and generated/exported `store/entalt-feature-graphic.png` (**1024 × 500**). Masters and exact built-in imagegen prompts/provenance are in `store/brand/`. The feature graphic is promotional artwork, not a screenshot.

## Verification actually performed

**PASS on current application sources:**

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Latest explicit run: `android/build/build-check.json`, exit code **0**. APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Current Gradle test reports contain **44 tests, zero failures**: Domain 16, API 4, Repository 19, real socket transport 5. The optimized build also passes 44 tests and lint; see the 0.3.0 handoff above. Lint succeeds; dependency-update suggestions remain warnings. No dependency upgrades were made simply to remove those warnings.

The offline checker `python3 scripts/check-core.py` also passed **24 tests** after the new demo regression test. It excludes real socket tests and Android/Compose code. The full Gradle test results above are the authoritative verification.

**Instrumentation: 21 ran, 9 passed, 12 failed** on the isolated API 36 emulator. Results: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Small_Phone(AVD) - 16-_app-.xml` and `android/app/build/reports/androidTests/connected/debug/index.html`.

Passed:

- Demo entry → room selection → light toggle → Back in all six configurations: 240×240, 320×320, 480×320, 360×800, 1280×800, and 240×240 with Slovenian/200% font scale.
- Actual Android Keystore encrypted record round-trip/replacement/new-instance load/clear, damaged ciphertext rejection, and missing-key rejection.

Failed:

- Temperature/editor/navigation and blind/ventilation/sensor test methods in every window configuration.
- Failures include targets reported not displayed after scrolling, stale/ambiguous nodes, scrolling an already visible tablet node without a scrollable ancestor, and localized Apply text not found in the large-text Slovenian dialog. **Separate test-helper problems from real layout/dialog problems before changing production code.** Do not claim all controls or panel layouts validated.
- The instrumented build predates the final change to screenshot output location. Tests originally wrote screenshots into app external files, which can be deleted when Gradle uninstalls the fixture. The updated test writes to `additionalTestOutputDir` for Gradle collection, but this small test-only change has not been rerun. No curated screenshot set or visual acceptance review has been completed.

No live Entia account, physical wall panel, TalkBack session, app process-death/reboot credential restoration, or complete DataStore failure/restart instrumentation has been validated. New-instance credential load is not a process/reboot test.

## Next implementation work

1. Let the user use the open 800×600 emulator or install the 0.3.0 optimized APK. Accept the first-use notice manually, choose demo/sign-in, then choose Room display and Display focus in Settings. Do not interfere with their open emulator or enter credentials on their behalf. The exact wall-panel resolution/density is unknown; use the provided photo and 4:3 requirement without claiming hardware verification.
2. Inspect the existing instrumentation XML and test helper before any further suite run. Diagnose compact scrolling and numeric editor behavior; tablet failures may be selector/helper issues. Add focused checks only when the manual session has ended.
3. Complete persistence/process/reboot and DataStore instrumentation with isolated fixture storage. Never reset a real signed-in account to run tests.
4. Capture and inspect genuine rendered screenshots once the relevant flows pass. Finish visual/accessibility review for every capability and the plan's screen matrix; current screenshots/physical-panel support are not accepted yet.
5. Continue live protocol and physical blind-direction validation only when the user supplies an account/device environment; credentials must stay out of source/logs.
