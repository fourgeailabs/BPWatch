package com.fourgeailabs.bpwatch.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication (v2.3, tap action v2.4.2): the latest stress
 * estimate (0-100). Tapping the complication opens the BPWatch app.
 * Never throws — any failure returns the "—" no-data card.
 */
class StressComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        card("45")

    override suspend fun onComplicationRequest(
        request: ComplicationRequest,
    ): ComplicationData = try {
        val score = WatchSettings.loadLatestStress(this)
        card(if (score >= 0) score.toString() else "—")
    } catch (_: Exception) {
        card("—")
    }

    private fun card(text: String): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder("Stress $text out of 100").build(),
        )
            .setTitle(PlainComplicationText.Builder("Stress").build())
            .setTapAction(openAppAction())
            .build()

    /** Tapping the complication opens the watch app. */
    private fun openAppAction(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
