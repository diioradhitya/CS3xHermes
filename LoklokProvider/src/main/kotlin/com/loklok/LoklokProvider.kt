package com.loklok

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Loklok (loklok.my.id) — aggregator drama pendek / film / anime subtitle Indonesia.
 *
 * Struktur: situsnya SPA (Vite) yang talking ke backend sendiri di
 * "/api/indocast/<platform>/". Hanya family indocast yang hidup; family
 * "/api/drama/" (dipakai beranda situs) balas "Could not resolve host:
 * api.dracinku.site" dan "/api/sapi/" + "/api/apinesia/" semuanya disabled.
 *
 * Semua endpoint di sini ditelusuri dari bundle JS situs, bukan ditebak.
 */
class LoklokProvider : MainAPI() {
    override var mainUrl = "https://loklok.my.id"
    override var name = "Loklok"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val api = "https://loklok.my.id/api/indocast"
    private val referer = "https://loklok.my.id/"

    private companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Halaman penuh = 30 kartu (drakorid) / 15 (animekompi) / 20 (filmbox). */
        const val DRAKORID_PAGE = 30
        const val ANIME_PAGE = 15
        const val FILMBOX_PAGE = 20

        /** Batas jumlah halaman yang di-scroll per tab. */
        const val MAX_PAGES = 20

        /** Prefix URL internal; isi = platform|id[|param...]. */
        const val ITEM_PATH = "/d/"

        /**
         * convert.d-cdn.me itu image-resize proxy: `<base64(urlAsli)>/<W>x<H>/1.jpg`.
         * Semua platform bisa dilewatkan ke sini supaya poster seragam.
         */
        const val CONVERT = "https://convert.d-cdn.me/convert"

        /** Rasio 300x420 = 0.71 (potrait 2:3). */
        const val POSTER_SIZE = "300x420"
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------ http

    private fun getText(url: String): String? = try {
        Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()
            .let { http.newCall(it).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }
    } catch (e: Exception) {
        null
    }

    private fun postJson(url: String, body: String): String? = try {
        Request.Builder().url(url)
            .post(body.toRequestBody(jsonMedia))
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()
            .let { http.newCall(it).execute().use { r -> r.body?.string() } }
    } catch (e: Exception) {
        null
    }

    /**
     * Buka amplop respons jadi objek payload.
     *
     * Bentuk umumnya `{"success":true,"data":{"code":0,"message":"ok","data":{...}}}`.
     * Tapi iqiyi/play melingkar dua lapis: `data.data` berisi STRING JSON, jadi
     * harus di-parse lagi. Aturannya: turun ke "data" selama isinya String, atau
     * selama objek sekarang punya "code"/"message" (amplop), atau punya "success".
     */
    private fun payload(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        try {
            var cur: Any? = JSONObject(raw)
            var guard = 0
            while (guard++ < 6) {
                val obj = when (cur) {
                    is String -> try { JSONObject(cur) } catch (e: Exception) { return null }
                    is JSONObject -> cur
                    else -> return null
                }
                if (obj.has("success") && !obj.optBoolean("success")) return null
                val nxt = obj.opt("data")
                val isEnvelope = obj.has("code") || obj.has("message") || obj.has("success")
                if (nxt is String && (isEnvelope || obj.length() == 1)) {
                    cur = nxt
                    continue
                }
                if (nxt is JSONObject && isEnvelope) {
                    cur = nxt
                    continue
                }
                return obj
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun get(path: String): JSONObject? = payload(getText(api + path))

    private fun post(path: String, body: JSONObject): JSONObject? =
        payload(postJson(api + path, body.toString()))

    /**
     * Panggil endpoint /play. Upstream FilmBox (H5 fetch ke CDN) kadang balas
     * "Koneksi Error: read ECONNRESET" atau "Invalid upstream response" secara
     * acak -- dari 8 movie, 4 gagal sekali lalu sisanya berhasil. Jadi
     * coba ulang sebentar sebelum menyerah.
     */
    private suspend fun playRetry(path: String, body: JSONObject? = null): JSONObject? {
        repeat(2) {
            val d = if (body == null) get(path) else post(path, body)
            if (d != null) return d
            Thread.sleep(1200)
        }
        return null
    }

    /** Sama seperti playRetry, untuk request detail (kadang kosong sesaat). */
    private suspend fun getRetry(path: String): JSONObject? {
        repeat(2) {
            val d = get(path)
            if (d != null) return d
            Thread.sleep(1200)
        }
        return null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun abs(u: String): String =
        if (u.startsWith("http")) u else "https://loklok.my.id" + u

    /**
     * Samakan semua poster jadi potrait 300x420 lewat resize proxy.
     *
     * Sumbernya campur: filmbox 0.56-0.80 dengan resolusi 300x450 sampai
     * 3900x5700, drakorid 180x200 (nyaris persegi), iqiyi punya varian
     * potrait & landscape, animekompi 424x600. Di grid aplikasi poster
     * bercampur campur kelihatan rusak, jadi semua dilewatkan ke
     * convert.d-cdn.me dengan ukuran yang sama.
     */
    private fun portrait(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty() || !u.startsWith("http")) return null
        // Sudah lewat convert.d-cdn.me (mis. drakorid) -> ganti ukuran saja.
        if (u.startsWith(CONVERT)) {
            return u.replace(Regex("/\\d+x\\d+/1\\.jpg$"), "/$POSTER_SIZE/1.jpg")
        }
        // android.util.Base64 (API 8+), BUKAN java.util.Base64 (API 26) --
        // plugin ini harus jalan di TV box Android 6 (API 23).
        val b64 = android.util.Base64.encodeToString(
            u.toByteArray(),
            android.util.Base64.URL_SAFE or
                android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING
        )
        return "$CONVERT/$b64/$POSTER_SIZE/1.jpg"
    }

    /** Resolusi -> bitmask track video (dipakai ExtractorLink.quality). */
    private fun qualityOf(q: Int): Int = when {
        q >= 2160 -> Qualities.P2160.value
        q >= 1080 -> Qualities.P1080.value
        q >= 720 -> Qualities.P720.value
        q >= 480 -> Qualities.P480.value
        q >= 360 -> Qualities.P360.value
        q >= 240 -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }

    // ------------------------------------------------------------------ main page

    /**
     * Tab mengikuti apa yang benar-benar ada di backend. Baris "beranda"
     * menampilkan beberapa section sekaligus (pola beranda situs), sisa baris
     * adalah daftar satu per halaman.
     */
    override val mainPage = mainPageOf(
        "beranda|%d" to "Beranda",
        "drakorid|%d" to "Drakor",
        "genre|drama-korea|%d" to "Drama Korea",
        "genre|drama-china|%d" to "Drama China",
        "genre|romance|%d" to "Romance",
        "genre|comedy|%d" to "Comedy",
        "genre|action|%d" to "Action",
        "genre|thriller|%d" to "Thriller",
        "filmbox|1|%d" to "Film",
        "filmbox|2|%d" to "Series",
        "animekompi|%d" to "Anime",
        "iqiyi" to "iQIYI",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("|")
        val kind = parts.getOrElse(0) { "" }
        val p = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: page

        if (kind == "beranda") return homeSections(page)

        return when (kind) {
            "drakorid" -> {
                val d = get("/drakorid/home?page=$p&type=terbaru")
                val items = d?.optJSONArray("items")?.mapDrakorid().orEmpty()
                newHomePageResponse(
                    request.name, items,
                    items.size >= DRAKORID_PAGE && p < MAX_PAGES
                )
            }

            "genre" -> {
                val slug = parts.getOrElse(1) { "" }
                val d = get("/drakorid/kategori/detail?slug=${enc(slug)}&page=$p")
                val items = d?.optJSONArray("items")?.mapDrakorid().orEmpty()
                newHomePageResponse(
                    request.name, items,
                    items.size >= DRAKORID_PAGE && p < MAX_PAGES
                )
            }

            "filmbox" -> {
                val st = parts.getOrElse(1) { "1" }
                val d = get("/filmbox/trending?page=$p&perPage=$FILMBOX_PAGE&subjectType=$st")
                val items = d?.optJSONArray("items")?.mapFilmbox().orEmpty()
                // pager.hasMore dari filmbox selalu true (totalCount 1.000.000),
                // jadi harus kita cap sendiri.
                val more = d?.optJSONObject("pager")?.optBoolean("hasMore", false) ?: false
                newHomePageResponse(request.name, items, more && p < MAX_PAGES)
            }

            "animekompi" -> {
                val d = get("/animekompi/home?page=$p")
                val items = d?.optJSONArray("latest_updates")?.mapAnime().orEmpty()
                newHomePageResponse(
                    request.name, items,
                    items.size >= ANIME_PAGE && p < MAX_PAGES
                )
            }

            "iqiyi" -> {
                val d = get("/iqiyi/trending")
                val items = d?.iqiyiItems().orEmpty()
                newHomePageResponse(request.name, items, false)
            }

            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    /**
     * Beranda: beberapa section, masing-masing satu platform.
     *
     * Tiap halaman scroll mengambil halaman BERIKUTNYA dari tiap platform, jadi
     * scroll tidak mentok di 5 baris pertama. iQIYI tidak punya paginasi
     * (halaman 1 = halaman 2), jadi barisnya cuma di halaman 1.
     */
    private suspend fun homeSections(page: Int): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        val drakor = get("/drakorid/home?page=$page&type=terbaru")
            ?.optJSONArray("items")?.mapDrakorid().orEmpty()
        if (drakor.isNotEmpty()) sections.add(HomePageList("Drakor Terbaru", drakor, true))

        val film = get("/filmbox/trending?page=$page&perPage=$FILMBOX_PAGE&subjectType=1")
            ?.optJSONArray("items")?.mapFilmbox().orEmpty()
        if (film.isNotEmpty()) sections.add(HomePageList("Film Trending", film, true))

        val series = get("/filmbox/trending?page=$page&perPage=$FILMBOX_PAGE&subjectType=2")
            ?.optJSONArray("items")?.mapFilmbox().orEmpty()
        if (series.isNotEmpty()) sections.add(HomePageList("Series Trending", series, true))

        val anime = get("/animekompi/home?page=$page")
            ?.optJSONArray("latest_updates")?.mapAnime().orEmpty()
        if (anime.isNotEmpty()) sections.add(HomePageList("Anime Terbaru", anime, true))

        if (page == 1) {
            val iq = get("/iqiyi/trending")?.iqiyiItems().orEmpty()
            if (iq.isNotEmpty()) sections.add(HomePageList("iQIYI Populer", iq, true))
        }

        val full = drakor.size >= DRAKORID_PAGE ||
            film.size >= FILMBOX_PAGE ||
            series.size >= FILMBOX_PAGE ||
            anime.size >= ANIME_PAGE
        return newHomePageResponse(sections, full && page < MAX_PAGES)
    }

    // ------------------------------------------------------------------ mapping

    private fun org.json.JSONArray?.mapDrakorid(): List<SearchResponse> {
        if (this == null) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            out.add(
                newMovieSearchResponse(title, itemUrl("drakorid", id), TvType.TvSeries) {
                    posterUrl = portrait(o.optString("imageUrl"))
                }
            )
        }
        return out
    }

    private fun org.json.JSONArray?.mapFilmbox(): List<SearchResponse> {
        if (this == null) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val dp = o.optString("detailPath").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            val sid = o.optString("subjectId")
            val st = o.optInt("subjectType", 2)
            val type = if (st == 1) TvType.Movie else TvType.TvSeries
            out.add(
                newMovieSearchResponse(title, itemUrl("filmbox", dp, "sid=$sid", "st=$st"), type) {
                    posterUrl = portrait(o.optString("cover_url"))
                    this.score = Score.from10(o.optDouble("rating", 0.0).takeIf { it > 0 }?.toString())
                }
            )
        }
        return out
    }

    private fun org.json.JSONArray?.mapAnime(): List<SearchResponse> {
        if (this == null) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val path = o.optString("path").ifBlank { continue }
            val title = o.optString("title").ifBlank { continue }
            out.add(
                newMovieSearchResponse(title, itemUrl("animekompi", path), TvType.Anime) {
                    posterUrl = portrait(o.optString("cover_url"))
                    this.score = Score.from10(o.optDouble("rating", 0.0).takeIf { it > 0 }?.toString())
                }
            )
        }
        return out
    }

    /** iqiyi selalu membungkus daftar di sections[].items[]. */
    private fun JSONObject.iqiyiItems(): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val sections = optJSONArray("sections") ?: return out
        for (s in 0 until sections.length()) {
            val items = sections.optJSONObject(s)?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val pd = o.optString("pathdetail").ifBlank { continue }
                val title = o.optString("title").ifBlank { continue }
                // PENTING: hanya varian potrait. Versi landscape (480x270)
                // bikin poster bercampur campur di grid aplikasi, jadi item
                // tanpa potrait dilewati saja.
                val cover = o.optJSONObject("cover")
                val poster = portrait(cover?.optString("potrait")) ?: continue
                val type = if (o.optString("contentType") == "movie") TvType.Movie else TvType.TvSeries
                out.add(
                    newMovieSearchResponse(title, itemUrl("iqiyi", pd), type) {
                        posterUrl = poster
                        this.score = Score.from10(o.optDouble("voteAverage", 0.0).takeIf { it > 0 }?.toString())
                    }
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()

        get("/drakorid/search?q=${enc(q)}&page=1")
            ?.optJSONArray("items")?.mapDrakorid()
            ?.forEach { out[it.name ?: ""] = it }

        try {
            val body = JSONObject()
                .put("keyword", q)
                .put("page", "1")
                .put("perPage", "20")
                .put("subjectType", "2")
            post("/filmbox/search", body)
                ?.optJSONArray("items")?.mapFilmbox()
                ?.forEach { out[it.name ?: ""] = it }
        } catch (e: Exception) {
            // search filmbox bersifat opsional
        }

        get("/iqiyi/search?q=${enc(q)}")?.iqiyiItems()
            ?.forEach { out[it.name ?: ""] = it }

        return out.values.toList()
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse {
        val (platform, id, params) = parseItemUrl(url)
        return when (platform) {
            "drakorid" -> loadDrakorid(id)
            "filmbox" -> loadFilmbox(id, params)
            "animekompi" -> loadAnime(id)
            "iqiyi" -> loadIqiyi(id)
            else -> newMovieLoadResponse("Error", url, TvType.Movie, url)
        }
    }

    private suspend fun loadDrakorid(slug: String): LoadResponse {
        val d = getRetry("/drakorid/detail?slug=${enc(slug)}")
            ?: return newMovieLoadResponse("Error", itemUrl("drakorid", slug), TvType.TvSeries, itemUrl("drakorid", slug))
        val title = d.optString("title").ifBlank { slug }
        val poster = portrait(d.optString("poster"))
        val eps = d.optJSONArray("episodes")
        val episodes = mutableListOf<Episode>()
        if (eps != null) {
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val num = e.optInt("number", i + 1)
                val label = e.optString("episode").ifBlank { "Episode $num" }
                episodes.add(
                    newEpisode(itemUrl("drakorid", slug, "ep=$num")) {
                        this.name = label
                        this.episode = num
                        this.season = 1
                    }
                )
            }
        }
        val details = d.optJSONObject("details")
        val year = yearOf(details?.optString("Release Date") ?: d.optString("releaseDate"))
        return newTvSeriesLoadResponse(title, itemUrl("drakorid", slug), TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = d.optString("synopsis").ifBlank { null }
            this.year = year
            details?.optString("Genres")?.let { g ->
                this.tags = g.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            addScore(details?.optString("Rating")?.takeIf { it.isNotBlank() })
        }
    }

    private suspend fun loadFilmbox(detailPath: String, params: Map<String, String>): LoadResponse {
        val d = getRetry("/filmbox/detail?detailPath=${enc(detailPath)}")
            ?: return newMovieLoadResponse("Error", itemUrl("filmbox", detailPath), TvType.Movie, itemUrl("filmbox", detailPath))
        val title = d.optString("title").ifBlank { detailPath }
        val sid = d.optString("subjectId").ifBlank { params["sid"].orEmpty() }
        val st = d.optInt("subjectType", params["st"]?.toIntOrNull() ?: 2)
        val totalEp = d.optInt("totalEpisode", 0)
        val pageUrl = itemUrl("filmbox", detailPath, "sid=$sid", "st=$st")

        val episodes = mutableListOf<Episode>()
        val seasons = d.optJSONArray("seasons_list")
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val sn = seasons.optJSONObject(s) ?: continue
                val seasonNo = sn.optInt("season", 1)
                val eps = sn.optJSONArray("episodes") ?: continue
                for (i in 0 until eps.length()) {
                    val en = eps.optInt(i, i + 1)
                    episodes.add(
                        newEpisode(itemUrl("filmbox", detailPath, "sid=$sid", "se=$seasonNo", "ep=$en")) {
                            this.name = "Episode $en"
                            this.episode = en
                            this.season = seasonNo
                        }
                    )
                }
            }
        }

        val year = yearOf(d.optString("releaseDate"))
        val genre = d.optString("genre")
        val plot = d.optString("description").ifBlank { null }
        val poster = portrait(d.optString("cover_url"))
        val rating = d.optDouble("rating", 0.0).takeIf { it > 0 }?.toString()

        // Film (subjectType=1) diputar dengan penomoran 0-based: seasons_list
        // berisi episodes:[0] dan endpoint /play hanya menerima se=0&ep=0.
        // Series (subjectType=2) 1-based (episodes:[1..N], se=1&ep=1).
        if (st == 1 || (totalEp <= 1 && episodes.isEmpty())) {
            val movieUrl = itemUrl("filmbox", detailPath, "sid=$sid", "st=$st", "se=0", "ep=0")
            return newMovieLoadResponse(title, movieUrl, TvType.Movie, movieUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                if (genre.isNotBlank()) {
                    this.tags = genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                addScore(rating)
            }
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            if (genre.isNotBlank()) {
                this.tags = genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            addScore(rating)
        }
    }

    private suspend fun loadAnime(path: String): LoadResponse {
        val d = getRetry("/animekompi/detail?path=${enc(path)}")
            ?: return newMovieLoadResponse("Error", itemUrl("animekompi", path), TvType.Anime, itemUrl("animekompi", path))
        val title = d.optString("title").ifBlank { path }
        val eps = d.optJSONArray("episode_list")
        val episodes = mutableListOf<Episode>()
        if (eps != null) {
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val eid = e.optString("episode_id").ifBlank { continue }
                val num = e.optString("episode_number").toIntOrNull() ?: (i + 1)
                episodes.add(
                    newEpisode(itemUrl("animekompi", eid)) {
                        this.name = e.optString("title").ifBlank { "Episode $num" }
                        this.episode = num
                        this.season = 1
                    }
                )
            }
        }
        val genres = d.optJSONArray("genres")
        return newTvSeriesLoadResponse(title, itemUrl("animekompi", path), TvType.Anime, episodes) {
            posterUrl = portrait(d.optString("cover_url"))
            plot = d.optString("synopsis").ifBlank { null }
            if (genres != null && genres.length() > 0) {
                this.tags = (0 until genres.length()).map { genres.optString(it) }.filter { it.isNotEmpty() }
            }
            addScore(d.optDouble("rating", 0.0).takeIf { it > 0 }?.toString())
        }
    }

    private suspend fun loadIqiyi(pathDetail: String): LoadResponse {
        val d = getRetry("/iqiyi/detail?pathplay=${enc(pathDetail)}&lang=id")
            ?: return newMovieLoadResponse("Error", itemUrl("iqiyi", pathDetail), TvType.TvSeries, itemUrl("iqiyi", pathDetail))
        val title = d.optString("title").ifBlank { pathDetail }
        val cover = d.optJSONObject("cover")
        val poster = portrait(cover?.optString("potrait"))

        val episodes = mutableListOf<Episode>()
        val seasons = d.optJSONArray("seasons")
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val sn = seasons.optJSONObject(s) ?: continue
                val seasonNo = sn.optInt("seasonNumber", 1)
                val eps = sn.optJSONArray("episodes") ?: continue
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    val esl = e.optString("slug").ifBlank { continue }
                    val num = e.optInt("episodeNumber", i + 1)
                    episodes.add(
                        newEpisode(itemUrl("iqiyi", esl)) {
                            this.name = e.optString("title").ifBlank { "Episode $num" }
                            this.episode = num
                            this.season = seasonNo
                        }
                    )
                }
            }
        }
        val genres = d.optJSONArray("genres")
        return newTvSeriesLoadResponse(title, itemUrl("iqiyi", pathDetail), TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = d.optString("overview").ifBlank { null }
            this.year = yearOf(d.optString("releaseDate"))
            if (genres != null && genres.length() > 0) {
                this.tags = (0 until genres.length()).map { genres.optString(it) }.filter { it.isNotEmpty() }
            }
            addScore(d.optDouble("voteAverage", 0.0).takeIf { it > 0 }?.toString())
        }
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (platform, id, params) = parseItemUrl(data)
        return when (platform) {
            "drakorid" -> drakoridLinks(id, params, callback)
            "filmbox" -> filmboxLinks(id, params, subtitleCallback, callback)
            "animekompi" -> animeLinks(id, callback)
            "iqiyi" -> iqiyiLinks(id, subtitleCallback, callback)
            else -> false
        }
    }

    private suspend fun drakoridLinks(
        slug: String,
        params: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ep = params["ep"]?.toIntOrNull() ?: 1
        val quality = params["q"]?.takeIf { it.isNotBlank() } ?: "720"
        val body = JSONObject().put("id", slug).put("episode", ep).put("quality", quality)
        val d = playRetry("/drakorid/play", body) ?: return false
        val url = d.optString("vid_url").ifBlank { d.optString("vid_url_proxy") }.ifBlank { return false }
        callback(
            newExtractorLink(name, "Loklok", url) {
                this.referer = referer
                this.type = ExtractorLinkType.M3U8
                this.quality = qualityOf(d.optInt("quality", 0))
            }
        )
        return true
    }

    private suspend fun filmboxLinks(
        detailPath: String,
        params: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sid = params["sid"].orEmpty()
        val season = params["se"]?.toIntOrNull() ?: 1
        val ep = params["ep"]?.toIntOrNull() ?: 1

        // Penomoran filmbox bergantung tipe: film (subjectType=1) 0-based
        // (se=0&ep=0), series (subjectType=2) 1-based (se=1&ep=1). Kalau
        // tebakan pertama kosong, coba penomoran lawannya.
        val se = if (ep == 0) 0 else season
        var d = playRetry(
            "/filmbox/play?subjectId=${enc(sid)}&detailPath=${enc(detailPath)}" +
                "&se=$se&ep=$ep&lang=in_id"
        )
        if (d == null) {
            val altSe = if (se == 0) 1 else 0
            val altEp = if (ep == 0) 1 else 0
            d = playRetry(
                "/filmbox/play?subjectId=${enc(sid)}&detailPath=${enc(detailPath)}" +
                    "&se=$altSe&ep=$altEp&lang=in_id"
            )
        }
        if (d == null) return false

        // Subtitle.
        val subs = d.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val u = s.optString("subUrl_proxy").ifBlank { s.optString("subUrl") }
                if (u.isBlank()) continue
                val label = s.optString("label").ifBlank { s.optString("lang") }.ifBlank { "Indonesia" }
                subtitleCallback(SubtitleFile(label, abs(u)))
            }
        }
        if (subs == null || subs.length() == 0) {
            val su = d.optString("sub_url_proxy").ifBlank { d.optString("sub_url") }
            if (su.isNotBlank()) subtitleCallback(SubtitleFile("Indonesia", abs(su)))
        }

        // CDN asli membalas 429 untuk klien luar -> WAJIB pakai *_proxy.
        // Filmbox bisa mengembalikan url_proxy yang sama untuk beberapa
        // resolusi, jadi dedupe per URL supaya tidak ada server kembar.
        //
        // Resolusi TIDAK ditulis di nama link (nama jadi kembar semua). Resolusi
        // diserahkan ke field quality -> jadi track video di player, jadi
        // pengguna pindah kualitas lewat track, bukan lewat daftar source.
        var emitted = 0
        val seen = mutableSetOf<String>()
        val main = d.optString("vid_url_proxy").ifBlank { d.optString("vid_url") }
        if (main.isNotBlank() && seen.add(main)) {
            callback(
                newExtractorLink(name, "Loklok", main) {
                    this.referer = referer
                    this.type = ExtractorLinkType.VIDEO
                    this.quality = qualityOf(d.optInt("quality", 0))
                }
            )
            emitted++
        }
        val hls = d.optJSONArray("hls")
        if (hls != null) {
            for (i in 0 until hls.length()) {
                val h = hls.optJSONObject(i) ?: continue
                val u = h.optString("url_proxy").ifBlank { h.optString("url") }
                if (u.isBlank() || !seen.add(u)) continue
                val res = h.optString("resolutions")
                callback(
                    newExtractorLink(name, "Loklok", u) {
                        this.referer = referer
                        this.type = ExtractorLinkType.VIDEO
                        this.quality = qualityOf(res.toIntOrNull() ?: 0)
                    }
                )
                emitted++
            }
        }
        return emitted > 0
    }

    private suspend fun animeLinks(episodeId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val d = get("/animekompi/play?episode_id=${enc(episodeId)}") ?: return false
        val servers = d.optJSONArray("servers") ?: return false
        var emitted = 0
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val label = s.optString("name").ifBlank { "Server ${i + 1}" }
            val u = s.optString("url")
            if (u.isNotBlank()) {
                callback(
                    newExtractorLink(label, "Loklok", u) {
                        this.referer = referer
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
                emitted++
            } else {
                // Server tanpa proxy cuma punya embed_url (blogger / abyssplayer).
                // Tetap keluarkan sebagai cadangan supaya ada pilihan lain.
                val embed = s.optString("embed_url")
                if (embed.isNotBlank()) {
                    callback(
                        newExtractorLink(label, "Loklok", embed) {
                            this.referer = referer
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                    emitted++
                }
            }
        }
        return emitted > 0
    }

    private suspend fun iqiyiLinks(
        episodeSlug: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val d = get("/iqiyi/play?slug=${enc(episodeSlug)}&lang=id") ?: return false

        val subs = d.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                // PENTING: pakai URL langsung (subUrl), bukan subUrl_proxy. Proxy
                // /api/iqiyi/subtitle membalas 404 sementara file aslinya (srt)
                // di meta.video.iqiyi.com balas 200 + application/x-subrip.
                val u = s.optString("subUrl").ifBlank { s.optString("subUrl_proxy") }
                if (u.isBlank()) continue
                val label = s.optString("label").ifBlank { s.optString("lang") }.ifBlank { "Indonesia" }
                subtitleCallback(SubtitleFile(label, abs(u)))
            }
        }

        val hlsUrl = d.optString("hls_url").ifBlank { return false }
        callback(
            newExtractorLink(name, "Loklok", hlsUrl) {
                this.referer = referer
                this.type = ExtractorLinkType.M3U8
                this.quality = qualityOf(d.optInt("quality", 0))
            }
        )
        return true
    }

    // ------------------------------------------------------------------ helpers

    private fun itemUrl(platform: String, id: String, vararg params: String): String {
        val q = params.filter { it.isNotBlank() }.joinToString("&")
        return "$mainUrl$ITEM_PATH$platform/$id" + if (q.isNotEmpty()) "?$q" else ""
    }

    /** Balikin (platform, id, params) dari URL internal. */
    private fun parseItemUrl(url: String): Triple<String, String, Map<String, String>> {
        val noQuery = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        val parts = noQuery.split("/").filter { it.isNotEmpty() }
        val platform = parts.getOrElse(parts.size - 2) { "" }
        val id = parts.getOrElse(parts.size - 1) { "" }
        val params = mutableMapOf<String, String>()
        if (query.isNotEmpty()) {
            for (kv in query.split("&")) {
                val k = kv.substringBefore("=")
                val v = kv.substringAfter("=", "")
                if (k.isNotBlank()) params[k] = v
            }
        }
        return Triple(platform, id, params)
    }

    private fun yearOf(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("(19|20)\\d{2}").find(raw) ?: return null
        return m.value.toIntOrNull()
    }
}
