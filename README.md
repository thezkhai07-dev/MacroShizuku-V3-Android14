# MacroShizuku V3 — Android 14 Debug

Android macro recorder/player using Shizuku, floating controls, multi-macro playback, backup/restore, and volume-key hotkeys.

## Build target

- `compileSdk 35`, `targetSdk 35`, `minSdk 26`.
- Touchscreen capture uses `AccessibilityService.onMotionEvent` and is available only on Android 14 (API 34) and newer.
- The GitHub workflow builds a **debug APK**. It is for testing, not Play Store distribution.

## Important Android 14 touch-capture limitation

Android documents that motion events from sources requested by `AccessibilityServiceInfo.setMotionEventSources()` are **not sent to the rest of the system**. Therefore, while this app is recording touchscreen events, those touches are not forwarded to the app underneath. This API cannot transparently record ordinary taps while guaranteeing that the target app receives the same taps. The current recorder is therefore experimental and is not yet a practical recorder for interacting with a normal target app.

To avoid blocking touch input when not recording, the service requests the touchscreen source only during an active recording and releases it when recording stops. Press **Volume Down** once to stop capture, return to MacroShizuku, then press **LƯU** to save the captured actions. The implementation currently records single-pointer tap/swipe gestures; multi-touch is not implemented.

## Requirements and permissions

- Shizuku installed, running, and permission granted to MacroShizuku.
- Accessibility service enabled for recording and global volume-key hotkeys.
- “Display over other apps” permission for floating controls.
- Android 14+ for touch recording. Macro playback/hotkeys do not require the touch-recording callback, but still require Shizuku and Accessibility respectively.

## Volume hotkeys

- Hotkeys are **off by default**. Enable them in the app and select a macro.
- **Volume Up (one press):** play the selected macro exactly once; the saved loop count is ignored.
- **Volume Down (one press):** stop all running macros.
- While recording, **Volume Down** ends touch capture instead. Return to the app and press **LƯU** to save.
- While hotkeys are enabled, volume keys are consumed by the accessibility service and will not adjust media volume.

## Build APK on GitHub

1. Extract this ZIP.
2. Create a GitHub repository.
3. Upload the **contents inside `MacroShizuku`** to the repository root. The root must contain `settings.gradle`, `build.gradle`, `gradle.properties`, `app/`, and `.github/`.
4. Open **Actions** → **Build APK** → **Run workflow**.
5. Wait for the workflow to finish successfully.
6. Open the completed run and download the `MacroShizuku-debug` artifact.
7. Extract the artifact ZIP and install `app-debug.apk` on Android. Allow installation from the browser/file manager if Android asks.

A push to `main` or `master` also triggers the workflow.
