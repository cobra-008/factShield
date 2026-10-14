package com.factshield.app

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────
// GROQ API Key is loaded from local.properties or environment variable via BuildConfig.
val GROQ_API_KEY: String = BuildConfig.GROQ_API_KEY

private const val TAG = "FactCheckRepo"
private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

// Models confirmed available on this Groq account
private const val PRIMARY_MODEL   = "openai/gpt-oss-120b"
private const val FAST_TEXT_MODEL = "openai/gpt-oss-20b"

// ─────────────────────────────────────────────────────────────
//  Domain models
// ─────────────────────────────────────────────────────────────

enum class FactStatus { GREEN, YELLOW, RED }

data class FactSource(
    val name: String,
    val url: String
)

data class FactCheckResult(
    val status: FactStatus,
    val confidence: Int,
    val shortVerdict: String,
    val sources: List<FactSource> = emptyList()
)

data class RssArticle(
    val title: String,
    val source: String,
    val link: String
)

/** Typed exception for non-2xx Groq HTTP responses — carries the status code. */
class GroqHttpException(val code: Int, message: String) : RuntimeException(message)

// ─────────────────────────────────────────────────────────────
//  Repository
// ─────────────────────────────────────────────────────────────

object FactCheckRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** RSS client — slightly longer timeout to handle slow connections. */
    private val rssClient = OkHttpClient.Builder()
        .callTimeout(4000, TimeUnit.MILLISECONDS)
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .build()

    // No persistent cache — each scroll lands on a new post; caching caused
    // stale results from the previous post to appear on the current one.

    // ── Public entry point ───────────────────────────────────

    suspend fun analyze(
        tweetText: String,
        screenshot: Bitmap?
    ): FactCheckResult = withContext(Dispatchers.IO) {


        // ── Step 1: RSS headlines (inline, best-effort, never cancels analysis) ─
        val rssArticles: List<RssArticle> = try {
            fetchLiveNews(tweetText)
        } catch (e: CancellationException) {
            throw e  // always rethrow cancellation
        } catch (e: Exception) {
            Log.d(TAG, "RSS skipped (${e.javaClass.simpleName})")
            emptyList()
        }

        val liveHeadlines = rssArticles.map { "[${it.source}] ${it.title}" }
        Log.d(TAG, "articles=${rssArticles.size} keyLen=${GROQ_API_KEY.length}")

        // ── Step 2: Groq call ──────────────────────────────────────
        // Always text model — vision model requires preview access and returns 404.
        val result = try {
            callGroqFastText(tweetText, liveHeadlines, rssArticles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GroqHttpException) {
            // If primary model unavailable (404/403), fall back to smaller model
            if (e.code == 404 || e.code == 403) {
                Log.w(TAG, "Primary model unavailable (${e.code}), falling back to $FAST_TEXT_MODEL")
                try {
                    callGroqFastText(tweetText, liveHeadlines, rssArticles, FAST_TEXT_MODEL)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: GroqHttpException) {
                    val msg = when (e2.code) {
                        401, 403 -> "API key invalid. Replace GROQ_API_KEY in local.properties."
                        429      -> "Rate limit reached. Wait and tap to retry."
                        else     -> "Groq HTTP ${e2.code}: ${e2.message?.take(60)}"
                    }
                    return@withContext FactCheckResult(FactStatus.YELLOW, 0, msg, emptyList())
                } catch (e2: IOException) {
                    val detail = "${e2.javaClass.simpleName}: ${e2.message?.take(80)}"
                    Log.e(TAG, "Fallback IO error: $detail", e2)
                    return@withContext FactCheckResult(FactStatus.YELLOW, 0, "Network error — $detail", emptyList())
                } catch (e2: Exception) {
                    val detail = "${e2.javaClass.simpleName}: ${e2.message?.take(80)}"
                    Log.e(TAG, "Fallback failed: $detail", e2)
                    return@withContext FactCheckResult(FactStatus.YELLOW, 0, "Error — $detail", emptyList())
                }
            } else {
                val msg = when (e.code) {
                    401, 403 -> "API key invalid. Replace GROQ_API_KEY in local.properties."
                    429      -> "Rate limit reached. Wait and tap to retry."
                    400      -> "Bad Groq request (400): ${e.message?.take(60)}"
                    500, 503 -> "Groq server error (${e.code}). Try again."
                    else     -> "Groq HTTP ${e.code}: ${e.message?.take(60)}"
                }
                Log.e(TAG, "GroqHttpException ${e.code}: ${e.message}")
                return@withContext FactCheckResult(FactStatus.YELLOW, 0, msg, emptyList())
            }
        } catch (e: IOException) {
            val detail = "${e.javaClass.simpleName}: ${e.message?.take(80)}"
            Log.e(TAG, "IOException during Groq call: $detail", e)
            return@withContext FactCheckResult(
                FactStatus.YELLOW, 0,
                "Network error — $detail",
                emptyList()
            )
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message?.take(80)}"
            Log.e(TAG, "Unexpected error during Groq call: $detail", e)
            return@withContext FactCheckResult(
                FactStatus.YELLOW, 0,
                "Error — $detail",
                emptyList()
            )
        }

        result
    }

    // ── Step A: Google News RSS ──────────────────────────────

    private fun fetchLiveNews(tweetText: String): List<RssArticle> {
        if (tweetText.isBlank()) return emptyList()
        return try {
            // Use the raw tweet text (cleaned) as the query — much better than extracted keywords
            // because it preserves names like "Jamahal Hill", company names, etc.
            val rawQuery = tweetText
                .replace(Regex("[^\\w\\s]"), " ")  // strip punctuation/emoji
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)  // Google News RSS handles up to ~150 chars
            if (rawQuery.isBlank()) return emptyList()

            val query = URLEncoder.encode(rawQuery, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$query&hl=en&gl=US&ceid=US:en"

            Log.d(TAG, "RSS query: $rawQuery")
            val request = Request.Builder().url(url).get().build()
            val body = rssClient.newCall(request).execute().use { it.body?.string() ?: "" }

            val items = mutableListOf<RssArticle>()
            val itemPattern = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titlePattern = Regex("<title>(.*?)</title>")
            val linkPattern = Regex("<link>(.*?)</link>")
            val sourcePattern = Regex("<source[^>]*>(.*?)</source>")

            for (match in itemPattern.findAll(body).take(6)) {
                val block = match.groupValues[1]
                val title = titlePattern.find(block)?.groupValues?.get(1)
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: continue
                if (title.length < 10) continue  // skip junk

                val rawLink = linkPattern.find(block)?.groupValues?.get(1)
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.replace("&amp;", "&")?.trim() ?: ""

                val rawSource = sourcePattern.find(block)?.groupValues?.get(1)
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""

                val sourceName = if (rawSource.isNotBlank()) {
                    rawSource
                } else {
                    val dashIdx = title.lastIndexOf(" - ")
                    if (dashIdx != -1 && dashIdx < title.length - 3) {
                        title.substring(dashIdx + 3).trim()
                    } else "Google News"
                }

                val link = if (rawLink.isNotBlank()) rawLink else "https://news.google.com/search?q=$query"

                items += RssArticle(title = title, source = sourceName, link = link)
            }
            Log.d(TAG, "RSS returned ${items.size} articles")
            items
        } catch (e: Exception) {
            Log.w(TAG, "RSS fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    // ── Groq API Calls ───────────────────────────────────────

    private fun callGroqFastText(
        tweetText: String,
        headlines: List<String>,
        rssArticles: List<RssArticle>,
        model: String = PRIMARY_MODEL
    ): FactCheckResult {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 500)
            // Note: response_format json_object is NOT supported by all Groq models.
            // The system prompt instructs the model to output JSON — we parse it manually.
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
        return executeGroqRequest(payload, rssArticles, tweetText)
    }

    /**
     * Executes a Groq API request.
     * Throws [GroqHttpException] for non-2xx HTTP responses.
     * Throws [IOException] for network errors.
     * Throws [RuntimeException] for empty/unparseable responses.
     */
    private fun executeGroqRequest(
        payload: JSONObject,
        rssArticles: List<RssArticle>,
        tweetText: String
    ): FactCheckResult {
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
                Log.e(TAG, "Groq HTTP ${response.code}: $bodyString")
                // Extract Groq's error message if available
                val groqMessage = try {
                    JSONObject(bodyString).optJSONObject("error")?.optString("message") ?: bodyString
                } catch (_: Exception) { bodyString }
                throw GroqHttpException(response.code, groqMessage)
            }
            if (bodyString.isBlank()) throw RuntimeException("Empty Groq response")
            bodyString
        }

        return parseGroqResponse(responseBody, rssArticles, tweetText)
    }

    // ── Response Parsing ─────────────────────────────────────

    private fun parseGroqResponse(
        rawResponse: String,
        rssArticles: List<RssArticle>,
        tweetText: String
    ): FactCheckResult {
        try {
            val root = JSONObject(rawResponse)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = content.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val obj = JSONObject(cleaned)
            val statusStr = obj.optString("status", "GREEN").uppercase()
            val status = try { FactStatus.valueOf(statusStr) } catch (_: Exception) { FactStatus.GREEN }
            val confidence = obj.optInt("confidence", 70).coerceIn(0, 100)
            var verdict = obj.optString("shortVerdict", "Content appears credible.").take(120).trim()

            // ── Sanity check: catch evasive/useless verdicts the model sometimes outputs ──
            val evasivePhrases = listOf(
                "no factual claim", "no claim", "cannot verify", "cannot assess",
                "insufficient information", "insufficient info", "not a factual",
                "no verifiable", "unable to verify", "no fact", "not verifiable"
            )
            if (evasivePhrases.any { verdict.lowercase().contains(it) }) {
                Log.w(TAG, "Evasive verdict intercepted: '$verdict' — replacing with useful default")
                verdict = when (status) {
                    FactStatus.GREEN  -> "Claim appears credible based on available information."
                    FactStatus.YELLOW -> "Specific claim could not be independently confirmed."
                    FactStatus.RED    -> "Claim appears to be false or misleading."
                }
            }

            // ── Parse sources from model output ──
            val sourcesArray = obj.optJSONArray("sources")
            val rawSources = mutableListOf<String>()
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    val s = sourcesArray.optString(i, "").trim()
                    if (s.isNotBlank()) rawSources += s
                }
            }

            val finalSources = mutableListOf<FactSource>()
            for (raw in rawSources) {
                if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                    val domain = try {
                        val host = java.net.URI(raw).host
                        host?.removePrefix("www.") ?: "Source"
                    } catch (_: Exception) { "Link" }
                    finalSources += FactSource(name = domain.take(20), url = raw)
                } else {
                    val matching = rssArticles.firstOrNull {
                        it.source.contains(raw, ignoreCase = true) || raw.contains(it.source, ignoreCase = true)
                    }
                    if (matching != null && matching.link.isNotBlank()) {
                        finalSources += FactSource(name = raw.take(20), url = matching.link)
                    } else {
                        val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(raw, "UTF-8")}"
                        finalSources += FactSource(name = raw.take(20), url = searchUrl)
                    }
                }
            }

            // Fallback 1: If model gave no sources, use top RSS articles if available
            if (finalSources.isEmpty() && rssArticles.isNotEmpty()) {
                for (article in rssArticles.take(2)) {
                    finalSources += FactSource(
                        name = article.source.ifBlank { "News" }.take(20),
                        url = article.link
                    )
                }
            }

            // Fallback 2: If still empty and tweet has text, provide Google Search link
            if (finalSources.isEmpty() && tweetText.isNotBlank()) {
                val query = tweetText
                    .replace(Regex("[^\\w\\s]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(80)
                if (query.isNotBlank()) {
                    val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                    finalSources += FactSource(name = "Google Search", url = searchUrl)
                }
            }

            val resultSources = finalSources.distinctBy { it.url }.take(2)

            // ── Contradiction guard: if verdict says the claim is false/denied but
            //    the model still output GREEN, downgrade to RED automatically.
            val contradictionWords = listOf(
                "denied", "misattributed", "false", "fabricated", "debunked",
                "wrong", "inaccurate", "not true", "no evidence", "hoax",
                "misleading", "disproven", "contradicted"
            )
            if (status == FactStatus.GREEN &&
                contradictionWords.any { verdict.lowercase().contains(it) }) {
                Log.w(TAG, "Contradiction: verdict='$verdict' but status=GREEN — upgrading to RED")
                return FactCheckResult(FactStatus.RED, confidence, verdict, resultSources)
            }

            return FactCheckResult(status, confidence, verdict, resultSources)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message} | raw: ${rawResponse.take(300)}")
            throw RuntimeException("Failed to parse Groq response: ${e.message}")
        }
    }

    // ── Prompts ──────────────────────────────────────────────

    private fun buildSystemPrompt(): String =
        """You are a strict social media fact-checker. Your job is to catch misinformation. Be CRITICAL and SKEPTICAL.

ASSIGN RED when the post contains any of these (you know these from your training):
- Conspiracy theories: flat earth, moon landing denial, "vaccines cause autism", "5G causes cancer", "microchips in vaccines", chemtrails, lizard people, etc.
- Fabricated or wildly inaccurate statistics (e.g. "90% of scientists agree X" when they don't)
- Known hoaxes or debunked claims
- Quotes falsely attributed to real people
- Historical revisionism (denying the Holocaust, etc.)
- Health misinformation (fake cures, fake disease claims)
- Election fraud claims that were officially debunked
- Anything contradicted by the provided live news headlines

ASSIGN YELLOW when:
- The claim is very specific and you genuinely cannot confirm or deny it (e.g. "Company X lost $5B today")
- Breaking news too recent for your training AND no headlines confirm it
- Anonymous sources making serious allegations

ASSIGN GREEN when:
- Live news headlines confirm the claim → mention the outlet
- Well-documented fact from science, history, or public record
- Genuine personal opinion clearly stated as such
- Public figure statement consistent with their documented positions

shortVerdict rules (CRITICAL):
- Max 10 words. Say exactly WHAT is true or false.
- For RED: state what is wrong. E.g. "Vaccines do not contain microchips — thoroughly debunked." / "No credible evidence supports this claim."
- For GREEN with headlines: "Confirmed by [outlet name] reports."
- For GREEN from knowledge: state the fact. E.g. "Sam Altman testified about AI risks in 2023."
- FORBIDDEN phrases: "no factual claim", "cannot verify", "opinion content", "entertainment", "no misinformation", "insufficient information"

sources rules:
- Name 1 or 2 specific news outlets or official entities relevant to the verdict (e.g. ["The Hill", "Reuters"] or ["CDC"]). If none, use empty array [].

OUTPUT — JSON only, nothing else:
{"status":"GREEN","confidence":85,"shortVerdict":"specific verdict max 10 words","sources":["Source Name"]}"""

    private fun buildUserText(tweetText: String, headlines: List<String>): String {
        val claim = tweetText.trim().ifBlank { "(social media post — text not readable)" }
        return if (headlines.isNotEmpty()) {
            val headlineBlock = headlines.joinToString("\n") { "  • $it" }
            """SOCIAL MEDIA POST: "$claim"

LIVE NEWS HEADLINES (verified, from Google News right now — treat as ground truth):
$headlineBlock

Do the headlines confirm, contradict, or relate to the post? Output your JSON verdict:"""
        } else {
            """SOCIAL MEDIA POST: "$claim"

(No live news headlines available — use your knowledge.)
Output your JSON verdict:"""
        }
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
