package com.harufilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl

/**
 * Harufilm custom streaming extractors.
 *
 * HaruStream pages expose a direct video via:
 *   embed page -> window.streamUrl = "/stream/<id>" -> direct MKV URL
 *
 * The site uses several HaruStream host aliases that share the SAME structure:
 *   - haru-stream.pages.dev
 *   - harustream.eu.cc
 * Each serves a plain HTML page with window.streamUrl = "/stream/<id>".
 *
 * HaruPlayer (embed4me.com): a JS/protected P2P player. Kept for embeddings that
 * genuinely reference embed4me, but most anime episodes use HaruStream aliases.
 *
 * NOTE: separate filename so release.sh (which overwrites Extractors.kt
 * with the shared collection) does NOT clobber these.
 */

abstract class HaruPageExtractor : ExtractorApi() {
    override val requiresReferer = true

    protected open val streamUrlRegex: Regex = Regex("""window\.streamUrl\s*=\s*["']([^"']+)["']""")
    protected open val sourceRegexes: List<Regex> = listOf(
        Regex("""(?:file|url|src)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']([^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer).text
        } catch (_: Exception) {
            ""
        }

        // 1. window.streamUrl = "/stream/<id>"
        streamUrlRegex.find(html)?.groupValues?.get(1)?.let { raw ->
            val resolved = if (raw.startsWith("http")) raw else fixUrl(raw)
            callback(
                newExtractorLink(name, name, resolved) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // 2. direct m3u8/mp4 in HTML
        for (re in sourceRegexes) {
            val m = re.find(html)?.groupValues?.get(1) ?: continue
            val resolved = if (m.startsWith("http")) m else fixUrl(m)
            callback(
                newExtractorLink(name, name, resolved) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // 3. Fallback: embed page URL — CloudStream WebView plays it natively
        callback(
            newExtractorLink(name, name, url) {
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

/** Movies: haru-stream.pages.dev */
class HaruStreamExtractor : HaruPageExtractor() {
    override val name = "HaruStream"
    override val mainUrl = "https://haru-stream.pages.dev"
}

/** Anime episodes (most): harustream.eu.cc — same HaruStream structure */
class HaruStreamEuCcExtractor : HaruPageExtractor() {
    override val name = "HaruStream"
    override val mainUrl = "https://harustream.eu.cc"
}

/**
 * Anime episodes (genuine embed4me): protected JS/P2P player. Keep VidStack
 * fallback for the rare embeddings that reference haruplayer.embed4me.com.
 */
class HaruPlayerExtractor : VidStack() {
    override var name = "HaruPlayer"
    override var mainUrl = "https://haruplayer.embed4me.com"
}
