package ch.trainingskompass.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Bildschirmfotos aller Uhr-Ansichten mit Beispieldaten. CI legt sie im Branch «screens» ab,
 * damit sich das Design ohne Uhr prüfen lässt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class ScreensTest {

    private val today = LocalDate.now()
    private fun item(id: String, n: String, sets: Int = 3, reps: String = "6–10", w: Double = 0.0, uni: Boolean = false, bw: Boolean = false, cardio: Boolean = false, txt: String = "") =
        JSONObject().put("id", id).put("n", n).put("sets", sets).put("reps", reps).put("w", w).put("uni", uni).put("bw", bw).put("cardio", cardio).put("txt", txt).put("rest", 120).put("step", 2.5)

    private val planJson = JSONObject().put("maxHr", 188).put("restHr", 56).put(
        "sessions",
        org.json.JSONArray()
            .put(
                JSONObject().put("date", today.plusDays(1).toString()).put("name", "Unterkörper B").put(
                    "items",
                    org.json.JSONArray()
                        .put(item("bss", "Bulgarian Split Squat", uni = true, bw = true))
                        .put(item("hyper", "Rückenstrecker (45° Hyperextension)", bw = true))
                        .put(item("rdl", "Rumänisches Kreuzheben", w = 70.0))
                        .put(item("legcurl", "Beinbeuger-Maschine", w = 35.0))
                        .put(item("calf", "Wadenheben stehend", w = 40.0, reps = "10–15"))
                        .put(item("plank", "Unterarmstütz", reps = "30–45 s").put("sec", true))
                        .put(item("bike", "Ergometer locker", cardio = true, txt = "10 min, Zone 2")),
                ).put(
                    "warm",
                    JSONObject().put("min", 6).put(
                        "types",
                        org.json.JSONArray()
                            .put(JSONObject().put("k", "cardio").put("n", "Cardio").put("on", true).put("steps", org.json.JSONArray().put(JSONObject().put("n", "Rudergerät").put("d", "5 min locker"))))
                            .put(JSONObject().put("k", "mob").put("n", "Mobilität").put("on", false).put("steps", org.json.JSONArray())),
                    ),
                ),
            )
            .put(JSONObject().put("date", today.plusDays(5).toString()).put("name", "Oberkörper A").put("items", org.json.JSONArray().put(item("bench", "Bankdrücken (Langhantel)", w = 60.0)).put(item("row", "Langhantelrudern", w = 50.0))))
            .put(JSONObject().put("date", today.plusDays(7).toString()).put("name", "Ganzkörper").put("deload", true).put("items", org.json.JSONArray().put(item("squat", "Kniebeuge", w = 80.0)))),
    ).toString()

    private val plan = parsePlan(planJson)!!

    @After
    fun reset() {
        WorkoutService.hr.value = 0; WorkoutService.kcal.value = 0; Motion.count.value = 0; Motion.lastSummary.value = null
    }

    @get:Rule
    val rule = createAndroidComposeRule<WatchActivity>()

    private fun shot(name: String, setup: (WatchActivity) -> Unit) {
        // Uhr anhalten: sonst laufen die Zeitschleifen (Pause, Zähler) endlos weiter
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { val a = rule.activity; a.plan = plan; setup(a) }
        repeat(10) { rule.mainClock.advanceTimeBy(100) }
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun runOf(i: Int = 0, warm: Boolean = false) = Run(if (warm) plan.sessions[i] else plan.sessions[i].copy(warm = null))

    @Test fun planList() = shot("01-plan") { }

    @Test fun exercise() = shot("02-uebung") {
        WorkoutService.hr.value = 128; WorkoutService.kcal.value = 46; Motion.count.value = 8
        it.run = runOf().apply { reps = 8 }
    }

    @Test fun exerciseLong() = shot("03-uebung-lang") {
        it.run = runOf().apply { index = 1; reps = 12 }
    }

    @Test fun exerciseWeight() = shot("04-uebung-gewicht") {
        WorkoutService.hr.value = 152; WorkoutService.kcal.value = 120
        it.run = runOf().apply { index = 2; setNo = 2; weight = 70.0; reps = 8 }
    }

    @Test fun rest() = shot("05-pause") {
        WorkoutService.hr.value = 112; WorkoutService.kcal.value = 58; Motion.lastSummary.value = "8 gezählt · gleichmässiges Tempo"
        it.run = runOf().apply { setNo = 2; restLen = 120_000; restUntil = System.currentTimeMillis() + 92_000 }
    }

    @Test fun order() = shot("06-reihenfolge") { it.run = runOf().apply { picking = true } }

    @Test fun warmup() = shot("07-aufwaermen") { WorkoutService.hr.value = 104; it.run = runOf(warm = true) }

    @Test fun warmReady() = shot("08-aufgewaermt") { WorkoutService.hr.value = 124; it.run = runOf(warm = true).apply { warmReady = true } }

    @Test fun cardio() = shot("09-cardio") { WorkoutService.hr.value = 131; it.run = runOf().apply { index = 6 } }

    @Test fun finish() = shot("10-fertig") {
        WorkoutService.kcal.value = 312
        it.run = runOf().apply { repeat(3) { logSet() }; index = items.size }
    }

    @Test fun ambient() = shot("11-always-on") { WorkoutService.hr.value = 118; it.run = runOf(); it.ambient = true }

    @Test fun empty() = shot("12-leer") { it.plan = null }
}
