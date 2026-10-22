package com.oploverz

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Shared extractor collection for CS3xHermes plugins.
 *
 * Adapted from CloudX-V2 plugin family (Asm0d3usX):
 *  - Dingtezuni family (Earnvids-like)   - generic JS unpack + m3u8 regex
 *  - StreamWish family (Hglink-like)     - extends CloudStream built-in
 *  - VidStack family                     - extends CloudStream built-in
 *  - Gofile (API resolver)               - direct CDN link via API token
 *  - Kotakajaib (Base64 obfuscation)     - decode iframe data-* attribute
 *  - HlsTereaLayarwibu (master.m3u8)     - direct m3u8 + Base64 fallback
 *  - StreamTape wrapper                  - alternate domain
 *  - Luluvdo (specialized StreamWish)    - custom referer handling
 *  - Hydrax (Hydrax CDN)                 - direct source extraction
 *
 * To use in a plugin:
 *   1. Set package = <your plugin package> above (manually edit per plugin)
 *   2. In Plugin.kt load():
 *        registerExtractorAPI(Dingtezuni())
 *        registerExtractorAPI(Movearnpre())
 *        // ... etc
 *   3. Plugin.kt loadLinks() can call loadExtractor() — CloudStream
 *      auto-routes URLs to the matching registered extractor.
 */

// =====================================================================
// 1. DINGTEZUNI FAMILY — Earnvids-style (JS pack + m3u8 regex)
// =====================================================================

open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val response = app.get(getEmbedUrl(url), referer = referer, headers = mapOf("User-Agent" to BROWSER_UA))
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if (result.contains("var links")) result = result.substringAfter("var links")
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            M3u8Helper.generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}

// Aliases — same engine, different domain
class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://mivalyo.com"
}

class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Bingezove : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://bingezove.com"
}

class Morencius : Dingtezuni() {
    override var name = "Morencius"
    override var mainUrl = "https://morencius.com"
}

class Dhtpre : Dingtezuni() {
    override var name = "Dhtpre"
    override var mainUrl = "https://dhtpre.com"
}

class Dinisglows : Dingtezuni() {
    override var name = "Dinisglows"
    override var mainUrl = "https://dinisglows.com"
}

class Dintezuvio : Dingtezuni() {
    override var name = "Dintezuvio"
    override var mainUrl = "https://dintezuvio.com"
}

class Smoothpre : Dingtezuni() {
    override var name = "Smoothpre"
    override var mainUrl = "https://smoothpre.com"
}

// =====================================================================
// 2. STREAMWISH FAMILY — Hglink-style (extends CloudStream built-in)
// =====================================================================

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class Dhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Hgcloud : StreamWishExtractor() {
    override var name = "Hgcloud"
    override val mainUrl = "https://hgcloud.to"
}

class Short : StreamWishExtractor() {
    override var name = "Short"
    override var mainUrl = "https://short.icu"
}

class Shorticu : StreamWishExtractor() {
    override var name = "Shorticu"
    override val mainUrl = "https://shorticu.com"
}

/**
 * UpBolt (https://upbolt.to/e/<code>).
 *
 * Format BARU: halaman embed tidak lagi memuat link stream langsung, tapi
 * form POST ke /dl:
 *   op=embed&file_code=<code>&auto=1&referer=<referer>
 * Balasan HTML-nya memuat:
 *   - master.m3u8  (HLS, token t/s/e di query)
 *   - <code>_sli.vtt (subtitle)
 *   - <title> berisi kualitas, mis. "OPLOVERZ PLUS - BLC 415 [mp4 480p]"
 *
 * Ekstraktor StreamWish bawaan tidak bisa menangani format ini, jadi
 * ditImplementasikan manual; kalau gagal, jatuh ke logika StreamWish lama.
 */
/** Kualitas dari tinggi (px) -> nilai Qualities SDK. */
internal fun qualityByHeight(h: Int): Int =
        when (h) {
            144 -> Qualities.P144.value
            240 -> Qualities.P240.value
            360 -> Qualities.P360.value
            480 -> Qualities.P480.value
            720 -> Qualities.P720.value
            1080 -> Qualities.P1080.value
            1440 -> Qualities.P1440.value
            2160 -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }

/**
 * Baca master playlist HLS, kembalikan daftar (url, kualitas) per variant.
 * Semua variant diambil (tidak ada batas resolusi), jadi aplikasi bisa
 * offer 360p/480p/720p/1080p sekaligus kalau memang tersedia.
 */
internal suspend fun parseM3u8Variants(masterUrl: String): List<Pair<String, Int>> {
    // CDN upbolt kadang gagal saat request pertama (master berisi koma di
    // path), jadi dicoba ulang; kalau masih gagal, query string dibuang.
    val candidates =
            listOf(masterUrl, masterUrl.substringBefore("?")).distinct()
    var text: String? = null
    for (c in candidates) {
        for (attempt in 0..1) {
            try {
                val r = app.get(c, referer = "https://upbolt.to/", headers = mapOf("User-Agent" to BROWSER_UA)).text
                if (r.contains("#EXTM3U")) {
                    text = r
                    break
                }
            } catch (e: Throwable) {
            }
        }
        if (text != null) break
    }
    if (text == null) return emptyList()
    if (!text.contains("#EXTM3U")) return emptyList()
    val out = mutableListOf<Pair<String, Int>>()
    val lines = text.lines()
    for (i in lines.indices) {
        val l = lines[i]
        if (!l.startsWith("#EXT-X-STREAM-INF")) continue
        val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)
        val height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val url = lines.getOrNull(i + 1)?.trim() ?: continue
        if (url.isEmpty() || url.startsWith("#")) continue
        out.add(url to qualityByHeight(height))
    }
    return out
}

class UpBolt : StreamWishExtractor() {
    override var name = "UpBolt"
    override val mainUrl = "https://upbolt.to"
    override val requiresReferer = true

    private fun qualityOf(q: Int): Int =
            when (q) {
                1080 -> Qualities.P1080.value
                720 -> Qualities.P720.value
                480 -> Qualities.P480.value
                360 -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfterLast("/").substringBefore("?")
        var resolved = false
        try {
            val res =
                    app.post(
                            url = "$mainUrl/dl",
                            data =
                                    mapOf(
                                            "op" to "embed",
                                            "file_code" to code,
                                            "auto" to "1",
                                            "referer" to (referer ?: "$mainUrl/")
                                    ),
                            referer = referer,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
            val html = res.text
            val m3u8 =
                    Regex("""["'](https?://[^"'\s]+?\.m3u8[^"'\s]*)["']""")
                            .find(html)?.groupValues?.get(1)
            if (!m3u8.isNullOrBlank()) {
                Regex("""["'](https?://[^"'\s]+?\.vtt)["']""")
                        .find(html)?.groupValues?.get(1)
                        ?.let { subtitleCallback(SubtitleFile(it, "Indonesia")) }
                val title =
                        Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                                .find(html)?.groupValues?.get(1)?.trim()
                val q =
                        Regex("""\[mp4 (\d{3,4})p\]""")
                                .find(title ?: "")?.groupValues?.get(1)?.toIntOrNull()
                // Master playlist bisa punya beberapa variant (mis. 360p + 480p).
                // Semuanya dikembalikan sebagai link terpisah supaya aplikasi
                // bisa offer track video resolusi tertinggi yang tersedia.
                val variants = parseM3u8Variants(m3u8)
                if (variants.isEmpty()) {
                    callback(
                            newExtractorLink(
                                    "UpBolt",
                                    title?.ifBlank { null } ?: "UpBolt",
                                    m3u8,
                                    ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = q?.let { qualityOf(it) } ?: Qualities.Unknown.value
                            })
                } else {
                    for ((vUrl, vQuality) in variants) {
                        callback(
                                newExtractorLink(
                                        "UpBolt",
                                        title?.ifBlank { null } ?: "UpBolt",
                                        vUrl,
                                        ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = vQuality
                                })
                    }
                }
                resolved = true
            }
        } catch (e: Throwable) {
            // fallback ke StreamWish di bawah
        }
        if (!resolved) super.getUrl(url, referer, subtitleCallback, callback)
    }
}


/**
 * Blogger (https://www.blogger.com/video.g?token=...).
 *
 * Halaman Blogger membungkus URL video GoogleVideo dengan escape unicode
 * (\u003d "=" dan \u0026 "&"), jadi pola searching harus di-decode dulu.
 * Extractor bawaan SDK tidak menangani escape itu -> link kosong.
 */
class Blogger : ExtractorApi() {
    override val name = "Blogger"
    override val mainUrl = "https://www.blogger.com"
    override val requiresReferer = false

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = mapOf("User-Agent" to BROWSER_UA)).text
        // Halaman Blogger memuat satu URL GoogleVideo per itag (18=360p,
        // 22=720p, 37=1080p, ...). Semua varian dikembalikan supaya aplikasi
        // bisa memilih track video resolusi tertinggi yang tersedia.
        val raws =
                Regex("""https://[^"&]*googlevideo\.com/videoplayback[^"&]*""")
                        .findAll(html)
                        .map { it.groupValues.get(0) }
                        .toList()
        if (raws.isEmpty()) return
        val byItag = LinkedHashMap<Int, String>()
        for (raw in raws) {
            val itag =
                    Regex("""itag(?:\\u003d|=)(\d+)""")
                            .find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!byItag.containsKey(itag)) byItag[itag] = raw
        }
        if (byItag.isEmpty()) return
        for ((itag, raw) in byItag) {
            val videoUrl =
                    raw.replace("\\u003d", "=")
                            .replace("\\u0026", "&")
                            .replace("\\u0026amp;", "&")
                            .replace("\\/", "/")
            if (videoUrl.isBlank()) continue
            val quality =
                    when (itag) {
                        37, 136, 137, 271, 59, 78 -> Qualities.P1080.value
                        22 -> Qualities.P720.value
                        18 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
            callback(
                    newExtractorLink("Blogger", "Blogger", videoUrl, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                    })
        }
    }
}

// =====================================================================
// 3. VIDSTACK FAMILY — VidStack-style (extends CloudStream built-in)
// =====================================================================

class Vidshare : VidStack() {
    override var name = "Vidshare"
    override var mainUrl = "https://vidshare.rpmvid.com"
    override var requiresReferer = true
}

class Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class Dm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
}

class IDFL : VidStack() {
    override var name = "IDFL"
    override var mainUrl = "https://idfl.seeks.cloud"
}

class P2pplay : VidStack() {
    override var name = "P2pplay"
    override var mainUrl = "https://nf21.p2pplay.pro"
}

class Playerngefilm21 : VidStack() {
    override var name = "Playerngefilm21"
    override var mainUrl = "https://playerngefilm21.rpmlive.online"
}

// =====================================================================
// 4. GOFILE — API-based CDN resolver
// =====================================================================

open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1)
        val token = AppUtils.tryParseJson<Account>(app.get("$mainApi/createAccount").text)?.data?.get("token")
        val websiteToken = app.get("$mainUrl/dist/js/alljs.js").text.let {
            Regex("""fetchData\.wt\s*=\s*"([^"]+)""").find(it)?.groupValues?.get(1)
        }

        val contents = AppUtils.tryParseJson<Source>(
            app.get("$mainApi/getContent?contentId=$id&token=$token&wt=$websiteToken").text
        )?.data?.contents
        contents?.forEach {
            val link = it.value["link"] ?: return@forEach
            callback(
                newExtractorLink(name, name, link) {
                    this.quality = getQuality(it.value["name"])
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        }
    }

    private fun getQuality(name: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(name ?: "")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Account(@JsonProperty("data") val data: HashMap<String, String>? = null)
    data class Data(@JsonProperty("contents") val contents: HashMap<String, HashMap<String, String>>? = null)
    data class Source(@JsonProperty("data") val data: Data? = null)
}

// =====================================================================
// 5. KOTAKAJAIB — Base64 obfuscation in data-* attribute
// =====================================================================

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val element = document.selectFirst("li[data-frame]") ?: return
        val packed = element.attr("data-frame")

        if (packed.isBlank()) return
        val decoded = try {
            String(android.util.Base64.decode(packed, android.util.Base64.DEFAULT))
        } catch (_: Exception) {
            return
        }

        // decoded typically contains an iframe URL or m3u8
        M3u8Helper.generateM3u8(
            name,
            fixUrl(decoded.trim()),
            referer = "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).forEach(callback)
    }
}

// =====================================================================
// 6. HLSTEREALAYARWIBU — direct m3u8 + Base64 fallback
// =====================================================================

class HlsTereaLayarwibu : ExtractorApi() {
    override val name = "HlsTereaLayarwibu"
    override val mainUrl = "https://hls-terea.layarwibu.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val customUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        val headers = mapOf(
            "User-Agent" to customUserAgent,
            "Referer" to url,
            "Origin" to mainUrl,
            "Sec-Ch-Ua" to """"Not;A=Brand";v="8", "Chromium";v="150", "Google Chrome";v="150"""",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to """"Windows"""",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        var masterUrl: String? = null

        // A. Direct .m3u8
        if (url.endsWith(".m3u8")) {
            masterUrl = url
        }
        // B. Base64 from /player2/ path
        else if (url.contains("/player2/")) {
            val base64String = url.substringAfter("/player2/").substringBefore("?").trim()
            try {
                val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                val decodedUrl = String(decodedBytes, Charsets.UTF_8)
                if (decodedUrl.contains(".m3u8")) {
                    masterUrl = decodedUrl
                }
            } catch (_: Exception) { }
        }

        // C. HTTP fallback
        if (masterUrl == null) {
            val html = app.get(url = url, referer = url, headers = headers).text
            masterUrl = Regex("""https?://[^\s"'<>]+/master\.m3u8""").find(html)?.value
        }

        val finalMasterUrl = masterUrl ?: return

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = finalMasterUrl,
            referer = url,
            headers = headers
        ).forEach(callback)
    }
}

// =====================================================================
// 7. STREAMTAPE WRAPPER — alternate domain
// =====================================================================

class StreamTapeCustom : StreamTape() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.xyz"
}

// =====================================================================
// 8. LULUVDO — specialized StreamWish variant
// =====================================================================

class Luluvdo : StreamWishExtractor() {
    override val name = "Luluvdo"
    override val mainUrl = "https://luluvdo.com"
    override val requiresReferer = true
}

// =====================================================================
// 9. HYDRAX — Hydrax CDN direct extraction
// =====================================================================

class Hydrax : ExtractorApi() {
    override val name = "Hydrax"
    override val mainUrl = "https://playhydrax.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        // Hydrax stores the actual m3u8 link in a script tag
        document.selectFirst("script:containsData(master.m3u8)")?.data()?.let { script ->
            Regex("""https?://[^\s"'<>]+master\.m3u8""").find(script)?.groupValues?.get(0)?.let { m3u8 ->
                M3u8Helper.generateM3u8(
                    name,
                    fixUrl(m3u8),
                    referer = "$mainUrl/",
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).forEach(callback)
                return
            }
        }

        // Fallback: extract from any iframe
        document.selectFirst("iframe")?.attr("src")?.let { iframeUrl ->
            loadExtractor(iframeUrl, referer = "$mainUrl/", subtitleCallback = subtitleCallback, callback = callback)
        }
    }
}