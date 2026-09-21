package com.fourgeailabs.bpwatch.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication (v2.3): the latest stress estimate (0-100).
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
            .build()
}
