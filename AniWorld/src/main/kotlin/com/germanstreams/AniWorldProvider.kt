package com.germanstreams

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class AniWorldProvider : MainAPI() {
    override var mainUrl = "https://aniworld.to"
    override var name = "AniWorld"
    override val hasMainPage = true
    override var lang = "de"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // lang-key on the hoster <li>: 1 = German Dub, 2 = English Sub, 3 = German Sub
    private fun langLabel(key: String): String = when (key) {
        "1" -> "🇩🇪 Dub"
        "2" -> "🇬🇧 Sub"
        "3" -> "🇩🇪 Sub"
        else -> ""
    }

    // Source ordering tie-breaker: German Dub first, then German Sub, then the rest.
    // (Quality stays the primary sort key, this only orders sources of equal quality.)
    private fun langPriority(key: String): Int = when (key) {
        "1" -> 0 // German Dub
        "3" -> 1 // German Sub
        "2" -> 2 // English Sub
        else -> 3
    }

    override val mainPage = mainPageOf(
        "$mainUrl/neue-episoden" to "Neue Folgen (Deutsch Dub)",
        "$mainUrl/beliebte-animes" to "Beliebt bei AniWorld",
        "$mainUrl/neu" to "Neue Animes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // These catalog pages are not paginated, only return content on the first page.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data).document

        // Special case: /neue-episoden lists newly added episodes with a language flag
        // per row. Keep only rows that offer a German dub (/public/img/german.svg, not the
        // German-subtitle flag japanese-german.svg) and link to the series.
        if (request.data.endsWith("/neue-episoden")) {
            val items = doc.select("div.newEpisodeList div.row").mapNotNull { row ->
                // German dub flag is /public/img/german.svg. Must use endsWith("/german.svg")
                // so we don't match the German-subtitle flag (japanese-german.svg).
                val hasGermanDub = row.select("img.flag").any { img ->
                    img.attr("data-src").endsWith("/german.svg") ||
                        img.attr("src").endsWith("/german.svg")
                }
                if (!hasGermanDub) return@mapNotNull null
                val a = row.selectFirst("a[href*=/episode-]") ?: return@mapNotNull null
                val seriesUrl = fixUrl(a.attr("href").replace(Regex("/staffel-.*$"), ""))
                val title = row.selectFirst("strong")?.text()?.ifBlank { null } ?: return@mapNotNull null
                newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                    addDubStatus(dubExist = true, subExist = false)
                }
            }.distinctBy { it.url }
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        // Cards are wrapped in .coverListItem (homepage carousels) or a grid column
        // div (.col-md-15 on /beliebte-animes and /neu).
        val items = doc.select("div.coverListItem, div.col-md-15")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("a[href*=/anime/stream/]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = selectFirst("h3")?.text()?.ifBlank { null }
            ?: a.attr("title").substringBefore(" stream").trim().ifBlank { a.text().trim() }
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$mainUrl/ajax/seriesSearch",
            params = mapOf("keyword" to query)
        ).text
        // Response is a JSON array: [{ "name", "link", "cover", "productionYear", "description" }]
        val items = tryParseJson<List<SearchItem>>(res) ?: emptyList()
        return items.mapNotNull { item ->
            val link = item.link ?: return@mapNotNull null
            val name = item.name?.unescapeHtml() ?: return@mapNotNull null
            newAnimeSearchResponse(name, "$mainUrl/anime/stream/$link", TvType.Anime) {
                this.posterUrl = item.cover?.let { fixUrlNull(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.series-title h1 span")?.text()
            ?: doc.selectFirst("h1[itemprop=name]")?.text()
            ?: doc.selectFirst("h1")?.text().orEmpty()
        val poster = doc.selectFirst("div.seriesCoverBox img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.let { fixUrlNull(it) }
        val plot = doc.selectFirst(".seri_des")?.attr("data-full-description")?.ifBlank { null }
            ?: doc.selectFirst(".seri_des")?.text()
            ?: doc.selectFirst("[itemprop=description]")?.text()
        val tags = doc.select("div.genres a[itemprop=genre], .genres li a").map { it.text() }
        val year = doc.selectFirst("span[itemprop=startDate] a, .series-title small")
            ?.text()?.take(4)?.toIntOrNull()

        // Collect season URLs (incl. /filme) from the season navigation. Match by URL
        // pattern so we don't depend on the exact nav container markup.
        val seasonUrls = doc.select("a[href*=/anime/stream/]")
            .map { it.attr("href") }
            .filter { (it.contains("/staffel-") || it.endsWith("/filme")) && !it.contains("/episode-") }
            .map { fixUrl(it) }
            .distinct()
            .ifEmpty { listOf(url) } // fall back to the detail page itself (single-season)

        val raw = seasonUrls.amap { seasonUrl ->
            val sdoc = if (seasonUrl == url) doc else app.get(seasonUrl).document
            val seasonNum = Regex("/staffel-(\\d+)").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (seasonUrl.endsWith("/filme")) 0 else 1
            sdoc.select("table.seasonEpisodesList tbody tr").mapNotNull { row ->
                val a = row.selectFirst("td.seasonEpisodeTitle a") ?: return@mapNotNull null
                val epUrl = fixUrl(a.attr("href"))
                val epNum = Regex("/episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                val epName = a.selectFirst("strong")?.text()?.ifBlank { null }
                    ?: a.selectFirst("span")?.text()
                // Per-episode language flags decide dub/sub availability.
                val flags = row.select("img.flag").map {
                    (it.attr("data-src").ifBlank { it.attr("src") }).substringAfterLast("/").removeSuffix(".svg")
                }
                val hasDub = flags.any { it == "german" }
                // japanese-german = German subtitle, japanese-english = English subtitle.
                val hasSub = flags.any { it == "japanese-german" || it == "japanese-english" }
                RawEp(epUrl, epName, seasonNum, epNum, hasDub, hasSub || (!hasDub && !hasSub))
            }
        }.flatten()

        val dubEpisodes = raw.filter { it.dub }.map {
            newEpisode(EpisodeData(it.url, dub = true).toJson()) {
                this.name = it.name; this.season = it.season; this.episode = it.episode
            }
        }
        val subEpisodes = raw.filter { it.sub }.map {
            newEpisode(EpisodeData(it.url, dub = false).toJson()) {
                this.name = it.name; this.season = it.season; this.episode = it.episode
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data is JSON {url, dub}. Older data may be a bare URL (load all languages).
        val epData = tryParseJson<EpisodeData>(data)
        val pageUrl = epData?.url ?: data
        val allowed = when {
            epData == null -> setOf("1", "2", "3")
            epData.dub -> setOf("1")              // German Dub only
            else -> setOf("2", "3")               // German Sub + English Sub
        }

        val doc = app.get(pageUrl).document
        val hosters = doc
            .select("div.hosterSiteVideo ul li[data-link-target], li.col-md-3.col-xs-12[data-link-target]")
            .filter { it.attr("data-lang-key") in allowed }
        if (hosters.isEmpty()) return false

        // Collect every resolved source together with its language priority, then emit
        // sorted by quality (desc) and language (Ger Sub before Eng Sub within the Sub track).
        val sources = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, ExtractorLink>>())
        hosters.amap { li ->
            val redirect = li.attr("data-link-target").ifBlank {
                li.selectFirst("a.watchEpisode")?.attr("href").orEmpty()
            }
            if (redirect.isBlank()) return@amap
            val key = li.attr("data-lang-key")
            val lang = langLabel(key)
            val weight = langPriority(key)
            // /redirect/{id} responds with a 30x to the real hoster embed (voe.sx, dood, ...).
            val real = app.get(fixUrl(redirect), allowRedirects = false)
                .headers["location"] ?: return@amap
            // loadExtractor's callback is not suspend, so collect first, then rebuild.
            val collected = mutableListOf<ExtractorLink>()
            loadExtractor(real, "$mainUrl/", subtitleCallback) { collected.add(it) }
            collected.forEach { link ->
                val named = if (lang.isBlank()) link else newExtractorLink(
                    link.source, "$lang · ${link.name}", link.url, link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
                sources.add(weight to named)
            }
        }

        sources
            .sortedWith(compareByDescending<Pair<Int, ExtractorLink>> { it.second.quality }.thenBy { it.first })
            .forEach { callback(it.second) }
        return sources.isNotEmpty()
    }

    private data class SearchItem(
        val name: String?,
        val link: String?,
        val cover: String?,
        val description: String? = null,
        val productionYear: String? = null,
    )

    // Intermediate per-episode parse result before splitting into dub/sub tracks.
    private data class RawEp(
        val url: String,
        val name: String?,
        val season: Int,
        val episode: Int?,
        val dub: Boolean,
        val sub: Boolean,
    )

    // Serialized into Episode.data so loadLinks knows which language track to load.
    data class EpisodeData(
        val url: String = "",
        val dub: Boolean = false,
    )

    private fun String.unescapeHtml(): String =
        org.jsoup.parser.Parser.unescapeEntities(this, false)
}
