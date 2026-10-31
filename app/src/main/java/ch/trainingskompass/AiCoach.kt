package ch.trainingskompass

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
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

    suspend fun ask(apiKey: String, system: String, user: String): JSONObject = withContext(Dispatchers.IO) {
        val out = JSONObject()
        try {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .system(system)
                .addUserMessage(user)
                // Lehnt das Modell ab, beantwortet ein passendes anderes Modell die Anfrage
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val msg = client.messages().create(params)
            if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") {
                out.put("error", "Die KI hat diese Anfrage abgelehnt.")
            } else {
                out.put("text", msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
            }
        } catch (e: UnauthorizedException) {
            out.put("error", "API-Schlüssel ungültig.")
        } catch (e: PermissionDeniedException) {
            out.put("error", "Dieser API-Schlüssel darf das Modell nicht nutzen.")
        } catch (e: RateLimitException) {
            out.put("error", "Gerade zu viele Anfragen, später nochmals.")
        } catch (e: AnthropicServiceException) {
            out.put("error", if (e.statusCode() == 402) "Kein Guthaben auf dem Anthropic-Konto." else "Die KI hat nicht geantwortet (${e.statusCode()}).")
        } catch (e: AnthropicIoException) {
            // Auf manchen Handys scheitert die Verbindung der Bibliothek; dann direkt per HTTPS versuchen
            return@withContext direct(apiKey, system, user, e)
        } catch (e: Throwable) {
            // Auch Fehler der Bibliothek auf Android melden, mit Ursache, damit sich das eingrenzen lässt
            out.put("error", "Die KI hat nicht geantwortet (${e.javaClass.simpleName}: ${e.message?.take(120) ?: "-"}).")
        }
        out
    }

    /** Gleiche Anfrage ohne Bibliothek, über HttpURLConnection (wie die Lebensmittel-Suche) */
    private fun direct(apiKey: String, system: String, user: String, first: Throwable): JSONObject {
        val out = JSONObject()
        try {
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 16000)
                .put("system", system)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                .put("output_config", JSONObject().put("effort", "medium"))
                .put("fallbacks", "default")
            val con = (java.net.URL("https://api.anthropic.com/v1/messages").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 150_000
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
            val why = (e.message ?: e.javaClass.simpleName).take(100)
            val why1 = (first.cause?.message ?: first.message ?: first.javaClass.simpleName).take(80)
            out.put("error", "Keine Verbindung zur KI ($why · $why1).")
        }
        return out
    }
}
