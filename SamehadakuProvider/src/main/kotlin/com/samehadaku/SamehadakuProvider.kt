package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Oploverz - katalog anime sub Indo. SUMBER UTAMA: v2.samehadaku.how.
 *
 * Per 23-10-2026 situs oploverz.am sedang migrasi tema/domain (wp-json
 * memakai nama "oploverz.best"): listing /anime/ 404, permalink halaman
 * series massal 404/301 ke post episode, hasil search hanya 10 skeleton
 * JS. Karplugnya sumber browse (home/search/load) dipindah ke
 * v2.samehadaku.how (katalog anime sub Indo hampir sama), sedangkan
 * host Oploverz tetap dipakai sebagai FALLBACK sumber video di
 * loadLinks (post episode /slug-episode-N-subtitle-indonesia/ masih hidup).
 *
 * Markup samehadaku (tema Tailwind, BUKAN Madara - jangan pakai classes
 * eplister/bs/bixbox):
 * - listing       : div.thumb > a[href=post-episode] (poster) bersebelahan
 *                   div.dtla > h2.entry-title > a[href=/anime/<slug>/]
 * - listing path  : /anime-terbaru/[page/N/], root "/" [page/N/]
 *                   (tanpa page/1 - WP responsnya kosong)
 * - search        : /?s=<q> lalu /page/N/?s=<q> -> article.animepost
 *                   (kartu langsung ke post episode; slug episode = slug
 *                   series jadi /anime/<slug> tanpa -episode-N)
 * - halaman series: h1[itemprop=headline] "<Judul> Sub Indo", info rows
 *                   div.flex.justify-between (label span.w-28), baris
 *                   episode <a href="https://host/<slug>-episode-<N>/"
 *                   (link nomor + link judul menunjuk URL yang sama)
 * - film di halaman /anime/ kadang TIDAK punya baris episode (hanya info):
 *                   dataUrl null-able via MovieLoadResponse url fallback.
 * - episode       : div#player-option-N data-embed="&lt;iframe src=&quot;...&quot;&gt;"
 *                   server Blogspot (blogger.com, multi itag) + Mega
 *                   480p/720p/1080p/4K - diparse oleh SamehadakuParser.
 */
class SamehadakuProvider : MainAPI() {
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    /** Situs utama (browse: home/search/load). */
    private val mainHost = "https://v2.samehadaku.how"

    /** Host lama Oploverz - fallback sumber video di loadLinks. */
    private val oplHosts =
            listOf(
                    "https://oploverz.am",
                    "https://oploverz.ch",
                    "https://oploverz.site"
            )

    /** Index host Oploverz yang terakhir berhasil. */
    @Volatile private var oplHostIndex = 0

    override var mainUrl: String = mainHost

    companion object {
        fun getType(t: String): TvType {
            val s = t.trim()
            return when {
                s.contains("OVA", true) || s.contains("Special", true) -> TvType.OVA
                s.contains("Movie", true) || s.contains("Film", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        /** Status bisa English (Ongoing/Completed) atau Indonesia (Sedang Tayang dst). */
        fun getStatus(t: String?): ShowStatus {
            val s = t?.trim().orEmpty()
            return when {
                s.contains("Ongoing", true) ||
                        s.contains("tayang", true) ||
                        s.contains("berjalan", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    // Hanya 2 tab: Terbaru (= /anime-terbaru/) dan Top Minggu.
    // Tab "Latest Episode" dihapus karena isinya sama persis dengan
    // /anime-terbaru/ (sama-sama daftar 10 episode terbaru).
    override val mainPage =
            mainPageOf(
                    "anime-terbaru" to "Terbaru",
                    "top10" to "Top Minggu",
            )

    /**
     * Ambil halaman dari situs utama dengan 1 retry (TLS situs ini kadang
     * memutus koneksi baru; retry berikutnya biasanya sukses).
     *
     * @param marker potongan HTML yang wajib ada supaya response dianggap
     * valid (mencegah 404/placeholder dianggap sukses).
     */
    private suspend fun samehDoc(path: String, marker: String): Document =
            samehDocAny(path, listOf(marker))

    /**
     * Versi multi-marker: lolos kalau SALAH SATU marker ada di HTML.
     * Halaman series punya "headline" (h1[itemprop=headline]) tapi halaman
     * movie/post (mis. /chainsaw-man-reze-hen-movie/) TIDAK punya - hanya
     * h1.entry-title + div#player-option. Marker tunggal "headline" bikin
     * load() throw "marker 'headline' tidak ada" untuk movie.
     */
    private suspend fun samehDocAny(path: String, markers: List<String>): Document {
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                val doc =
                        app.get(mainHost + path, headers = mapOf("User-Agent" to BROWSER_UA))
                                .document
                val html = doc.html()
                if (doc.body() != null && markers.any { html.contains(it, ignoreCase = true) }) {
                    return doc
                }
                lastError = IllegalStateException("marker '${markers.joinToString("/")}' tidak ada: $path")
            } catch (e: Throwable) {
                lastError = e
                // network/TLS gagal: tunggu sebentar lalu coba sekali lagi
                // (perhatikan: error marker TIDAK melempar langsung, di-retry
                // agar Cloudflare transien tidak dianggap halaman hilang)
                if (e is IllegalStateException && e.message?.contains("marker") == true) {
                    delay(500)
                } else {
                    delay(700)
                }
            }
        }
        throw lastError ?: Exception("samehadaku: $path gagal diakses")
    }

    /**
     * Banner/background 16:9 berkualitas.
     * 1) TMDB (api.themoviedb.org) bila [TMDB_API_KEY] diisi - ambil dari
     *    https://www.themoviedb.org/settings/api (gratis), isi konstanta di
     *    bawah. Tanpa key, langkah ini dilewati.
     * 2) AniList GraphQL (tanpa key) bannerImage - kualitas backdrop sama
     *    seperti TMDB, dipakai sebagai fallback otomatis.
     */
    private val TMDB_API_KEY = "0e07042b3e94cd9bbb1901443500693b"

    private suspend fun fetchBanner(title: String, malId: Int?, type: TvType): String? {
        // 1) TMDB bila API key dikonfigurasi
        if (TMDB_API_KEY.isNotBlank()) {
            runCatching { fetchTmdbBackdrop(title, type) }?.getOrNull()?.let { return it }
        }
        // 2) AniList (tanpa key) - bannerImage
        runCatching { fetchAniListBanner(title, malId) }?.getOrNull()?.let { return it }
        return null
    }

    private suspend fun fetchTmdbBackdrop(title: String, type: TvType): String? {
        // Bersihkan judul supaya search TMDB lebih relevan
        // ("Chainsaw Man Reze-hen Movie" -> "Chainsaw Man Reze-hen")
        val clean =
            title.replace(Regex("\\s*(Movie|Film)\\s*$", RegexOption.IGNORE_CASE), "").trim()
        if (clean.isBlank()) return null
        val q = java.net.URLEncoder.encode(clean, "UTF-8")
        val primary =
            if (type == TvType.AnimeMovie || type == TvType.Movie) "movie" else "tv"
        val kinds = listOf(primary, if (primary == "movie") "tv" else "movie")
        // Coba en-US dulu (judul TMDB Inggris), lalu id-ID. Pakai app.get
        // (NiceHttp) agar TLS sama seperti request situs lain yang terbukti jalan di HP.
        for (lang in listOf("en-US", "id-ID")) {
            for (kind in kinds) {
                try {
                    val url =
                        "https://api.themoviedb.org/3/search/$kind" +
                                "?api_key=$TMDB_API_KEY&query=$q&language=$lang&include_adult=false&page=1"
                    val text =
                        withContext(Dispatchers.IO) {
                            app.get(url, headers = mapOf("User-Agent" to BROWSER_UA)).text
                        }
                    val obj = JSONObject(text)
                    val arr = obj.optJSONArray("results") ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val backdrop =
                            item.optString("backdrop_path").takeIf { it.isNotBlank() }
                                ?: continue
                        return "https://image.tmdb.org/t/p/w1280$backdrop"
                    }
                } catch (e: Throwable) {
                    // coba kombinasi berikutnya
                }
            }
        }
        return null
    }

    private suspend fun fetchAniListBanner(title: String, malId: Int?): String? =
            withContext(Dispatchers.IO) {
                val query: String
                val variables: org.json.JSONObject
                if (malId != null) {
                    query =
                        "query (\$id: Int) { Media(idMal: \$id, type: ANIME) { bannerImage } }"
                    variables = JSONObject().put("id", malId)
                } else {
                    val esc = title.replace("\"", "").trim()
                    if (esc.isBlank()) return@withContext null
                    query =
                        "query (\$s: String) { Media(search: \$s, type: ANIME, sort: SEARCH_MATCH) { bannerImage } }"
                    variables = JSONObject().put("s", esc)
                }
                val body = JSONObject()
                    .put("query", query)
                    .put("variables", variables)
                    .toString()
                val conn = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", BROWSER_UA)
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                conn.outputStream.flush()
                conn.outputStream.close()
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val banner = JSONObject(text)
                    .optJSONObject("data")
                    ?.optJSONObject("Media")
                    ?.optString("bannerImage")
                    ?.takeIf { it.isNotBlank() }
                banner
            }

    /**
     * Ambil halaman episode-fallback dari host Oploverz, failover antar
     * domain (alur lama, dipakai untuk embedder bawaan Oploverz).
     */
    private suspend fun oplDoc(path: String, marker: String): Document {
        var lastError: Throwable? = null
        for (offset in oplHosts.indices) {
            val idx = (oplHostIndex + offset) % oplHosts.size
            val host = oplHosts[idx]
            // URL absolut dipakai apa adanya (bukan ditukar host), supaya
            // halaman yang diketahui dari domain lain tidak diambil dari
            // domain yang salah.
            val target = if (path.startsWith("http")) path else host + path
            try {
                val doc = app.get(target, headers = mapOf("User-Agent" to BROWSER_UA)).document
                if (doc.body() != null && doc.html().contains(marker, ignoreCase = true)) {
                    oplHostIndex = idx
                    return doc
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: Exception("oploverz: semua domain gagal diakses")
    }

    private fun normalizeHref(raw: String): String =
            if (raw.startsWith("http")) raw
            else mainHost + if (raw.startsWith("/")) raw else "/" + raw

    /**
     * URL halaman series dari href kartu:
     * - /anime/<slug>/          -> dipakai apa adanya
     * - /<series>-episode-<N>/  -> /anime/<series>/
     */
    private fun seriesUrl(href: String): String? {
        val normalized = normalizeHref(href)
        val path = normalized.substringAfter("://").substringAfter('/')
        if (path.startsWith("anime/")) return "$mainHost/anime/${path.removePrefix("anime/").trim('/')}/"
        val slug = path.trim('/').substringBefore('?').substringBefore('/')
                .replace(Regex("-episode-\\d+.*$"), "").trim('-')
        if (slug.isBlank()) return null
        // Movie hanya punya halaman post /<slug-movie>/, TIDAK punya halaman
        // series /anime/<slug-movie>/ (404 / tanpa marker). Jangan paksa
        // jadi /anime/... - load() akan memproses post langsung sebagai Movie.
        if (slug.contains("-movie", ignoreCase = true)) return normalized
        return "$mainHost/anime/$slug/"
    }

    /** Bersihkan judul dari embel ep / subtitle yang lekat di situs ini. */
    private fun cleanTitle(t: String): String =
            t.replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*(Sub|Subtitle)\\s*(Indonesia|indo)?\\s*$", RegexOption.IGNORE_CASE), "")
                    .trim()

    private fun Element.posterSrc(): String? {
        // Utama src, fallback lazy-load data-src / srcset (situs bisa ganti ke lazy)
        val img = if (tagName().lowercase() == "img") this else selectFirst("img")
        val src =
                img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("srcset")
                                ?.split(',')
                                ?.firstOrNull()
                                ?.substringBefore(' ')
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")?.takeIf { it.isNotBlank() }
        val trimmed = src?.trim()?.ifEmpty { null } ?: return null
        return if (trimmed.startsWith("http")) trimmed else mainHost + (if (trimmed.startsWith("/")) trimmed else "/$trimmed")
    }

    /**
     * Kartu listing /anime-terbaru/ SAJA (Tanpa widget Top 10).
     * Dulu widget Top 10 digabung di sini sehingga tab Terbaru tercampur
     * poster Top 10 (portrait seri yang jarang berubah) dan banner terasa
     * tidak update. Top 10 punya tab sendiri (top10Results).
     * Struktur terverifikasi dari homepage source Samehadaku:
     * li > div.thumb > a[href=post episode] > img -300x169 (still episode,
     * filename berubah per episode) + div.dtla > h2.entry-title > a[href=/anime/...]
     */
    private fun Document.homeResults(): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in select("h2.entry-title > a[href]")) {
            val url = seriesUrl(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            val title = cleanTitle(a.attr("title").ifBlank { a.text() }).ifEmpty { continue }
            val li = a.closest("li") ?: a.parent()
            val imgEl =
                    li?.selectFirst("div.thumb img")
                            ?: a.parents().firstOrNull { it.selectFirst("div.thumb img") != null }
                                    ?.selectFirst("div.thumb img")
            out.add(
                    newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = imgEl?.posterSrc()
                    })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        // Pagination benar untuk kedua tab:
        // - Top Minggu: /top10/ (page 1) dan /top10/page/N/ (page 2+)
        //   Terverifikasi: /top10/page/2/ menampilkan 10 seri BERBEDA
        //   (ranking 11-20), bukan widget yang sama. hasNext harus true
        //   selama halaman tidak kosong supaya CloudStream lanjut scroll.
        // - Terbaru: /anime-terbaru/ dan /anime-terbaru/page/N/
        when (base) {
            "top10" -> {
                val path = if (page > 1) "/top10/page/$page/" else "/top10/"
                val results = runCatching { samehDoc(path, "entry-title").top10Results() }.getOrNull() ?: emptyList()
                return newHomePageResponse(request.name, results, results.isNotEmpty())
            }
            else -> {
                val path = if (page > 1) "/anime-terbaru/page/$page/" else "/anime-terbaru/"
                val results = samehDoc(path, "entry-title").homeResults()
                return newHomePageResponse(request.name, results, results.isNotEmpty())
            }
        }
    }

    // Parse Top 10 section dari root page (.widget_senction.top10-section)
    private fun Document.top10Results(): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val widget = selectFirst("div.widget_senction.top10-section") ?: return out
        for (a in widget.select("a.series[href*=/anime/]")) {
            val url = seriesUrl(a.attr("href")) ?: continue
            val title = cleanTitle(a.attr("title").ifBlank { a.selectFirst("span.judul")?.text() ?: a.text() }).ifEmpty { continue }
            val img = a.selectFirst("img")
            out.add(
                    newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = img?.posterSrc()
                    })
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // 1) Tebakan slug langsung: query "black clover" -> /anime/black-clover/
        //    Kalau halamannya ada, ini hasil paling relevan (listing lengkap
        //    semua episode, bukan cuma episode terbaru seperti lewat search).
        val slug = query.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isNotEmpty()) {
            runCatching {
                val doc = samehDocAny("/anime/$slug/", listOf("headline", "entry-title", "player-option"))
                val name =
                        cleanTitle(
                                doc.selectFirst("h1[itemprop=headline]")?.text()
                                        ?: doc.selectFirst("h1.entry-title")?.text()
                                        ?: doc.selectFirst("h1")?.text()
                                        ?: "")
                if (name.isNotEmpty()) {
                    out.add(
                            newAnimeSearchResponse(
                                    name, "$mainHost/anime/$slug/", TvType.Anime) {
                                this.posterUrl =
                                        doc.selectFirst("div.w-48 img")?.attr("src")
                            })
                    seen.add("$mainHost/anime/$slug/")
                }
            }
        }

        // 2) Pencarian WP: kartu post episode -> dinormalisasi ke /anime/<series>/
        for (page in 1..2) {
            val path = if (page == 1) "/?s=$q" else "/page/$page/?s=$q"
            val doc = runCatching { samehDoc(path, "animepost") }.getOrNull() ?: break
            val before = out.size
            for (card in doc.select("article.animepost")) {
                val a = card.selectFirst("div.animposx > a[href]") ?: continue
                val url = seriesUrl(a.attr("href")) ?: continue
                if (!seen.add(url)) continue
                val title =
                        cleanTitle(a.attr("title").ifBlank { card.selectFirst("h2")?.text() ?: "" })
                                .ifEmpty { continue }
                val type = getType(card.selectFirst("div.type")?.text().orEmpty())
                out.add(
                        newAnimeSearchResponse(title, url, type) {
                            this.posterUrl = card.selectFirst("img")?.attr("src")
                        })
            }
            if (out.size == before) break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val path =
                if (url.startsWith("http"))
                    url.substringAfter("://").substringAfter('/').let { "/$it" }
                else url
        val normPath = if (path.endsWith("/")) path else "$path/"
        val doc =
                try {
                    samehDocAny(normPath, listOf("headline", "entry-title", "player-option"))
                } catch (e: Throwable) {
                    // Kartu lama bisa menunjuk /anime/<slug-movie>/ yang tidak
                    // ada markernya. Fallback ke post /<slug-movie>/.
                    val altPath =
                            if (normPath.startsWith("/anime/")) "/${normPath.removePrefix("/anime/")}"
                            else "/anime/${normPath.trim('/')}/"
                    if (altPath == normPath) throw e
                    samehDocAny(altPath, listOf("headline", "entry-title", "player-option"))
                }

        val title =
                cleanTitle(
                                doc.selectFirst("h1[itemprop=headline]")?.text()
                                        ?: doc.selectFirst("h1.entry-title")?.text()
                                        ?: doc.selectFirst("h1")?.text()
                                        ?: "")
                        .ifEmpty { url.trimEnd('/').substringAfterLast('/') }

        // kartu detail utama: div.card yang punya img poster (w-48 aspect-[3/4])
        val card = doc.select("div.card").firstOrNull { it.selectFirst("img") != null } ?: doc
        val poster = card.selectFirst("img")?.attr("src")?.trim()?.ifEmpty { null }

        // sinopsis: paragraf entry-content di kartu (buang baris "Tonton Juga")
        val plot =
                card.select("div.entry-content p")
                        .map { it.text().trim() }
                        .filter { it.isNotEmpty() && !it.equals("Tonton Juga", true) }
                        .joinToString("\n\n")
                        .trim()
                        .ifEmpty { null }

        // info rows: div.flex.justify-between > span.w-28 (label) + span (nilai)
        var typeText = ""
        var statusText = ""
        for (row in doc.select("div.flex.justify-between")) {
            val label = row.selectFirst("span.w-28")?.text()?.trim() ?: continue
            val value =
                    row.select("span")
                            .firstOrNull { !it.className().contains("w-28") }
                            ?.text()
                            ?.trim()
                            .orEmpty()
            when {
                label.equals("Type", true) -> typeText = value
                label.equals("Status", true) -> statusText = value
            }
        }
        // badge tipe di blok listing bisa jadi fallback label type
        if (typeText.isEmpty()) {
            typeText =
                    doc.select("span.type-badge, div.type")
                            .firstOrNull()
                            ?.text()
                            .orEmpty()
        }
        // Halaman movie/post (mis. /chainsaw-man-reze-hen-movie/) TIDAK punya
        // info Type/Status, TAPI punya div#player-option langsung di halaman
        // itu. Kalau dikenali sebagai movie, paksa AnimeMovie supaya
        // loadLinks dipanggil dengan dataUrl = halaman movie itu sendiri.
        val isMoviePage =
                path.contains("-movie", ignoreCase = true) ||
                        doc.select("div[id^=player-option-]").isNotEmpty()
        val type =
                if (isMoviePage) TvType.AnimeMovie
                else getType(typeText.ifEmpty { if (isMoviePage) "Movie" else "" })

        // baris episode: link ke post "<seriesSlug>-episode-<N>/".
        // Saring dengan prefix slug series supaya widget episode ANIME LAIN di
        // sidebar tidak ikut; link nomor & judul menunjuk URL sama -> dedupe.
        val seriesSlug =
                path.trim('/').removePrefix("anime/").removePrefix("/")
                        .trimEnd('/')
        val epLinks = LinkedHashMap<String, Int>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val m = Regex("""-episode-(\d+)""").find(href) ?: continue
            // tail tanpa trailing slash ("...-s2-episode-3"), bukan string kosong
            val linkSlug = href.removeSuffix("/").substringAfterLast('/')
                    .substringBefore("-episode-")
            if (linkSlug != seriesSlug) continue
            val abs = if (href.startsWith("http")) href else mainHost + href
            // link nomor & judul di baris yang sama menunjuk URL identik
            epLinks[abs] = epLinks[abs] ?: (m.groupValues[1].toIntOrNull() ?: 0)
        }
        val epItems = epLinks.entries.map { it.key to it.value }.sortedBy { it.second }

        val episodes =
                epItems.map { (link, num) ->
                    newEpisode(link) { this.episode = if (num > 0) num else null }
                }

        // rekomendasi: widget sidebar berisi <li> > a.series[href=/anime/] + img
        val recs = mutableListOf<SearchResponse>()
        for (li in doc.select("li")) {
            val a = li.selectFirst("a.series[href*=/anime/]") ?: continue
            val title = a.text().trim().ifEmpty { li.selectFirst("img")?.attr("alt").orEmpty() }
            if (title.isEmpty()) continue
            val rUrl = seriesUrl(a.attr("href")) ?: continue
            if (recs.any { it.url == rUrl } || rUrl == url) continue
            if (recs.size >= 24) break
            recs.add(
                    newAnimeSearchResponse(title, rUrl, TvType.Anime) {
                        this.posterUrl = li.selectFirst("img")?.attr("src")
                    })
        }

        // tema baru tidak memuat tahun rilis eksplisit di info rows; tracker
        // dipasang tanpa year - poster/cover dari tracker yang lebih akurat.
        val year: Int? = null
        val tracker =
                runCatching { APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), null, true) }
                        .getOrNull()

        // Banner 16:9: TMDB (bila API key diisi) else AniList bannerImage,
        // fallback ke cover MAL. Ini yang tampil sebagai background di app.
        val banner = fetchBanner(title, tracker?.malId, type) ?: tracker?.cover

        // Film: kalau halamannya punya baris episode (biasanya 1), jadikan
        // dataUrl supaya loadLinks ambil player dari post episode.
        // Khusus halaman movie/post, dataUrl = url sendiri (player ada di halaman itu).
        if (type == TvType.AnimeMovie || type == TvType.Movie) {
            val dataUrl = if (isMoviePage) url else (epItems.firstOrNull()?.first ?: url)
            return newMovieLoadResponse(name = title, url = url, type = type, dataUrl = dataUrl) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = banner
                this.year = year
                this.plot = plot
                this.tags =
                        doc.select("a[href*=/genre/]").map { it.text().trim() }.distinctBy { it.lowercase() }
                this.recommendations = recs
                runCatching { addMalId(tracker?.malId) }
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster ?: tracker?.image
            backgroundPosterUrl = banner
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(statusText)
            this.plot = plot
            this.tags = doc.select("a[href*=/genre/]").map { it.text().trim() }.distinctBy { it.lowercase() }
            this.recommendations = recs
            runCatching { addMalId(tracker?.malId) }
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = mutableListOf<ExtractorLink>()

        // Normalisasi data menjadi URL penuh
        val normData = if (data.startsWith("http")) data else mainHost + (if (data.startsWith("/")) data else "/$data")

        if (normData.contains("samehadaku.how", ignoreCase = true)) {
            // 1) SUMBER UTAMA: halaman episode samehadaku.
            //    Bedakan URL langsung (file .mp4/.m3u8 yang bisa diputar
            //    ExoPlayer) vs URL embed (halaman HTML yang harus lewat
            //    extractor CloudStream). Embed yang dikembalikan apa adanya
            //    sebagai VIDEO bakal Error 2001 (filedon/pixeldrain) / 3003 (mega).
            //    Dedup by URL file: dua server (mis. embed yang resolve ke
            //    file MP4 yang sama dengan link langsung) tidak boleh tampil
            //    dua kali dengan resolusi sama.
            val seenUrls = mutableSetOf<String>()
            for (server in SamehadakuParser.episodeServers(normData)) {
                val u = server.embedUrl
                when {
                    u.contains("blogger.com", ignoreCase = true) -> {
                        // Pakai extractor Blogger bawaan CloudStream (parse JSON
                        // "streams":[..] di script tag). Extractor regex custom
                        // lama ikut mengambil URL SABR tanpa itag (Error 3003)
                        // dan URL yang kena 403 di ExoPlayer.
                        val collected = mutableListOf<ExtractorLink>()
                        runCatching {
                            loadExtractor(u, server.referer, subtitleCallback) { collected.add(it) }
                        }
                        for (c in collected) {
                            if (!seenUrls.add(c.url)) continue
                            if (c.quality == Qualities.Unknown.value) {
                                c.quality = server.quality
                            }
                            links.add(c)
                        }
                    }
                    u.contains("mega.nz", ignoreCase = true) -> {
                        // Mega terenkripsi AES - TIDAK bisa diputar native.
                        // Tetap ditambahkan terakhir (validasi isFileAlive)
                        // untuk keperluan copy ke downloader, bukan untuk play utama.
                        if (!seenUrls.add(u)) continue
                        links.add(
                                newExtractorLink(
                                        "Samehadaku",
                                        server.sourceName.ifBlank { "Mega" },
                                        server.embedUrl,
                                        ExtractorLinkType.VIDEO) {
                                    this.quality = server.quality
                                    this.referer = server.referer
                                })
                    }
                    // File video langsung: wibufile s0.*.mp4, dsb.
                    u.substringBefore('?').endsWith(".mp4", true) ||
                            u.substringBefore('?').endsWith(".m3u8", true) -> {
                        if (!seenUrls.add(u)) continue
                        links.add(
                                newExtractorLink(
                                        "Samehadaku",
                                        server.sourceName.ifBlank { "Samehadaku" },
                                        u,
                                        if (u.substringBefore('?').endsWith(".m3u8", true))
                                            ExtractorLinkType.M3U8
                                        else ExtractorLinkType.VIDEO) {
                                    this.quality = server.quality
                                    this.referer = server.referer
                                })
                    }
                    // Embed lain (filedon, pixeldrain viewer, api.wibufile embed,
                    // dsb.) -> lewat extractor bawaan CloudStream (Wibufile,
                    // PixelDrain, StreamWish, dsb.). Bila tidak ada extractor
                    // yang cocok (mis. filedon SPA), hasilnya kosong dan link
                    // itu tidak ditampilkan - tidak memicu Error 2001.
                    else -> {
                        val collected = mutableListOf<ExtractorLink>()
                        runCatching {
                            loadExtractor(u, server.referer, subtitleCallback) { collected.add(it) }
                        }
                        for (c in collected) {
                            if (!seenUrls.add(c.url)) continue
                            if (c.quality == Qualities.Unknown.value) {
                                c.quality = server.quality
                            }
                            links.add(c)
                        }
                    }
                }
            }
        }

        if (links.isEmpty()) {
            // 2) FALLBACK / TAMBAHAN: embedder bawaan Oploverz.
            //    - data masih URL oploverz (sisa data lama / alur lama),  ATAU
            //    - halaman episode samehadaku tidak punya server hidup.
            //    Post episode oploverz: /<slug>-episode-<N>-subtitle-indonesia/
            //    (slug & nomor bisa ditebak langsung dari URL episode samehadaku).
            val path = normData.substringAfterLast('/').trim('/').substringBefore('?')
            val slug = path.substringBefore("-episode-").trim('-')
            val num = Regex("-episode-(\\d+)").find(path)?.groupValues?.get(1)
            val host = oplHosts[oplHostIndex]
            val targetUrl =
                    if (data.contains("oploverz", ignoreCase = true)) {
                        // oploverz URL: pakai apa adanya (suffix -subtitle-indonesia
                        // sudah ada di path-nya)
                        data
                    } else if (slug.isNotEmpty()) {
                        "$host/$slug-episode-${num ?: "1"}-subtitle-indonesia/"
                    } else null

            if (targetUrl != null) {
                try {
                    val doc = oplDoc(targetUrl, "iframe")
                    // 2a) mapping episode samehadaku dari judul anime oploverz
                    val parsed = SamehadakuParser.parseOploverzEpisode(doc, targetUrl)
                    if (parsed != null) {
                        val (animeTitle, episodeNumber) = parsed
                        // Batas waktu: jangan menahan UI app > ~20 detik.
                        val sameEpisode =
                                withTimeout(20_000) {
                                    SamehadakuParser.guessEpisode(targetUrl, episodeNumber)
                                            ?: SamehadakuParser.resolveEpisode(animeTitle, episodeNumber)
                                } ?: null
                        if (sameEpisode != null) {
                            for (server in SamehadakuParser.episodeServers(sameEpisode)) {
                                if (links.any { it.url == server.embedUrl }) continue
                                if (server.embedUrl.contains("blogger.com", ignoreCase = true)) {
                                    Blogger().getUrl(server.embedUrl, server.referer) {
                                        links.add(it)
                                    }
                                } else {
                                    links.add(
                                            newExtractorLink(
                                                    "Samehadaku",
                                                    server.label.ifBlank { "Mega" },
                                                    server.embedUrl,
                                                    ExtractorLinkType.VIDEO) {
                                                this.quality = server.quality
                                                this.referer = server.referer
                                            })
                                }
                            }
                        }
                    }
                    // 2b) iframe bawaan halaman episode oploverz (#pembed/
                    //     #embed_holder + src iframe) - upbolt/blogspot/filedon...
                    val seen = links.map { it.url }.toMutableSet()
                    val collect: (ExtractorLink) -> Unit = { link ->
                        if (seen.add(link.url)) links.add(link)
                    }
                    val iframes =
                            doc.select(
                                            "#pembed iframe[src], #embed_holder iframe[src], iframe[src]")
                                    .map { it.attr("src") }
                                    .filter { it.isNotEmpty() }
                                    .distinct()
                    for (iframe in iframes) {
                        // iframe bisa relatif terhadap host oploverz (bukan mainUrl
                        // yang sudah pindah ke samehadaku); absolutkan manual.
                        val abs =
                                if (iframe.startsWith("http")) iframe
                                else {
                                    val h =
                                            targetUrl.substringAfter("://").substringBefore('/')
                                    "https://$h" + (if (iframe.startsWith("/")) iframe else "/$iframe")
                                }
                        loadExtractor(abs, targetUrl, subtitleCallback, collect)
                    }
                } catch (e: Throwable) {
                    // situs fallback mati total -> links dicoba apa adanya
                }
            }
        }

        if (links.isEmpty()) return false

        // 3) Validasi link media SECARA PARALEL (GET Range 1 KB):
        //    - Blogger/UpBolt kadang balas 403/400 (video mati / dioff sesaat).
        //      Link yang mati dibuang dari baris awal daftar supaya sumber
        //      pertama yang ditawarkan app benar-benar bisa diputar.
        //    - Mega.nz dites lewat API mega (lihat Mega.isFileAlive): entry
        //      yang filenya sudah dihapus / inaktif TIDAK ditambahkan lagi,
        //      jadi tidak memicu Error 3003 saat dipilih user. (File Mega
        //      yang masih hidup tetap dikeluarkan di urutan terakhir untuk
        //      keperluan copy manual ke downloader/aplikasi Mega.)
        //    Semua pemeriksaan jalan bersamaan (dulu berurutan: 1 HEAD per
        //    link, bisa >5 detik di box Android sebelum daftar muncul).
        val playable = links.filter { !it.url.contains("mega.nz", ignoreCase = true) }
        val mega = links.filter { it.url.contains("mega.nz", ignoreCase = true) }

        val (alive, dead, megaAlive) =
                coroutineScope {
                    val checkPlayable =
                            playable.sortedByDescending { it.quality }.map { l ->
                                async { l to isMediaLinkAlive(l.url, l.referer) }
                            }
                    val checkMega =
                            mega.sortedByDescending { it.quality }.map { l ->
                                async { l to Mega.isFileAlive(l.url) }
                            }
                    val pl = checkPlayable.awaitAll()
                    val ml = checkMega.awaitAll()
                    Triple(
                            pl.filter { it.second }.map { it.first },
                            pl.filterNot { it.second }.map { it.first },
                            ml.filter { it.second }.map { it.first })
                }

        // Urutan: link hidup (kualitas tertinggi) -> link yang gagal
        // divalidasi (fallback, mis. video Blogger yang keburu diblokir
        // sesaat) -> Mega yang terverifikasi masih hidup.
        for (l in alive + dead + megaAlive) {
            callback(l)
        }
        return true
    }
}
