package ch.trainingskompass.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Plan und erledigte Tage, gemeinsam für App, Kachel und Komplikation. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tk", Context.MODE_PRIVATE)

    fun plan(ctx: Context): Plan? = prefs(ctx).getString("plan", null)?.let { parsePlan(it) }
    fun done(ctx: Context): Set<String> = prefs(ctx).getStringSet("done", emptySet())!!.toSet()

    fun savePlan(ctx: Context, json: String) {
        prefs(ctx).edit().putString("plan", json).apply()
        refresh(ctx)
    }

    fun saveDone(ctx: Context, done: Set<String>) {
        prefs(ctx).edit().putStringSet("done", done).apply()
        refresh(ctx)
    }

    /** Das nächste offene Training ab heute. */
    fun next(ctx: Context): Session? {
        val today = LocalDate.now().toString()
        val done = done(ctx)
        return plan(ctx)?.sessions?.filter { it.date >= today && it.date !in done }?.minByOrNull { it.date }
    }

    fun dayLabel(date: String, long: Boolean = true): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val today = LocalDate.now()
        return when (d) {
            today -> "Heute"
            today.plusDays(1) -> "Morgen"
            else -> d.dayOfWeek.getDisplayName(if (long) TextStyle.FULL else TextStyle.SHORT, Locale.GERMAN).trimEnd('.') +
                if (long) ", ${d.dayOfMonth}.${d.monthValue}." else ""
        }
    }

    /** Kachel und Komplikation neu zeichnen lassen. */
    fun refresh(ctx: Context) {
        runCatching { TileService.getUpdater(ctx).requestUpdate(TodayTile::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, NextComplication::class.java)).requestUpdateAll()
        }
    }

    fun openIntent(ctx: Context, start: String?): PendingIntent {
        val i = Intent(ctx, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (start != null) i.putExtra(WatchActivity.EXTRA_START, start)
        return PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Neuer Plan vom Handy, auch wenn die Uhr-App gerade nicht offen ist. */
class PlanListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WatchActivity.PLAN_PATH) {
                DataMapItem.fromDataItem(e.dataItem).dataMap.getString("json")?.let { Store.savePlan(this, it) }
            }
        }
    }
}

/** Kachel: nächstes Training, ein Tipp startet es. */
class TodayTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val s = Store.next(this)
        val purple = argb(0xFFB450FF.toInt())
        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(if (s == null) "4Peak" else Store.dayLabel(s.date), 14f, purple, bold = true))
            .addContent(spacer(4f))
            .addContent(text(s?.name ?: "Keine offenen Trainings", 18f, argb(0xFFFFFFFF.toInt()), bold = true, lines = 2))
            .addContent(spacer(4f))
            .addContent(text(if (s == null) "Gute Erholung!" else "${s.items.size} Übungen", 13f, argb(0xFFA0A6B0.toInt())))
        if (s != null) {
            col.addContent(spacer(10f)).addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(96f)).setHeight(dp(40f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(0xFFA83AFD.toInt()))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                                    .build(),
                            )
                            .setClickable(launch(s.date))
                            .build(),
                    )
                    .addContent(text("Starten", 15f, argb(0xFFFFFFFF.toInt()), bold = true))
                    .build(),
            )
        }
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch(null)).build())
            .addContent(col.build())
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES)
            .setFreshnessIntervalMillis(30 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES).build())

    private fun launch(start: String?): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(WatchActivity::class.java.name)
        if (start != null) activity.addKeyToExtraMapping(WatchActivity.EXTRA_START, ActionBuilders.stringExtra(start))
        return ModifiersBuilders.Clickable.Builder()
            .setId(start ?: "open")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    private fun text(t: String, size: Float, color: androidx.wear.protolayout.ColorBuilders.ColorProp, bold: Boolean = false, lines: Int = 1) =
        LayoutElementBuilders.Text.Builder()
            .setText(t)
            .setMaxLines(lines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(color)
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).setWidth(dp(1f)).build()

    companion object { private const val RES = "1" }
}

/** Komplikation fürs Zifferblatt: wann und welches Training als Nächstes kommt. */
class NextComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, "Heute", "Ganzkörper A", null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val s = Store.next(this)
        return if (s == null) build(request.complicationType, "Frei", "Keine offenen Trainings", null)
        else build(request.complicationType, Store.dayLabel(s.date, long = false), s.name, s.date)
    }

    private fun build(type: ComplicationType, short: String, name: String, start: String?): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_notify)).build()
        val desc = PlainComplicationText.Builder("$short: $name").build()
        val tap = Store.openIntent(this, start)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(short).build(), desc)
                .setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(name).build(), desc)
                .setTitle(PlainComplicationText.Builder(short).build())
                .setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}
