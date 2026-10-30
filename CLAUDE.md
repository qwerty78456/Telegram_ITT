# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A fork of upstream Telegram for Android (DrKLO/Telegram, currently on 13.1.1) for a headless Android 10 smart helmet: one back camera, no screen, no Google Play Services, root `adb shell`. The fork adds an SOS hardware button handler and a single-camera fallback for video calls; everything else is upstream. **[HELMET.md](HELMET.md) is the source of truth for the fork**: behaviour, the list of changed files, build/install steps, device test status and known limitations. When you change helmet behaviour, update its file table and Status table too. [README.md](README.md) below its first line is upstream's.

Upstream releases arrive as large "update to X.Y.Z" commits, so fork changes stay small and confined to the files listed in HELMET.md.

## Building

Submodules must be checked out first: `settings.gradle` applies `TMessagesProj_Modules/media/core_settings.gradle`, so Gradle won't even configure without them, and the native build needs the ones under `TMessagesProj/jni`.

```bash
git submodule update --init --recursive --depth=1
```

Requirements: SDK 36, build-tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1, and a **JDK 17 or 21** for Gradle 8.13 (it does not run on Java 25, Android Studio's bundled JDK).

The repo has no `gradlew.bat`. On Windows use `build-helmet.cmd` (calls the wrapper jar directly; JDK from `HELMET_JDK`, SDK from `ANDROID_HOME`; extra args go to Gradle; output in `build-helmet.log`):

```bat
set HELMET_JDK=C:\path\to\jdk-17-or-21
build-helmet.cmd
```

It runs `:TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a`. From Git Bash the POSIX wrapper works too: `./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a`.

- Because the build is limited to one ABI, the APK lands in `TMessagesProj_App/build/intermediates/apk/afat/debug/app.apk`, not `outputs/`.
- The first build compiles all native code (~20 min); Java-only rebuilds take a few minutes. Building without `-Pandroid.injected.build.abi` compiles native code for all four ABIs.
- Only the `afat` flavor exists for non-release build types (`variantFilter`); `bundleAfat` is release-only. Debug installs as `org.telegram.messenger.beta`.
- `Dockerfile` reproduces upstream's release build (`bundleBundleAfatRelease`, `assembleAfatStandalone`, Huawei `assembleAfatRelease`).
- `BuildVars.java`, `TMessagesProj/config/release.keystore` and `TMessagesProj/google-services.json` are upstream's dummy/public values; don't publish builds with them.

## Tests

There are no JVM unit tests. The only automated tests are instrumentation tests in `TMessagesProj_AppTests` (Kotlin, needs a connected device/emulator, minSdk 26) covering TL schema serialization, DB migration and the TLS handshake:

```bash
./gradlew :TMessagesProj_AppTests:connectedAfatDebugAndroidTest
./gradlew :TMessagesProj_AppTests:connectedAfatDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.telegram.tgnet.test.NativeSchemeTest
```

The `test-generator` plugin (`buildSrc/src/main/kotlin/com/example`) runs `generateScheme` before every `preBuild` and regenerates `src/androidTest/kotlin/.../generated/` from `TMessagesProj_AppTests/tlscheme/*.json` and the TL classes in `TMessagesProj`/`TMessagesProj_AppStandalone`. Don't hand-edit generated files.

Helmet features have no automated tests; they're verified on the device by sending the button broadcasts over adb and reading `HelmetSos:` log lines (commands in HELMET.md, "Testing and logs").

## Architecture

### Modules

- `TMessagesProj` is an Android **library** holding essentially the whole app: Java under `org.telegram.*`, resources, and native code (`jni/`, CMake target `tmessages.49`).
- `TMessagesProj_App` (Play/regular), `TMessagesProj_AppHuawei`, `TMessagesProj_AppStandalone` (web/direct APK) and `TMessagesProj_AppHockeyApp` are thin application shells: an `ApplicationLoader` subclass (`ApplicationLoaderImpl`, or `HuaweiApplicationLoader`) plus store-specific push, maps, billing and voice services. Shared code goes in `TMessagesProj`, not in a shell.
- `buildSrc` has the custom Gradle plugins: `org.telegram.build-plugin` (library; generates localization helper Java) and `org.telegram.build-app-plugin` (apps; merges `strings.xml` from `TMessagesProj` and the app module into generated resources with stable ids, plus string-id and Lottie metadata assets), and the TL-schema code generator.

### Core library (`TMessagesProj/src/main/java/org/telegram`)

- `tgnet/`: MTProto networking. `ConnectionsManager` is the JNI bridge to the C++ stack in `jni/tgnet`; `TLRPC.java` and `tgnet/tl/` hold the TL schema classes.
- `messenger/`: per-account controllers and app services. The app supports `UserConfig.MAX_ACCOUNT_COUNT` (4) accounts; `AccountInstance` groups the per-account singletons (`MessagesController`, `MessagesStorage` (SQLite), `ContactsController`, `NotificationsController`, `ConnectionsManager`, …), each obtained with `X.getInstance(account)` and extending `BaseController(currentAccount)`. Code that touches account data must carry an account index; there is no global "current user".
- `NotificationCenter` is the internal event bus, per account (`getInstance(account)`) plus `getGlobalInstance()`.
- Threading: UI work via `AndroidUtilities.runOnUIThread`; background work on the `DispatchQueue`s in `Utilities` (`stageQueue`, `globalQueue`, …); `MessagesStorage` has its own per-account `storageQueue`.
- `ui/`: no XML layouts or AndroidX fragments. Screens are `BaseFragment` subclasses (often named `*Activity`) shown with `presentFragment(...)` inside the custom `ui/ActionBar` navigation stack, with views built in code and colours from `Theme`. Simple settings screens extend `UniversalFragment` and fill a list of `UItem`s (see `HelmetSosSettingsActivity`).
- `messenger/voip/`: `VoIPService` is the foreground service that owns a call's state; `VoIPHelper` starts calls from the UI; `VideoCapturerDevice` feeds the camera into WebRTC (`jni/voip`, `jni/tgcalls`).
- Strings: add new ones only to `TMessagesProj/src/main/res/values/strings.xml` (English) and read them with `LocaleController.getString(R.string.X)`. Translations come from translations.telegram.org, not from this repo.
- Logging: `FileLog.d/e`, written only when `BuildVars.LOGS_ENABLED` (debug builds).
- Small typed preferences live in `utils/settings/SharedSettings` (`BooleanSetting.of(key, default)` etc., stored in `mainconfig`).

### The helmet fork

- `HelmetSosController` (`messenger/voip/`) is initialised from `ApplicationLoader.onCreate()` and registers a runtime receiver for the vendor broadcasts `rd.sdk.action.F11_DOWN6` / `F11_UP6`, requiring sender permission `android.permission.INJECT_EVENTS` so only `system_server`/root can trigger it. It only works while the process is alive (implicit broadcasts can't use a manifest receiver on API 26+).
- Outgoing SOS calls start `VoIPService` directly with `startForegroundService` (no Activity, like `VoIPHelper` without UI). Incoming calls are only answered in `VoIPService.STATE_WAITING_INCOMING`; answering earlier makes Telegram ring again later.
- The device is headless, so failures must produce a toast plus an error tone, and every decision is logged with the `HelmetSos:` prefix.
- Settings are the `helmet_sos_*` entries in `SharedSettings`; the SOS contact is stored together with the account that picked it.
- `VideoCapturerDevice.init()` falls back to the first available camera when the requested facing doesn't exist and reports the real facing; `VoIPService` starts with `isFrontFaceCamera = VideoCapturerDevice.hasFrontCamera()`, and `VoIPFragment` / `PrivateVideoPreviewDialogNew` hide the flip button and front-camera page on single-camera devices.
