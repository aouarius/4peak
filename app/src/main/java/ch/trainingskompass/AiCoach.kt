package ch.trainingskompass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * KI-Trainer in der Handy-App: schickt die Zusammenfassung der Web-App an Claude.
 * Der API-Schlüssel kommt von der Person selbst (Profil), er bleibt nur auf dem Handy.
 * Ergebnis: {text} oder {error}.
 */
object AiCoach {
    private const val MODEL = "claude-opus-5-5"

    /**
     * Direkt per HTTPS (HttpURLConnection). Die Java-Bibliothek kam auf dem Handy nicht durch
     * (Verbindungsfehler, dann Zeitüberschreitung), der einfache Weg funktioniert wie die Lebensmittel-Suche.
     */
    suspend fun ask(apiKey: String, system: String, user: String, effort: String = "medium", imageJpegB64: String? = null): JSONObject = withContext(Dispatchers.IO) {
        direct(apiKey, system, user, effort, imageJpegB64)
    }

    /** Gleiche Anfrage ohne Bibliothek, über HttpURLConnection (wie die Lebensmittel-Suche) */
    private fun direct(apiKey: String, system: String, user: String, effort: String, imageJpegB64: String?): JSONObject {
        val t0 = System.currentTimeMillis()
        val out = JSONObject()
        try {
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 16000)
                .put("system", system)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content",
                    if (imageJpegB64 == null) user
                    else org.json.JSONArray()
                        .put(JSONObject().put("type", "image").put("source", JSONObject()
                            .put("type", "base64").put("media_type", "image/jpeg").put("data", imageJpegB64)))
                        .put(JSONObject().put("type", "text").put("text", user)))))
                .put("output_config", JSONObject().put("effort", effort))
                .put("fallbacks", "default")
            val con = (java.net.URL("https://api.anthropic.com/v1/messages").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 110_000
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
            }
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when (code) {
                in 200..299 -> {
                    val o = JSONObject(text)
                    if (o.optString("stop_reason") == "refusal") out.put("error", "Die KI hat diese Anfrage abgelehnt.")
                    else {
                        val c = o.optJSONArray("content") ?: org.json.JSONArray()
                        out.put("text", (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") })
                    }
                }
                401 -> out.put("error", "API-Schlüssel ungültig.")
                403 -> out.put("error", "Dieser API-Schlüssel darf das Modell nicht nutzen.")
                429 -> out.put("error", "Gerade zu viele Anfragen, später nochmals.")
                402 -> out.put("error", "Kein Guthaben auf dem Anthropic-Konto.")
                else -> {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                    out.put("error", "Die KI hat nicht geantwortet ($code${if (!msg.isNullOrBlank()) ": " + msg.take(140) else ""}).")
                }
            }
        } catch (e: Throwable) {
            val why = (e.message ?: e.javaClass.simpleName).take(120)
            out.put("error", "Keine Verbindung zur KI (${e.javaClass.simpleName}: $why, nach ${(System.currentTimeMillis() - t0) / 1000} s).")
        }
        return out
    }
}
