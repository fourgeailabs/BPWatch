package com.fourgeailabs.bpwatch.wear

import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Wear OS Tile (v2.4.2): the swipeable watch card showing the most recent
 * BP reading and the time it was taken. Tapping it opens the BPWatch app.
 * Tiles refresh whenever the user swipes to them; the freshness interval is
 * a backstop so a stale tile never sits for hours. Never throws — any
 * failure renders the "--/--" no-data tile.
 *
 * Note: no Guava here — the tile answers synchronously from SharedPrefs,
 * so a tiny hand-rolled immediate future is all TileService needs.
 */
class BpTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val FRESHNESS_MS = 15 * 60 * 1000L
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = try {
        val est = try {
            WatchSettings.loadEstimate(this)
        } catch (_: Exception) {
            null
        }
        val hasReading = est != null && est.sys > 0 && est.dia > 0
        val reading = if (hasReading) "${est!!.sys}/${est.dia}" else "--/--"
        val whenText = if (hasReading) {
            "mmHg · " + SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(est!!.timestamp))
        } else {
            "No readings yet"
        }
        immediateFuture(buildTile(requestParams, reading, whenText))
    } catch (_: Exception) {
        immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTimeline(singleTextTimeline("--/--"))
                .build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build(),
        )

    private fun buildTile(
        requestParams: RequestBuilders.TileRequest,
        reading: String,
        whenText: String,
    ): TileBuilders.Tile {
        val deviceParams = requestParams.deviceParameters
            ?: DeviceParametersBuilders.DeviceParameters.Builder().build()

        val openApp = ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()

        // The whole tile is tappable; the semantics node names it for
        // accessibility ("Blood pressure 128/84, taken 2:30 PM").
        val root = LayoutElementBuilders.Column.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(openApp)
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription("Blood pressure $reading, taken $whenText")
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(reading)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyles.display2(deviceParams).build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(
                        DimensionBuilders.DpProp.Builder().setValue(4f).build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(whenText)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyles.title3(deviceParams).build(),
                    )
                    .build(),
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(root)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun singleTextTimeline(text: String): TimelineBuilders.Timeline =
        TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(
                                LayoutElementBuilders.Text.Builder()
                                    .setText(text)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

    /** A ListenableFuture that's already done — no Guava needed. */
    private fun <T> immediateFuture(value: T): ListenableFuture<T> =
        object : ListenableFuture<T> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override fun isCancelled(): Boolean = false
            override fun isDone(): Boolean = true
            override fun get(): T = value
            override fun get(timeout: Long, unit: TimeUnit): T = value
            override fun addListener(listener: Runnable, executor: Executor) {
                executor.execute(listener)
            }
        }
}
