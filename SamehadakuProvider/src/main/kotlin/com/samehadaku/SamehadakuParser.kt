package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.Jsoup

/**
 * Parser server video untuk samehadaku.how
 *
 * Samehadaku menyediakan beberapa server per episode:
 *   - "Blogspot"  -> iframe blogger.com (bisa 360p/720p/1080p)
 *   - "VIP STREAMING" -> iframe filedon.co
 *   - "Mega 480p" / "Mega 720p" / "Mega 1080p" / "Mega 4K" -> mega.nz/embed/...
 *
 * Modul ini mengubah markup halaman episode (div#player-option-N) menjadi
 * daftar server yang bisa dipakai provider.
 */
internal object SamehadakuParser {
    const val BASE = "https://v2.samehadaku.how"

    data class Server(
            val label: String,
            val embedUrl: String,
            val referer: String
    ) {
        /** True kalau server ini mega.nz (butuh extractor yang tidak ada di CloudStream). */
        val isMega: Boolean
            get() = embedUrl.contains("mega.nz", ignoreCase = true)

        /**
         * Nama sumber SAJA tanpa resolusi ("Wibufile", "Mega").
         * CloudStream menampilkan `name + quality`, jadi kalau name masih
         * "Wibufile 720p" dan quality=720 tampil "Wibufile 720p 720p".
         */
        val sourceName: String
            get() =
                    label.replace(
                                    Regex(
                                            """\s*(4k|2160p?|2k|1440p?|1080p?|720p?|480p?|360p?|uhd|qhd|full\s?hd|mp4\s?hd|hd)\s*$""",
                                            RegexOption.IGNORE_CASE),
                                    "")
                            .trim()
                            .ifBlank { "Samehadaku" }

        /**
         * Resolusi untuk track/submenu di player CloudStream.
         * [com.lagradost.cloudstream3.utils.ExtractorLink.quality] dibaca ExoPlayer
         * sebagai track video terpisah - WAJIB beda per resolusi (480/720/1080/1440/2160),
         * kalau semua Unknown maka player hanya menampilkan 1 track.
         * Urutan dicek dari tertinggi, agar "2160" tidak ketangkap sebagai "160".
         */
        val quality: Int
            get() =
                    when {
                        label.contains("4k", true) ||
                                label.contains("2160", true) ||
                                label.contains("uhd", true) -> Qualities.P2160.value
                        label.contains("2k", true) ||
                                label.contains("1440", true) ||
                                label.contains("qhd", true) -> Qualities.P1440.value
                        label.contains("1080", true) ||
                                label.contains("fullhd", true) -> Qualities.P1080.value
                        label.contains("720", true) ||
                                label.contains("mp4hd", true) ||
                                label.contains("hd", true) -> Qualities.P720.value
                        label.contains("480", true) -> Qualities.P480.value
                        label.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
    }

    /** Parse daftar server (Blogger + Mega) dari halaman episode Samehadaku. */
    suspend fun episodeServers(episodeUrl: String): List<Server> {
        // Normalisasi URL agar selalu lengkap dengan scheme + host
        val url = if (episodeUrl.startsWith("http")) episodeUrl
            else BASE + (if (episodeUrl.startsWith("/")) episodeUrl else "/$episodeUrl")
        var html: String = ""
        try {
            var lastError: Throwable? = null
            for (attempt in 0..2) {
                try {
                    html = app.get(url, headers = mapOf("User-Agent" to BROWSER_UA)).text
                    break
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < 2) delay(700)
                    else throw e
                }
            }
        } catch (e: Throwable) {
            return emptyList()
        }
        val out = mutableListOf<Server>()
        // Pakai jsoup supaya tahan terhadap HTML multi-baris seperti:
        // <div id="player-option-1"
        //      data-embed="...">
        val doc = Jsoup.parse(html)
        for (el in doc.select("div[id^=player-option-]")) {
            val embed = el.attr("data-embed")
            if (embed.isBlank()) continue
            // nilai atribut di-escape sebagai HTML entity: <iframe src="...">
            // Juga ada numeric entity &#038; (= &) yang harus didecode.
            val embedHtml =
                    embed.replace("&quot;", "\"")
                            .replace("&#039;", "'")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
                            .replace("&#038;", "&")
            // data-embed berisi iframe HTML-escaped: <iframe src="URL">
            val src =
                    Regex("""src="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: Regex("""src='([^']+)'""").find(embedHtml)?.groupValues?.get(1)
                            ?: continue
            val url = src.trim().replace("&", "&")
            if (url.isEmpty()) continue
            // Label server = teks PENUH di dalam div (mis. "Wibufile 720p",
            // "Mega 1080p"). Dulu hanya kata pertama ("Wibufile") yang dipakai
            // sehingga quality selalu Unknown dan resolusi tidak terpisah
            // jadi track di player. Sekarang label penuh disimpan.
            val fullText =
                    el.text()
                            .replace("&nbsp;", " ")
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                            .trim('"', '\'', ',', ';', ':')
            // Ambil "Server Resolusi" saja (2 kata), buang sisa teks lain.
            val label =
                    Regex("""(?i)^(.*?)\s*(4k|2160p?|2k|1440p?|1080p?|720p?|480p?|360p?|uhd|qhd|full\s?hd|mp4\s?hd|hd)\b""")
                            .find(fullText)
                            ?.groupValues
                            ?.let { (it.getOrNull(1).orEmpty() + " " + it.getOrNull(2).orEmpty()).trim() }
                            ?.ifBlank { fullText }
                            ?: fullText.takeIf { it.isNotEmpty() }
                            ?: "Samehadaku"
            if (out.any { it.embedUrl == url }) continue
            out.add(Server(label.ifBlank { "Samehadaku" }, url, episodeUrl))
        }
        return out
    }
}