package com.germanstreams.common

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Collections

/**
 * The one place where `loadLinks` link handling lives.
 *
 * Four rules caused nearly every "link error" this repo has seen, and keeping them as prose
 * in CLAUDE.md meant re-implementing them per provider. They are enforced here instead:
 *
 * 1. Hosters are always given the **site root** as referer, never the episode URL — a wrong
 *    referer gets an anti-bot stub back instead of a video.
 * 2. Redirect endpoints are read via the Location header, never by walking the chain
 *    (see [Net.resolveRedirect]).
 * 3. [emitTo] returns whether anything was actually found; an unconditional `true` makes
 *    CloudStream report success and then show nothing, which looks exactly like a load error.
 * 4. Nothing suspending runs inside `loadExtractor`'s callback — links are collected raw and
 *    rebuilt afterwards.
 */
class SourceCollector(private val siteRoot: String) {

    private data class Entry(
        val link: ExtractorLink,
        val language: SourceLanguage,
        val captionOverride: String?,
    )

    private val entries = Collections.synchronizedList(mutableListOf<Entry>())

    /**
     * Runs the extractors for one already-resolved hoster URL.
     *
     * [captionOverride] is for sites that describe a source better than the extractor does —
     * filmo's chips read "VOE WEB-DL 720p", which beats a bare "Voe".
     */
    suspend fun addTarget(
        target: String,
        language: SourceLanguage = SourceLanguage.Unknown,
        captionOverride: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit = {},
    ) {
        val collected = mutableListOf<ExtractorLink>()
        // Referer is the site root: hosters validate against it, not against the page we came
        // from. The callback only appends — rebuilding needs suspend, so it happens in emitTo.
        runCatching { loadExtractor(target, "$siteRoot/", subtitleCallback) { collected.add(it) } }
        collected.forEach { entries.add(Entry(it, language, captionOverride)) }
    }

    /**
     * Resolves a redirect/play endpoint and then extracts from it. Does nothing if the
     * endpoint cannot be resolved, so one dead hoster never fails the whole episode.
     */
    suspend fun addRedirect(
        redirectUrl: String,
        language: SourceLanguage = SourceLanguage.Unknown,
        referer: String = "$siteRoot/",
        captionOverride: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit = {},
    ) {
        val target = Net.resolveRedirect(redirectUrl, referer) ?: return
        addTarget(target, language, captionOverride, subtitleCallback)
    }

    /**
     * Emits every collected source, best first, and reports whether there was anything.
     *
     * Ordering is quality-first because that is what CloudStream itself sorts on; language is
     * only the tie-breaker between sources of equal quality (German dub, German sub, then the
     * rest). Identical URLs collapse — the same file offered under two language buttons is one
     * source, and the better-ranked entry is the one that survives.
     */
    suspend fun emitTo(callback: (ExtractorLink) -> Unit): Boolean {
        val snapshot = synchronized(entries) { entries.toList() }
        val ordered = snapshot.sortedWith(
            compareByDescending<Entry> { qualityOf(it) }
                .thenBy { it.language.priority }
                .thenBy { it.link.source }
                .thenBy { it.link.name }
        )

        val seen = HashSet<String>()
        var emitted = 0
        for (entry in ordered) {
            if (!seen.add(entry.link.url)) continue
            callback(rebuild(entry))
            emitted++
        }
        return emitted > 0
    }

    /**
     * Some extractors put the resolution in the link name ("Voe 712p") and leave `quality`
     * unset, which would sort them as if unknown — recover it. A site-supplied caption is the
     * better source for it when there is one. Adaptive playlists and bare MP4 links genuinely
     * have no fixed resolution and stay unknown.
     */
    private fun qualityOf(entry: Entry): Int {
        val link = entry.link
        if (link.quality > 0) return link.quality
        entry.captionOverride?.let { caption ->
            getQualityFromName(caption).takeIf { it > 0 }?.let { return it }
        }
        return getQualityFromName(link.name)
    }

    private suspend fun rebuild(entry: Entry): ExtractorLink {
        val link = entry.link
        val display = displayName(entry)
        return newExtractorLink(link.source, display, link.url, link.type) {
            this.referer = link.referer
            this.quality = qualityOf(entry)
            this.headers = link.headers
            this.extractorData = link.extractorData
        }
    }

    /**
     * Renders "<language> · <host> · <variant>", e.g. "🇩🇪 Deutsch · Voe · 712p".
     *
     * A site caption replaces the host/variant half when it already says more than we could
     * assemble, and the variant is only appended when it adds something the caption does not
     * already state.
     */
    private fun displayName(entry: Entry): String {
        val language = entry.language.display
        val variant = variantOf(entry.link)
        val caption = entry.captionOverride?.trim()?.ifBlank { null }

        val body = when {
            caption == null -> listOfNotNull(entry.link.source.ifBlank { null }, variant)
                .joinToString(" · ")
            caption.contains(variant, ignoreCase = true) -> caption
            else -> "$caption · $variant"
        }
        return listOfNotNull(language.ifBlank { null }, body.ifBlank { null }).joinToString(" · ")
    }

    companion object {
        /**
         * A host yields several links that differ by variant, not by quality: a bare name is
         * the adaptive master playlist, others append the resolution ("Voe 712p") or the
         * container ("Voe MP4"). Strip the host prefix to get at that variant; if nothing is
         * left it is the adaptive playlist, which is labelled "Auto" rather than left blank.
         */
        fun variantOf(link: ExtractorLink): String =
            link.name.removePrefix(link.source).trim()
                .trimStart('-', '·', '|', ':').trim()
                .ifBlank { "Auto" }
    }
}
