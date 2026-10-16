package com.germanstreams

import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.AniWorldParser
import kotlin.test.Test
import org.jsoup.Jsoup
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression cover for the aniworld.to parser.
 *
 * The live fixtures are trimmed HTTP captures from 2026-10-16; see fixtures/README.md.
 * Legacy synthetic fixtures and inline synthetic edge cases remain explicitly separate.
 * A passing fixture test does not establish current site reachability or video playback.
 */
class AniWorldParserTest {

    @Test
    fun `season navigation collects staffel and filme links`() {
        val hrefs = AniWorldParser.seasonHrefs(Fixtures.document("aniworld-season-synthetic.html"))
        assertEquals(
            listOf(
                "/anime/stream/test-anime/staffel-1",
                "/anime/stream/test-anime/staffel-2",
                "/anime/stream/test-anime/filme",
            ),
            hrefs,
        )
    }

    @Test
    fun `season numbers come from the url and movies sort first`() {
        assertEquals(1, AniWorldParser.seasonNumber("/anime/stream/x/staffel-1"))
        assertEquals(7, AniWorldParser.seasonNumber("/anime/stream/x/staffel-7"))
        assertEquals(0, AniWorldParser.seasonNumber("/anime/stream/x/filme"))
    }

    @Test
    fun `episode rows prefer the german title`() {
        val episodes = AniWorldParser.episodes(Fixtures.document("aniworld-season-synthetic.html"))
        assertEquals(2, episodes.size)
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("Der Anfang", episodes.first().title)
    }

    @Test
    fun `global legends and language pickers are not episode availability`() {
        val doc = Jsoup.parse(
            """
            <div class="legend">${flags("german.svg", "japanese-german.svg", "japanese-english.svg")}</div>
            <div class="changeLanguageBox"><img data-lang-key="1" src="/public/img/german.svg"></div>
            <table class="seasonEpisodesList"><tbody>${seasonRow(episodeHref, "")}</tbody></table>
            <div class="newEpisodeList">${feedRow(episodeHref, "")}</div>
            """.trimIndent(),
        )
        assertTrue(AniWorldParser.episodes(doc).single().languages.isEmpty())
        assertTrue(AniWorldParser.newEpisodeEntries(doc).single().languages.isEmpty())
        assertTrue(AniWorldParser.hosterLanguages(doc).isEmpty())
    }

    @Test
    fun `episode page hosters carry the real language keys`() {
        val doc = Fixtures.document("aniworld-episode-synthetic.html")

        assertEquals(
            setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
            AniWorldParser.hosterLanguages(doc),
        )

        val hosters = AniWorldParser.hosters(doc)
        assertEquals(3, hosters.size)
        assertEquals(SourceLanguage.GermanDub, hosters.first().language)
        assertTrue(hosters.all { it.redirect.startsWith("/redirect/") })
    }

    @Test
    fun `captured one piece season rows match corresponding hoster pages`() {
        val episodes = AniWorldParser.episodes(Fixtures.document("aniworld-one-piece-season-22-live.html"))
        val expected = mapOf(
            34 to setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
            35 to setOf(SourceLanguage.GermanSub),
            67 to setOf(SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
        )
        assertEquals(listOf(34, 35, 67), episodes.map { it.number })
        episodes.forEach { episode ->
            assertEquals(expected[episode.number], episode.languages)
            val ref = assertNotNull(AniWorldParser.episodeReference(episode.href))
            assertEquals("/anime/stream/one-piece", ref.animeHref)
            assertEquals(22, ref.season)
            assertEquals(episode.number, ref.number)
            assertFalse(ref.isMovie)
            val hosterDoc = Fixtures.document("aniworld-one-piece-season-22-episode-${episode.number}-live.html")
            assertEquals(expected[episode.number], AniWorldParser.hosterLanguages(hosterDoc))
            assertEquals(expected[episode.number], AniWorldParser.hosters(hosterDoc).map { it.language }.toSet())
        }
        assertEquals("Die letzte Lektion! – Der vererbte Impact!", episodes.first().title)
        assertEquals("The World Shakes! The Straw Hats' Hostage Situation [Episode 1123]", episodes[1].title)
    }

    @Test
    fun `captured feed merges language updates by episode without mixing films`() {
        val doc = Fixtures.document("aniworld-new-episodes-live.html")
        val entries = AniWorldParser.newEpisodeEntries(doc)
        assertEquals(5, entries.size)
        val jojo = entries.single { it.reference.animeHref == "/anime/stream/jojos-bizarre-adventure" }
        assertEquals("JoJo's Bizarre Adventure (2012)", jojo.title)
        assertEquals(6, jojo.reference.season)
        assertEquals(5, jojo.reference.number)
        assertEquals(setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub), jojo.languages)
        val linkClick = entries.single { it.reference.animeHref == "/anime/stream/link-click" }
        assertEquals(setOf(SourceLanguage.GermanSub, SourceLanguage.EnglishSub), linkClick.languages)
        assertEquals(setOf(SourceLanguage.GermanDub), entries.single { it.title == "Bleach" }.languages)
        val films = entries.filter { it.reference.isMovie }
        assertEquals(listOf(12, 11), films.map { it.reference.number })
        assertTrue(films.all { it.reference.season == 0 && it.languages == setOf(SourceLanguage.EnglishSub) })
        val cards = AniWorldParser.newEpisodeCards(doc).associate { it.first.href to it.second }
        assertEquals(4, cards.size)
        assertEquals(true, cards["/anime/stream/jojos-bizarre-adventure"])
        assertEquals(true, cards["/anime/stream/bleach"])
        assertEquals(false, cards["/anime/stream/link-click"])
        assertEquals(false, cards["/anime/stream/the-prince-of-tennis"])
    }

    @Test
    fun `captured film rows have film numbers and match film hosters`() {
        val films = AniWorldParser.episodes(Fixtures.document("aniworld-prince-of-tennis-films-live.html"))
        assertEquals(listOf(1, 11, 12), films.map { it.number })
        films.forEach { film ->
            val ref = assertNotNull(AniWorldParser.episodeReference(film.href))
            assertEquals("/anime/stream/the-prince-of-tennis", ref.animeHref)
            assertTrue(ref.isMovie)
            assertEquals(setOf(SourceLanguage.EnglishSub), film.languages)
        }
        assertEquals("Futari no Samurai [Movie]", films.last().title)
        assertEquals(
            films.last().languages,
            AniWorldParser.hosterLanguages(Fixtures.document("aniworld-prince-of-tennis-film-12-live.html")),
        )
    }

    @Test
    fun `all seven nonempty language combinations are independent in feed and season rows`() {
        val variants = listOf(
            "german.svg" to SourceLanguage.GermanDub,
            "japanese-german.svg" to SourceLanguage.GermanSub,
            "japanese-english.svg" to SourceLanguage.EnglishSub,
        )
        for (mask in 1..7) {
            val selected = variants.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val images = flags(*selected.map { it.first }.toTypedArray())
            val expected = selected.map { it.second }.toSet()
            val doc = Jsoup.parse(
                "<div class=newEpisodeList>${feedRow(episodeHref, images)}</div>" +
                    "<table class=seasonEpisodesList><tbody>${seasonRow(episodeHref, images)}</tbody></table>",
            )
            assertEquals(expected, AniWorldParser.newEpisodeEntries(doc).single().languages, "feed mask $mask")
            assertEquals(expected, AniWorldParser.episodes(doc).single().languages, "season mask $mask")
        }
    }

    @Test
    fun `lazy flags query strings and noscript duplicates use exact filenames`() {
        val images = """
            <img class=flag src="data:image/gif;base64,placeholder" data-src="/public/img/japanese-german.svg?v=1#flag">
            <noscript><img class=flag src="/public/img/japanese-german.svg"></noscript>
            <img class=flag src="https://aniworld.to/public/img/japanese-english.svg?cache=2">
            <img class="flag flag-german" src="/public/img/not-german.svg">
            <img class=flag src="/public/img/german.svg.png">
            <img class=flag src="/public/img/unknown.svg?file=german.svg">
            <img class=flag src="/public/img/%67erman.svg">
            <img class=flag src="http://[malformed">
        """.trimIndent()
        val doc = Jsoup.parse(
            "<div class=newEpisodeList>${feedRow(episodeHref, images)}</div>" +
                "<table class=seasonEpisodesList><tbody>${seasonRow(episodeHref, images)}</tbody></table>",
        )
        val expected = setOf(SourceLanguage.GermanSub, SourceLanguage.EnglishSub)
        assertEquals(expected, AniWorldParser.newEpisodeEntries(doc).single().languages)
        assertEquals(expected, AniWorldParser.episodes(doc).single().languages)
        assertFalse(AniWorldParser.newEpisodeCards(doc).single().second)
    }

    @Test
    fun `src and data-src both contribute positive observations`() {
        val images = "<img class=flag src=/public/img/german.svg data-src=/public/img/japanese-german.svg>"
        val doc = Jsoup.parse("<div class=newEpisodeList>${feedRow(episodeHref, images)}</div>")
        assertEquals(
            setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub),
            AniWorldParser.newEpisodeEntries(doc).single().languages,
        )
    }

    @Test
    fun `duplicates merge positives without mixing anime seasons films or episode numbers`() {
        val paths = listOf(
            episodeHref,
            "/anime/stream/test-anime/staffel-1/episode-2",
            "/anime/stream/test-anime/staffel-2/episode-1",
            "/anime/stream/other-anime/staffel-1/episode-1",
            "/anime/stream/test-anime/filme/film-1",
        )
        val feeds = paths.joinToString("") { feedRow(it, flags("japanese-german.svg")) } +
            feedRow("https://aniworld.to$episodeHref/?source=feed", flags("german.svg")) + feedRow(episodeHref, "")
        val rows = paths.joinToString("") { seasonRow(it, flags("japanese-german.svg")) } +
            seasonRow(episodeHref, flags("german.svg")) + seasonRow(episodeHref, "")
        val doc = Jsoup.parse(
            "<div class=newEpisodeList>$feeds</div><table class=seasonEpisodesList><tbody>$rows</tbody></table>",
        )
        val feed = AniWorldParser.newEpisodeEntries(doc)
        val season = AniWorldParser.episodes(doc)
        assertEquals(paths, feed.map { it.reference.href })
        assertEquals(paths, season.map { it.href })
        val combined = setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub)
        assertEquals(combined, feed.first().languages)
        assertEquals(combined, season.first().languages)
        assertTrue(feed.drop(1).all { it.languages == setOf(SourceLanguage.GermanSub) })
        assertTrue(season.drop(1).all { it.languages == setOf(SourceLanguage.GermanSub) })
    }

    @Test
    fun `reference parser rejects ambiguous malformed and unsupported paths`() {
        listOf(
            "", "/anime/stream/test-anime", "/anime/stream/test-anime/staffel-1",
            "/anime/stream/test-anime/staffel-1/episode-1/extra",
            "/anime/stream/test-anime/staffel-0/episode-1",
            "/anime/stream/test-anime/staffel-1/episode-99999999999999999999",
            "/anime/stream/test-anime/filme/episode-1",
            "javascript:$episodeHref", "ftp://example.org$episodeHref", "http://[malformed",
        ).forEach { assertNull(AniWorldParser.episodeReference(it), it) }
        val ref = assertNotNull(AniWorldParser.episodeReference(" https://aniworld.to$episodeHref/?x=1#part "))
        assertEquals("/anime/stream/test-anime", ref.animeHref)
        assertEquals(1, ref.season)
        assertEquals(1, ref.number)
        assertEquals("https://aniworld.to$episodeHref/?x=1#part", ref.href)
    }

    @Test
    fun `missing flags remain unknown and broken entries do not invent data`() {
        val invalidFeed = """
            <div class=row><strong>No link</strong>${flags("german.svg")}</div>
            <div class=row><a href="$episodeHref">No strong title</a>${flags("german.svg")}</div>
            ${feedRow("/anime/stream/test-anime", flags("german.svg"))}
        """.trimIndent()
        val invalidRows = """
            <tr><td>No title link</td><td class=editFunctions>${flags("german.svg")}</td></tr>
            ${seasonRow("/unknown/path", flags("german.svg"))}
        """.trimIndent()
        val doc = Jsoup.parse(
            "<div class=newEpisodeList>$invalidFeed${feedRow(episodeHref, flags("unknown.svg"))}</div>" +
                "<table class=seasonEpisodesList><tbody>$invalidRows${seasonRow(episodeHref, "")}</tbody></table>",
        )
        assertTrue(AniWorldParser.newEpisodeEntries(doc).single().languages.isEmpty())
        assertTrue(AniWorldParser.episodes(doc).single().languages.isEmpty())
        assertFalse(AniWorldParser.newEpisodeCards(doc).single().second)
    }

    @Test
    fun `unknown layouts and empty or unknown hoster keys produce no language claims`() {
        val doc = Jsoup.parse(
            """
            <div class=redesignedFeed>${feedRow(episodeHref, flags("german.svg"))}</div>
            <table class=redesignedTable><tbody>${seasonRow(episodeHref, flags("german.svg"))}</tbody></table>
            <li data-link-target="/redirect/1" data-lang-key=99></li>
            <li data-link-target="" data-lang-key=1></li>
            <li data-lang-key=3></li>
            """.trimIndent(),
        )
        assertTrue(AniWorldParser.newEpisodeEntries(doc).isEmpty())
        assertTrue(AniWorldParser.episodes(doc).isEmpty())
        assertTrue(AniWorldParser.hosterLanguages(doc).isEmpty())
        assertTrue(AniWorldParser.newEpisodeEntries(Jsoup.parse(" ")).isEmpty())
        assertTrue(AniWorldParser.episodes(Jsoup.parse(" ")).isEmpty())
    }

    @Test
    fun `hoster language evidence accepts the existing watch link fallback`() {
        val doc = Jsoup.parse(
            """
            <div class=hosterSiteVideo><ul>
              <li data-link-target=" " data-lang-key=1><a class=watchEpisode href=" /redirect/1 ">Watch</a></li>
              <li data-link-target="" data-lang-key=3><a class=watchEpisode href="">Missing</a></li>
            </ul></div>
            """.trimIndent(),
        )
        assertEquals(setOf(SourceLanguage.GermanDub), AniWorldParser.hosterLanguages(doc))
        assertEquals(listOf(AniWorldParser.Hoster("/redirect/1", SourceLanguage.GermanDub)), AniWorldParser.hosters(doc))
    }

    @Test
    fun `repeated hosters merge known keys without promoting unknown ones`() {
        val doc = Jsoup.parse(
            """
            <div class=hosterSiteVideo><ul>
              <li data-link-target=/redirect/1 data-lang-key=3></li>
              <li data-link-target=/redirect/1 data-lang-key=3></li>
              <li data-link-target=/redirect/2 data-lang-key=2></li>
              <li data-link-target=/redirect/3 data-lang-key=99></li>
            </ul></div>
            """.trimIndent(),
        )
        assertEquals(setOf(SourceLanguage.GermanSub, SourceLanguage.EnglishSub), AniWorldParser.hosterLanguages(doc))
        assertEquals(3, AniWorldParser.hosters(doc).size)
        assertEquals(SourceLanguage.Unknown, AniWorldParser.hosters(doc).last().language)
    }

    // Inline HTML below is deliberately synthetic and supplements, rather than rewrites, captures.
    private val episodeHref = "/anime/stream/test-anime/staffel-1/episode-1"

    private fun flags(vararg names: String): String =
        names.joinToString("") { "<img class=flag src=\"/public/img/$it\">" }

    private fun feedRow(href: String, images: String): String =
        "<div class=row><div class=col-md-12><a href=\"$href\"><strong>Test Anime</strong></a>$images</div></div>"

    private fun seasonRow(href: String, images: String): String =
        "<tr><td class=seasonEpisodeTitle><a href=\"$href\"><strong>Title</strong></a></td>" +
            "<td class=editFunctions>$images</td></tr>"

    @Test
    fun `series metadata prefers the full description over the clamped one`() {
        val meta = AniWorldParser.meta(Fixtures.document("aniworld-season-synthetic.html"))
        assertEquals("Test Anime", meta.title)
        assertEquals("Die vollständige Beschreibung.", meta.plot)
        assertEquals(listOf("Action", "Comedy"), meta.tags)
        // The cover is lazy-loaded; the base64 placeholder in src must not win.
        assertEquals("/cover/test.jpg", meta.poster)
    }
}
