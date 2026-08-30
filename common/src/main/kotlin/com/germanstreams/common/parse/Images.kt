package com.germanstreams.common.parse

import org.jsoup.nodes.Element

/**
 * Image URL extraction that survives lazy loading.
 *
 * These sites ship a 1x1 base64 GIF in `src` and put the real URL in `data-src` /
 * `data-srcset`, so reading `src` first yields a transparent pixel rather than a poster.
 * Candidates are tried in order of how likely they are to be the real file, and any
 * `data:` URI is rejected outright.
 */
object Images {

    fun from(element: Element?): String? {
        if (element == null) return null
        val direct = sequenceOf(
            element.attr("data-src"),
            element.attr("data-original"),
            element.attr("src"),
        ).mapNotNull { it.usable() }.firstOrNull()
        if (direct != null) return direct

        val fromSets = sequenceOf(element.attr("data-srcset"), element.attr("srcset"))
            .mapNotNull { firstFromSrcset(it) }
            .firstOrNull()
        if (fromSets != null) return fromSets

        // <picture><source srcset>…<img></picture>: the sources sit next to the img.
        return element.parent()?.select("source")
            ?.asSequence()
            ?.mapNotNull { firstFromSrcset(it.attr("data-srcset")) ?: firstFromSrcset(it.attr("srcset")) }
            ?.firstOrNull()
    }

    /** First candidate of a `url 1x, url 2x` / `url 375w, url 768w` list. */
    fun firstFromSrcset(srcset: String): String? = srcset
        .split(',')
        .asSequence()
        .map { it.trim().substringBefore(' ') }
        .mapNotNull { it.usable() }
        .firstOrNull()

    private fun String?.usable(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
}
