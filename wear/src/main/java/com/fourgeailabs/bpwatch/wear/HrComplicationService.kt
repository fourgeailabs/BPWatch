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
 * Watch-face complication (v2.3, tap action v2.4.2): the latest measured
 * heart rate. Tapping the complication opens the BPWatch app.
 * Never throws — any failure returns the "—" no-data card.
 */
class HrComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        card("72")

    override suspend fun onComplicationRequest(
        request: ComplicationRequest,
    ): ComplicationData = try {
        val bpm = WatchSettings.loadLatestHr(this)
        card(if (bpm > 0f) bpm.toInt().toString() else "—")
    } catch (_: Exception) {
        card("—")
    }

    private fun card(text: String): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder("Heart rate $text beats per minute").build(),
        )
            .setTitle(PlainComplicationText.Builder("HR").build())
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
