package com.kuronime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "KuroNime"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "page/%d/" to "Anime Terbaru"
    )

    private val apiBase = "https://animeku.org/api/v9/sources"
    private val aesKey = "3&!Z0M,VIZ;dZW=="

    // Homepage cards use class "bsu", search results use "bs" — match both.
    private val cardSelector = "article.bsu, article.bs"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document
        val items = doc.select(cardSelector).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select(cardSelector).mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[itemprop=url]") ?: return null
        val title = (selectFirst("h2[itemprop=headline]")?.text() ?: select(".bsuxtt").text())
            .trim().ifEmpty { return null }
        var href = a.attr("href")
        // Homepage cards point straight to an episode page (/nonton-...).
        // Keep the card tappable: load() will resolve it to the anime page.
        val img = selectFirst("img")
        val poster = img?.attr("src")?.ifBlank { null } ?: img?.attr("data-src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var target = url
        if (target.contains("/nonton-")) {
            // Episode page -> resolve to anime detail page via its breadcrumb link.
            val epDoc = app.get(target).document
            val animeLink = epDoc.selectFirst("a[href*=/anime/]")?.attr("href")
            if (animeLink != null && animeLink.contains("/anime/")) {
                target = animeLink
            }
        }
        val doc = app.get(target).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".main-info .l img")?.attr("src")
        val synopsis = doc.selectFirst(".entry-content .conx")?.text()

        val episodes = doc.select(".bixbox.bxcl ul li").mapNotNull { li ->
            val a = li.selectFirst("span.lchx a[href*=nonton-]") ?: return@mapNotNull null
            val epName = a.text().trim()
            val epUrl = a.attr("href")
            newEpisode(epUrl) { this.name = epName }
        }.reversed()

        return newTvSeriesLoadResponse(title, target, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Extract the _0xa100d42aa blob from script tags
        val scriptTexts = doc.select("script").map { it.html() }.joinToString("\n")
        val blob = Regex("""_0xa100d42aa\s*=\s*["']([^"']+)["']""").find(scriptTexts)
            ?.groupValues?.get(1) ?: return false

        // Call animeku API (requires JSON body — okhttp langsung)
        val body = JSONObject().put("id", blob).toString()
        val response = try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(apiBase)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", mainUrl)
                .addHeader("Referer", "$mainUrl/")
                .build()
            client.newCall(request).execute().use { it.body?.string() ?: "" }
        } catch (e: Exception) {
            return false
        }

        val resp = try {
            JSONObject(response)
        } catch (e: Exception) {
            return false
        }
        if (resp.optInt("status", 0) != 200) return false

        // Decrypt src (main HLS stream)
        var added = false
        val srcEncrypted = resp.optString("src", "")
        val srcSdEncrypted = resp.optString("src_sd", "")

        if (srcEncrypted.isNotEmpty()) {
            try {
                val parsed = JSONObject(decryptCryptoJS(srcEncrypted))
                val hlsUrl = parsed.optString("src", "")
                if (hlsUrl.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            hlsUrl,
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = "$mainUrl/"
                        }
                    )
                    added = true
                }
            } catch (_: Exception) {}
        }

        if (srcSdEncrypted.isNotEmpty()) {
            try {
                val parsed = JSONObject(decryptCryptoJS(srcSdEncrypted))
                val hlsUrl = parsed.optString("src", "")
                if (hlsUrl.isNotEmpty() && hlsUrl != srcEncrypted) {
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            hlsUrl,
                        ) {
                            this.quality = Qualities.P480.value
                            this.referer = "$mainUrl/"
                        }
                    )
                    added = true
                }
            } catch (_: Exception) {}
        }

        return added
    }

    // --- CryptoJS-compatible decryption ---
    // EvpKDF (OpenSSL-style, MD5-based) matching CryptoJS behavior
    private fun evpkdf(password: ByteArray, salt: ByteArray, dklen: Int = 48): ByteArray {
        val derived = ByteArrayOutputStream()
        var prev = byteArrayOf()
        while (derived.size() < dklen) {
            val md = MessageDigest.getInstance("MD5")
            md.update(prev)
            md.update(password)
            md.update(salt)
            prev = md.digest()
            derived.write(prev)
        }
        return derived.toByteArray().copyOf(dklen)
    }

    private fun decryptCryptoJS(envelopeBase64: String): String {
        // envelope is base64-encoded JSON: {"ct":"...", "iv":"...", "s":"..."}
        val jsonStr = String(
            android.util.Base64.decode(envelopeBase64, android.util.Base64.DEFAULT),
            Charsets.UTF_8
        )
        val env = JSONObject(jsonStr)

        val ctBytes = android.util.Base64.decode(env.getString("ct"), android.util.Base64.DEFAULT)
        val ivHex = env.getString("iv")
        val saltHex = env.optString("s", "")

        val keyBytes = aesKey.toByteArray(Charsets.UTF_8)
        val ivBytes: ByteArray
        val actualKey: ByteArray

        if (saltHex.isNotEmpty()) {
            val saltBytes = hexToBytes(saltHex)
            val derived = evpkdf(keyBytes, saltBytes)
            actualKey = derived.copyOf(32)
            ivBytes = derived.copyOfRange(32, 48)
        } else {
            actualKey = keyBytes
            ivBytes = hexToBytes(ivHex)
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(actualKey, "AES"), IvParameterSpec(ivBytes))
        val plain = cipher.doFinal(ctBytes)
        return String(plain, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}