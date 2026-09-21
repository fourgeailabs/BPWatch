package com.fourgeailabs.bpwatch.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication (v2.3): the latest BP estimate the phone sent.
 * Never throws — any failure returns the "—" no-data card.
 */
class BpComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        card("128/84")

    override suspend fun onComplicationRequest(
        request: ComplicationRequest,
    ): ComplicationData = try {
        val est = WatchSettings.loadEstimate(this)
        card(
            if (est != null && est.sys > 0 && est.dia > 0) {
                "${est.sys}/${est.dia}"
            } else {
                "—"
            },
        )
    } catch (_: Exception) {
        card("—")
    }

    private fun card(text: String): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder("Blood pressure $text").build(),
        )
            .setTitle(PlainComplicationText.Builder("BP").build())
            .build()
}
