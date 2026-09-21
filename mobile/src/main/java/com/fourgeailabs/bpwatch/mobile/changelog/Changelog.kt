package com.fourgeailabs.bpwatch.mobile.changelog

/**
 * BPWatch release history (v2.3, "What's new" screen).
 *
 * HOW TO ADD A RELEASE: add one [ChangelogEntry] at the TOP of [CHANGELOG]
 * (newest first) with the new versionName, versionCode, release date
 * (yyyy-MM-dd) and 1-4 short bullet notes. The device's installed version
 * (BuildConfig.VERSION_NAME) automatically gets the "Current" badge, so
 * nothing else needs changing.
 */
data class ChangelogEntry(
    val versionName: String,
    val versionCode: Int,
    /** Release date, ISO yyyy-MM-dd. */
    val date: String,
    val notes: List<String>,
)

/** Newest first. The full history starts at the first public version. */
val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        versionName = "2.3.0",
        versionCode = 25,
        date = "2026-09-21",
        notes = listOf(
            "Home reshuffle: hydration and the discontinued blood-oxygen tiles " +
                "removed, new Stress 0-100 tile added; the phone can now " +
                "trigger a BP check on the watch (90-second timeout, retry).",
            "Health data: blood-oxygen sensing removed entirely; sleep duration now counts " +
                "sleep stages only; live heart-rate ticks persist for HR " +
                "Trends; the stress tile falls back to the newest timestamped value.",
            "New: opt-in phone-side snore detection (overnight microphone, " +
                "clips stay on the phone), an About screen, and this What's " +
                "new changelog. Fixed the Trends chart going blank when " +
                "switching metrics quickly.",
            "Watch side (companion build): watch-face complications, broader " +
                "Wear OS support, off-body pause with reliable BP intervals, " +
                "latest BP and heart rate on watch launch. Nothing in 2.3.0 " +
                "has run on real hardware yet: needs real-device validation.",
        ),
    ),
    ChangelogEntry(
        versionName = "2.2.0",
        versionCode = 24,
        date = "2026-09-20",
        notes = listOf(
            "Full health coverage: new Health Connect reads (resting heart " +
                "rate, HRV, body fat and more) with permission hardening.",
            "New BMI tile on the Home grid; the watch reports its own step " +
                "count directly instead of waiting on Samsung's sync.",
            "Settings shows every Health Connect permission's real grant " +
                "status with per-permission re-request buttons.",
        ),
    ),
    ChangelogEntry(
        versionName = "2.1.0",
        versionCode = 23,
        date = "2026-09-20",
        notes = listOf(
            "Every Home tile now taps through to its own Trends history view.",
            "New Hour range plus history graphs for steps, distance, " +
                "calories, weight, sleep and hydration.",
            "Heart-rate tile contrast and readability fix.",
        ),
    ),
    ChangelogEntry(
        versionName = "2.0.0",
        versionCode = 22,
        date = "2026-09-20",
        notes = listOf(
            "Google Health-style Home dashboard and hand-rolled Trends " +
                "graphs (heart rate, stress, blood pressure, blood oxygen).",
            "Continuous heart-rate and stress recording (opt-in): the watch " +
                "samples every 10 minutes and batches to the phone.",
            "Watch updater hardened: beamed APKs are SHA-256 verified " +
                "before install; real installer status codes reported.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.15.2",
        versionCode = 21,
        date = "2026-09-20",
        notes = listOf(
            "The bundled watch APK is now always re-extracted from assets, " +
                "fixing stale watch updates after phone app updates.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.15.1",
        versionCode = 20,
        date = "2026-09-20",
        notes = listOf(
            "Watch UI fixes and a one-tap updater bootstrap hint.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.15.0",
        versionCode = 19,
        date = "2026-09-20",
        notes = listOf(
            "One-tap watch updater: the phone beams the watch APK over " +
                "Bluetooth and the watch installs it itself.",
            "One shared signing key so updates preserve watch settings; " +
                "the phone re-pushes config, calibration and resting heart " +
                "rate on every connection.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.14.0",
        versionCode = 18,
        date = "2026-09-20",
        notes = listOf(
            "Google-colour shifting loader on the watch; alert severity " +
                "levels with per-severity cooldowns.",
            "Phone \"Watch live\" card with live heart-rate dot; extreme " +
                "alerts take over the phone screen with vibration.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.13.0",
        versionCode = 17,
        date = "2026-09-20",
        notes = listOf(
            "Monitoring and alerts: opt-in continuous heart-rate service, " +
                "high heart-rate alert, BP check intervals, high/low BP alerts.",
            "Watch alert screen with vibration; live heart-rate ticks and " +
                "alert mirroring to the phone over the Data Layer.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.12.0",
        versionCode = 16,
        date = "2026-09-20",
        notes = listOf(
            "Full Pixel-style Material 3 overhaul of the phone app plus a " +
                "Wear OS Material refresh on the watch.",
            "Settings footer now reads the version dynamically instead of " +
                "a hard-coded string.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.11.4",
        versionCode = 15,
        date = "2026-09-20",
        notes = listOf(
            "The actual Health Connect fix: permission-rationale entry " +
                "points declared in the manifest, so the system permission " +
                "dialog finally appears on Android 14+.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.11.3",
        versionCode = 14,
        date = "2026-09-20",
        notes = listOf(
            "Health Connect read-only permission A/B test button to " +
                "diagnose the silent permission failure.",
            "Fresh README and CI-built APKs published as release artifacts.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.11.2",
        versionCode = 13,
        date = "2026-09-20",
        notes = listOf(
            "\"Grant permissions manually\" fallback opening Health " +
                "Connect's per-app screen, plus a settings-screen fallback.",
        ),
    ),
    ChangelogEntry(
        versionName = "1.11.1",
        versionCode = 12,
        date = "2026-09-20",
        notes = listOf(
            "Semantic versioning adopted with a documented versioning " +
                "policy; mobile and watch modules stay in sync.",
            "Health Connect diagnostics: grant-count toasts and " +
                "per-permission status lines in Settings.",
        ),
    ),
    ChangelogEntry(
        versionName = "11.0.0",
        versionCode = 11,
        date = "2026-09-19",
        notes = listOf(
            "Health Connect connection fix attempt: manifest queries block " +
                "so the Health Connect intent resolves on Android 30+.",
        ),
    ),
    ChangelogEntry(
        versionName = "10.0.0",
        versionCode = 10,
        date = "2026-09-19",
        notes = listOf(
            "Experimental watch stress estimate (0-100) shown on the " +
                "watch; watch results auto-upload to the phone with a " +
                "notification carrying BP, heart rate, stress, blood oxygen and time.",
            "Resting heart rate pushed back to the watch as the stress " +
                "baseline; body-profile section (height, weight, age, sex, " +
                "BMI) added to Settings; Samsung Health connect flow reworked.",
        ),
    ),
    ChangelogEntry(
        versionName = "9.0.0",
        versionCode = 9,
        date = "2026-09-19",
        notes = listOf(
            "Initial public release: package renamed to " +
                "com.fourgeailabs.bpwatch; full source and APKs published " +
                "to GitHub.",
        ),
    ),
)
