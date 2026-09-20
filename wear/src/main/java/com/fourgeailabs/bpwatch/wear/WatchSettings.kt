package com.fourgeailabs.bpwatch.wear

import android.content.Context

/**
 * Watch-side settings and persisted state (SharedPreferences).
 *
 * The last BP estimate is persisted because the hourly background check
 * delivers it while the app UI may not be running — without persistence the
 * estimate would die with the listener's process.
 */
object WatchSettings {
    enum class CheckMode { MANUAL, HOURLY }

    private const val PREFS = "bpwatch_watch"
    private const val KEY_MODE = "check_mode"
    private const val KEY_EST_SYS = "est_sys"
    private const val KEY_EST_DIA = "est_dia"
    private const val KEY_EST_TS = "est_ts"
    private const val KEY_CALIBRATED = "calibrated"
    private const val KEY_RESTING_HR = "resting_hr"
    /** Fallback resting HR (bpm) until the phone sends the real baseline. */
    const val DEFAULT_RESTING_HR = 70f

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCheckMode(context: Context): CheckMode =
        when (prefs(context).getString(KEY_MODE, CheckMode.MANUAL.name)) {
            CheckMode.HOURLY.name -> CheckMode.HOURLY
            else -> CheckMode.MANUAL
        }

    fun setCheckMode(context: Context, mode: CheckMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun saveEstimate(context: Context, sys: Int, dia: Int, timestamp: Long) {
        prefs(context).edit()
            .putInt(KEY_EST_SYS, sys)
            .putInt(KEY_EST_DIA, dia)
            .putLong(KEY_EST_TS, timestamp)
            .apply()
    }

    fun loadEstimate(context: Context): BpEstimate? {
        val p = prefs(context)
        val ts = p.getLong(KEY_EST_TS, 0L)
        if (ts == 0L) return null
        return BpEstimate(
            sys = p.getInt(KEY_EST_SYS, 0),
            dia = p.getInt(KEY_EST_DIA, 0),
            timestamp = ts,
        )
    }

    fun saveCalibrated(context: Context, calibrated: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALIBRATED, calibrated).apply()
    }

    fun isCalibrated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CALIBRATED, false)

    /**
     * Resting-HR baseline for stress estimation, pushed from the phone
     * (lowest HR across calibration points). Falls back to a generic 70.
     */
    fun saveRestingHr(context: Context, restingHr: Float) {
        prefs(context).edit().putFloat(KEY_RESTING_HR, restingHr).apply()
    }

    fun getRestingHr(context: Context): Float =
        prefs(context).getFloat(KEY_RESTING_HR, DEFAULT_RESTING_HR)
}
