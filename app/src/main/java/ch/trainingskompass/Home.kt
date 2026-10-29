package ch.trainingskompass

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Daten für Widget und Erinnerungen. Die Seite schickt sie bei jeder Änderung über TKNative.setHome:
 * {days:[{date,name,n,done}], supps:{datum:[Namen]}, food:{datum:{n,k}}, kcalGoal, remind:{train:{on,t}, supp:{on,t}, food:{on,t}}}
 */
object HomeStore {
    private const val PREFS = "home"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", json).apply()
    }

    fun load(ctx: Context): JSONObject? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Das Training an einem Tag, oder null an Ruhetagen. */
    fun day(data: JSONObject, date: LocalDate): JSONObject? {
        val days = data.optJSONArray("days") ?: return null
        for (i in 0 until days.length()) {
            val d = days.optJSONObject(i) ?: continue
            if (d.optString("date") == date.toString()) return d
        }
        return null
    }

    /** Das nächste offene Training ab morgen. */
    fun next(data: JSONObject, from: LocalDate): JSONObject? {
        val days = data.optJSONArray("days") ?: return null
        var best: JSONObject? = null
        for (i in 0 until days.length()) {
            val d = days.optJSONObject(i) ?: continue
            val date = runCatching { LocalDate.parse(d.optString("date")) }.getOrNull() ?: continue
            if (date.isAfter(from) && !d.optBoolean("done") &&
                (best == null || date.isBefore(LocalDate.parse(best.optString("date"))))) best = d
        }
        return best
    }

    fun supps(data: JSONObject, date: LocalDate): List<String> {
        val a = data.optJSONObject("supps")?.optJSONArray(date.toString()) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
}

/** Plant die täglichen Erinnerungen und die Aktualisierung des Widgets um Mitternacht. */
object Reminders {
    const val CHANNEL = "erinnerungen"
    const val EXTRA_START = "start"
    private val TYPES = listOf("train", "supp", "food")

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val remind = HomeStore.load(ctx)?.optJSONObject("remind")
        for ((i, type) in TYPES.withIndex()) {
            val pi = alarmIntent(ctx, type, i)
            val r = remind?.optJSONObject(type)
            val time = r?.optString("t")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            if (r?.optBoolean("on") == true && time != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(time), pi)
            else am.cancel(pi)
        }
        // Kurz nach Mitternacht springt das Widget auf den neuen Tag.
        am.setAndAllowWhileIdle(AlarmManager.RTC, nextAt(LocalTime.of(0, 1)), alarmIntent(ctx, "widget", 9))
    }

    private fun nextAt(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var at = LocalDate.now().atTime(time)
        if (!at.isAfter(now.plusMinutes(2))) at = at.plusDays(1)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun alarmIntent(ctx: Context, type: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ReminderReceiver::class.java).setAction("ch.trainingskompass.ALARM").putExtra("type", type),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Öffnet die App; mit Datum startet sie gleich das Training dieses Tages. */
    fun openApp(ctx: Context, start: String?, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (start != null) i.putExtra(EXTRA_START, start)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT))
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFA83AFD.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val data = HomeStore.load(ctx)
        val today = LocalDate.now()
        if (data != null) when (intent.getStringExtra("type")) {
            "train" -> HomeStore.day(data, today)?.takeIf { !it.optBoolean("done") }?.let { d ->
                val n = d.optInt("n")
                Reminders.notify(
                    ctx, 1, "Heute: ${d.optString("name")}",
                    (if (n > 0) "$n Übungen · " else "") + "Tippe, um das Training zu starten.",
                    Reminders.openApp(ctx, today.toString(), 1),
                )
            }
            "supp" -> HomeStore.supps(data, today).takeIf { it.isNotEmpty() }?.let { names ->
                Reminders.notify(ctx, 2, "Supplements für heute", names.joinToString(", "), Reminders.openApp(ctx, null, 2))
            }
            // Trainer-Modus: abends erinnern, wenn heute kaum etwas eingetragen ist
            "food" -> {
                val goal = data.optInt("kcalGoal")
                val k = data.optJSONObject("food")?.optJSONObject(today.toString())?.optDouble("k", 0.0) ?: 0.0
                if (goal > 0 && k < goal * 0.6) Reminders.notify(
                    ctx, 3, "Ernährung eintragen",
                    if (k <= 0) "Heute ist noch nichts eingetragen." else "Erst ${k.toInt()} von $goal kcal eingetragen.",
                    Reminders.openApp(ctx, null, 3),
                )
            }
        }
        // Nach dem Neustart, einem Update oder einer Erinnerung den nächsten Termin planen.
        TodayWidget.updateAll(ctx)
        Reminders.reschedule(ctx)
    }
}

/** Widget für den Startbildschirm: zeigt das heutige Training und startet es mit einem Tipp. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { m.updateAppWidget(it, views(ctx)) }
        }

        private fun weekday(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).trimEnd('.')

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_today)
            val data = HomeStore.load(ctx)
            val today = LocalDate.now()
            val d = data?.let { HomeStore.day(it, today) }
            val next = data?.let { HomeStore.next(it, today) }
            val nextTxt = next?.let {
                val nd = LocalDate.parse(it.optString("date"))
                val label = if (nd == today.plusDays(1)) "Morgen" else "${weekday(nd)} ${nd.dayOfMonth}.${nd.monthValue}."
                "Nächstes: $label · ${it.optString("name")}"
            } ?: ""
            var start: String? = null
            when {
                data == null -> {
                    v.setTextViewText(R.id.w_label, "4Peak")
                    v.setTextViewText(R.id.w_title, "Öffne die App einmal")
                    v.setTextViewText(R.id.w_sub, "Danach steht hier dein Training.")
                }
                d != null && !d.optBoolean("done") -> {
                    val n = d.optInt("n")
                    v.setTextViewText(R.id.w_label, "Heute")
                    v.setTextViewText(R.id.w_title, d.optString("name"))
                    v.setTextViewText(R.id.w_sub, if (n > 0) "$n Übungen" else "")
                    start = today.toString()
                }
                d != null -> {
                    v.setTextViewText(R.id.w_label, "Heute ✓ erledigt")
                    v.setTextViewText(R.id.w_title, d.optString("name"))
                    v.setTextViewText(R.id.w_sub, nextTxt)
                }
                else -> {
                    v.setTextViewText(R.id.w_label, "Heute")
                    v.setTextViewText(R.id.w_title, "Ruhetag")
                    v.setTextViewText(R.id.w_sub, nextTxt)
                }
            }
            v.setViewVisibility(R.id.w_start, if (start != null) android.view.View.VISIBLE else android.view.View.GONE)
            v.setOnClickPendingIntent(R.id.w_root, Reminders.openApp(ctx, start, 10))
            return v
        }
    }
}
