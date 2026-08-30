package com.germanstreams.common

/**
 * The three language tracks these sites actually offer, plus an unknown bucket.
 *
 * Every site encodes them differently — a numeric key on aniworld, a free-text label and an
 * SVG class on serienstream, a chip caption on filmo — so the parsers normalise onto this
 * enum and everything downstream (flags, ordering, source names) shares one vocabulary.
 */
enum class SourceLanguage(val flag: String, val label: String, val priority: Int) {
    GermanDub("🇩🇪", "Deutsch", 0),
    GermanSub("🇩🇪", "Ger-Sub", 1),
    EnglishSub("🇬🇧", "Englisch", 2),
    Unknown("", "", 3);

    /** "🇩🇪 Deutsch", or empty for [Unknown] so callers can drop the segment entirely. */
    val display: String get() = if (this == Unknown) "" else "$flag $label"

    companion object {
        /**
         * aniworld's hoster `<li data-lang-key>`: 1 = German dub, 2 = English sub,
         * 3 = German sub.
         */
        fun fromAniWorldKey(key: String): SourceLanguage = when (key.trim()) {
            "1" -> GermanDub
            "2" -> EnglishSub
            "3" -> GermanSub
            else -> Unknown
        }

        /**
         * serienstream's flag SVG class (`svg-flag-<x>`) on an episode row.
         * `english-german` is German subtitles over the English audio track.
         */
        fun fromFlagClass(cls: String): SourceLanguage = when (cls.trim().lowercase()) {
            "german" -> GermanDub
            "english-german" -> GermanSub
            "english" -> EnglishSub
            else -> Unknown
        }

        /**
         * A free-text language caption, as carried by serienstream's `data-language-label`
         * and (loosely) by filmo's provider chips.
         *
         * Verified values on serienstream are "Deutsch", "Ger-Sub" and "Englisch" — the
         * abbreviated form is the one that is easy to miss, so match it explicitly before
         * falling back to the spelled-out words.
         */
        fun fromLabel(raw: String): SourceLanguage {
            val l = raw.trim().lowercase()
            if (l.isEmpty()) return Unknown
            val german = "deutsch" in l || "german" in l || l.startsWith("ger")
            val subbed = "sub" in l || "untertitel" in l || "ut" == l
            return when {
                german && subbed -> GermanSub
                german -> GermanDub
                "englisch" in l || "english" in l || l.startsWith("eng") -> EnglishSub
                else -> Unknown
            }
        }
    }
}
