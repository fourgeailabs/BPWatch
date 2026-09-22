package com.fourgeailabs.bpwatch.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watch-face complication (v2.3, tap action + reading time v2.4.2): the
 * latest BP estimate the phone sent, with the time it was taken. Tapping
 * the complication opens the BPWatch app. Never throws — any failure
 * returns the "—" no-data card.
 */
class BpComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        card("128/84", "Blood pressure 128/84 at 2:30 PM")

    override suspend fun onComplicationRequest(
        request: ComplicationRequest,
    ): ComplicationData = try {
        val est = WatchSettings.loadEstimate(this)
        if (est != null && est.sys > 0 && est.dia > 0) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(est.timestamp))
            card("${est.sys}/${est.dia}", "Blood pressure ${est.sys}/${est.dia} at $time")
        } else {
            card("—", "Blood pressure — no readings yet")
        }
    } catch (_: Exception) {
        card("—", "Blood pressure — no readings yet")
    }

    private fun card(text: String, contentDescription: String): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(contentDescription).build(),
        )
            .setTitle(PlainComplicationText.Builder("BP").build())
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
