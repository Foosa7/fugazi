package com.fugazi.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kimi K3 through Moonshot's OpenAI-compatible endpoint, over the platform's own HTTP and
 * JSON so it adds no dependency. K3 always thinks; temperature, top_p and n are fixed on
 * their side and rejected if sent, so they're left out.
 */
object Kimi {
    const val DEFAULT_MODEL = "kimi-k3"

    /** Models offered in Settings. Any other model id can be typed in. */
    val PRESETS = listOf(
        "kimi-k3" to "K3 — deepest, slower",
        "kimi-k2.6" to "K2.6 — faster, about a third of the price",
    )

    /** "default" sends no reasoning_effort, leaving it to the model — K3's own default is "max". */
    val EFFORTS = listOf("low", "high", "max", "default")
    private const val URL_CHAT = "https://api.moonshot.ai/v1/chat/completions"

    suspend fun chat(key: String, model: String, effort: String, system: String, user: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("model", model)
                .put("max_completion_tokens", 32_000)
            if (effort != "default") body.put("reasoning_effort", effort)
            body.put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
            val json = try {
                post(key, body)
            } catch (e: IllegalStateException) {
                // Not every model takes an effort setting; ask again without it rather than fail.
                if (!body.has("reasoning_effort") || e.message?.contains("reasoning", ignoreCase = true) != true) throw e
                body.remove("reasoning_effort")
                post(key, body)
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: error("No answer in the response")
            if (choice.optString("finish_reason") == "length") error("The answer was cut off")
            choice.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                ?: error("The answer was empty")
        }
    }

    private fun post(key: String, body: JSONObject): JSONObject {
        val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                error("HTTP $code${if (msg.isNullOrBlank()) "" else " — $msg"}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

/** The key lives only in this app's private storage on the phone — never in the source or the APK. */
object AiSettings {
    private const val PREFS = "ai"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(ctx: Context): String = prefs(ctx).getString("key", "").orEmpty()
    fun setKey(ctx: Context, key: String) = prefs(ctx).edit().putString("key", key.trim()).apply()

    fun model(ctx: Context): String = prefs(ctx).getString("model", null)?.ifBlank { null } ?: Kimi.DEFAULT_MODEL
    fun setModel(ctx: Context, model: String) = prefs(ctx).edit().putString("model", model.trim()).apply()

    /** K3 at "high" by default: "max" can take minutes, and a day's reflection doesn't need it. */
    fun effort(ctx: Context): String = prefs(ctx).getString("effort", "high")!!
    fun setEffort(ctx: Context, effort: String) = prefs(ctx).edit().putString("effort", effort).apply()

    /** Reflect on yesterday by itself each morning. */
    fun daily(ctx: Context): Boolean = prefs(ctx).getBoolean("daily", true)
    fun setDaily(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("daily", on).apply()
}
