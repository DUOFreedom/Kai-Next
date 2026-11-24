# Device Control (Privileged Shell, /sdcard, UI Automation)

**Last verified:** 2026-11-24

The Android build can grant the AI root or Shizuku (adb shell) privileges to run commands, read and write shared storage, and drive the phone's user interface. Every capability is off by default and gated behind its own switch.

## Concepts

### Privilege Source

Three ways to drive the phone, in order of preference:

- **Accessibility** — the user turns on Kai's accessibility service in system settings. No root, no extra app, no adb: the service reads the live node tree (exact bounds and labels) and dispatches gestures through the same pipeline the system uses for TalkBack. This is the backend that keeps working on locked-down devices.
- **Root** — spawns `su -c <command>`. Works on rooted devices with a standard `su` binary; no extra app required.
- **Shizuku** — connects to the Shizuku app's binder and runs commands through `Shizuku.newProcess`, which gives adb shell (uid 2000) privileges after the user grants access. Requires the Shizuku app to be installed and running.

**Auto** mode prefers root, then Shizuku, then an unprivileged `sh` child of the app process — the last one is only useful for app-private paths and cannot read `/sdcard` under scoped storage.

UI automation is the exception to that ordering: it reads the tree through accessibility first and falls back to a shell-driven `uiautomator` dump, so a phone with neither root nor Shizuku can still be operated.

### Screen capture

A fourth source, separate from the three above because it needs a consent dialog rather than a privilege: MediaProjection mirrors the display into the app's own video pipeline. The user turns it on in Settings → Device → Screen capture, accepts the system dialog ("Kai wants to start capturing the screen"), and from then on the app holds a virtual display it can pull frames from. Off by default, like everything else here.

The capture runs in a foreground service so Android does not freeze it when the app is backgrounded, which is where an automation loop spends most of its time. Android 14 and later require the `mediaProjection` foreground-service type, declared both in the manifest and at `startForeground`; earlier versions ignore the type and are otherwise unchanged. The service draws frames from an `ImageReader` behind a `VirtualDisplay` with `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`, and rebuilds both when the display metrics change so a rotation does not leave it feeding stale geometry.

Consent is never bypassed and never re-asked invisibly: the projection ends when the user stops it from the system UI or revokes it, and the app learns that from `MediaProjection.Callback` rather than from a read it made earlier. The settings switch follows a `StateFlow` the service owns for that reason — a single read taken when the dialog was raised would show "off" for as long as the user took to tap Allow, and would stay "on" after they stopped capture from the notification shade.

### Capabilities

| Switch | Tool exposed to the AI | What it does |
|---|---|---|
| Shell commands | `android_shell` | Runs arbitrary shell commands (`am`, `pm`, `settings`, `dumpsys`, `input`, `getprop`, …) with a timeout and output cap. |
| /sdcard file access | `android_files` | `list`, `read`, `write`, `append`, `delete`, `move`, `mkdir`, `stat` under `/sdcard`, `/storage`, `/mnt/media_rw`. Path traversal (`/../`) is rejected. |
| Phone UI automation | `android_ui` | `dump` (condensed accessibility tree with node bounds and tap centers), `tap`, `swipe`, `text`, `key`, `launch`, `current_app`, `screenshot`, `wait`, `back`, `home`, `recents`. |

Files are read/written through the privileged shell rather than the app process, so `android_files` works even when the app itself lacks `MANAGE_EXTERNAL_STORAGE`.

Screenshots do not need root either. `android_ui`'s `screenshot` action tries three backends in order, and stops at the first that yields an image:

1. `screencap` through the privileged shell, when root or Shizuku is available. The PNG lands in `/sdcard`.
2. A frame from an active screen-capture projection, when the user approved one. Needs no privilege at all and keeps working while the app is backgrounded; the PNG is written to app-private cache.
3. The accessibility service, on Android 11 and up, when neither of the above applies.

Every backend returns a full-resolution PNG — never scaled, never re-encoded — because the agent reads small text off it, and the reply names the backend that produced the image so a refusal reads as a refusal instead of a blank screen.

## Behavior

- Settings → Device shows the master toggle, the privilege-source chips (Auto/Root/Shizuku), a live status row, and one switch per capability.
- Toggling the master on probes for root and Shizuku and reports which backend would be used, plus a "Grant Shizuku access" action when Shizuku is running without permission.
- Tool availability is decided by the master and per-capability switches; the three tools are not individually listed in the Tools tab, matching how sandbox tools are gated.
- UI automation is off by default because it can operate the whole phone; a model with the tool enabled can tap through any app.
- The manifest declares `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (legacy), and `MANAGE_EXTERNAL_STORAGE` for app-side consumers, and registers the `ShizukuProvider` bridge.
- Screen capture asks for system consent the first time and shows a persistent notification while it runs; stopping it from the notification shade or from the system's own capture UI switches the setting off too.
- Screenshot requests are rate-limited to roughly three per second on the accessibility path, because the platform answers a faster request with an error rather than an image; the wait is applied before the capture so a screenshot/tap/screenshot loop does not lose frames. The other two paths have no such limit, and the video pipeline of an approved projection can be sampled continuously.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../DeviceControl.kt` | `DeviceControlManager` expect declaration and status model |
| `composeApp/src/androidMain/.../DeviceControl.android.kt` | Probes root/Shizuku, reports the effective backend |
| `composeApp/src/androidMain/.../tools/PrivilegedShell.kt` | Root/Shizuku process spawning, timeout, output capture, shell quoting |
| `composeApp/src/androidMain/.../tools/DeviceControlTools.kt` | `android_shell`, `android_files`, `android_ui` |
| `composeApp/src/androidMain/.../Platform.android.kt` | Injects the tools into the agent tool set behind the toggles |
| `composeApp/src/androidMain/.../capture/ScreenCaptureService.kt` | Foreground service holding the projection, virtual display and frame reader |
| `composeApp/src/androidMain/.../capture/ScreenCaptureController.kt` | Consent launcher, start/stop, PNG encoding of the latest frame |
| `composeApp/src/androidMain/.../MainActivity.kt` | Registers the consent-result launcher and installs the controller |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Master/per-capability toggles and privilege mode |
| `composeApp/src/commonMain/.../data/DataRepository.kt` | Probe/permission/toggle surface |
| `composeApp/src/commonMain/.../ui/settings/DeviceControlSettings.kt` | Settings tab UI |
| `androidApp/src/main/AndroidManifest.xml` | Storage permissions and Shizuku provider |
