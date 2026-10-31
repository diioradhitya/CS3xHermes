package com.samehadaku

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Extractor Blogger (blogger.com/video.g).
 * Mengambil semua itag (360p/720p/1080p) dari halaman video Blogger
 * sebagai link terpisah.
 */
class Blogger {

    suspend fun getUrl(
            embedUrl: String,
            referer: String?,
            callback: (ExtractorLink) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect(embedUrl)
                        .userAgent(BROWSER_UA)
                        .referrer(referer ?: embedUrl)
                        .timeout(20000)
                        .get()

                val html = doc.html()
                // Cari URL GoogleVideo (googlevideo.com/videoplayback)
                val urlRegex =
                        """https://[^"'\s\\<>]*googlevideo\.com/videoplayback[^"'\s\\<>]*""".toRegex()
                val found = urlRegex.findAll(html)

                for (m in found) {
                    var url = m.value
                    // Decode HTML escape
                    url = url.replace("&quot;", "\"")
                            .replace("&amp;", "&")
                            .replace("&#038;", "&")
                            .replace("\\u0026", "&")
                            .replace("\\u003d", "=")
                            .replace("\\u0022", "\"")
                            .replace("\\/", "/")
                    if (url.isNotEmpty()) {
                        val quality =
                                when {
                                    url.contains("itag=22") -> 720
                                    url.contains("itag=18") -> 360
                                    url.contains("itag=137") -> 1080
                                    url.contains("itag=136") -> 720
                                    url.contains("itag=135") -> 480
                                    else -> Qualities.Unknown.value
                                }
                        callback(
                                newExtractorLink(
                                        "Blogger",
                                        "Blogger",
                                        url,
                                        ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.referer = referer ?: embedUrl
                                })
                    }
                }
            } catch (e: Exception) {
                // gagal extract, biarkan kosong
            }
        }
    }
}
