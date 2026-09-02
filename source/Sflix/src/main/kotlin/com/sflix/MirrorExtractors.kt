package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl

/**
 * Sflix mirror-host extractors.
 * Registered in SflixPlugin — separate file from shared Extractors.kt
 * (release.sh overwrites Extractors.kt per-plugin).
 */

abstract class SflixEmbedExtractor : ExtractorApi() {
    override val requiresReferer = true

    protected open val videoPatterns: List<Regex> = listOf(
        Regex("""(?:file|src|url)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try { app.get(url, referer = referer).text } catch (_: Exception) { "" }

        for (p in videoPatterns) {
            val m = p.find(html)?.groupValues?.get(1) ?: continue
            val resolved = if (m.startsWith("http")) m else fixUrl(m)
            callback(
                newExtractorLink(name, name, resolved) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Fallback: expose the embed URL itself
        callback(
            newExtractorLink(name, name, url) {
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class MoviesapiExtractor : SflixEmbedExtractor() {
    override val name = "MoviesApi"
    override val mainUrl = "https://moviesapi.to"
}

class VidcoreExtractor : SflixEmbedExtractor() {
    override val name = "VidCore"
    override val mainUrl = "https://vidcore.net"
}

class VideasyExtractor : SflixEmbedExtractor() {
    override val name = "VidEasy"
    override val mainUrl = "https://videasy.net"
}

class VidfastExtractor : SflixEmbedExtractor() {
    override val name = "VidFast"
    override val mainUrl = "https://vidfast.vc"
}

class VidsrcEmbedExtractor : SflixEmbedExtractor() {
    override val name = "VidSrc"
    override val mainUrl = "https://vidsrc-embed.ru"
}

class EmbedmasterExtractor : SflixEmbedExtractor() {
    override val name = "EmbedMaster"
    override val mainUrl = "https://embedmaster.link"
}
