package ch.trainingskompass.wear

import android.app.Application
import android.content.Context

/**
 * Hält Abstürze fest: Der Fehler landet in den Einstellungen und wird beim nächsten Start
 * auf der Uhr angezeigt, damit man ihn abfotografieren kann.
 */
class FourPeakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = e.stackTraceToString().lineSequence().take(40).joinToString("\n")
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("trace", "Version ${BuildConfig.VERSION_NAME}\n$trace").commit()
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CRASH_PREFS = "crash"
        fun take(ctx: Context): String? {
            val p = ctx.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
            return p.getString("trace", null)
        }
        fun clear(ctx: Context) = ctx.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
