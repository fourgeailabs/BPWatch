package com.fourgeailabs.bpwatch.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Refreshes the three watch-face complications (BP, HR, stress) when new
 * data lands (v2.3). Called only from data-arrival points — an estimate
 * arriving from the phone, a measurement completing — never polled, so no
 * extra battery cost. Never throws.
 */
object ComplicationUpdater {

    fun requestUpdate(context: Context) {
        update(context, BpComplicationService::class.java)
        update(context, HrComplicationService::class.java)
        update(context, StressComplicationService::class.java)
    }

    private fun update(context: Context, service: Class<*>) {
        try {
            ComplicationDataSourceUpdateRequester.create(
                context.applicationContext,
                ComponentName(context, service),
            ).requestUpdateAll()
        } catch (_: Exception) {
        }
    }
}
