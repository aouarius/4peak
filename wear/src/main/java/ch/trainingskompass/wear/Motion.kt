package ch.trainingskompass.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Zählt Wiederholungen über Beschleunigungssensor und Gyroskop der Uhr.
 *
 * Ablauf: Während eines Satzes werden die Rohdaten gesammelt. Alle halbe Sekunde wird der
 * Satz neu ausgewertet: ruhige Abschnitte abtrennen, im bewegtesten Abschnitt die
 * Hauptbewegungsrichtung bestimmen (Hauptkomponente) und darauf ganze Hin-und-her-Zyklen zählen.
 * Ein Zyklus ist eine Wiederholung. Die Daten jedes Satzes landen zusätzlich als CSV auf der Uhr,
 * damit später eine Übungserkennung damit trainiert werden kann.
 */
object Motion : SensorEventListener {

    /** Übungen, bei denen das Handgelenk still bleibt: dort wird nicht gezählt. */
    private val NO_COUNT = setOf(
        "legpress", "legext", "legcurl", "seatedcurl", "calf", "seatedcalf", "legpresscalf", "hipabd",
        "plank", "sideplank", "deadbug", "hollow", "lsit", "copenhagen", "supporthold", "deadhang",
        "tuckfl", "birddog", "clamshell", "pallof", "farmer", "cablekick", "tibraise",
    )

    fun supports(id: String) = id !in NO_COUNT

    /** Gezählte Wiederholungen im laufenden Satz (0 = noch nichts erkannt). */
    val count = MutableStateFlow(0)
    /** Kurze Auswertung des letzten gespeicherten Satzes, z. B. "8 gezählt · Ø 2,6 s/Wdh." */
    val lastSummary = MutableStateFlow<String?>(null)

    private const val MAX = 50 * 60 * 3 // höchstens 3 Minuten bei 50 Hz
    private val t = LongArray(MAX)
    private val a = Array(3) { FloatArray(MAX) }
    private val g = Array(3) { FloatArray(MAX) }
    private var n = 0
    private val gyro = FloatArray(3)
    private var phase = ""
    private var counting = false
    private var lastEval = 0L
    private var repTimes: List<Long> = emptyList()

    private var sm: SensorManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun start(ctx: Context) {
        if (sm != null) return
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val th = HandlerThread("4peak-motion").also { it.start() }
        val h = Handler(th.looper)
        sm = m; thread = th; handler = h
        // 50 Hz; Stapelung bis 1 s, damit bei dunklem Bildschirm nichts verloren geht
        m.registerListener(this, acc, 20_000, 1_000_000, h)
        m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { m.registerListener(this, it, 20_000, 1_000_000, h) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        thread?.quitSafely()
        sm = null; thread = null; handler = null
        phase = ""; counting = false; n = 0; count.value = 0
    }

    /**
     * Meldet, was gerade auf der Uhr passiert. Ein neuer Satz (anderer [tag]) leert den Puffer.
     * [active] = false in Pausen, beim Aufwärmen oder bei Übungen ohne Zählung.
     */
    fun setPhase(tag: String, active: Boolean) {
        val h = handler ?: return
        h.post {
            if (tag != phase) { phase = tag; n = 0; repTimes = emptyList(); count.value = 0 }
            counting = active
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) { e.values.copyInto(gyro, 0, 0, 3); return }
        if (!counting || n >= MAX) return
        t[n] = e.timestamp / 1_000_000
        for (k in 0..2) { a[k][n] = e.values[k]; g[k][n] = gyro[k] }
        n++
        if (t[n - 1] - lastEval >= 500) { lastEval = t[n - 1]; evaluate() }
    }

    private fun evaluate() {
        if (n < 50) return
        val fs = ((n - 1) * 1000.0 / (t[n - 1] - t[0]).coerceAtLeast(1)).coerceIn(10.0, 200.0)
        // 1. Glätten (gleitender Mittelwert über ~0,15 s)
        val sw = (fs * 0.15).toInt().coerceAtLeast(1)
        val s = Array(3) { k -> smooth(a[k], n, sw) }
        // 2. Bewegt oder ruhig? Streuung im 1-s-Fenster
        val w = fs.toInt().coerceAtLeast(5)
        val active = BooleanArray(n)
        val pre = Array(3) { DoubleArray(n + 1) }; val pre2 = Array(3) { DoubleArray(n + 1) }
        for (k in 0..2) for (i in 0 until n) { pre[k][i + 1] = pre[k][i] + s[k][i]; pre2[k][i + 1] = pre2[k][i] + s[k][i] * s[k][i] }
        for (i in 0 until n) {
            val lo = (i - w / 2).coerceAtLeast(0); val hi = (i + w / 2).coerceAtMost(n); val c = (hi - lo).toDouble()
            var v = 0.0
            for (k in 0..2) { val m = (pre[k][hi] - pre[k][lo]) / c; v += (pre2[k][hi] - pre2[k][lo]) / c - m * m }
            active[i] = sqrt(v.coerceAtLeast(0.0)) > 0.18
        }
        // 3. Abschnitte: Lücken unter 2,5 s gehören dazu, Abschnitte unter 3 s zählen nicht
        val bouts = mutableListOf<IntArray>()
        var st = -1; var last = -1
        val gap = (fs * 2.5).toInt()
        for (i in 0 until n) if (active[i]) {
            if (st < 0) st = i else if (i - last > gap) { bouts += intArrayOf(st, last); st = i }
            last = i
        }
        if (st >= 0) bouts += intArrayOf(st, last)
        var best = 0; var bestTimes: List<Long> = emptyList()
        for (b in bouts) {
            if (t[b[1]] - t[b[0]] < 3000) continue
            val times = boutReps(s, b[0], b[1])
            if (times.size >= 2 && times.size >= best) { best = times.size; bestTimes = times }
        }
        repTimes = bestTimes
        count.value = best
    }

    /**
     * Wiederholungen in einem Abschnitt: Zyklen entlang jeder der drei Hauptrichtungen zählen,
     * jeweils nur die längste gleichmässige Folge behalten (Gehen davor, Uhr-Anheben danach fallen weg).
     * Gewählt wird die Richtung mit der längsten Folge; bei fast gleicher Dauer die mit der grösseren
     * Bewegung (eine Drehung wie beim Curl erzeugt auf der Nebenachse doppelt so viele Zyklen).
     */
    private fun boutReps(s: Array<FloatArray>, from: Int, to: Int): List<Long> {
        val len = to - from + 1
        val mean = DoubleArray(3) { k -> (from..to).sumOf { s[k][it].toDouble() } / len }
        val c = Array(3) { DoubleArray(3) }
        for (i in from..to) for (p in 0..2) for (q in 0..2) c[p][q] += (s[p][i] - mean[p]) * (s[q][i] - mean[q])
        val axes = eigenvectors(c)
        data class Cand(val dur: Long, val sd: Double, val times: List<Long>)
        val cands = mutableListOf<Cand>()
        for (v in axes) {
            val proj = DoubleArray(len) { j -> val i = from + j; (0..2).sumOf { k -> (s[k][i] - mean[k]) * v[k] } }
            val sd = sqrt(proj.sumOf { it * it } / len)
            if (sd < 0.12) continue
            val times = regular(cycles(proj, from, 0.4 * sd))
            if (times.size >= 2) cands += Cand(times.last() - times.first(), sd, times)
        }
        if (cands.isEmpty()) return emptyList()
        val md = cands.maxOf { it.dur }
        return cands.filter { it.dur >= 0.85 * md }.maxByOrNull { it.sd }!!.times
    }

    /** Ein Zyklus = über +h und danach unter −h; mindestens 0,6 s Abstand. */
    private fun cycles(proj: DoubleArray, from: Int, h: Double): List<Long> {
        val out = mutableListOf<Long>()
        var up = false
        for (j in proj.indices) {
            if (!up && proj[j] > h) up = true
            else if (up && proj[j] < -h) {
                up = false
                val tm = t[from + j]
                if (out.isEmpty() || tm - out.last() >= 600) out += tm
            }
        }
        return out
    }

    /** Längste Folge mit gleichmässigem Takt (0,6- bis 1,7-fache des mittleren Abstands, höchstens 8 s). */
    private fun regular(ts: List<Long>): List<Long> {
        if (ts.size < 3) return ts
        val d = ts.zipWithNext { x, y -> y - x }
        val m = d.sorted()[d.size / 2].toDouble()
        var bs = 0; var be = 0; var st = 0
        for (i in d.indices) {
            val x = d[i]
            if (x < 0.6 * m || x > 1.7 * m || x > 8000) st = i + 1
            else if (i + 1 - st > be - bs) { bs = st; be = i + 1 }
        }
        return ts.subList(bs, be + 1)
    }

    /** Eigenvektoren einer symmetrischen 3×3-Matrix (Jacobi-Verfahren). */
    private fun eigenvectors(m: Array<DoubleArray>): List<DoubleArray> {
        val a = Array(3) { m[it].copyOf() }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(20) {
            for ((p, q) in listOf(0 to 1, 0 to 2, 1 to 2)) {
                if (abs(a[p][q]) < 1e-12) continue
                val th = 0.5 * kotlin.math.atan2(2 * a[p][q], a[q][q] - a[p][p])
                val cs = kotlin.math.cos(th); val sn = kotlin.math.sin(th)
                for (k in 0..2) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = cs * akp - sn * akq; a[k][q] = sn * akp + cs * akq
                }
                for (k in 0..2) {
                    val apk = a[p][k]; val aqk = a[q][k]
                    a[p][k] = cs * apk - sn * aqk; a[q][k] = sn * apk + cs * aqk
                }
                for (k in 0..2) {
                    val vkp = v[k][p]; val vkq = v[k][q]
                    v[k][p] = cs * vkp - sn * vkq; v[k][q] = sn * vkp + cs * vkq
                }
            }
        }
        return (0..2).map { j -> DoubleArray(3) { i -> v[i][j] } }
    }

    private fun smooth(x: FloatArray, n: Int, w: Int): FloatArray {
        val o = FloatArray(n); var sum = 0.0
        for (i in 0 until n) {
            sum += x[i]; if (i >= w) sum -= x[i - w]
            o[i] = (sum / minOf(i + 1, w)).toFloat()
        }
        return o
    }

    /**
     * Beim Speichern eines Satzes: Kurzfazit fürs Pausenbild und Rohdaten als CSV ablegen.
     * Gibt die gezählten Wiederholungen zurück (0 = nichts erkannt).
     */
    fun finishSet(ctx: Context, exId: String, logged: Int, weight: Double): Int {
        val h = handler ?: return 0
        val counted = count.value
        val times = repTimes
        count.value = 0
        lastSummary.value = if (counted >= 2) summary(counted, times) else null
        val dir = File(ctx.filesDir, "motion")
        val stamp = System.currentTimeMillis()
        h.post { runCatching { writeCsv(dir, stamp, exId, logged, counted, weight) } }
        return counted
    }

    private fun summary(counted: Int, times: List<Long>): String {
        val d = times.zipWithNext { x, y -> (y - x) / 1000.0 }
        if (d.size < 2) return "$counted gezählt"
        val avg = d.average()
        var s = "$counted gezählt · Ø " + String.format(Locale.GERMAN, "%.1f", avg) + " s/Wdh."
        // Letzte Wiederholungen deutlich langsamer als die ersten: Zeichen, dass du nah am Versagen warst
        if (d.size >= 4) {
            val k = (d.size / 3).coerceAtLeast(1)
            val first = d.take(k).average(); val end = d.takeLast(k).average()
            val pct = ((end / first - 1) * 100).toInt()
            if (pct >= 20) s += " · zuletzt $pct % langsamer"
        }
        return s
    }

    private fun writeCsv(dir: File, stamp: Long, exId: String, logged: Int, counted: Int, weight: Double) {
        if (n < 50) return
        dir.mkdirs()
        // Platz begrenzen: höchstens 400 Sätze aufbewahren
        dir.listFiles()?.sortedBy { it.name }?.let { fs -> if (fs.size >= 400) fs.take(fs.size - 399).forEach { it.delete() } }
        val f = File(dir, "${stamp}_${exId}.csv")
        f.bufferedWriter().use { out ->
            out.write("# ex=$exId logged=$logged counted=$counted w=$weight\n")
            out.write("ms,ax,ay,az,gx,gy,gz\n")
            val t0 = t[0]
            for (i in 0 until n) {
                out.write("${t[i] - t0},${f2(a[0][i])},${f2(a[1][i])},${f2(a[2][i])},${f2(g[0][i])},${f2(g[1][i])},${f2(g[2][i])}\n")
            }
        }
    }

    private fun f2(x: Float) = if (abs(x) < 0.005f) "0" else String.format(Locale.US, "%.2f", x)
}
