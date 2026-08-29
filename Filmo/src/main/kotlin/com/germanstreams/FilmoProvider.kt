package com.germanstreams

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class FilmoProvider : MainAPI() {
    override var mainUrl = "https://filmo.to"
    override var name = "Filmo ★"
    override val hasMainPage = true
    override var lang = "de"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    // The site is picky about the client; a desktop UA keeps the markup consistent.
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:152.0) Gecko/20100101 Firefox/152.0"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/popular" to "Beliebt bei Filmo",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The catalog page is not paginated.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = headers).document

        val sections = doc.select("section.popular-spotlight, div.video-row").mapNotNull { section ->
            val header = section.selectFirst("h3")?.ownText()?.ifBlank { null }
                ?: section.selectFirst("h3")?.text()?.ifBlank { null }
                ?: return@mapNotNull null
            val items = section.select(".popular-spotlight-card__link, a.video-card")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }
        if (sections.isNotEmpty()) return newHomePageResponse(sections, hasNext = false)

        val flat = doc.select(".popular-spotlight-card__link, a.video-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, flat, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search", params = mapOf("q" to query), headers = headers).document
        return doc.select("section.search-top-results article > a, a.movie-poster-grid-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = if (tagName() == "a") attr("href") else selectFirst("a[href]")?.attr("href")
        val href = fixUrlNull(rawHref?.ifBlank { null }) ?: return null

        // Card layouts differ: spotlight cards carry the title in an <h4>, grid cards in the
        // image alt text, and search hits in a "__title" element.
        val img = selectFirst("img")
        val title = selectFirst("[class*=__title]")?.text()?.ifBlank { null }
            ?: selectFirst("h4")?.text()?.ifBlank { null }
            ?: img?.attr("alt")?.ifBlank { null }
            ?: return null

        val poster = fixUrlNull(
            selectFirst(".ft-packshot img, img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        )
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst(".primary-container h1")?.text()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.ifBlank { null }
            ?: return null

        // Metadata is a list of <dl> definition pairs keyed by a German label.
        val details = doc.select("div.details-group dl").mapNotNull { dl ->
            val key = dl.selectFirst("dt")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val value = dl.selectFirst("dd")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            key to value
        }.toMap()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.plot = doc.selectFirst("p.movie-detail-synopsis")?.text()?.ifBlank { null }
            this.posterUrl = fixUrlNull(doc.selectFirst("img.ft-packshot-meta")?.attr("src"))
            this.year = details["Erscheinungsdatum"]?.take(4)?.toIntOrNull()
            this.duration = details["Laufzeit"]?.takeWhile { it.isDigit() }?.toIntOrNull()
            this.tags = details["Genres"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            addActors(details["Darsteller"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // The movie page hands out the CSRF cookie that /n requires, so it has to be fetched
        // first and its cookies carried through every later call.
        val page = app.get(data, headers = headers)
        val cookies = page.cookies
        // Laravel URL-encodes the cookie; only the base64 padding is affected in practice.
        val xsrf = cookies["XSRF-TOKEN"]?.replace("%3D", "=")

        val chips = page.document.select(".provider-chip[data-p]")
        if (chips.isEmpty()) return false

        val sources = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())

        chips.amap { chip ->
            val payload = chip.attr("data-p").ifBlank { return@amap }
            // The chip text reads like "VOE WEB-DL 720p" — host, release and quality in one,
            // which is more informative than the extractor's own name.
            val label = chip.text().replace(Regex("\\s+"), " ").trim()

            val target = resolveTarget(payload, xsrf, cookies, data) ?: return@amap

            val collected = mutableListOf<ExtractorLink>()
            runCatching { loadExtractor(target, "$mainUrl/", subtitleCallback) { collected.add(it) } }
            collected.forEach { link ->
                // The chip label states the quality ("VOE WEB-DL 720p") even when the
                // extractor leaves it unset, so fall back to it before the link's own name.
                val quality = when {
                    link.quality > 0 -> link.quality
                    getQualityFromName(label) > 0 -> getQualityFromName(label)
                    else -> getQualityFromName(link.name)
                }
                val named = newExtractorLink(
                    link.source,
                    label.ifBlank { link.name },
                    link.url,
                    link.type,
                ) {
                    this.referer = link.referer
                    this.quality = quality
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
                sources.add(named)
            }
        }

        sources.sortedByDescending { it.quality }.forEach { callback(it) }
        return sources.isNotEmpty()
    }

    /**
     * Turns a provider chip's payload into the hoster URL.
     *
     * POST /n exchanges the payload for a one-shot slug, and GET /n/{slug} resolves it. The
     * slug is consumed by that single request — a second call answers 404 — so this must not
     * retry the same slug. Redirects are therefore left unfollowed and both outcomes handled
     * from one response: VOE-style hosts answer 30x with a Location, Byse-style ones answer
     * 200 with an interstitial page linking out to the host.
     */
    private suspend fun resolveTarget(
        payload: String,
        xsrf: String?,
        cookies: Map<String, String>,
        referer: String,
    ): String? = runCatching {
        val postHeaders = headers + mapOf(
            "Referer" to referer,
            "Content-Type" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
        ) + (xsrf?.let { mapOf("X-XSRF-TOKEN" to it) } ?: emptyMap())

        val slugRes = app.post(
            "$mainUrl/n",
            json = mapOf("p" to payload),
            cookies = cookies,
            headers = postHeaders,
        ).text
        val slug = tryParseJson<SlugResponse>(slugRes)?.x?.ifBlank { null } ?: return@runCatching null

        val res = app.get(
            "$mainUrl/n/$slug",
            cookies = cookies,
            headers = headers + mapOf("Referer" to referer),
            allowRedirects = false,
        )

        res.headers["location"]?.ifBlank { null }
            // No redirect: pull the outbound link out of the interstitial page.
            ?: res.document.select("a[href]")
                .map { it.attr("href") }
                .firstOrNull { it.startsWith("http") && !it.contains("filmo.to") }
    }.getOrNull()

    private data class SlugResponse(val x: String? = null)
}
