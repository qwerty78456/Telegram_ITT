# Telegram for the smart helmet (SOS button fork)

This fork of [Telegram for Android](https://github.com/DrKLO/Telegram) adds support for a headless Android 10 smart helmet (a Runde `rd_aqm6812`, MediaTek MT8768, one camera, no screen, no Google Play Services):

- the helmet's **SOS button** answers Telegram calls and dials a chosen contact
- **video calls use the helmet's only camera**, which Android reports as a back camera

All other Telegram behaviour is unchanged. The work sits on top of upstream 13.1.1 (developed on the `helmet-sos` branch).

## What the SOS button does

| Situation | SOS action |
|---|---|
| A 1:1 Telegram call is ringing | **Press:** answer it (the same path as the notification's Answer button) |
| An incoming call is still connecting (not ringing yet) | **Press:** answer it as soon as it starts ringing (waits up to 6 s) |
| No call | **Press:** call the contact chosen in settings, voice or video |
| A call is in progress (outgoing ringing or connected) | **Hold for 2 s:** hang up. A short press does nothing |
| A conference invite is ringing | Nothing (SOS never places a call over it) |

When something prevents a call, the helmet shows a toast and plays a short error tone (it has no screen of its own):

| Message | Cause |
|---|---|
| `SOS: no contact set` | No contact chosen, or the account that chose it is logged out |
| `SOS: microphone access is not allowed` | `RECORD_AUDIO` not granted |
| `SOS: camera access is not allowed, answering without video` | An incoming video call is answered while `CAMERA` is not granted |
| `SOS: not connected to Telegram` | No network at all |
| `SOS: contact not found` | The contact is not in the local database or is deleted |

### Settings

**Settings → Helmet SOS button**:

- **Use SOS button**: on by default
- **Contact to call**: opens the contacts picker (bots, yourself and deleted accounts are rejected). The contact is stored together with the account that picked it, so it keeps working if account slots move, and shows "Not set" if that account logs out
- **Start as video call**: off by default. Falls back to a voice call if camera access isn't allowed
- **Microphone and camera**: shows whether both permissions are allowed; tap to request them, or to open the app's system settings if they were denied permanently

## How it works

The helmet's vendor-modified `system_server` sends an implicit broadcast for each hardware button. For SOS:

| Event | Broadcast |
|---|---|
| Press | `rd.sdk.action.F11_DOWN6` |
| Release | `rd.sdk.action.F11_UP6` |

They are sent by `system_server` (uid 1000) to all users. A receiver gets one DOWN per press and one UP per release. `system_server` logs a "Sending non-protected broadcast" warning twice for each, but only one broadcast is delivered. The foreground app also gets `KEYCODE_F11` key events, which this fork ignores.

| File | Change |
|---|---|
| `TMessagesProj/src/main/java/org/telegram/messenger/voip/HelmetSosController.java` | New. Registers the receiver in `ApplicationLoader.onCreate()`, with `android.permission.INJECT_EVENTS` as the required sender permission: system and root pass every permission check, so the real button works while other apps can't fake a press. Debounces presses (800 ms), decides answer / dial / hold-to-hang-up, and gives feedback |
| `TMessagesProj/src/main/java/org/telegram/ui/HelmetSosSettingsActivity.java` | New. The settings screen |
| `TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java` | The "Helmet SOS button" row in Settings |
| `TMessagesProj/src/main/java/org/telegram/utils/settings/SharedSettings.java` | The six settings (`helmet_sos_*` in `mainconfig`) |
| `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java` | Calls `HelmetSosController.init()` |
| `TMessagesProj/src/main/res/values/strings.xml` | The new strings |
| `TMessagesProj/src/main/java/org/telegram/messenger/voip/VideoCapturerDevice.java` | Camera fix, see below |
| `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java` | `isFrontFaceCamera` starts as `VideoCapturerDevice.hasFrontCamera()` |
| `TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java` | Hides the flip-camera button when there's only one camera |
| `TMessagesProj/src/main/java/org/telegram/ui/Components/voip/PrivateVideoPreviewDialogNew.java` | Hides the front-camera page when there's no front camera |

Outgoing SOS calls start `VoIPService` directly with `startForegroundService` and the stored account, like `VoIPHelper` does but without needing an Activity. An incoming call is only answered in `VoIPService.STATE_WAITING_INCOMING`; answering earlier makes Telegram ring again later.

### The camera fix

Upstream asks for the front camera by default, and `VideoCapturerDevice.init()` silently opened **nothing** when the requested camera didn't exist. Now it falls back to the first available camera and reports the camera's real facing to `VoIPService`. On phones that have the requested camera, nothing changes.

## Building on Windows

| Requirement | Version |
|---|---|
| Android SDK platform | 36 (`compileSdk 36`) |
| Build tools | 36.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| JDK for Gradle | **17 or 21**. Gradle 8.13 does not run on Java 25, which is the JDK bundled with current Android Studio |

The repo only ships the Unix `gradlew` script, so use **`build-helmet.cmd`** in the repo root. It runs the Gradle wrapper jar directly and builds an arm64-only debug APK:

```bat
set HELMET_JDK=C:\path\to\jdk-21
build-helmet.cmd
```

- Without `HELMET_JDK`, the script uses `%LOCALAPPDATA%\Programs\jdk-21.0.13+9`.
- The SDK is taken from `ANDROID_HOME`, or `%LOCALAPPDATA%\Android\Sdk`.
- The log goes to `build-helmet.log` in the repo root.
- The APK lands in `TMessagesProj_App\build\intermediates\apk\afat\debug\app.apk`. It's under `intermediates`, not `outputs`, because the build is limited to one ABI.
- The first build compiles all native code (about 21 minutes on a 12-thread PC). Java-only rebuilds take about 4 minutes.

In Android Studio, open (don't import) the repo, set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** to a JDK 17 or 21, and build the `afatDebug` variant of `TMessagesProj_App`.

The debug build installs as **`org.telegram.messenger.beta`**, next to an official Telegram. It is signed with the repo's dummy keystore and still uses upstream's API id and hash (`BuildVars.java`) and Firebase config (`google-services.json`). Before publishing builds, follow the upstream [README](README.md) and use your own.

## Installing on the helmet

```bat
adb install -r -t TMessagesProj_App\build\intermediates\apk\afat\debug\app.apk
adb shell pm grant org.telegram.messenger.beta android.permission.RECORD_AUDIO
adb shell pm grant org.telegram.messenger.beta android.permission.CAMERA
adb shell appops set org.telegram.messenger.beta SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +org.telegram.messenger.beta
```

| Step | Why |
|---|---|
| Grant microphone and camera | Nobody can tap a permission dialog on a headless helmet |
| Allow "display over other apps" | Android 10 lets the call screen open from the background |
| Exempt from battery optimisation | The helmet has no Google Play Services, so no push wake-ups; Telegram has to keep its own connection |

Then log in (through scrcpy or similar), and choose a contact under **Settings → Helmet SOS button**. If an official Telegram on the same device is logged in to the same account, both will ring for incoming calls.

## Testing and logs

Simulate the button from a root adb shell (the helmet's `adb shell` is root):

```bat
adb shell am broadcast -a rd.sdk.action.F11_DOWN6
adb shell am broadcast -a rd.sdk.action.F11_UP6
```

Every decision is logged with the prefix `HelmetSos:`.

- **Logcat:** the helmet filters debug-level logs. Enable them with `adb shell setprop log.tag.tmessages D`, which resets on reboot.
- **Log files:** Telegram also writes full logs to `/sdcard/Android/data/org.telegram.messenger.beta/files/logs/`.

### Status

| Test | Result |
|---|---|
| Real SOS button reaches Telegram through the `INJECT_EVENTS` sender check | Works |
| One press triggers exactly one SOS action | Works |
| Press with no contact set: toast and tone | Works |
| Press while idle: video call to the chosen contact rings their phone, back camera detected | Works |
| Hold for 2 s to hang up | Not tested on the device yet |
| Press to answer an incoming call | Not tested on the device yet |
| Helmet camera picture in a connected video call | Not tested on the device yet |

## Known limitations

- **The process must be alive:** the receiver is registered at runtime, so SOS only works while Telegram's process is running. Implicit broadcasts can't be received by a manifest receiver on API 26+.
- **Network stalls:** if Telegram's servers can't be reached even though there is a network, an SOS call stays in "requesting" until you hold SOS to hang up.
- **Group calls:** the old group-call camera preview still offers front and back pages. On a single-camera device, switching is now a harmless no-op.
- **Debounce:** a DOWN within 0.8 s of the previous one is ignored. This is a safeguard only; the helmet sends one DOWN per press. It also means two deliberate presses less than 0.8 s apart count as one.
