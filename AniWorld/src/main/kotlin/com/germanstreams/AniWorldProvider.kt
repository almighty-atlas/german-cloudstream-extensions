package com.germanstreams

import com.germanstreams.common.EpisodeLanguageCache
import com.germanstreams.common.AniWorldLanguageIndex
import com.germanstreams.common.Net
import com.germanstreams.common.SourceCollector
import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.AniWorldParser
import com.germanstreams.common.parse.ParsedEpisode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

class AniWorldProvider : MainAPI() {
    override var mainUrl = "https://aniworld.to"
    override var name = "AniWorld ★"
    override val hasMainPage = true
    override var lang = "de"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    /**
     * Lets the user point the provider at a new domain from the app's provider settings.
     *
     * These sites move — s.to became serienstream.to — and until now that meant the plugin
     * was dead until a new build shipped. CloudStream's own override covers exactly this, so
     * it is enabled explicitly rather than left to the default.
     */
    override var canBeOverridden = true

    // Bound ordinary season-page requests; language detection adds no episode-page fetches.
    private val seasonBatchSize = 3

    override val mainPage = mainPageOf(
        "$mainUrl/neue-episoden" to "Neue Episoden",
        "$mainUrl/beliebte-animes" to "Beliebt bei AniWorld",
        "$mainUrl/neu" to "Neue Animes",
    )

    private suspend fun document(url: String): Document =
        app.get(url, referer = "$mainUrl/", headers = Net.browserHeaders, timeout = Net.TIMEOUT).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // These catalog pages are not paginated.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = document(request.data)

        // Feed rows already advertise languages. Share their positive observations with
        // future catalog/search responses; unknown entries remain unlabelled.
        val items = if ("/neue-episoden" in request.data) {
            AniWorldParser.newEpisodeEntries(doc)
                .groupBy { it.reference.animeHref }
                .values
                .map { entries ->
                    val first = entries.first()
                    val languages = entries.flatMap { it.languages }.toSet()
                    AniWorldLanguageIndex.observe(first.reference.animeHref, languages)
                    animeCard(first.title, first.reference.animeHref, null)
                }
        } else {
            AniWorldParser.cards(doc).map { card ->
                animeCard(card.title, card.href, card.poster)
            }
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$mainUrl/ajax/seriesSearch",
            params = mapOf("keyword" to query),
            headers = Net.browserHeaders,
            timeout = Net.TIMEOUT,
        ).text
        // Response is a JSON array: [{ "name", "link", "cover", "productionYear", "description" }]
        val items = tryParseJson<List<SearchItem>>(res) ?: emptyList()
        return items.mapNotNull { item ->
            val link = item.link ?: return@mapNotNull null
            val name = item.name?.unescapeHtml() ?: return@mapNotNull null
            val href = "$mainUrl/anime/stream/$link"
            animeCard(name, href, item.cover, year = item.productionYear?.take(4)?.toIntOrNull())
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = document(url)
        val meta = AniWorldParser.meta(doc)

        val seasonUrls = AniWorldParser.seasonHrefs(doc)
            .map { fixUrl(it) }
            .distinct()
            .ifEmpty { listOf(url) } // single-season shows list episodes on the detail page

        // The captured AniWorld season tables contain real per-episode languages. Read the
        // existing page responses once; no per-episode HTTP probes or sampling are needed.
        val refs: List<Pair<Int, ParsedEpisode>> = seasonUrls.chunked(seasonBatchSize)
            .flatMap { batch ->
                batch.amap { seasonUrl ->
                    val sdoc = if (seasonUrl == url) doc else document(seasonUrl)
                    val season = AniWorldParser.seasonNumber(seasonUrl)
                    AniWorldParser.episodes(sdoc).map { season to it }
                }.flatten()
            }

        val observed = refs.map { (season, ep) ->
            val episodeUrl = fixUrl(ep.href)
            val languageSet = ep.languages.ifEmpty { EpisodeLanguageCache[episodeUrl].orEmpty() }
            if (ep.languages.isNotEmpty()) EpisodeLanguageCache.put(episodeUrl, ep.languages)
            Triple(season, ep, languageSet)
        }
        AniWorldLanguageIndex.observe(url, observed.flatMap { it.third }.toSet())

        val episodes = observed.map { (season, ep, languages) ->
            val epUrl = fixUrl(ep.href)
            newEpisode(epUrl) {
                this.name = appendLanguages(ep.title.orEmpty(), languages).ifBlank { null }
                this.season = season
                this.episode = ep.number
            }
        }

        val seasonLanguages = observed.groupBy { it.first }
            .mapValues { (_, rows) -> rows.flatMap { it.third }.toSet() }
        val seasonNames = seasonLanguages.toSortedMap().map { (season, languages) ->
            val label = if (season == 0) "Filme" else "Staffel $season"
            SeasonData(season, appendLanguages(label, languages))
        }

        return newAnimeLoadResponse(meta.title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(meta.poster)
            this.plot = meta.plot
            this.tags = meta.tags
            this.year = meta.year
            addActors(meta.actors.map { it.name })
            addEpisodes(DubStatus.None, episodes)
            addSeasonNames(seasonNames)
        }
    }

    private fun appendLanguages(title: String, languages: Set<SourceLanguage>): String {
        val suffix = listOfNotNull(
            "GerDub".takeIf { SourceLanguage.GermanDub in languages },
            "GerSub".takeIf { SourceLanguage.GermanSub in languages },
            "EngSub".takeIf { SourceLanguage.EnglishSub in languages },
        ).joinToString(" · ")
        return listOf(title.trim(), suffix).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun animeCard(
        title: String,
        href: String,
        poster: String?,
        year: Int? = null,
    ): AnimeSearchResponse {
        val languages = AniWorldLanguageIndex.languages(href)
        return newAnimeSearchResponse(appendLanguages(title, languages), fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            if (languages.isNotEmpty()) {
                addDubStatus(
                    dubExist = SourceLanguage.GermanDub in languages,
                    subExist = SourceLanguage.GermanSub in languages || SourceLanguage.EnglishSub in languages,
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data is the episode URL (older builds may have stored JSON {url, dub}).
        val pageUrl = tryParseJson<EpisodeData>(data)?.url?.ifBlank { null } ?: data

        val doc = document(pageUrl)
        val hosters = AniWorldParser.hosters(doc)
        if (hosters.isEmpty()) return false

        // Remember what this page said; it is the same data load() pays for.
        AniWorldParser.hosterLanguages(doc)
            .takeIf { it.isNotEmpty() }
            ?.let {
                EpisodeLanguageCache.put(fixUrl(pageUrl), it)
                AniWorldLanguageIndex.observe(pageUrl, it)
            }

        val collector = SourceCollector(mainUrl)
        hosters.amap { hoster ->
            collector.addRedirect(
                redirectUrl = fixUrl(hoster.redirect),
                language = hoster.language,
                subtitleCallback = subtitleCallback,
            )
        }
        return collector.emitTo(callback)
    }

    private data class SearchItem(
        val name: String?,
        val link: String?,
        val cover: String?,
        val description: String? = null,
        val productionYear: String? = null,
    )

    // Serialized into Episode.data by older builds; still read so existing bookmarks resolve.
    data class EpisodeData(
        val url: String = "",
        val dub: Boolean = false,
    )

    private fun String.unescapeHtml(): String =
        org.jsoup.parser.Parser.unescapeEntities(this, false)
}
