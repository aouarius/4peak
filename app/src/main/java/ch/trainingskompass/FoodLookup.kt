package ch.trainingskompass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sucht ein Lebensmittel per Barcode in der freien Datenbank Open Food Facts. */
object FoodLookup {

    private const val FIELDS = "product_name,product_name_de,brands,nutriments,serving_quantity,quantity,nova_group"

    suspend fun lookup(ean: String): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject().put("ean", ean)
        val conn = runCatching {
            (URL("https://world.openfoodfacts.org/api/v2/product/$ean.json?fields=$FIELDS").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                // Open Food Facts verlangt eine erkennbare App-Kennung
                setRequestProperty("User-Agent", "4peak/0.1 (Android; github.com/aouarius/4peak)")
            }
        }.getOrNull() ?: return@withContext result.put("error", "Keine Verbindung zu Open Food Facts.")
        try {
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            val product = json?.optJSONObject("product")
            if (json == null || json.optInt("status") != 1 || product == null) {
                return@withContext result.put("error", "Dieses Produkt ist bei Open Food Facts noch nicht erfasst.")
            }
            val n = product.optJSONObject("nutriments") ?: JSONObject()
            val kcal = n.num("energy-kcal_100g") ?: n.num("energy_100g")?.div(4.184)
            result
                .put("name", product.optString("product_name_de").ifBlank { product.optString("product_name") })
                .put("brand", product.optString("brands").substringBefore(',').trim())
                .put("kcal", kcal)
                .put("prot", n.num("proteins_100g"))
                .put("carb", n.num("carbohydrates_100g"))
                .put("fat", n.num("fat_100g"))
                .put("sugar", n.num("sugars_100g"))
                .put("satfat", n.num("saturated-fat_100g"))
                .put("fiber", n.num("fiber_100g"))
                .put("salt", n.num("salt_100g"))
                // NOVA 1–4: wie stark das Produkt industriell verarbeitet ist
                .put("nova", product.optInt("nova_group", 0).takeIf { it in 1..4 })
                .put("serving", product.num("serving_quantity"))
                .put("qty", product.optString("quantity"))
        } catch (e: Exception) {
            result.put("error", "Keine Verbindung zu Open Food Facts. Prüfe dein Internet.")
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.num(key: String): Double? =
        if (has(key)) optDouble(key).takeIf { !it.isNaN() && it >= 0 } else null
}
