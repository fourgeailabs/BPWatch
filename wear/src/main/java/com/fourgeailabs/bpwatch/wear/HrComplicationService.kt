package com.fourgeailabs.bpwatch.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication (v2.3): the latest measured heart rate.
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
            .build()
}
