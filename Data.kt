package com.example.intradayscreener

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface StockSource { suspend fun load(): List<RawStock> }

/** Calls the endpoint configured in SourceConfig and maps JSON via key aliases. */
class HttpSource : StockSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun load(): List<RawStock> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(SourceConfig.ENDPOINT)
            .header("User-Agent", "Mozilla/5.0 (Android)")
            .header("Accept", "application/json")
        if (SourceConfig.HTTP_METHOD.equals("POST", true))
            builder.post(SourceConfig.POST_BODY.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            parse(resp.body?.string().orEmpty())
        }
    }

    private fun parse(body: String): List<RawStock> {
        val root = body.trim()
        val arr = if (root.startsWith("[")) JSONArray(root) else findArray(JSONObject(root))
            ?: error("No stock list found in response")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sym = o.str(SourceConfig.SYMBOL) ?: return@mapNotNull null
            RawStock(
                symbol = sym,
                name = o.str(SourceConfig.NAME) ?: sym,
                ltp = o.num(SourceConfig.LTP) ?: return@mapNotNull null,
                dayChangePct = o.num(SourceConfig.DAY_CHANGE_PCT) ?: 0.0,
                high52 = o.num(SourceConfig.HIGH_52W) ?: return@mapNotNull null,
                low52 = o.num(SourceConfig.LOW_52W) ?: return@mapNotNull null,
                volume = o.num(SourceConfig.VOLUME) ?: 0.0,
                avgVolume1w = o.num(SourceConfig.AVG_VOLUME_1W) ?: return@mapNotNull null,
                marketCapCr = (o.num(SourceConfig.MARKET_CAP) ?: return@mapNotNull null) /
                    SourceConfig.MCAP_TO_CRORE_DIVISOR,
            )
        }
    }

    /** Depth-first search for the first array of objects in the response. */
    private fun findArray(o: JSONObject): JSONArray? {
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is JSONArray -> if (v.length() > 0 && v.get(0) is JSONObject) return v
                is JSONObject -> findArray(v)?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.num(keys: List<String>): Double? =
        keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optString(k).toDoubleOrNull() else null }

    private fun JSONObject.str(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null }
}

/** Offline demo data so the app is usable before the endpoint is configured. */
class SampleSource : StockSource {
    override suspend fun load(): List<RawStock> = listOf(
        RawStock("ALPHA", "Alpha Industries", 250.0, 4.8, 280.0, 180.0, 3_000_000.0, 1_500_000.0, 5200.0),
        RawStock("BETA", "Beta Chemicals", 1450.0, 2.1, 1800.0, 1000.0, 900_000.0, 500_000.0, 22000.0),
        RawStock("GAMMA", "Gamma Pharma", 640.0, -1.2, 650.0, 400.0, 2_200_000.0, 1_000_000.0, 9100.0),  // fails h%
        RawStock("DELTA", "Delta Motors", 95.0, 6.3, 140.0, 60.0, 8_000_000.0, 3_000_000.0, 800.0),      // fails mcap
        RawStock("OMEGA", "Omega Finance", 3200.0, 3.4, 3900.0, 2300.0, 1_700_000.0, 900_000.0, 480000.0),
        RawStock("SIGMA", "Sigma Tech", 410.0, 0.9, 520.0, 380.0, 700_000.0, 600_000.0, 7000.0),         // fails v%, l%
    )
}

object Repository {
    val source: StockSource = if (SourceConfig.ENDPOINT.isBlank()) SampleSource() else HttpSource()
    val isSample get() = SourceConfig.ENDPOINT.isBlank()
}
