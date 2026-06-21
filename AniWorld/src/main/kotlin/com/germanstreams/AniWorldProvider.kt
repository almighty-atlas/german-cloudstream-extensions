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
        "$mainUrl/beliebte-animes" to "Beliebt bei AniWorld",
        "$mainUrl/neu" to "Neue Animes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // These catalog pages are not paginated, only return content on the first page.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data).document

        // Cards are wrapped in .coverListItem (homepage carousels) or a grid column
        // div (.col-md-15 on /beliebte-animes and /neu).
        val base = doc.select("div.coverListItem, div.col-md-15")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        // Listing pages carry no language info, so enrich each card with a Dub/Sub badge by
        // probing its first episode. Bounded batches keep the request burst reasonable.
        val items = base.chunked(20).flatMap { chunk ->
            chunk.amap { card -> card.also { it.addDubSubBadge() } }
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // Probe staffel-1/episode-1 to tag a search card with its available languages.
    private suspend fun AnimeSearchResponse.addDubSubBadge() {
        val keys = runCatching {
            app.get("$url/staffel-1/episode-1").document
                .select("li[data-link-target][data-lang-key]")
                .map { it.attr("data-lang-key") }
                .toSet()
        }.getOrDefault(emptySet())
        if (keys.isNotEmpty()) {
            val dub = "1" in keys
            val sub = keys.any { it == "2" || it == "3" }
            // Show the Dub chip whenever a dub exists; only mark Sub when there is no dub,
            // so a badge actually distinguishes dub-available titles from sub-only ones.
            addDubStatus(dubExist = dub, subExist = sub && !dub)
        }
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

        // Episode list from the season pages. The language flags shown here are a static
        // legend (always all three) and do NOT reflect real availability, so we ignore them.
        val refs = seasonUrls.amap { seasonUrl ->
            val sdoc = if (seasonUrl == url) doc else app.get(seasonUrl).document
            val seasonNum = Regex("/staffel-(\\d+)").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (seasonUrl.endsWith("/filme")) 0 else 1
            sdoc.select("table.seasonEpisodesList tbody tr").mapNotNull { row ->
                val a = row.selectFirst("td.seasonEpisodeTitle a") ?: return@mapNotNull null
                val epUrl = fixUrl(a.attr("href"))
                val epNum = Regex("/episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                val epName = a.selectFirst("strong")?.text()?.ifBlank { null }
                    ?: a.selectFirst("span")?.text()
                RawEp(epUrl, epName, seasonNum, epNum)
            }
        }.flatten()

        // Real per-episode languages live on each episode page (the hoster list carries the
        // actual data-lang-key values). Fetch in bounded batches so long series don't fire
        // hundreds of concurrent requests.
        val withLangs = refs.chunked(20).flatMap { chunk ->
            chunk.amap { ref ->
                val keys = runCatching {
                    app.get(ref.url).document
                        .select("li[data-link-target][data-lang-key]")
                        .map { it.attr("data-lang-key") }
                        .toSet()
                }.getOrDefault(emptySet())
                ref to keys
            }
        }

        val dubEpisodes = withLangs.filter { "1" in it.second }.map { (ref, _) ->
            newEpisode(EpisodeData(ref.url, dub = true).toJson()) {
                this.name = ref.name; this.season = ref.season; this.episode = ref.episode
            }
        }
        val subEpisodes = withLangs.filter { it.second.any { k -> k == "2" || k == "3" } }.map { (ref, _) ->
            newEpisode(EpisodeData(ref.url, dub = false).toJson()) {
                this.name = ref.name; this.season = ref.season; this.episode = ref.episode
            }
        }

        // CloudStream's season spinner is a union across Dub+Sub, so dub-less seasons cannot be
        // hidden when Dub is selected. Annotate the season name instead, so it is clear which
        // seasons actually offer a German dub.
        val seasonNames = withLangs.groupBy { it.first.season }.toSortedMap().map { (season, eps) ->
            val dub = eps.any { "1" in it.second }
            val sub = eps.any { it.second.any { k -> k == "2" || k == "3" } }
            val tag = when {
                dub && sub -> "Dub + Sub"
                dub -> "Dub"
                else -> "Sub"
            }
            val label = if (season == 0) "Filme" else "Staffel $season"
            SeasonData(season, "$label · $tag")
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            addSeasonNames(seasonNames)
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

    // Intermediate per-episode parse result before resolving languages.
    private data class RawEp(
        val url: String,
        val name: String?,
        val season: Int,
        val episode: Int?,
    )

    // Serialized into Episode.data so loadLinks knows which language track to load.
    data class EpisodeData(
        val url: String = "",
        val dub: Boolean = false,
    )

    private fun String.unescapeHtml(): String =
        org.jsoup.parser.Parser.unescapeEntities(this, false)
}
