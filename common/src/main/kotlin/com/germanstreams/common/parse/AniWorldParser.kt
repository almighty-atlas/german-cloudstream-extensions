package com.germanstreams.common.parse

import com.germanstreams.common.SourceLanguage
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Selectors for aniworld.to.
 *
 * Language selectors are covered by trimmed HTTP captures from 2026-10-16. Availability is
 * scoped to individual feed entries, season rows or hoster entries, never global flag legends.
 * Empty language sets mean unknown, not a verified absence of other tracks.
 */
object AniWorldParser {

    fun cards(doc: Document): List<ParsedCard> =
        doc.select("div.coverListItem, div.col-md-15, div.seriesListContainer > div")
            .mapNotNull { it.toCard() }
            .distinctBy { it.href }

    private fun Element.toCard(): ParsedCard? {
        val a = selectFirst("a[href*=/anime/stream/]") ?: return null
        val href = a.attr("href").trim().ifBlank { null } ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").substringBefore(" stream").trim().ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null
        return ParsedCard(title, href, Images.from(selectFirst("img")))
    }

    /** A site-relative anime identity plus the original, unresolved episode or film href. */
    data class EpisodeReference(
        val animeHref: String,
        val href: String,
        val season: Int,
        val number: Int,
    ) {
        val isMovie: Boolean get() = season == 0
    }

    data class NewEpisodeEntry(
        val reference: EpisodeReference,
        val title: String,
        val languages: Set<SourceLanguage>,
    )

    private val episodePath = Regex("^(/anime/stream/[^/]+)/staffel-([1-9]\\d*)/episode-([1-9]\\d*)/?$")
    private val filmPath = Regex("^(/anime/stream/[^/]+)/filme/film-([1-9]\\d*)/?$")

    fun episodeReference(href: String): EpisodeReference? {
        val raw = href.trim()
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != null && uri.scheme.lowercase() !in setOf("http", "https")) return null
        val path = uri.rawPath ?: return null
        episodePath.matchEntire(path)?.let { match ->
            val season = match.groupValues[2].toIntOrNull() ?: return null
            val number = match.groupValues[3].toIntOrNull() ?: return null
            return EpisodeReference(match.groupValues[1], raw, season, number)
        }
        filmPath.matchEntire(path)?.let { match ->
            val number = match.groupValues[2].toIntOrNull() ?: return null
            return EpisodeReference(match.groupValues[1], raw, 0, number)
        }
        return null
    }

    /** Positive feed observations only: separate language updates do not exclude other tracks. */
    fun newEpisodeEntries(doc: Document): List<NewEpisodeEntry> =
        doc.select("div.newEpisodeList div.row").mapNotNull { row ->
            val a = row.selectFirst("a[href*=/anime/stream/]") ?: return@mapNotNull null
            val ref = episodeReference(a.attr("href")) ?: return@mapNotNull null
            val title = a.selectFirst("strong")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            NewEpisodeEntry(ref, title, flagLanguages(row.select("img.flag")))
        }.groupBy { it.reference.identity() }.values.map { entries ->
            entries.first().copy(languages = entries.flatMap { it.languages }.toSet())
        }

    /** Compatibility projection for the existing provider; richer feed data stays available above. */
    fun newEpisodeCards(doc: Document): List<Pair<ParsedCard, Boolean>> =
        newEpisodeEntries(doc).groupBy { it.reference.animeHref }.values.map { entries ->
            val first = entries.first()
            ParsedCard(first.title, first.reference.animeHref, null) to
                entries.any { SourceLanguage.GermanDub in it.languages }
        }

    private fun EpisodeReference.identity(): Triple<String, Int, Int> = Triple(animeHref, season, number)

    private fun flagLanguages(images: List<Element>): Set<SourceLanguage> = images.flatMap { img ->
        listOf("src", "data-src").mapNotNull { attr ->
            val path = runCatching { URI(img.attr(attr).trim()).rawPath }.getOrNull()
            when (path?.substringAfterLast('/')) {
                "german.svg" -> SourceLanguage.GermanDub
                "japanese-german.svg" -> SourceLanguage.GermanSub
                "japanese-english.svg" -> SourceLanguage.EnglishSub
                else -> null
            }
        }
    }.toSet()

    fun seasonHrefs(doc: Document): List<String> =
        doc.select("a[href*=/anime/stream/]")
            .map { it.attr("href").trim() }
            .filter { (it.contains("/staffel-") || it.endsWith("/filme")) && !it.contains("/episode-") }
            .distinct()

    fun seasonNumber(href: String): Int =
        Regex("/staffel-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (href.endsWith("/filme")) 0 else 1

    /** Episode and film rows carry their own flags; flags elsewhere on the page are ignored. */
    fun episodes(doc: Document): List<ParsedEpisode> =
        doc.select("table.seasonEpisodesList tbody tr").mapNotNull { row ->
            val a = row.selectFirst("td.seasonEpisodeTitle a") ?: return@mapNotNull null
            val ref = episodeReference(a.attr("href")) ?: return@mapNotNull null
            ParsedEpisode(
                href = ref.href,
                number = ref.number,
                title = a.selectFirst("strong")?.text()?.trim()?.ifBlank { null }
                    ?: a.selectFirst("span")?.text()?.trim()?.ifBlank { null },
                languages = flagLanguages(row.select("td.editFunctions img")),
            )
        }.groupBy { episodeReference(it.href)?.identity() }.values.map { entries ->
            entries.first().copy(
                title = entries.firstNotNullOfOrNull { it.title },
                languages = entries.flatMap { it.languages }.toSet(),
            )
        }

    fun meta(doc: Document): ParsedMeta {
        val title = doc.selectFirst("div.series-title h1 span")?.text()?.trim()
            ?: doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        return ParsedMeta(
            title = title,
            poster = Images.from(doc.selectFirst("div.seriesCoverBox img")),
            plot = doc.selectFirst(".seri_des")?.attr("data-full-description")?.trim()?.ifBlank { null }
                ?: doc.selectFirst(".seri_des")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("[itemprop=description]")?.text()?.trim()?.ifBlank { null },
            tags = doc.select("div.genres a[itemprop=genre], .genres li a")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            year = doc.selectFirst("span[itemprop=startDate] a, .series-title small")
                ?.text()?.trim()?.take(4)?.toIntOrNull(),
            actors = doc.select("li[itemprop=actor] span[itemprop=name], .cast li a")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
                .map { ParsedActor(it) },
        )
    }

    /** Read hoster entries, not the language picker, which may contain unoffered tracks. */
    fun hosterLanguages(doc: Document): Set<SourceLanguage> =
        doc.select("li[data-link-target][data-lang-key]")
            .filter { it.hosterTarget() != null }
            .map { SourceLanguage.fromAniWorldKey(it.attr("data-lang-key")) }
            .filter { it != SourceLanguage.Unknown }
            .toSet()

    data class Hoster(val redirect: String, val language: SourceLanguage)

    fun hosters(doc: Document): List<Hoster> =
        doc.select(
            "div.hosterSiteVideo ul li[data-link-target], li.col-md-3.col-xs-12[data-link-target]"
        ).mapNotNull { li ->
            val target = li.hosterTarget() ?: return@mapNotNull null
            Hoster(target, SourceLanguage.fromAniWorldKey(li.attr("data-lang-key")))
        }.distinctBy { it.redirect }

    private fun Element.hosterTarget(): String? = attr("data-link-target").trim().ifBlank {
        selectFirst("a.watchEpisode")?.attr("href").orEmpty().trim()
    }.ifBlank { null }
}
