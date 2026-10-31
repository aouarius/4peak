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
            out.put("error", "Keine Verbindung zur KI.")
        } catch (e: Throwable) {
            // Auch Fehler der Bibliothek auf Android melden, mit Ursache, damit sich das eingrenzen lässt
            out.put("error", "Die KI hat nicht geantwortet (${e.javaClass.simpleName}: ${e.message?.take(120) ?: "-"}).")
        }
        out
    }
}
