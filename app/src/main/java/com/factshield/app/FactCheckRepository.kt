package com.factshield.app

import android.graphics.Bitmap
import android.util.LruCache
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────
// GROQ API Key is loaded from local.properties or environment variable via BuildConfig.
val GROQ_API_KEY: String = BuildConfig.GROQ_API_KEY

private const val TAG = "FactCheckRepo"
private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
private const val VISION_MODEL = "qwen/qwen3.8-27b"
private const val FAST_TEXT_MODEL = "openai/gpt-oss-20b"

// ─────────────────────────────────────────────────────────────
//  Domain models
// ─────────────────────────────────────────────────────────────

enum class FactStatus { GREEN, YELLOW, RED }

data class FactCheckResult(
    val status: FactStatus,
    val confidence: Int,
    val shortVerdict: String,
    val sources: List<String>
)

// ─────────────────────────────────────────────────────────────
//  Repository
// ─────────────────────────────────────────────────────────────

object FactCheckRepository {

    // Fast client for Groq API
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    // Strict 1.8s timeout client for Google News RSS so it never hangs the pipeline
    private val rssClient = OkHttpClient.Builder()
        .callTimeout(1800, TimeUnit.MILLISECONDS)
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /** LruCache keyed by tweetText.hashCode() — 0ms for re-checked posts */
    private val cache = LruCache<Int, FactCheckResult>(50)

    // ── Public entry point ───────────────────────────────────

    suspend fun analyze(
        tweetText: String,
        screenshot: Bitmap?
    ): FactCheckResult = withContext(Dispatchers.IO) {

        val cacheKey = (tweetText.trim().ifEmpty { "IMG_${screenshot?.byteCount ?: 0}" }).hashCode()
        cache.get(cacheKey)?.let { cached ->
            Log.d(TAG, "Cache hit for key $cacheKey")
            return@withContext cached
        }

        // Run Step A (Google News RSS) in parallel with bitmap preparation
        val headlinesDeferred = async(Dispatchers.IO) {
            fetchLiveNewsHeadlines(tweetText)
        }

        val base64Image = if (screenshot != null) {
            try { screenshot.toBase64Jpeg() } catch (e: Exception) { null }
        } else null

        val liveHeadlines = headlinesDeferred.await()
        Log.d(TAG, "Live headlines fetched: ${liveHeadlines.size}")

        // Fast Model Routing:
        // If tweet has readable text (>= 20 chars), use ultra-fast text model (1.1s turnaround).
        // If image-only or meme, use vision model.
        val result = if (base64Image != null && tweetText.length < 20) {
            try {
                callGroqVision(tweetText, liveHeadlines, base64Image)
            } catch (e: Exception) {
                Log.w(TAG, "Vision model call failed (${e.message}), falling back to fast text model")
                callGroqFastText(tweetText, liveHeadlines)
            }
        } else {
            try {
                callGroqFastText(tweetText, liveHeadlines)
            } catch (e: Exception) {
                Log.w(TAG, "Fast text model failed (${e.message}), attempting vision fallback")
                callGroqVision(tweetText, liveHeadlines, base64Image)
            }
        }

        cache.put(cacheKey, result)
        result
    }

    // ── Step A: Google News RSS ──────────────────────────────

    private fun fetchLiveNewsHeadlines(tweetText: String): List<String> {
        if (tweetText.isBlank()) return emptyList()
        return try {
            val keywords = extractTopKeywords(tweetText, limit = 5)
            if (keywords.isBlank()) return emptyList()
            val query = URLEncoder.encode(keywords, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$query&hl=en-IN&gl=IN&ceid=IN:en"

            val request = Request.Builder().url(url).get().build()
            val body = rssClient.newCall(request).execute().use { it.body?.string() ?: "" }

            val items = mutableListOf<String>()
            val itemPattern = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titlePattern = Regex("<title>(.*?)</title>")
            val sourcePattern = Regex("<source[^>]*>(.*?)</source>")

            for (match in itemPattern.findAll(body).take(4)) {
                val block = match.groupValues[1]
                val title = titlePattern.find(block)?.groupValues?.get(1)
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: continue
                val source = sourcePattern.find(block)?.groupValues?.get(1)
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                items += if (source.isNotBlank()) "[$source] $title" else title
            }
            items
        } catch (e: Exception) {
            Log.d(TAG, "News RSS query finished: ${e.message}")
            emptyList()
        }
    }

    private fun extractTopKeywords(text: String, limit: Int): String {
        val stopWords = setOf(
            "the", "a", "an", "is", "in", "on", "at", "to", "for", "of",
            "and", "or", "but", "it", "this", "that", "was", "are", "be",
            "has", "had", "have", "with", "from", "by", "as", "i", "we",
            "you", "he", "she", "they", "not", "no", "so", "just", "like"
        )
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split("\\s+".toRegex())
            .filter { it.length > 3 && it !in stopWords }
            .distinct()
            .sortedByDescending { it.length }
            .take(limit)
            .joinToString(" ")
    }

    // ── Groq API Calls ───────────────────────────────────────

    private fun callGroqFastText(
        tweetText: String,
        headlines: List<String>
    ): FactCheckResult {
        val payload = JSONObject().apply {
            put("model", FAST_TEXT_MODEL)
            put("temperature", 0.0)
            put("max_completion_tokens", 140)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", buildSystemPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", buildUserText(tweetText, headlines))
                })
            })
        }
        return executeGroqRequest(payload)
    }

    private fun callGroqVision(
        tweetText: String,
        headlines: List<String>,
        base64Image: String?
    ): FactCheckResult {
        val userText = buildUserText(tweetText, headlines)
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userText)
            })
            if (base64Image != null) {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
            }
        }

        val payload = JSONObject().apply {
            put("model", VISION_MODEL)
            put("temperature", 0.0)
            put("max_completion_tokens", 140)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", buildSystemPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", if (base64Image != null) contentArray else userText)
                })
            })
        }
        return executeGroqRequest(payload)
    }

    private fun executeGroqRequest(payload: JSONObject): FactCheckResult {
        val requestBody = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(GROQ_ENDPOINT)
            .addHeader("Authorization", "Bearer $GROQ_API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "Groq HTTP error ${response.code}: $bodyString")
                throw RuntimeException("Groq API error: ${response.code} $bodyString")
            }
            if (bodyString.isBlank()) throw RuntimeException("Empty Groq response")
            bodyString
        }

        return parseGroqResponse(responseBody)
    }

    // ── Response Parsing ─────────────────────────────────────

    private fun parseGroqResponse(rawResponse: String): FactCheckResult {
        return try {
            val root = JSONObject(rawResponse)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = content.trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val obj = JSONObject(cleaned)
            val statusStr = obj.optString("status", "YELLOW").uppercase()
            val status = try { FactStatus.valueOf(statusStr) } catch (_: Exception) { FactStatus.YELLOW }
            val confidence = obj.optInt("confidence", 75).coerceIn(0, 100)
            val verdict = obj.optString("shortVerdict", "Information appears unverified. Check sources.").take(120)
            val sourcesArray = obj.optJSONArray("sources")
            val sources = mutableListOf<String>()
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    val s = sourcesArray.getString(i).trim()
                    if (s.isNotBlank()) sources += s
                }
            }
            // Don't inject fake generic sources — if the AI didn't return real ones, show nothing

            FactCheckResult(status, confidence, verdict, sources.take(2))
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            FactCheckResult(
                status = FactStatus.YELLOW,
                confidence = 50,
                shortVerdict = "Content requires further verification.",
                sources = listOf("Google Search", "Official Outlets")
            )
        }
    }

    // ── Prompts with Strict Epistemic Calibration ───────────

    private fun buildSystemPrompt(): String = """
        You are FactShield, a calibrated, objective, and truthful fact-checking engine.
        Your goal is to detect true hoaxes and misinformation — NOT to debunk satire, sarcasm, irony, or the author's own words.

        CRITICAL CALIBRATION RULES:
        1. DETECT SATIRE AND IRONY FIRST:
           - Before evaluating factual accuracy, determine if the post is satirical, sarcastic, ironic, or humorous.
           - If the post is clearly written as satire, sarcasm, or a joke (even if the content is technically false), classify it as GREEN with a note like "Satirical post — not a factual claim."
           - If a verified public figure (e.g. @elonmusk, @potus) posted it themselves, it is their own statement. Do NOT call it fabricated just because it sounds unusual.
        2. DO NOT HALLUCINATE FALSEHOODS:
           - The live news headlines provided are only sample stories. Absence of a matching headline does NOT mean a claim is fake.
           - NEVER claim a technology, AI model, or product "does not exist" simply because it is new or not in the sample headlines.
        3. VERDICT CLASSIFICATION:
           - GREEN (Likely Reliable): Authentic posts, verified announcements, satire/irony, or credible statements.
           - YELLOW (Needs Verification): Breaking or unverified claims where context is unclear.
           - RED (Potential Fake): ONLY when there is concrete evidence of fabrication, manipulated media, or a demonstrably false claim. When in doubt, prefer YELLOW.
        4. SHORT VERDICT:
           - Max 15 words. Be factual and neutral. If satirical, say so briefly.
        5. SOURCES:
           - Only include sources if you have specific, real outlet names relevant to verifying this claim (e.g. ["Reuters", "TechCrunch"]).
           - If no specific sources are relevant, return an empty array: "sources": []
           - Do NOT invent generic sources like "Google Search" or "Fact Check Search".

        RESPONSE MUST BE VALID JSON ONLY:
        {"status":"GREEN"|"YELLOW"|"RED","confidence":<0-100>,"shortVerdict":"<max 15 words>","sources":["Outlet1","Outlet2"]}
    """.trimIndent()

    private fun buildUserText(tweetText: String, headlines: List<String>): String {
        val headlineBlock = if (headlines.isNotEmpty()) {
            "\n\nLIVE HEADLINE SAMPLES:\n" + headlines.joinToString("\n") { "• $it" }
        } else ""
        val textPrompt = if (tweetText.isNotBlank()) "\"$tweetText\"" else "(Evaluate screenshot media directly)"
        return """
            POST TO FACT-CHECK:
            $textPrompt
            $headlineBlock

            Analyze the post accurately. If the topic is new, technical, or unverified, use YELLOW.
            Respond ONLY with the JSON object.
        """.trimIndent()
    }

    // ── Bitmap Utilities ─────────────────────────────────────

    private fun Bitmap.toBase64Jpeg(): String {
        val maxWidth = 384
        val scaled = if (width > maxWidth) {
            val ratio = maxWidth.toFloat() / width
            Bitmap.createScaledBitmap(this, maxWidth, (height * ratio).toInt(), true)
        } else this

        val software = if (scaled.config == Bitmap.Config.HARDWARE) {
            scaled.copy(Bitmap.Config.ARGB_8888, false)
        } else scaled

        val baos = ByteArrayOutputStream()
        software.compress(Bitmap.CompressFormat.JPEG, 60, baos)
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }
}

