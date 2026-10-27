package com.jarvis.agent.tools.impl

import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.core.await
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URLDecoder

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

class WebSearchTool : BaseTool(
    "web_search", "Search the web. Returns titles, links and snippets.", RiskLevel.LOW, readOnly = true,
    {
        string("query", "Search query")
        integer("max_results", "Default 5, max 10", required = false, min = 1, max = 10)
    },
) {
    override fun describe(input: JsonObject) = "Search the web: ${input.str("query")}"

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", input.str("query").orEmpty()).build()
        val html = ctx.graph.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).await().use {
            if (!it.isSuccessful) return ToolResult.error("Search failed: HTTP ${it.code}")
            withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
        }
        val doc = Jsoup.parse(html)
        val max = (input.int("max_results") ?: 5).coerceIn(1, 10)
        val results = doc.select(".result").take(max).mapNotNull { r ->
            val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
            val href = cleanDdgLink(a.attr("href"))
            val snippet = r.selectFirst(".result__snippet")?.text().orEmpty()
            "- ${a.text()}\n  $href\n  $snippet"
        }
        return ToolResult.untrusted("web_search", results.joinToString("\n").ifEmpty { "No results." })
    }

    private fun cleanDdgLink(href: String): String {
        val idx = href.indexOf("uddg=")
        if (idx < 0) return href
        val enc = href.substring(idx + 5).substringBefore('&')
        return runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
    }
}

class ReadWebPageTool : BaseTool(
    "read_web_page", "Fetch a web page and return its readable text.", RiskLevel.LOW, readOnly = true,
    {
        string("url", "https:// URL")
        integer("max_chars", "Default 6000, max 12000", required = false, min = 500, max = 12_000)
    },
) {
    override fun describe(input: JsonObject) = "Read ${input.str("url")}"

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val url = input.str("url").orEmpty().toHttpUrlOrNull()
            ?: return ToolResult.error("Only http(s) URLs are supported")
        if (withContext(Dispatchers.IO) { isPrivateHost(url.host) }) return ToolResult.error("BLOCKED: local network addresses are not allowed")
        val html = ctx.graph.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).await().use {
            if (!it.isSuccessful) return ToolResult.error("HTTP ${it.code}")
            val type = it.header("content-type").orEmpty()
            if (!type.contains("html") && !type.contains("text")) return ToolResult.error("Not a text page ($type)")
            withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
        }
        val doc = Jsoup.parse(html, url.toString())
        doc.select("script, style, nav, footer, header, noscript, svg, form").remove()
        val title = doc.title()
        val text = doc.body()?.text().orEmpty()
        val max = (input.int("max_chars") ?: 6000).coerceIn(500, 12_000)
        return ToolResult.untrusted("web_page $url", "Title: $title\n${text.take(max)}")
    }

    private fun isPrivateHost(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
    }.getOrDefault(true)
}
