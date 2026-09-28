package com.idnmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * IDNMovie (idnmovie.com) — film, TV series, subtitle Indonesia.
 *
 * Katalog (/api/catalog) memberi ID numerik TMDB, tapi halaman ber-ID itu TIDAK punya
 * player. Identitas yang bisa diputar adalah slug, dan slug itu hanya muncul di
 * listing page (/movies, /tv) sebagai kartu {"id":"<slug>", ...}. Karena itu katalog
 * dibangun dari parsing listing, bukan dari /api/catalog.
 *
 * Dua keluarga playback, keduanya sudah diverifikasi live:
 *
 *  1) slug biasa (movie + tv)  ->  embedfilm.com
 *     GET  https://embedfilm.com/idx/movie/{slug}?ui=lorong
 *     GET  https://embedfilm.com/idx/tvseries/{slug}/{season}/{ep}?ui=lorong
 *          -> RSC: playerId (uuid) + playData (JSON string, ter-escape 2 lapis)
 *     POST https://embedfilm.com/{playerId}/api/idx/play
 *          body {"data": "<playData sebagai JSON string>"}   (WAJIB string, objek -> 422)
 *          -> { success, sources:[{url,referer,isM3u8}], subtitles:[{lang,url}] }
 *     URL config-*.json itu master HLS asli, bukan JSON yang perlu di-parse.
 *
 *  2) slug "sfl-{dracinId}"  ->  proxy MP4 milik situs sendiri
 *     GET https://idnmovie.com/sfl/{dracinId}
 *          -> RSC: "sources":[{name,url,quality,isM3u8}]  (url = /api/dracin/seg?u=...)
 *             "subtitles":[{lang,label,url}]            (url = /api/sflix/subtitle?u=...)
 */
class IdnMovieProvider : MainAPI() {
    override var mainUrl = "https://idnmovie.com"
    override var name = "IDNMovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private companion object {
        const val SITE = "https://idnmovie.com"
        const val EMBED = "https://embedfilm.com"
        const val UI = "lorong"
        const val QUOTE = "\""
        /** android's org.json ships in the dex, so no extra dependency is needed */
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------ utils

    private fun getText(url: String, referer: String? = null): String? = try {
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        referer?.let { b.header("Referer", it) }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    } catch (e: Exception) {
        null
    }

    /** JSON POST. app.post() only accepts Map<String,String> form bodies — use OkHttp. */
    private fun postJson(url: String, body: String, referer: String? = null): String? = try {
        val b = Request.Builder().url(url)
            .post(body.toRequestBody(jsonMedia))
            .header("User-Agent", userAgent)
        referer?.let { b.header("Referer", it) }
        http.newCall(b.build()).execute().use { r -> r.body?.string() }
    } catch (e: Exception) {
        null
    }

    private fun abs(u: String) = if (u.startsWith("http")) u else "$mainUrl$u"

    private fun posterOf(raw: String?): String? = when {
        raw.isNullOrBlank() || raw == "null" -> null
        raw.startsWith("http") -> raw
        else -> "https://image.tmdb.org/t/p/w500$raw"
    }

    // ------------------------------------------------------------- RSC helpers

    /** Gabungkan seluruh chunk `self.__next_f.push([1,"..."])` jadi satu payload. */
    private fun rsc(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val sb = StringBuilder()
        val re = Regex("""self\.__next_f\.push\(\[1,\s*"(.*?)"\s*\]\)""", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(html)) sb.append(m.groupValues[1])
        if (sb.isEmpty()) return html
        val raw = sb.toString()
        return try {
            JSONTokener(QUOTE + raw + QUOTE).nextValue() as? String ?: raw
        } catch (e: Exception) {
            raw
        }
    }

    /**
     * String literal tepat setelah field bernama [name].
     *
     * Nama field TANPA kutip: di dalam RSC payload ia bersarang di dalam nilai,
     * mis. `...,\"initialSrc\":null,\"playData\":\"{...}\"`, jadi delimiter
     * sebenarnya adalah `"<name>":"`.
     */
    private fun strAfter(data: String?, name: String): String? {
        if (data == null) return null
        val re = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        val m = re.find(data) ?: return null
        return decode(m.groupValues[1])
    }

    private fun decode(escaped: String?): String? = try {
        if (escaped == null) null else JSONTokener(QUOTE + escaped + QUOTE).nextValue() as? String
    } catch (e: Exception) {
        null
    }

    /** RSC meng-escape payload sampai dua lapis; kupas sampai berhenti. */
    private fun unescapeDeep(value: String?): String? {
        var out = value ?: return null
        var rounds = 0
        while (rounds++ < 3 && out.contains("\\\\")) {
            out = decode(out) ?: break
        }
        return out
    }

    /** Teks JSON (array/object) yang mulai tepat setelah [key]. */
    private fun balancedAfter(data: String?, key: String): String? {
        if (data == null) return null
        var i = data.indexOf(key)
        if (i < 0) return null
        i += key.length
        if (i >= data.length) return null
        val open = data[i]
        if (open != '[' && open != '{') return null
        val start = i
        var depth = 0
        var inStr = false
        var esc = false
        while (i < data.length) {
            val c = data[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return data.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Objek JSON yang DIPERNAHIKAN prefix-nya, dihitung dari `{` pembuka prefix.
     *
     * [balancedAfter] tidak bisa dipakai di sini: ia mulai menghitung di karakter
     * SESUDAH key, sedangkan `"title":` diikuti tanda kutip, bukan `{` — hasilnya
     * selalu null dan seluruh kartu listing ter-skip (homepage kosong).
     */
    private fun objectStartingWith(data: String?, prefix: String): String? {
        if (data == null) return null
        val start = data.indexOf(prefix)
        if (start < 0) return null
        var i = start
        var depth = 0
        var inStr = false
        var esc = false
        while (i < data.length) {
            val c = data[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return data.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun jsonArrayAfter(data: String?, key: String): JSONArray? = try {
        balancedAfter(data, key)?.let { JSONArray(it) }
    } catch (e: Exception) {
        null
    }

    private fun numAfter(data: String?, key: String): Double? {
        if (data == null) return null
        val re = Regex(Regex.escape(key) + "\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        return re.find(data)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    // ---------------------------------------------------------------- listing

    private data class Card(
        val slug: String,
        val title: String,
        val poster: String?,
        val year: Int?,
        val type: TvType,
    )

    /**
     * Kartu listing diparse dari RSC: setiap kartu diawali `{"id":"<slug>","title":"`.
     * `id` di sini SUDAH berupa slug playback — inilah sumber katalog yang benar.
     */
    private fun listingCards(path: String, type: TvType): List<Card> {
        val data = rsc(getText("$mainUrl$path")) ?: return emptyList()
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        val re = Regex("""\{"id":"([a-z0-9\-]{6,})","title":""")
        for (m in re.findAll(data)) {
            val slug = m.groupValues[1]
            if (!seen.add(slug)) continue
            val raw = objectStartingWith(data, """{"id":"$slug","title":""") ?: continue
            val obj = try { JSONObject(raw) } catch (e: Exception) { continue }
            val title = obj.optString("title").ifBlank { slug }
            // kartu sfl tidak punya poster_path; mereka bring posterUrlRaw CDN sendiri
            val posterRaw = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                ?: obj.optString("posterUrlRaw")
            val date = obj.optString("release_date").takeIf { it != "null" }
                ?: obj.optString("first_air_date")
            out.add(
                Card(
                    slug = slug,
                    title = title,
                    poster = posterOf(posterRaw),
                    year = date.take(4).toIntOrNull()
                        ?: obj.optString("year").toIntOrNull(),
                    type = type,
                )
            )
        }
        return out
    }

    /** slug movie + sfl slug digabung; sfl didahulukan agar tidak tertimpa. */
    private fun movieCards(): List<Card> {
        val all = listingCards("/movies", TvType.Movie)
        return all.sortedBy { if (it.slug.startsWith("sfl-")) 0 else 1 }
    }

    /**
     * URL detail. TV harus ke /tv/{slug} — kalau落到 /movie/{slug}, [load] akan
     * mendeteksi "/tv/" tidak ada dan mengembalikan MovieLoadResponse tanpa episode.
     */
    private fun pageUrl(slug: String, type: TvType): String = when {
        slug.startsWith("sfl-") -> "$mainUrl/sfl/${slug.removePrefix("sfl-")}"
        type == TvType.TvSeries -> "$mainUrl/tv/$slug"
        else -> "$mainUrl/movie/$slug"
    }

    private fun cardResponse(c: Card): SearchResponse =
        newMovieSearchResponse(c.title, pageUrl(c.slug, c.type), TvType.Movie) {
            this.posterUrl = c.poster
            this.year = c.year
        }

    // --------------------------------------------------------------- mainpage

    /**
     * Section homepage. Nama diambil dari heading yang benar-benar ada di root page
     * (`/`) — bukan diterka dari URL, karena hampir semua route kandidat 404 dengan
     * halaman shell ~9.5KB tanpa kartu. Semua section di bawah sudah diverifikasi
     * punya kartu: Film Terbaru 72, Populer 36, Update 72, Papan Peringkat 36,
     * Film Indonesia 15, Serial TV 5.
     *
     * Section dibaca dari posisi heading di payload RSC, jadi kartu milik tiap
     * section diambil dari potongan payload sendiri — bukan dari /movies atau /tv.
     */
    private val homeHeadings = listOf(
        "Sorotan",
        "Film Terbaru",
        "Populer",
        "Film Indonesia",
        "Serial TV",
        "Update",
        "Papan Peringkat",
    )

    override val mainPage = mainPageOf(
        "movie" to "Movie",
        "tv" to "TV Series",
        "sorotan" to "Sorotan",
        "terbaru" to "Film Terbaru",
        "populer" to "Populer",
        "indonesia" to "Film Indonesia",
        "serialtv" to "Serial TV",
        "update" to "Update",
        "peringkat" to "Papan Peringkat",
    )

    /** Kartu di antara dua batas karakter pada payload RSC root. */
    private fun cardsInSpan(data: String, from: Int, to: Int): List<Card> {
        val chunk = data.substring(from, to)
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        val re = Regex("""\{"id":"([a-z0-9\-]{6,})","title":"""")
        for (m in re.findAll(chunk)) {
            val slug = m.groupValues[1]
            if (!seen.add(slug)) continue
            val obj = try {
                JSONObject(objectStartingWith(chunk, """{"id":"$slug","title":""") ?: continue)
            } catch (e: Exception) {
                continue
            }
            out.add(
                Card(
                    slug = slug,
                    title = obj.optString("title").ifBlank { slug },
                    poster = posterOf(
                        obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                            ?: obj.optString("posterUrlRaw")
                    ),
                    year = obj.optString("release_date").takeIf { it != "null" }?.take(4)?.toIntOrNull()
                        ?: obj.optString("first_air_date").takeIf { it != "null" }?.take(4)?.toIntOrNull(),
                    // TvSeries hanya kalau slug-nya muncul sebagai /tv/{slug} di root
                    type = if (data.contains("\"/tv/$slug\"")) TvType.TvSeries else TvType.Movie,
                )
            )
        }
        // shape B: slide sorotan/serial — {"href":"/tv/<slug>","src":"...","title":"..."}
        // Beda shape dari katalog, jadi parser terpisah; slug diambil dari href.
        val reB = Regex("""\{"href":"((?:/tv/|/movie/|/sfl/)?([a-z0-9\-]{6,}))","src":""")
        for (m in reB.findAll(chunk)) {
            val href = m.groupValues[1]
            val slug = m.groupValues[2]
            if (!seen.add(slug)) continue
            val obj = try {
                JSONObject(objectStartingWith(chunk, "{\"href\":\"$href\",\"src\":"))
            } catch (e: Exception) {
                continue
            }
            out.add(
                Card(
                    slug = slug,
                    title = obj.optString("title").ifBlank { slug },
                    poster = posterOf(obj.optString("src")),
                    year = obj.optString("year").toIntOrNull(),
                    type = if (href.startsWith("/tv/")) TvType.TvSeries else TvType.Movie,
                )
            )
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.name) {
            "TV Series" -> listingCards("/tv", TvType.TvSeries).map {
                newTvSeriesSearchResponse(it.title, pageUrl(it.slug, TvType.TvSeries), TvType.TvSeries) {
                    this.posterUrl = it.poster
                    this.year = it.year
                }
            }
            "Movie" -> movieCards().map { cardResponse(it) }
            else -> {
                val data = rsc(getText("$mainUrl/")) ?: return newHomePageResponse(request.name, emptyList())
                // urutkan semua heading, lalu tiap section mengambil rentang sampai
                // heading berikutnya — ini yang membatasi "Film Terbaru" agar tidak
                // menelan 72 kartu milik "Update".
                val marks = homeHeadings.mapNotNull { heading ->
                    data.indexOf(heading).takeIf { it >= 0 }?.let { heading to it }
                }.sortedBy { it.second }
                val idx = marks.indexOfFirst { it.first == request.name }
                if (idx < 0) emptyList()
                else {
                    val from = marks[idx].second
                    val to = marks.getOrNull(idx + 1)?.second ?: data.length
                    cardsInSpan(data, from, to).map { cardResponse(it) }
                }
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val txt = getText("$mainUrl/api/suggest?q=$q&full=1&page=1") ?: return emptyList()
        val arr = try { JSONArray(txt) } catch (e: Exception) { null }
        val results = arr?.optJSONObject(0)?.optJSONArray("results")
            ?: try { JSONObject(txt).optJSONArray("results") } catch (e: Exception) { null }
            ?: return emptyList()
        val out = ArrayList<SearchResponse>(results.length())
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val slug = o.optString("id")
            if (slug.isBlank()) continue
            val title = o.optString("title").ifBlank { slug }
            val type = when (o.optString("media")) {
                "tv" -> TvType.TvSeries
                else -> TvType.Movie
            }
            val year = o.optString("year").toIntOrNull()
            val poster = posterOf(o.optString("poster"))
            val url = if (slug.startsWith("sfl-"))
                "$mainUrl/sfl/${slug.removePrefix("sfl-")}" else "$mainUrl/movie/$slug"
            out.add(
                if (type == TvType.TvSeries)
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                else
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
            )
        }
        return out
    }

    // ----------------------------------------------------------------- detail

    override suspend fun load(url: String): LoadResponse {
        val data = rsc(getText(url))
            ?: return newMovieLoadResponse("Error", url, TvType.Movie, url)

        val title = strAfter(data, "title") ?: url.substringAfterLast("/")
        val poster = posterOf(
            strAfter(data, "poster") ?: strAfter(data, "poster_path") ?: strAfter(data, "posterUrlRaw")
        )
        val plot = strAfter(data, "overview")
        val year = (strAfter(data, "release_date") ?: strAfter(data, "first_air_date"))?.take(4)?.toIntOrNull()
        val score = numAfter(data, "vote_average")?.takeIf { it > 0 }?.toString()

        return when {
            url.contains("/tv/") -> loadTv(url, data, title, poster, plot, year, score)
            else -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addScore(score)
            }
        }
    }

    /**
     * Season/episode diambil dari RSC halaman detail. Indeks episode pada RSC
     * dimulai dari 0, sedangkan URL embedfilm memakai 1-based.
     */
    private suspend fun loadTv(
        url: String, data: String, title: String,
        poster: String?, plot: String?, year: Int?, score: String?,
    ): LoadResponse {
        val slug = url.substringAfterLast("/").substringBefore("|")
        val season = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episodes = mutableListOf<Episode>()

        val epsJson = jsonArrayAfter(data, "\"episodes\":")
        if (epsJson != null) {
            for (i in 0 until epsJson.length()) {
                val e = epsJson.getJSONObject(i)
                val num = e.optInt("number", e.optInt("episode", i + 1)).let { if (it > 0) it else i + 1 }
                episodes.add(
                    newEpisode("$url|s=$season|ep=$num") {
                        this.name = e.optString("name").ifBlank { "Episode $num" }
                        this.episode = num
                        this.season = season
                        this.posterUrl = posterOf(e.optString("still")) ?: poster
                    }
                )
            }
        }
        if (episodes.isEmpty()) {
            // Halaman detail tidak pernah mengirim daftar episode — hanya metadata
            // `seasons[]`. Jumlah episode diambil dari `episode_count` per season dan
            // URL playback dibangun lazy oleh loadLinks dari |s=|ep=. Dibatasi 200 per
            // season agar count rusak tidak meledakkan daftar episode.
            val seasonsJson = jsonArrayAfter(data, "\"seasons\":")
            val counts = mutableListOf<Pair<Int, Int>>()   // seasonNumber -> episodeCount
            if (seasonsJson != null) {
                for (i in 0 until seasonsJson.length()) {
                    val s = seasonsJson.optJSONObject(i) ?: continue
                    val sn = s.optInt("season_number", -1)
                    val ec = s.optInt("episode_count", -1)
                    if (sn > 0 && ec in 1..200) counts.add(sn to ec)
                }
            }
            if (counts.isEmpty()) {
                // fallback: baca episode_count pertama yang masuk rentang 1..200
                val ec = Regex(""""episode_count"\s*:\s*(\d+)""").findAll(data)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .firstOrNull { it in 1..200 } ?: 1
                counts.add(season to ec)
            }
            for ((sn, ec) in counts.sortedBy { it.first }) {
                for (n in 1..ec) {
                    episodes.add(
                        newEpisode("$url|s=$sn|ep=$n") {
                            this.name = "Episode $n"
                            this.episode = n
                            this.season = sn
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addScore(score)
        }
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = data.substringBefore("|")
        val isTv = page.contains("/tv/") || data.contains("|s=")
        val slug = page.substringAfterLast("/")

        // PENTING: pageUrl() menaruh sfl sebagai "/sfl/{dracinId}" (prefix "sfl-"
        // sudah dibuang). Jadi deteksi lewat slug.startsWith("sfl-") selalu
        // false dan tiap item sfl jatuh ke loadEmbedLinks yang tidak punya embed
        // -> "No Links Found". Deteksi harus dari segmen path "/sfl/".
        return when {
            isTv -> loadTvLinks(page, slug, data, subtitleCallback, callback)
            page.contains("/sfl/") || slug.startsWith("sfl-") ->
                loadSflLinks(page, subtitleCallback, callback)
            else -> loadEmbedLinks(page, "movie", slug, null, null, subtitleCallback, callback)
        }
    }

    /** Keluarga sfl: MP4 via proxy /api/dracin/seg milik situs. */
    private suspend fun loadSflLinks(
        page: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val data = rsc(getText(page)) ?: return false

        val subs = jsonArrayAfter(data, "\"subtitles\":")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                val u = s.optString("url")
                if (u.isBlank()) continue
                val label = s.optString("label").ifBlank { s.optString("lang") }.ifBlank { "Indonesia" }
                subtitleCallback(SubtitleFile(label, abs(u)))
            }
        }

        val srcs = jsonArrayAfter(data, "\"sources\":")
        if (srcs == null || srcs.length() == 0) return false
        for (i in 0 until srcs.length()) {
            val s = srcs.getJSONObject(i)
            val u = s.optString("url")
            if (u.isBlank()) continue
            val q = s.optInt("quality", 0)
            val isHls = s.optBoolean("isM3u8", false)
            callback(
                newExtractorLink(name, s.optString("name").ifBlank { "SFLIX" }, abs(u)) {
                    this.referer = mainUrl
                    this.quality = qualityOf(q)
                    // Proxy /api/dracin/seg tidak berakhiran ekstensi, jadi ExoPlayer
                    // butuh tipe eksplisit: HLS kalau flag isM3u8, selain itu MP4.
                    this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }
        return true
    }

    /** Keluarga slug: movie & TV lewat embedfilm, HLS langsung. */
    private suspend fun loadEmbedLinks(
        page: String,
        kind: String,
        slug: String,
        season: Int?,
        ep: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val idxUrl = if (kind == "tvseries" && season != null && ep != null)
            "$EMBED/idx/tvseries/$slug/$season/$ep?ui=$UI"
        else
            "$EMBED/idx/movie/$slug?ui=$UI"

        val data = rsc(getText(idxUrl, "$mainUrl/")) ?: return false

        val playerId = strAfter(data, "playerId") ?: return false
        // playData = JSON string yang isinya objek JSON; perlu dua lapis decode
        val playData = strAfter(data, "playData")?.let { unescapeDeep(it) } ?: return false
        val payload = try {
            JSONObject(playData)
        } catch (e: Exception) {
            // masih ter-escape satu lapis
            try { JSONObject(unescapeDeep(playData) ?: playData) } catch (e2: Exception) { return false }
        }
        if (payload.optString("id").isBlank()) return false

        // WAJIB string: {"data": "<json string>"} — mengirim objek memberi 422
        val body = JSONObject().put("data", payload.toString()).toString()
        val txt = postJson("$EMBED/$playerId/api/idx/play", body, "$EMBED/") ?: return false
        val res = try { JSONObject(txt) } catch (e: Exception) { return false }
        if (!res.optBoolean("success")) return false

        val subs = res.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                val u = s.optString("url")
                if (u.isBlank()) continue
                val lang = s.optString("lang").ifBlank { s.optString("label") }.ifBlank { "Indonesia" }
                subtitleCallback(SubtitleFile(lang, u))
            }
        }

        val srcs = res.optJSONArray("sources") ?: return false
        var emitted = 0
        for (i in 0 until srcs.length()) {
            val s = srcs.getJSONObject(i)
            val u = s.optString("url")
            if (u.isBlank()) continue
            val ref = s.optString("referer").ifBlank { "$EMBED/" }
            val q = s.optInt("quality", 0)
            callback(
                newExtractorLink(name, "IDLIX", u) {
                    this.referer = ref
                    this.quality = qualityOf(q)
                    // WAJIB: URL master berakhiran config-*.json, bukan .m3u8.
                    // Tanpa type=M3U8 ExoPlayer memperlakukannya sebagai file video
                    // biasa dan playback gagal ("no links found" di app, padahal
                    // HTTP + isi playlist selalu 200 #EXTM3U).
                    this.type = ExtractorLinkType.M3U8
                }
            )
            emitted++
        }
        return emitted > 0
    }

    private suspend fun loadTvLinks(
        page: String, slug: String, data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val season = Regex("""[?&]s=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val ep = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return loadEmbedLinks(page, "tvseries", slug, season, ep, subtitleCallback, callback)
    }

    private fun qualityOf(q: Int): Int = when {
        q >= 1080 -> Qualities.P1080.value
        q >= 720 -> Qualities.P720.value
        q >= 480 -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}
