# CamRes — Android Camera Video Capabilities Probe

**CamRes** is a lightweight Android app that enumerates **video-only** camera capabilities on your device and lets you test any configuration — including unsupported/probe combinations — **without crashing**. On failure, it shows a fallback black-screen log with the full error/stack trace instead of terminating.

Built as a **0-Gradle** project (pure Android SDK command-line tools), targeting **Android 12 (API 31)**.

## Features

- **Camera enumeration** — lists all device cameras (Camera2) with facing and hardware level.
- **Supported video capability scan (video only)** — for the selected camera:
  - `StreamConfigurationMap.getOutputSizes(MediaRecorder.class)` video sizes
  - High-speed video sizes
  - Sample output min-frame durations (~max fps)
  - `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`
  - `CamcorderProfile` entries (LOW/HIGH/QCIF/CIF/480P/720P/1080P/QVGA/2160P) with size, fps, bitrate, and codec when available
  - **Focus/lens & auto info** — min/hyperfocal focus distance, available AF/AE modes, ISO & exposure ranges, OIS/video stabilization modes (when reported)
- **Test any video configuration** — pick from reported-supported **and** probe/unsupported options:
  - **Resolution (WxH)** — common presets plus device-reported sizes
  - **FPS** — common frame rates plus range-derived values
  - **Video encoder** — H.264/AVC, H.263, MPEG4 SP, HEVC/H.265, VP8
  - **Output format** — MP4, 3GP, WebM
  - **Bitrate** — preset Mbps values **or** a custom bitrate in **Mbps** (overrides preset)
  - **Orientation** — Horizontal/Landscape (0°/180°) or Vertical/Portrait (90°/270°) via `MediaRecorder.setOrientationHint`
  - **Recording duration** — custom length in seconds
  - **Focus control** — AF mode (Auto, Continuous Video/Picture, Macro, EDOF, OFF) and **manual focus distance** (close ↔ far, diopters) when supported by the lens, plus **Furthest (calibrate & lock)**: self-calibrates by sweeping focus and measuring sharpness (point at a distant detailed scene) to lock the furthest *non-blurry* distance, avoiding mechanical overtravel past true optical infinity on uncalibrated sensors (with safe hyperfocal/offset fallback; far end avoids commanding exact 0.0)
  - **Audio** — optional (requests `RECORD_AUDIO`)
- **Live sensor metadata window** — during a test, displays **real-time, in-place** (non-spamming) capture metadata: AF/AE/AWB mode & state, lens focus distance/state, exposure time, ISO, frame duration (~fps), aperture, timestamp.
- **Presets** — **save**, **load**, and **rename** named presets of test parameters for quick reuse.
- **Real end-to-end test** — configures `MediaRecorder` (`VideoSource.SURFACE`) + a Camera2 capture session and records for the chosen duration.
- **Crash-safe fallback** — every test step runs off the UI thread with exceptions/callback errors caught; failures are reported as `FAILURE / FALLBACK` in a persistent **black-background** monospace panel with a step log and stack trace, and resources are cleaned up best-effort.
- **Stores test recordings** — successful test clips are saved by default to **`/storage/emulated/0/DCIM/Camera/`** (`CamRes_test_<ts>_<WxH>_<fps>fps.<ext>`) via `MediaStore` (scoped-storage friendly on Android 12). Incomplete entries from failed tests are removed best-effort.

## Requirements

- Android device/emulator running Android 5.0+ (minSdk 21), built targeting Android 12 (API 31)
- Camera permission (and Microphone if testing with audio)
- For building from source: Android SDK with `build-tools` and a platform JAR, plus JDK (`javac`)

## Build (no Gradle)

The project builds with the Android SDK command-line tools (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`).

```bash
# from the project root
chmod +x build.sh
./build.sh
# -> build/CamRes.apk
```

`build.sh` defaults to:
- `ANDROID_HOME` / `/home/hp/Android/Sdk`
- `build-tools/34.0.0`
- compile platform `platforms/android-33/android.jar`
- debug signing with `~/.android/debug.keystore`

Override via environment variables if needed: `ANDROID_HOME`, `DEBUG_KEYSTORE`, `KS_PASS`, `KEY_ALIAS`, `KEY_PASS`.

> Note: the script compiles with `javac --release 8 -parameters` to avoid a JDK 21 + d8 `MethodParameters` parsing issue.

## Install

```bash
adb install -r build/CamRes.apk
```

Or download the debug APK from [Releases](../../releases).

## Usage

1. Grant **Camera** (and **Microphone** if you enable audio).
2. Select a camera and tap **Scan supported video capabilities** to view reported sizes, FPS ranges, camcorder profiles, and focus/lens/AE info.
3. Choose a resolution, FPS, encoder, output format, bitrate (preset or custom **Mbps**), orientation (horizontal/vertical), **duration (s)**, **focus mode & manual focus distance** (close↔far, or use **Furthest (calibrate & lock)**), and audio option — including **unsupported probe** values. Save/load/rename **presets** as needed.
4. Tap **Test selected configuration**. Watch **live sensor metadata** update in place during recording; the result panel (black background) shows success or a detailed fallback error log. Successful recordings are stored in **DCIM/Camera**.

## Project structure

```
.
├── AndroidManifest.xml
├── build.sh
├── res/
│   ├── layout/activity_main.xml
│   └── values/strings.xml
└── src/
    └── com/example/camres/
        └── MainActivity.java
```

Build outputs (`build/`, APKs) are gitignored; a debug APK is published in Releases.

## Permissions

| Permission | Purpose |
|---|---|
| `android.permission.CAMERA` | Open and test camera video capture |
| `android.permission.RECORD_AUDIO` | Optional, for tests with audio enabled |

Camera hardware features are declared `required="false"`.

## Notes

- Compiled against `android-33` with `targetSdkVersion 31` (APIs used are compatible with the API 21+/31 runtime).
- "Supported" markers reflect what the device/Camera2 reports; actual recordability depends on the device HAL/encoder, which is exactly what the test probes (unsupported values are intentional).
- This is a diagnostic/debug tool — tested clips are written to shared storage when a test succeeds.

## License

Provided as-is for device diagnostics/testing.
