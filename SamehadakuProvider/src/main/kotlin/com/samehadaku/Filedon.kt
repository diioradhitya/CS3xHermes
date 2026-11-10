package com.samehadaku

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Extractor untuk filedon.co (embed/view).
 *
 * Filedon adalah hosting berbasis Inertia.js/Laravel. Beberapa file memiliki
 * HLS/preview URL; file lain hanya bisa diputar di embed pada domain yang
 * di-whitelist pemilik (domain_whitelist). Untuk kasus embed terblokir
 * ("This embed is not allowed on this website") tidak ada stream langsung
 * yang bisa diekstrak tanpa konteks domain yang diizinkan.
 */
class Filedon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = Regex("""/embed/([^/?#]+)|/view/([^/?#]+)""").find(url)?.let {
            it.groupValues[1].ifBlank { it.groupValues[2] }
        } ?: return

        val ref = referer ?: "https://v2.samehadaku.how/"
        val headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to ref)

        // Coba halaman view (lebih sering memuat metadata media/Inertia props)
        val pages = listOf(
            "$mainUrl/view/$slug",
            "$mainUrl/embed/$slug",
            url
        )

        for (pageUrl in pages.distinct()) {
            try {
                val html = app.get(pageUrl, headers = headers).text

                // Jika embed diblokir domain whitelist, tidak ada stream langsung
                if (html.contains("embed is not allowed", ignoreCase = true) ||
                    html.contains("not allowed on this website", ignoreCase = true)
                ) {
                    continue
                }

                // Cari URL media langsung di HTML/props
                val candidates = mutableListOf<Pair<String, ExtractorLinkType>>()
                Regex("""https?:\\?/\\?/[^"'\\\s]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s]*)?""")
                    .findAll(html)
                    .forEach { m ->
                        val u = m.value.replace("\\/", "/").replace("\\u0026", "&")
                        if (u.contains("filedon", true) || u.contains("r2.cloudflarestorage", true)) {
                            candidates.add(u to if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                        }
                    }

                // hls_url / preview_url di Inertia props JSON-escaped
                Regex("""["'](?:hls_url|preview_url|stream_url|file_url)["']\s*:\s*["']([^"']+)["']""")
                    .findAll(html)
                    .forEach { m ->
                        val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                        if (u.startsWith("http")) {
                            candidates.add(u to if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                        }
                    }

                for ((mediaUrl, type) in candidates.distinctBy { it.first }) {
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            mediaUrl,
                            type
                        ) {
                            this.referer = pageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                if (candidates.isNotEmpty()) return
            } catch (_: Exception) {
            }
        }
    }
}
