# Dev environment: emulator, device, logs, iOS simulator

## Project CLI loop

Use `tools/android.sh` for routine builds, launches and logs. Do not open
Android Studio for these.

```sh
tools/android.sh run      # build, install, and launch
tools/android.sh debug    # build, launch, and stream app-process logs
tools/android.sh logs     # stream logs from an already-running app
tools/android.sh stop     # stop the app
```

- `run`/`debug` start the `Pixel_10_Pro` AVD when no device is online
  (`ANDROID_AVD` picks another; `ANDROID_SERIAL` picks a connected device).
- The SDK path comes from `ANDROID_SDK_ROOT`, `ANDROID_HOME`,
  `local.properties`, then `~/Library/Android/sdk`. Unset `JAVA_HOME` falls back
  to Homebrew JDK 17.
- `debug` streams via `adb logcat --pid`; it does not attach a source debugger.

## adb and Orca

- SDK binaries are not on PATH: `~/Library/Android/sdk/platform-tools/adb`,
  `~/Library/Android/sdk/emulator/emulator`.
- `orca emulator` drives Android (adb/emulator/avdmanager backend). Working:
  `devices`, `attach <serial>` (once per worktree), `tap x y` (normalized
  0..1), `type "text"` (US ASCII), `gesture '[{...}]'`, `button home|back|...`,
  `rotate landscape_left|portrait`, `ax --json` (uiautomator tree in physical
  px; the AVD is 1280x2856), `install`, `launch com.curvemaps.app`,
  `logcat --lines N [--json]`, `kill`.
- `orca emulator exec` is broken (`Cannot use 'in' operator ...
  'screenshotStatus'`); use raw adb. A shutdown AVD must be booted first
  (`emulator @<avd>`).
- Taps are flaky for reasons unrelated to app code (IME layout shifts, bounds
  drift, adb races). After ~3 failed attempts stop, re-dump `ax`, or verify via
  `type`/`launch`/logcat. A tap that "did nothing" is not evidence of an app bug.

## App logs

`android.util.Log` under `CurveMaps.*` tags: `RouteScreen` (route
submit/success/errors, point resolution), `SearchField` (keystrokes, debounced
searches, picks), `GeocodeCtrl` (first index load), `SearchEngine` (query
timing), `GeocoderIndex` (asset copy/mmap), `Router` (GraphHopper init and
requests), `Media` (listener attach and observed volume; debug only),
`PointParser` (lat,lon fallback). Levels: `I` user actions/successes, `D`
lifecycle/timing, `V` per keystroke, `W` degradations, `E` failures.

```sh
adb logcat CurveMaps:V com.graphhopper:W *:S
```

## iOS simulator (Swift code on this Mac)

CurveMaps is Android-only; this is for other Swift/iOS work on the dev Mac.

- Xcode 27.0 is at `/Applications/Xcode.app` with the iOS 27.0 runtime. If
  `xcodebuild` complains about Command Line Tools:
  `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`.
- Do not install Xcode with `mas` (fails on macOS 27 with `ADAM ID 497799835`);
  use the App Store or `xcodes install <version> --experimental-unxip` (Apple
  ID + 2FA, so the user runs it with `!`).
- Runtimes: `xcodebuild -downloadPlatform iOS`, `xcrun simctl runtime list`.
- Build/test: `xcodebuild test -scheme <Scheme> -destination 'platform=iOS
  Simulator,name=iPhone 18 Pro'`. `swift test` runs on macOS only.
- Drive with `xcrun simctl` (`boot`, `bootstatus booted -b`, `install`,
  `launch`, `openurl`, `io booted screenshot out.png`, `spawn booted log
  stream`, `shutdown all`). There is no Simulator.app; use screenshots.
