package com.layarasia

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * layarasia.upns.live / *.upns.xyz / *.upns.online / *.upns.pro embed.
 *
 * The whole scheme turned out to be one AES-CBC round trip:
 *   GET /api/v1/video?id=<id>&w=<w>&h=<h>&r=<referrer host>
 * returns hex ciphertext that decrypts to JSON with the m3u8 in "source".
 *
 * The key and IV look obfuscated in the bundle (a string table plus
 * fromCodePoint arithmetic over window.location), but every page I traced
 * through a real browser derives the same pair, so they are constants here:
 *   key = "kiemtienmua911ca"
 *   iv  = "1234567890oiuytr"
 * Confirmed identical across two different video ids.
 *
 * Responses are hex, not base64, and are not always a multiple of 16 bytes,
 * so the trailing partial block is dropped before decrypting (the site does the
 * same by only emitting whole blocks).
 */
class UpnsLive : ExtractorApi() {
    override val name = "UpnsLive"
    override val mainUrl = "https://layarasia.upns.live"
    override val requiresReferer = true

    companion object {
        private const val TAG = "UpnsLive"
        private const val KEY = "kiemtienmua911ca"
        private const val IV = "1234567890oiuytr"
    }

    private fun decrypt(hex: String): String? = try {
        val raw = hex.trim()
        val ct = ByteArray(raw.length / 2) { raw.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // The site only emits whole blocks; drop any trailing partial one.
        val n = ct.size - (ct.size % 16)
        if (n == 0) return null
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        String(cipher.doFinal(ct.copyOf(n)), Charsets.UTF_8).trimStart('﻿')
    } catch (e: Exception) {
        Log.e(TAG, "decrypt failed: ${e.message}")
        null
    }

    /** The id is the fragment: https://host/#xliuij -> xliuij */
    private fun getId(url: String): String? {
        val frag = url.substringAfter("#", "")
        if (frag.isBlank()) return url.substringAfterLast("id=").substringBefore("&").ifBlank { null }
        return frag.substringBefore("&")
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host
    }.getOrNull()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = getId(url)
        if (id.isNullOrBlank()) {
            Log.e(TAG, "no video id in $url")
            return
        }
        val site = hostOf(referer ?: "") ?: mainUrl.removePrefix("https://")
        Log.d(TAG, "=== START id=$id refererHost=$site ===")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to (referer ?: mainUrl)
        )
        val api = "$mainUrl/api/v1/video?id=$id&w=1080&h=2340&r=$site"
        val hex = try {
            app.get(api, headers = headers, referer = referer).text
        } catch (e: Exception) {
            Log.e(TAG, "request failed: ${e.message}")
            return
        }

        val json = decrypt(hex)
        if (json.isNullOrBlank()) {
            Log.e(TAG, "decrypt returned nothing")
            return
        }
        val info = tryParseJson<VideoInfo>(json)
        val stream = info?.source ?: run {
            Log.e(TAG, "no source in payload: ${json.take(160)}")
            return
        }
        Log.d(TAG, "stream: ${stream.take(90)}...")

        callback(
            newExtractorLink(
                name,
                name,
                stream
            ) {
                this.referer = referer ?: mainUrl
                this.headers = mapOf(
                    "Referer" to (referer ?: mainUrl),
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
            }
        )
    }
}

data class VideoInfo(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("cf") val cf: String? = null
)
