package com.oploverz

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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

        val response = app.get(getEmbedUrl(url), referer = referer)
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

class UpBolt : StreamWishExtractor() {
    override var name = "UpBolt"
    override val mainUrl = "https://upbolt.to"
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