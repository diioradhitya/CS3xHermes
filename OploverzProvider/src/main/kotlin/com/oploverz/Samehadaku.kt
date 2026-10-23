package com.oploverz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Sumber video alternatif: samehadaku.how
 *
 * Situs Oploverz hanya menyediakan satu embedder per episode (Blogger 360p
 * atau UpBolt 360p/480p). Samehadaku menyediakan beberapa server:
 *   - "Blogspot"  -> iframe blogger.com (bisa 360p/720p/1080p)
 *   - "Mega 480p" / "Mega 720p" / "Mega 1080p" -> mega.nz/embed/...
 *
 * Modul ini memetakan episode Oploverz ke episode Samehadaku berdasarkan
 * judul anime + nomor episode, lalu mengembalikan daftar server yang tersedia.
 */
internal object Samehadaku {
    const val BASE = "https://v2.samehadaku.how"

    data class Server(
            val label: String,
            val embedUrl: String,
            val referer: String
    ) {
        /** True kalau server ini mega.nz (butuh extractor yang tidak ada di CloudStream). */
        val isMega: Boolean
            get() = embedUrl.contains("mega.nz", ignoreCase = true)

        val quality: Int
            get() =
                    when {
                        label.contains("4k", true) -> Qualities.P2160.value
                        label.contains("2160", true) -> Qualities.P2160.value
                        label.contains("1080", true) -> Qualities.P1080.value
                        label.contains("720", true) -> Qualities.P720.value
                        label.contains("480", true) -> Qualities.P480.value
                        label.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
    }

    /** Ambil judul anime + nomor episode dari halaman episode Oploverz. */
    fun parseOploverzEpisode(doc: org.jsoup.nodes.Document, url: String): Pair<String, Int>? {
        val h1 = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        // Judul Oploverz: "Bleach Episode 373 Subtitle Indonesia"
        val m = Regex("""^(.*?)\s+Episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(h1)
        if (m != null) {
            val title = m.groupValues[1].trim()
            val num = m.groupValues[2].toIntOrNull()
            if (title.isNotEmpty() && num != null) return title to num
        }
        // Fallback: dari URL episode
        val um = Regex("""-episode-(\d+)""").find(url)
        val num = um?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val slug = url.substringAfterLast("/").substringBefore("-episode-")
        val title = slug.replace("-", " ").trim()
        if (title.isEmpty()) return null
        return title to num
    }


    /**
     * Tebak cepat URL episode samehadaku dari slug episode Oploverz.
     * Oploverz: "/<slug>-episode-<N>-subtitle-indonesia/"
     * Samehadaku: "/<slug>-episode-<N>/"
     * Kalau slug-nya sama (umumnya), 1 request cukup - tidak perlu pencarian.
     */
    suspend fun guessEpisode(oploverzUrl: String, number: Int): String? {
        val path = oploverzUrl.substringAfterLast("/").substringBefore("?")
        val slug = path.substringBefore("-episode-").trim('-')
        if (slug.isEmpty()) return null
        for (n in listOf(number.toString(), number.toString().padStart(2, '0'))) {
            val guess = "$BASE/$slug-episode-$n/"
            try {
                val body = app.get(guess, headers = mapOf("User-Agent" to BROWSER_UA)).text
                if (body.contains("player-option")) return guess
            } catch (e: Throwable) {
                // slug berbeda -> caller pakai jalur pencarian
            }
        }
        return null
    }

    /** Cari URL halaman episode di Samehadaku. Null kalau tidak ada. */
    suspend fun resolveEpisode(title: String, number: Int): String? {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val searchHtml =
                try {
                    app.get("$BASE/?s=$q", headers = mapOf("User-Agent" to BROWSER_UA)).text
                } catch (e: Throwable) {
                    return null
                }
        val links =
                Regex("""href="(https://v2\.samehadaku\.how/[^"#?]+)"""")
                        .findAll(searchHtml)
                        .map { it.groupValues[1] }
                        .toList()
                        .distinct()

        // 1) hasil pencarian langsung ke halaman episode
        for (l in links) {
            val n = episodeNumber(l) ?: continue
            if (n == number) return l
        }
        // 2) lewat halaman anime: cari episode bernomor sama
        for (anime in links.filter { it.contains("/anime/") }.take(3)) {
            val ep = findEpisodeInAnime(anime, number)
            if (ep != null) return ep
        }
        // 3) tebak slug episode langsung
        val slug = slugify(title)
        val guess = "$BASE/$slug-episode-$number/"
        return try {
            val r = app.get(guess, headers = mapOf("User-Agent" to BROWSER_UA))
            if (r.text.contains("player-option")) guess else null
        } catch (e: Throwable) {
            null
        }
    }

    private suspend fun findEpisodeInAnime(animeUrl: String, number: Int): String? {
        val html =
                try {
                    app.get(animeUrl, headers = mapOf("User-Agent" to BROWSER_UA)).text
                } catch (e: Throwable) {
                    return null
                }
        val links =
                Regex("""href="(https://v2\.samehadaku\.how/[^"#?]+-episode-\d+/)"""")
                        .findAll(html)
                        .map { it.groupValues[1] }
                        .toList()
                        .distinct()
        for (l in links) {
            if (episodeNumber(l) == number) return l
        }
        return null
    }

    private fun episodeNumber(url: String): Int? =
            Regex("""-episode-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun slugify(title: String): String =
            title
                    .lowercase()
                    .replace(Regex("""[^a-z0-9]+"""), "-")
                    .trim('-')

    /** Parse daftar server (Blogger + Mega) dari halaman episode Samehadaku. */
    suspend fun episodeServers(episodeUrl: String): List<Server> {
        val html =
                try {
                    app.get(episodeUrl, headers = mapOf("User-Agent" to BROWSER_UA)).text
                } catch (e: Throwable) {
                    return emptyList()
                }
        val out = mutableListOf<Server>()
        // <div id="player-option-N" class="east_player_option ..." data-embed="&lt;iframe src=&quot;...&quot;&gt;">
        val tagRe = Regex("""<div[^>]*id="player-option-\d+"[^>]*>""", RegexOption.IGNORE_CASE)
        for (m in tagRe.findAll(html)) {
            val tag = m.groupValues[0]
            val embed =
                    Regex("""data-embed="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
            if (embed.isBlank()) continue
            // nilai atribut di-escape sebagai HTML entity: &lt;iframe src=&quot;...&quot;&gt;
            val embedHtml =
                    embed.replace("&quot;", "\"")
                            .replace("&#039;", "'")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
            // data-embed berisi iframe HTML-escaped: &lt;iframe src=&quot;URL&quot;&gt;
            val src =
                    Regex("""src="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: Regex("""src='([^']+)'""").find(embedHtml)?.groupValues?.get(1)
                            ?: continue
            val url = src.trim().replace("&amp;", "&")
            if (url.isEmpty()) continue
            // Label server = teks di dalam div (mis. "Mega 1080p").
            // Markup antar server bisa berbeda (span, span bersarang, atau
            // teks langsung), jadi semua tag di dalam div dibuang dulu.
            val closeAt = html.indexOf("</div>", m.range.last).let { if (it < 0) m.range.last + 600 else it }
            val inner = html.substring(m.range.last + 1, (closeAt).coerceAtMost(html.length))
            val label =
                    inner.replace(Regex("""<[^>]*>"""), " ")
                            .replace("&nbsp;", " ")
                            .trim()
                            .split(Regex("""\\s+"""))
                            .firstOrNull { it.isNotEmpty() }
                            .orEmpty()
                            .trim('"', '\'', ',', ';', ':')
            if (out.any { it.embedUrl == url }) continue
            out.add(Server(label.ifBlank { "Samehadaku" }, url, episodeUrl))
        }
        return out
    }
}
