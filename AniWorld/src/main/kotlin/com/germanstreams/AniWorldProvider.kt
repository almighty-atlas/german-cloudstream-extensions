package com.germanstreams

import com.germanstreams.common.EpisodeLanguageCache
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

    /**
     * Upper bound on episode pages fetched to determine languages for one series.
     *
     * Language availability only exists on the episode page here, so accuracy costs one
     * request per episode. That is fine for a 24-episode season and catastrophic for a
     * 1000-episode one: the old build fired a request per episode unconditionally, which on
     * a DDoS-Guard site is both minutes of load time and a good way to get blocked.
     *
     * Below the budget every episode is resolved exactly, as before. Above it the provider
     * samples each season instead and labels the season rather than the episodes.
     */
    private val probeBudget = 60

    /** Episodes sampled per season once the series is over [probeBudget]. */
    private val samplesPerSeason = 3

    /** Concurrency per batch. Deliberately modest — this site blocks bursts. */
    private val batchSize = 8

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

        // /neue-episoden is the one listing whose rows carry a real German-dub flag. Every
        // other listing carries none, and probing for it would mean one request per card just
        // to draw the home screen — the badge is not worth that.
        val items = if ("/neue-episoden" in request.data) {
            AniWorldParser.newEpisodeCards(doc).map { (card, dub) ->
                newAnimeSearchResponse(card.title, fixUrl(card.href), TvType.Anime) {
                    this.posterUrl = fixUrlNull(card.poster)
                    if (dub) addDubStatus(dubExist = true, subExist = false)
                }
            }
        } else {
            AniWorldParser.cards(doc).map { card ->
                newAnimeSearchResponse(card.title, fixUrl(card.href), TvType.Anime) {
                    this.posterUrl = fixUrlNull(card.poster)
                }
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
            newAnimeSearchResponse(name, "$mainUrl/anime/stream/$link", TvType.Anime) {
                this.posterUrl = fixUrlNull(item.cover)
                this.year = item.productionYear?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = document(url)
        val meta = AniWorldParser.meta(doc)

        val seasonUrls = AniWorldParser.seasonHrefs(doc)
            .map { fixUrl(it) }
            .distinct()
            .ifEmpty { listOf(url) } // single-season shows list episodes on the detail page

        // The language flags on a season page are a static legend — always all three,
        // regardless of what the episode offers — so they are not read here.
        val refs: List<Pair<Int, ParsedEpisode>> = seasonUrls.amap { seasonUrl ->
            val sdoc = if (seasonUrl == url) doc else document(seasonUrl)
            val season = AniWorldParser.seasonNumber(seasonUrl)
            AniWorldParser.episodes(sdoc).map { season to it }
        }.flatten()

        val languages = resolveLanguages(refs)

        val episodes = refs.map { (season, ep) ->
            val epUrl = fixUrl(ep.href)
            val hasDub = languages[epUrl]?.contains(SourceLanguage.GermanDub) == true
            newEpisode(epUrl) {
                this.name = listOfNotNull(ep.title?.ifBlank { null }, "🇩🇪".takeIf { hasDub })
                    .joinToString(" ").ifBlank { null }
                this.season = season
                this.episode = ep.number
            }
        }

        // Flag whole seasons that contain at least one German-dub episode. With sampling this
        // is the level the data is actually accurate at, so it is always filled in.
        val seasonNames = refs.groupBy { it.first }.toSortedMap().map { (season, eps) ->
            val dub = eps.any {
                languages[fixUrl(it.second.href)]?.contains(SourceLanguage.GermanDub) == true
            }
            val label = if (season == 0) "Filme" else "Staffel $season"
            SeasonData(season, if (dub) "$label 🇩🇪" else label)
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

    /**
     * Determines the language tracks per episode, within [probeBudget] requests.
     *
     * Anything already memoised is free, so re-opening a series costs nothing. For what is
     * left: a series that fits the budget is resolved exactly; a longer one is sampled per
     * season, and the sampled result is applied to that whole season so the season label
     * still says whether a dub exists.
     */
    private suspend fun resolveLanguages(
        refs: List<Pair<Int, ParsedEpisode>>,
    ): Map<String, Set<SourceLanguage>> {
        val urls = refs.map { fixUrl(it.second.href) }
        val known = urls.mapNotNull { url -> EpisodeLanguageCache[url]?.let { url to it } }.toMap()
        val missing = urls.filter { it !in known }
        if (missing.isEmpty()) return known

        if (missing.size <= probeBudget) {
            return known + probe(missing)
        }

        // Over budget: sample evenly within each season and spread the answer across it.
        val bySeason = refs.groupBy { it.first }
        val samples = bySeason.values.flatMap { seasonEps ->
            val seasonUrls = seasonEps.map { fixUrl(it.second.href) }.filter { it !in known }
            if (seasonUrls.isEmpty()) return@flatMap emptyList<String>()
            val step = maxOf(1, seasonUrls.size / samplesPerSeason)
            seasonUrls.filterIndexed { index, _ -> index % step == 0 }.take(samplesPerSeason)
        }

        val probed = probe(samples)
        val perSeason = bySeason.mapValues { (_, seasonEps) ->
            seasonEps.map { fixUrl(it.second.href) }
                .mapNotNull { probed[it] ?: known[it] }
                .flatten()
                .toSet()
        }

        val spread = refs.associate { (season, ep) ->
            val epUrl = fixUrl(ep.href)
            epUrl to (known[epUrl] ?: probed[epUrl] ?: perSeason[season].orEmpty())
        }
        return spread
    }

    private suspend fun probe(urls: List<String>): Map<String, Set<SourceLanguage>> =
        urls.chunked(batchSize).flatMap { chunk ->
            chunk.amap { url ->
                val languages = runCatching { AniWorldParser.hosterLanguages(document(url)) }
                    .getOrDefault(emptySet())
                if (languages.isNotEmpty()) EpisodeLanguageCache.put(url, languages)
                url to languages
            }
        }.toMap()

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
            ?.let { EpisodeLanguageCache.put(pageUrl, it) }

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
