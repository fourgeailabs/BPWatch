# AI Studio prompts for BPWatch

AI Studio can't compile or install native Wear OS APKs — but it's great at
generating and refactoring Kotlin. Paste these prompts into AI Studio (or
Gemini), then drop the resulting code into this project and build in
Android Studio.

All prompts assume the BPWatch codebase in this folder (`com.eric.bpwatch`,
watch `:wear` module + phone `:mobile` module, Data Layer paths in `Link.kt`).

---

## 1. Add a Wear OS Tile with the last estimate

```
In my Wear OS app (package com.eric.bpwatch.wear, minSdk 30, Wear Compose
1.3.1), add a TileService that shows the latest blood pressure estimate
(sys/dia) from a DataStore-backed repository, with a "Measure" button that
launches MainActivity. Include the AndroidManifest service declaration with
the BIND_TILE_PROVIDER permission and the androidx.wear.tiles:tiles
dependency for build.gradle.kts. Write it in Kotlin.
```

## 2. Calibration quality score

```
In com.eric.bpwatch.mobile.calibration.CalibrationEngine (Kotlin), extend
fit() to also compute R-squared for the systolic and diastolic regressions,
flag calibration points whose residual is more than 2 standard deviations
from the fit as outliers, and expose a data class CalibrationQuality with
rSquaredSys, rSquaredDia and outlierIndices. Keep the existing function
signatures backwards compatible.
```

## 3. CSV export of history

```
In my Android phone app (com.eric.bpwatch.mobile, Material3, Room database
with a Reading entity), add a "Export CSV" button to the History screen that
writes all readings to a CSV file via the Storage Access Framework
(ActivityResultContracts.CreateDocument) and shows a Snackbar on success.
Kotlin, no extra dependencies.
```

## 4. Complication for the watch face

```
Add a watch-face complication to my Wear OS app (com.eric.bpwatch.wear)
using androidx.wear.watchface:watchface-complications-data. It should be a
SHORT_TEXT complication showing the last BP estimate (e.g. "118/76") from
WatchState, updating when a new estimate arrives via WatchListenerService.
Include the manifest declaration and provider service in Kotlin.
```

## 5. Better estimation model

```
Review com.eric.bpwatch.mobile.calibration.CalibrationEngine, which currently
fits ordinary least squares of heart rate -> systolic/diastolic from cuff
calibration points. Propose and implement a more robust Kotlin version that:
uses Huber regression or RANSAC to resist outlier cuff readings, weights
recent points more heavily, and refuses to estimate when the input heart rate
is far outside the calibrated range. Explain the trade-offs in comments.
```

## 6. Localise the phone app UI

```
Add string resources and Spanish (es) translations for all user-visible text
in my Android app's Compose screens (Home, Calibrate, History, Settings in
com.eric.bpwatch.mobile.ui). Refactor the composables to use stringResource.
Keep medical disclaimer wording precise in both languages.
```

## 7. Unit tests for the calibration math

```
Write JUnit4 unit tests (in mobile/src/test) for
com.eric.bpwatch.mobile.calibration.CalibrationEngine covering: fewer than
3 points returns null, perfect linear data recovers exact slope/intercept,
identical heart rates return null (degenerate), and estimate() clamps to
sane physiological ranges.
```
