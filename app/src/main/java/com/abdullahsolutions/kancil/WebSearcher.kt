package com.abdullahsolutions.kancil

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object WebSearcher {

    private const val TAG = "WebSearcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Search DuckDuckGo for [query] and return a formatted context string
     * to inject into the model prompt, or null if the search failed.
     */
    suspend fun search(query: String, maxResults: Int = 4): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://html.duckduckgo.com/html/?q=${encode(query)}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "DDG HTTP ${response.code}")
                    return@withContext null
                }

                val html = response.body?.string() ?: return@withContext null
                parseResults(html, maxResults)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                null
            }
        }

    private val titleRegex   = Regex("""class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    private fun parseResults(html: String, maxResults: Int): String? {
        val titles   = titleRegex.findAll(html).map   { stripTags(it.groupValues[1]) }.toList()
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        if (titles.isEmpty()) return null

        val sb    = StringBuilder()
        val count = minOf(maxResults, titles.size, snippets.size)
        for (i in 0 until count) {
            val title   = titles[i].trim()
            val snippet = snippets[i].trim()
            if (snippet.isBlank()) continue
            sb.append("[${i + 1}] $title\n$snippet\n\n")
        }

        return sb.toString().trimEnd().ifBlank { null }
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&#39;",  "'")
            .replace("&nbsp;", " ")

    private fun encode(query: String): String =
        java.net.URLEncoder.encode(query, "UTF-8")
}
