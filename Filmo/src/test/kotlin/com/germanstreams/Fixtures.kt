package com.germanstreams

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Page fixtures captured from the live sites.
 *
 * They are trimmed copies — scripts, styles, inline SVG geometry and all but the first
 * candidate of every `srcset` are stripped, because none of that is parsed and all of it is
 * page weight. Everything the selectors touch is kept verbatim.
 *
 * When a site is redesigned these tests are supposed to fail: refresh the fixture, then fix
 * the selectors until they pass again.
 */
object Fixtures {
    fun document(name: String): Document {
        val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?: error("missing fixture: $name")
        return Jsoup.parse(stream.readBytes().toString(Charsets.UTF_8), "https://filmo.to/")
    }
}
