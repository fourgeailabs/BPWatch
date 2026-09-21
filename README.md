# BPWatch — Blood Pressure & Wellness Estimates for Galaxy Watch Ultra + Pixel

A two-app system that works around Samsung's lock-in:

- **Watch app** (`:wear`) — runs on your Galaxy Watch Ultra. Measures heart
  rate with the watch's PPG sensor, estimates an experimental stress score,
  and sends results to your phone over the Wear OS Data Layer. Shows the
  latest BP estimate sent back from the phone. Supports an hourly background
  mode (inexact alarm + boot receiver) so estimates survive the UI dying.
- **Phone app** (`:mobile`) — runs on your Pixel. Receives watch readings,
  estimates blood pressure from **your own cuff calibration**, stores
  history, charts trends, imports SpO2 via Health Connect, publishes BP
  estimates back to Health Connect, and can **install/update the watch app
  over Wi-Fi debugging** (no PC needed) — including the full pairing-code
  flow with a from-scratch SPAKE2 implementation.

**This is not a medical device.** Never use estimates to diagnose, treat, or
adjust medication. Always confirm with a cuff.

## The honest technical picture

Samsung's on-watch blood-pressure feature is proprietary: it uses pulse-wave
analysis plus cuff calibration, and Samsung only enables it when the watch is
paired to a Samsung phone. Google also restricts SpO2 and HRV sensor data to
system apps, so no third-party app can read blood oxygen (or blood pressure)
directly from the Galaxy Watch's sensors.

So BPWatch does the next-best honest thing — the same high-level approach as
Samsung's own feature, minus their proprietary algorithm:

1. **Heart rate is readable.** Any Wear OS app can read HR from the PPG
   sensor. The watch takes a 30-second resting measurement and averages it.
2. **You calibrate with a real cuff.** In the phone app you enter ≥3 cuff
   readings taken at the same time as watch HR measurements. The app fits a
   least-squares line mapping HR → systolic and HR → diastolic.
3. **Later readings are estimated** through that line. They are wellness
   estimates, not measurements — heart rate alone is a weak predictor of BP.
4. **SpO2 comes via Health Connect.** The watch app can't read SpO2 directly,
   but Samsung Health (which runs fine on your Pixel) can record the watch's
   SpO2 and sync it into Health Connect — the phone app picks it up from
   there. You can also log SpO2 manually on the Home tab.

## What's in the phone app

- **Home** — latest estimate, heart rate, SpO2, manual logging.
- **Calibrate** — cuff calibration points (≥3), least-squares fit.
- **History** — charts and past readings.
- **Watch** — update the watch app with one tap: the phone beams the bundled
  watch APK over Bluetooth and the watch installs it itself via
  PackageInstaller (no debugging, settings preserved). For first-time
  installs onto a fresh watch, the tab also has the Wi-Fi debugging
  installer: enter the watch IP/port, tap Test connection or
  Install/Update. Handles the pairing-code flow against the watch's TLS
  wireless-debugging port. A pure Kotlin ADB client (protocol framing, RSA
  auth, shell, sync push) with unit tests against a fake daemon lives in
  `mobile/…/adb/`.
- **Settings** — Health Connect connect flow, SDK-status diagnostics,
  body-profile section (height, weight, age, sex, BMI), app version.

Watch measurements auto-upload to the phone with a notification carrying the
BP estimate, heart rate, stress score and time; the phone pushes resting HR
back to the watch to calibrate the stress baseline.

## Project layout

```
bpwatch/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── wear/                          # Galaxy Watch app (Wear OS 3+, minSdk 30)
│   └── src/main/java/com/fourgeailabs/bpwatch/
│       ├── Link.kt                # Data Layer contract (keep in sync with mobile)
│       └── wear/
│           ├── MainActivity.kt    # Measure UI (Wear Compose)
│           ├── HeartRateMonitor.kt# PPG heart-rate via SensorManager
│           ├── StressEstimator.kt # Experimental 0–100 stress estimate
│           ├── DataLayer.kt       # Sends HR/stress to phone via MessageClient
│           ├── WatchListenerService.kt  # Receives estimates back
│           └── WatchState.kt
├── mobile/                        # Pixel companion app (minSdk 26)
│   └── src/main/java/com/fourgeailabs/bpwatch/
│       ├── Link.kt
│       ├── MainActivity.kt        # Bottom-nav host + disclaimer
│       ├── MainViewModel.kt
│       ├── BpRepository.kt
│       ├── adb/                   # Pure-Kotlin ADB client + SPAKE2 pairing
│       │   ├── AdbClient.kt AdbKey.kt AdbProtocol.kt AdbTls.kt
│       │   ├── PairingClient.kt WatchInstaller.kt
│       │   └── spake2/           # From-scratch SPAKE2 (BoringSSL transcript)
│       ├── calibration/           # CalibrationEngine (least-squares fit)
│       ├── data/                  # Room (Reading), CalibrationStore (DataStore)
│       ├── healthconnect/         # SpO2 import + BP publishing
│       ├── notifications/         # Upload notification helper
│       ├── profile/               # Body-profile store
│       ├── wearable/              # PhoneListenerService (receives watch data)
│       └── ui/                    # Home / Calibrate / History / Settings / Watch
└── docs/
    └── wireless-debugging-pairing-brief.md
```

## Building

This project compiles headlessly with the Android command-line tools — no
Android Studio required:

```bash
export ANDROID_HOME=~/workspace/android-sdk
export JAVA_HOME=~/workspace/tools/jdk17
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

Every phone build also rebuilds `:wear` and embeds the fresh watch APK as
`assets/bpwatch-wear.apk` (see the `bundleWearApk` task), so the Watch tab
always installs the latest build. Debug builds only; release signing is not
set up.

**Signing:** both modules sign debug builds with the pinned keystore in
`keystore/bpwatch-debug.keystore` (a debug key, safe to commit). CI runners
generate a fresh ephemeral debug key on every run — without the pinned key,
every build had a different signature, which forced an uninstall (wiping all
settings) on every update. With one stable key, updates install over the
top and all data is preserved. If BPWatch ever ships to the Play Store, swap
in a proper release key kept secret.

**Versioning:** proper semver `MAJOR.MINOR.PATCH` in `versionName`, with
`versionCode` incremented on every build. Both modules stay in sync; the
Settings footer reads `BuildConfig.VERSION_NAME` dynamically.

## Health Connect notes

- SpO2 import and BP publishing go through Health Connect
  (`1.1.0-alpha11`, compileSdk 35).
- On Android 16, requesting `WRITE_BLOOD_PRESSURE` alongside the read
  permissions can cause the system to cancel the whole permission request
  silently (no dialog, callback returns 0 granted). The Settings screen has a
  "Try read-only request" button to A/B this, plus a "Grant permissions
  manually" fallback that opens the platform per-app screen.
- The phone manifest declares the Health Connect `<queries>` package
  visibility block (required on API 30+ or the permission intent can't
  resolve), and the Settings card shows live SDK-status diagnostics
  (available / needs update / provider update required) so silent failures
  are visible.

## Prebuilt APKs

The `releases/` folder contains ready-to-install debug builds:

- `BPWatch-Phone-v1.11.3-debug.apk` — current phone app (embeds the watch APK;
  use the Watch tab to install/update the watch over Wi-Fi debugging)
- `BPWatch-Wear-v1.11.3-debug.apk` — current watch app, standalone
- `BPWatch-Phone-v9-debug.apk` / `BPWatch-Wear-GalaxyWatch-debug.apk` —
  legacy v9 builds

## Calibrating (do this first)

1. Sit quietly for 5 minutes, cuff on your arm, watch snug on the wrist.
2. On the watch, open BPWatch → **Measure**. Keep still for 30 seconds.
3. Take your cuff reading immediately and enter sys/dia in the phone app's
   Calibrate tab. The latest watch heart rate is shown there — tap refresh if
   needed — then **Save calibration point**.
4. Repeat at least **3 times**, ideally at different times of day (morning,
   evening, after light activity). More varied points = better fit.
5. Once calibrated, every watch measurement produces an estimate on the phone,
   which is also sent back to the watch display and written to Health Connect.

Recalibrate every few weeks, or when medication, fitness, or stress changes.

## SpO2 setup

- **Automatic:** Install Samsung Health on the Pixel, sign in with the same
  Samsung account as the watch, enable SpO2 measurement on the watch, and turn
  on Samsung Health's Health Connect sync. Then in BPWatch → Settings →
  **Connect Health Connect**.
- **Manual:** Enter a value any time on the Home tab.

## Roadmap ideas

- Wear OS Tile + complication showing the last estimate at a glance
- Calibration quality score (R²) and outlier warnings
- CSV export of history for your doctor
