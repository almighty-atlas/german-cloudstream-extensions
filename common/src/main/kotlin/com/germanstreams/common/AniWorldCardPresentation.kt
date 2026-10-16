package com.germanstreams.common

import com.germanstreams.common.parse.AniWorldParser

/** A single anime-level card from the episode feed, with positive language observations. */
data class AniWorldFeedCard(
    val title: String,
    val animeHref: String,
    val languages: Set<SourceLanguage>,
)

/**
 * JVM-testable bridge from feed evidence to CloudStream's poster-card fields.
 *
 * The app may hide its native Dub/Sub badges and truncate long titles on Android TV.
 * A short language prefix gives title-enabled layouts the most useful information first.
 * Poster layouts with titles AND badges hidden cannot be fixed by a provider alone.
 */
object AniWorldCardPresentation {
    data class Fields(
        val displayName: String,
        val hasDubBadge: Boolean,
        val hasSubBadge: Boolean,
    )

    fun feedCards(entries: List<AniWorldParser.NewEpisodeEntry>): List<AniWorldFeedCard> =
        entries.groupBy { it.reference.animeHref }.values.map { grouped ->
            AniWorldFeedCard(
                title = grouped.first().title,
                animeHref = grouped.first().reference.animeHref,
                languages = grouped.flatMap { it.languages }.toSet(),
            )
        }

    fun fields(title: String, languages: Set<SourceLanguage>): Fields {
        val labels = listOfNotNull(
            "GerDub".takeIf { SourceLanguage.GermanDub in languages },
            "GerSub".takeIf { SourceLanguage.GermanSub in languages },
            "EngSub".takeIf { SourceLanguage.EnglishSub in languages },
        )
        return Fields(
            displayName = if (labels.isEmpty()) title else "${labels.joinToString(" · ")} | $title",
            hasDubBadge = SourceLanguage.GermanDub in languages,
            hasSubBadge = SourceLanguage.GermanSub in languages || SourceLanguage.EnglishSub in languages,
        )
    }
}
