# BPWatch — Blood Pressure & Blood Oxygen Tracking for Galaxy Watch Ultra + Pixel

A two-app system that works around Samsung's lock-in:

- **Watch app** (`:wear`) — runs on your Galaxy Watch Ultra. Measures heart rate
  with the watch's PPG sensor and sends it to your phone over the Wear OS Data
  Layer. Shows the latest BP estimate sent back from the phone.
- **Phone app** (`:mobile`) — runs on your Pixel 10 Pro XL. Receives watch
  readings, estimates blood pressure from **your own cuff calibration**,
  stores history, charts trends, imports SpO2 via Health Connect, and publishes
  BP estimates back to Health Connect.

## The honest technical picture

Samsung's on-watch blood-pressure feature is proprietary: it uses pulse-wave
analysis plus cuff calibration, and Samsung only enables it when the watch is
paired to a Samsung phone. Google also restricts SpO2 and HRV sensor data to
system apps, so no third-party app can read blood oxygen (or blood pressure)
directly from the Galaxy Watch's sensors.

So BPWatch does the next-best honest thing — the same high-level approach as
Samsung's own feature, minus their proprietary algorithm:

1. **Heart rate is readable.** Any Wear OS app can read HR from the PPG sensor.
   The watch takes a 30-second resting measurement and averages it.
2. **You calibrate with a real cuff.** In the phone app you enter ≥3 cuff
   readings taken at the same time as watch HR measurements. The app fits a
   least-squares line mapping HR → systolic and HR → diastolic.
3. **Later readings are estimated** through that line. They are wellness
   estimates, not measurements — heart rate alone is a weak predictor of BP.
4. **SpO2 comes via Health Connect.** The watch app can't read SpO2 directly,
   but Samsung Health (which runs fine on your Pixel) can record the watch's
   SpO2 and sync it into Health Connect — the phone app picks it up from
   there. You can also log SpO2 manually on the Home tab.

**This is not a medical device.** Never use estimates to diagnose, treat, or
adjust medication. Always confirm with a cuff.

## Project layout

```
bpwatch/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── wear/                          # Galaxy Watch app (Wear OS 3+, minSdk 30)
│   └── src/main/java/com/eric/bpwatch/
│       ├── Link.kt                # Data Layer contract (keep in sync with mobile)
│       └── wear/
│           ├── MainActivity.kt    # Measure UI (Wear Compose)
│           ├── HeartRateMonitor.kt# PPG heart-rate via SensorManager
│           ├── DataLayer.kt       # Sends HR to phone via MessageClient
│           ├── WatchListenerService.kt  # Receives estimates back
│           └── WatchState.kt
├── mobile/                        # Pixel companion app (minSdk 26)
│   └── src/main/java/com/eric/bpwatch/
│       ├── Link.kt
│       ├── MainActivity.kt        # Bottom-nav host + disclaimer
│       ├── MainViewModel.kt
│       ├── BpRepository.kt
│       ├── data/                  # Room (Reading), CalibrationStore (DataStore)
│       ├── calibration/           # CalibrationEngine (least-squares fit)
│       ├── healthconnect/         # SpO2 import + BP publishing
│       ├── wearable/              # PhoneListenerService (receives watch HR)
│       └── ui/                    # Home / Calibrate / History / Settings
└── AI_STUDIO_PROMPTS.md           # Copy-paste prompts for iterating with Gemini
```

## Building & installing

You need **Android Studio** (AI Studio can't build or sign native Wear APKs —
see below). This project was written, not yet compiled — expect the usual
first-build dependency downloads.

1. Open the `bpwatch` folder in Android Studio and let Gradle sync.
2. **Phone app:** Run the `mobile` configuration with your Pixel 10 Pro XL
   connected over USB. Accept the Health Connect permission when asked.
3. **Watch app:** On the Galaxy Watch Ultra, enable *Developer options →
   Wireless debugging*. In Android Studio, pair via "Pair Devices Using Wi-Fi"
   and Run the `wear` configuration targeting the watch. (Or `adb install`
   the built APKs from `wear/build/outputs/apk/` and `mobile/build/outputs/apk/`.)
4. Open BPWatch on the phone once, accept the disclaimer, then open the
   **Calibrate** tab.

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

## About Google AI Studio

AI Studio is built for prompting Gemini (APIs, web prototypes) — it can't
compile, sign, or install native Wear OS APKs, and it can't log in as you to
do the device pairing. That's why this is a full Android Studio project
instead. Use `AI_STUDIO_PROMPTS.md` to have Gemini extend or refactor this
codebase, then paste the results back here and build in Android Studio.

## Roadmap ideas

- Wear OS Tile + complication showing the last estimate at a glance
- Calibration quality score (R²) and outlier warnings
- CSV export of history for your doctor
- Background periodic measurements via Health Services

## Prebuilt APKs

The `releases/` folder contains ready-to-install debug builds:

- `BPWatch-Phone-v9-debug.apk` — phone app (installs/updates the watch app over Wi-Fi debugging)
- `BPWatch-Wear-GalaxyWatch-debug.apk` — watch app (also bundled inside the phone APK)
