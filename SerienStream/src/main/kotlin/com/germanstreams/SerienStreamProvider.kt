package com.germanstreams

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class SerienStreamProvider : MainAPI() {
    // s.to redirects here; the site moved to this domain in mid-2026. If it moves again this
    // is the only line that needs to change.
    override var mainUrl = "https://serienstream.to"
    override var name = "SerienStream"
    override val hasMainPage = true
    override var lang = "de"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/beliebte-serien" to "Beliebte Serien",
    )

    // The site labels a source with a free-text language rather than a numeric key. Values
    // seen on the live site: "Deutsch" (dub), "Ger-Sub", "Englisch". Match tolerantly — the
    // abbreviated "Ger-" form in particular is easy to miss.
    private fun isGerman(label: String): Boolean {
        val l = label.lowercase()
        return "deutsch" in l || "german" in l || l.startsWith("ger")
    }

    private fun isSubbed(label: String): Boolean {
        val l = label.lowercase()
        return "untertitel" in l || "sub" in l
    }

    // Tie-breaker for sources of equal quality: German dub, German sub, then the rest.
    private fun langPriority(label: String): Int = when {
        isGerman(label) && !isSubbed(label) -> 0
        isGerman(label) -> 1
        isSubbed(label) -> 2
        else -> 3
    }

    private fun langLabel(label: String): String {
        if (label.isBlank()) return ""
        val l = label.lowercase()
        val flag = when {
            isGerman(label) -> "🇩🇪"
            "englisch" in l || "english" in l -> "🇬🇧"
            else -> return label
        }
        return "$flag $label"
    }

    // The pre-2026 markup used numeric keys; map them onto the same vocabulary so the
    // legacy fallback in loadLinks shares the labelling and ordering logic.
    private fun legacyLangLabel(key: String): String = when (key) {
        "1" -> "Deutsch"
        "2" -> "Englisch (Untertitel)"
        "3" -> "Deutsch (Untertitel)"
        else -> ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The catalog page is not paginated.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, referer = "$mainUrl/").document

        // The page groups shows into several titled sections.
        val sections = doc.select(".popular-page > div").mapNotNull { section ->
            val header = section.selectFirst("h2")?.text()?.ifBlank { null }
                ?: return@mapNotNull null
            val items = section.select("a.show-card, .card")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }
        if (sections.isNotEmpty()) return newHomePageResponse(sections, hasNext = false)

        // Fallback for a re-skinned catalog: treat the page as one flat grid.
        val flat = doc.select("a.show-card, .card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, flat, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/suche",
            params = mapOf("term" to query, "tab" to "shows"),
            referer = "$mainUrl/suche",
        ).document

        return doc.select(".results-group .card, .results-group a.show-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // A card is sometimes the <a> itself (a.show-card) and sometimes a wrapper around one.
        val rawHref = if (tagName() == "a") attr("href") else selectFirst("a[href]")?.attr("href")
        val href = fixUrlNull(rawHref?.ifBlank { null }) ?: return null

        val img = selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { null }
            ?: selectFirst("h2, h3, .card-title, .show-title")?.text()?.ifBlank { null }
            ?: attr("title").ifBlank { null }
            ?: return null

        val poster = fixUrlNull(img?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document
        // Prefer the metadata container, but fall back to the whole document rather than
        // throwing — a markup tweak should degrade the detail page, not break the provider.
        val meta = doc.selectFirst(".show-header-wrapper .container-fluid > div") ?: doc

        val title = meta.selectFirst("h1")?.text()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.ifBlank { null }
            ?: return null
        val poster = fixUrlNull(
            meta.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )
        val year = meta.selectFirst("h1 + p > a")?.text()?.trim()?.take(4)?.toIntOrNull()
        val plot = meta.select(".description-text").text().ifBlank { null }
        val actors = meta.select("li.series-group:contains(Besetzung:) a").map { it.text() }
        val genres = meta.select("li.series-group:contains(Genre:) a").map { it.text() }
        val trailer = meta.selectFirst("button[data-trailer-url]")?.attr("data-trailer-url")
            ?.ifBlank { null }

        val seasonLinks = doc.select("#season-nav ul > li a[href]")
        val parsed = if (seasonLinks.isEmpty()) {
            // Single-season shows may render the episode table straight onto the detail page.
            doc.parseEpisodes(seasonNum = 1)
        } else {
            seasonLinks.amap { a ->
                val seasonUrl = fixUrl(a.attr("href"))
                val seasonNum = a.text().trim().toIntOrNull()
                    ?: Regex("/staffel-(\\d+)").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0 // "Filme" / specials
                val sdoc = if (seasonUrl == url) doc else app.get(seasonUrl, referer = url).document
                sdoc.parseEpisodes(seasonNum)
            }.flatten()
        }
        // Two stable passes: episode within season, then season.
        val episodes = parsed.sortedBy { it.episode ?: 0 }.sortedBy { it.season ?: 0 }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun org.jsoup.nodes.Document.parseEpisodes(seasonNum: Int) =
        select(".episode-section .episode-row").mapNotNull { row ->
            // Rows navigate via an onclick handler; accept a plain href too in case the
            // markup ever gains one.
            val href = Regex("['\"](/[^'\"]+)['\"]").find(row.attr("onclick"))?.groupValues?.get(1)
                ?: row.selectFirst("a[href]")?.attr("href")?.ifBlank { null }
                ?: return@mapNotNull null
            val epNum = row.selectFirst(".episode-number-cell")?.text()?.trim()?.toIntOrNull()
                ?: Regex("/episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val epName = row.select(".episode-title-cell > *")
                .joinToString(" - ") { it.text() }
                .ifBlank { null }
                ?: row.selectFirst(".episode-title-cell")?.text()?.ifBlank { null }

            newEpisode(fixUrl(href)) {
                this.name = epName
                this.season = seasonNum
                this.episode = epNum
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = app.get(pageUrl, referer = "$mainUrl/").document

        // Collect first, emit sorted at the end: loadExtractor's callback is not suspend, so
        // links have to be rebuilt outside of it.
        val sources = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, ExtractorLink>>())

        val buttons = doc.select("button[data-play-url]").distinctBy { it.attr("data-play-url") }
        if (buttons.isNotEmpty()) {
            buttons.amap { btn ->
                val play = btn.attr("data-play-url").ifBlank { return@amap }
                val label = btn.attr("data-language-label").trim()
                collectSources(fixUrl(play), label, subtitleCallback, sources)
            }
        } else {
            // Fallback to the pre-2026 markup (hoster <li> + /redirect/{id}), which the
            // sibling sites in this family still serve.
            doc.select("li[data-link-target]").amap { li ->
                val target = li.attr("data-link-target").ifBlank { return@amap }
                collectSources(
                    fixUrl(target),
                    legacyLangLabel(li.attr("data-lang-key")),
                    subtitleCallback,
                    sources,
                )
            }
        }

        sources
            .sortedWith(compareByDescending<Pair<Int, ExtractorLink>> { it.second.quality }
                .thenBy { it.first })
            .forEach { callback(it.second) }
        return sources.isNotEmpty()
    }

    private suspend fun collectSources(
        redirectUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sink: MutableList<Pair<Int, ExtractorLink>>,
    ) {
        // The play endpoint answers with a 30x to the real hoster embed. Read the Location
        // header rather than following the chain: a full GET resolves to whatever the hoster
        // serves a refererless request, which is often an anti-bot stub with no video in it.
        val target = runCatching {
            val head = app.get(redirectUrl, referer = "$mainUrl/", allowRedirects = false)
            head.headers["location"]?.ifBlank { null }
                ?: app.get(redirectUrl, referer = "$mainUrl/").url.takeIf { it != redirectUrl }
        }.getOrNull()?.ifBlank { null } ?: return

        val collected = mutableListOf<ExtractorLink>()
        // Hosters validate the referer against the site root, not the episode URL.
        runCatching { loadExtractor(target, "$mainUrl/", subtitleCallback) { collected.add(it) } }

        val weight = langPriority(label)
        val prefix = langLabel(label)
        collected.forEach { link ->
            val named = if (prefix.isBlank()) link else newExtractorLink(
                link.source, "$prefix · ${link.name}", link.url, link.type
            ) {
                this.referer = link.referer
                this.quality = link.quality
                this.headers = link.headers
                this.extractorData = link.extractorData
            }
            sink.add(weight to named)
        }
    }
}
