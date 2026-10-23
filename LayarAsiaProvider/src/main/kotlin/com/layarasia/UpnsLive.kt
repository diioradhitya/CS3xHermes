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
 * returns hex ciphertext that decrypts to JSON carrying three stream URLs.
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
class UpnsLive(private val label: String = "UpnsLive") : ExtractorApi() {
    // The site runs several upns-family hosts side by side and lists them
    // under its own names ("UPNShare", "StreamP2P"), so the caller passes the
    // mirror label through -- otherwise every one of them shows up as
    // "UpnsLive" and the list reads as duplicate sources.
    override val name = label
    override val mainUrl = "https://layarasia.upns.live"
    override val requiresReferer = true

    companion object {
        private const val TAG = "UpnsLive"
        private const val KEY = "kiemtienmua911ca"
        private const val IV = "1234567890oiuytr"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private const val DEFAULT_REFERER_HOST = "server-1.layar.asia"
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

    /** Scheme + host of the embed URL, falling back to the default host. */
    private fun originOf(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host ?: return mainUrl
        val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        return "$scheme://$host"
    }

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
        // The host rotates per title (layarasia.upns.live, sf21.upns.online,
        // layarasia.upns.xyz, ...) while the /api/v1/video scheme and the
        // AES key/IV stay identical. Everything is served off whichever host
        // the embed URL came from, including the cfNative proxy and the
        // referrer the CDN demands, so take it from [url] rather than mainUrl.
        val origin = originOf(url)
        val site = hostOf(referer ?: "") ?: DEFAULT_REFERER_HOST
        Log.d(TAG, "=== START origin=$origin id=$id refererHost=$site ===")

        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to (referer ?: origin)
        )
        val api = "$origin/api/v1/video?id=$id&w=1080&h=2340&r=$site"
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

        // The payload offers three URLs and they are not interchangeable:
        //   source   a bare IP over plain HTTP, the in-house P2P origin
        //   cf       the zenlith Cloudflare worker
        //   cfNative the site's own proxy for that worker
        // All three 403 unless the request carries this host as its referrer --
        // sending the episode page instead yields 403 and ExoPlayer surfaces it
        // as ERROR_CODE_REMOTE_ERROR (2004). cfNative is also plain HTTPS with
        // the k/kx token baked into its variant URLs, so it needs no headers at
        // all and survives being resolved by the player.
        val stream = info?.cfNative ?: info?.cf ?: info?.source ?: run {
            Log.e(TAG, "no stream url in payload: ${json.take(160)}")
            return
        }
        Log.d(TAG, "stream: ${stream.take(90)}...")

        callback(
            newExtractorLink(
                name,
                name,
                stream
            ) {
                this.referer = origin
                this.headers = mapOf(
                    "Referer" to origin,
                    "User-Agent" to UA
                )
            }
        )
    }
}

data class VideoInfo(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("cf") val cf: String? = null,
    @JsonProperty("cfNative") val cfNative: String? = null
)
