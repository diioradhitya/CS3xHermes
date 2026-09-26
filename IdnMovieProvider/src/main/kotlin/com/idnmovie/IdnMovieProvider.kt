package com.idnmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * IDNMovie (idnmovie.com) — film, TV series, anime subtitle Indonesia.
 *
 * situsnya Next.js app, semua data lewat endpoint JSON:
 *   GET /api/catalog?kind={movie|tv}&page=N[&source=idlix] -> { items:[TMDB], hasMore:bool }
 *   GET /api/suggest?q={q}&full=1&page=N                    -> { results:[{id,media,title,...}], totalPages }
 *   GET /api/tv/{tmdbId}/season/{n}                        -> { episodes:[{number,name,still}] }
 *
 * playback diambil dari RSC payload halaman (`self.__next_f.push([1,"..."])`), bentuknya
 * beda per kelas konten:
 *   - sflix (/sfl/{id})  -> "sources"  : /api/dracin/seg?u=<mp4>&r=&ua=b  (proxy MP4)
 *                            "subtitles" : /api/sflix/subtitle?u=<vtt>
 *   - movie (/movie/{s}) -> "playerSrc": embedfilm.com/idx/movie/{slug}    (HLS m3u8)
 *   - anime (/anime/{s}) -> "episodes" : [{number,name,data:{url,referer}}]
 *                            halaman episode -> iframe desustream -> <source> MP4
 */
class IdnMovieProvider : MainAPI() {
    override var mainUrl = "https://idnmovie.com"
    override var name = "IDNMovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private companion object {
        const val QUOTE = "\""
    }

    private val reqHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "movie" to "Movie",
        "tv" to "TV Series",
        "anime" to "Anime",
        "movie_idlix" to "Movie IDLIX",
    )

    // ------------------------------------------------------------------ utils

    private suspend fun json(path: String): JSONObject? = try {
        val t = app.get("$mainUrl$path", headers = reqHeaders).text
        if (t.isBlank()) null else JSONObject(t)
    } catch (e: Exception) {
        null
    }

    private suspend fun html(url: String, referer: String = "$mainUrl/"): String? = try {
        app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).text
    } catch (e: Exception) {
        null
    }

    private fun abs(u: String) = if (u.startsWith("http")) u else mainUrl + u

    private fun posterOf(o: JSONObject): String? {
        o.optString("poster").takeIf { it.isNotBlank() }?.let { return it }
        val raw = o.optString("posterUrlRaw")
        if (raw.isNotBlank() && raw != "null") return raw
        val p = o.optString("poster_path")
        return if (p.isNotBlank() && p != "null") "https://image.tmdb.org/t/p/w500$p" else null
    }

    private fun yearOf(o: JSONObject): Int? =
        o.optString("year").toIntOrNull()
            ?: o.optString("release_date").take(4).toIntOrNull()
            ?: o.optString("first_air_date").take(4).toIntOrNull()

    /** id di sini = URL absolut halaman, mis. https://idnmovie.com/movie/barbie-2023 */
    private fun toSearchResponse(o: JSONObject, fallback: TvType): SearchResponse? {
        val rawId = o.optString("id")
        if (rawId.isBlank()) return null
        val media = o.optString("media")
        val type = when (media) {
            "anime" -> TvType.Anime
            "tv" -> TvType.TvSeries
            "sflix" -> TvType.Movie
            else -> fallback
        }
        val path = when (media) {
            "sflix" -> "/sfl/$rawId"
            "anime" -> "/anime/$rawId"
            "tv" -> "/tv/$rawId"
            else -> "/movie/$rawId"
        }
        val title = o.optString("title").ifBlank { o.optString("name") }
        if (title.isBlank()) return null
        val url = "$mainUrl$path"
        val poster = posterOf(o)
        val year = yearOf(o)
        val score = Score.from10(o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.toString())

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    // --------------------------------------------------------------- catalog

    private suspend fun catalogPage(kind: String, page: Int, source: String?): List<SearchResponse> {
        val src = if (source.isNullOrBlank()) "" else "&source=$source"
        val d = json("/api/catalog?kind=$kind&page=$page$src") ?: return emptyList()
        val items = d.optJSONArray("items") ?: return emptyList()
        val fallback = if (kind == "tv") TvType.TvSeries else TvType.Movie
        val out = ArrayList<SearchResponse>(items.length())
        for (i in 0 until items.length()) toSearchResponse(items.getJSONObject(i), fallback)?.let { out.add(it) }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.name) {
            "TV Series" -> catalogPage("tv", page, null)
            "Movie IDLIX" -> catalogPage("movie", page, "idlix")
            "Anime" -> search("anime").take(20)
            else -> catalogPage("movie", page, null)
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (p in 1..3) {
            val d = json("/api/suggest?q=${query.urlEncoded()}&full=1&page=$p") ?: break
            val res = d.optJSONArray("results") ?: break
            for (i in 0 until res.length()) {
                toSearchResponse(res.getJSONObject(i), TvType.Movie)?.let { out.add(it) }
            }
            if (p >= d.optInt("totalPages", 1)) break
        }
        return out.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- detail

    override suspend fun load(url: String): LoadResponse {
        val path = url.removePrefix(mainUrl)
        val body = html(url)
            ?: return newMovieLoadResponse("Error", url, TvType.Movie, url)
        val text = rsc(body)
            ?: return newMovieLoadResponse("Error", url, TvType.Movie, url)

        val title = text.strAfter("\"title\":\"").decode() ?: url.substringAfterLast("/")
        val poster = listOf("\"posterUrlRaw\":\"", "\"poster_path\":\"", "\"poster\":\"")
            .asSequence()
            .mapNotNull { text.strAfter(it).decode() }
            .map { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w500$it" }
            .firstOrNull { it.isNotBlank() && !it.endsWith("null") }
        val plot = text.strAfter("\"overview\":\"").decode()
        val year = (text.strAfter("\"release_date\":\"").decode() ?: text.strAfter("\"first_air_date\":\"").decode())
            ?.take(4)?.toIntOrNull()
        val score = text.numAfter("\"vote_average\":")?.takeIf { it > 0 }?.toString()

        return when {
            path.startsWith("/anime/") ->
                loadAnime(url, title, poster, plot, year, score, text)
            path.startsWith("/tv/") ->
                loadTv(url, path, title, poster, plot, year, score, text)
            else -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addScore(score)
                recommendations = recsOf(text, TvType.Movie)
            }
        }
    }

    private suspend fun loadTv(
        url: String, path: String, title: String, poster: String?, plot: String?,
        year: Int?, score: String?, text: String,
    ): LoadResponse {
        val tmdbId = Regex("""/tv/(\d+)""").find(path)?.groupValues?.get(1) ?: "0"
        val seasons = text.jsonArray("\"seasons\":")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).optInt("season_number", it + 1) }
        } ?: listOf(1)

        val episodes = mutableListOf<Episode>()
        for (s in seasons) {
            val d = json("/api/tv/$tmdbId/season/$s") ?: continue
            val eps = d.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val num = e.optInt("number", i + 1)
                episodes.add(newEpisode("$url|season=$s|ep=$num") {
                    this.name = e.optString("name").ifBlank { "Episode $num" }
                    this.episode = num
                    this.season = s
                    this.posterUrl = e.optString("still")
                        .takeIf { it.isNotBlank() && !it.startsWith("null") } ?: poster
                })
            }
        }
        if (episodes.isEmpty()) {
            episodes.add(newEpisode("$url|season=1|ep=1") {
                this.name = "Episode 1"
                this.episode = 1
                this.season = 1
                this.posterUrl = poster
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addScore(score)
            recommendations = recsOf(text, TvType.TvSeries)
        }
    }

    private suspend fun loadAnime(
        url: String, title: String, poster: String?, plot: String?,
        year: Int?, score: String?, text: String,
    ): LoadResponse {
        val raw = text.jsonArray("\"episodes\":")
        val episodes = mutableListOf<Episode>()
        if (raw != null) {
            for (i in 0 until raw.length()) {
                val e = raw.getJSONObject(i)
                val num = e.optInt("number", e.optInt("episode", i + 1))
                val data = e.optString("data")
                if (data.isBlank()) continue
                episodes.add(newEpisode("$url|$data") {
                    this.name = e.optString("name").ifBlank { "Episode $num" }
                    this.episode = num
                    this.season = 1
                    this.posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) {
            episodes.add(newEpisode("$url|") {
                this.name = "Episode 1"
                this.episode = 1
                this.season = 1
                this.posterUrl = poster
            })
        }
        // newAnimeLoadResponse punya arity berbeda; newTvSeriesLoadResponse dengan
        // TvType.Anime adalah bentuk yang dipakai KuronimeProvider di repo ini.
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addScore(score)
            recommendations = recsOf(text, TvType.Anime)
        }
    }

    private fun recsOf(text: String, fallback: TvType): List<SearchResponse> {
        val arr = text.jsonArray("\"initialItems\":") ?: return emptyList()
        val out = ArrayList<SearchResponse>(arr.length())
        for (i in 0 until arr.length()) toSearchResponse(arr.getJSONObject(i), fallback)?.let { out.add(it) }
        return out
    }

    // ----------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = data.removePrefix(mainUrl).let { if (it.startsWith("/")) it else "/$it" }

        // --- anime: data = "<page>|<episode json>"
        if (page.startsWith("/anime/")) {
            val parts = data.split("|", limit = 2)
            if (parts.size < 2 || parts[1].isBlank()) return false
            val ep = runCatching { JSONObject(parts[1]) }.getOrNull() ?: return false
            val epUrl = ep.optString("url")
            if (epUrl.isBlank()) return false
            val iframe = html(epUrl, ep.optString("referer").ifBlank { mainUrl })?.iframeSrc()?.let { abs(it) }
                ?: return false
            val stream = html(iframe, epUrl) ?: return false
            val mp4 = stream.sourceSrc()?.let { abs(it) } ?: return false
            callback(newExtractorLink(name, "IDNMovie", mp4) {
                this.referer = iframe
                this.quality = Qualities.P480.value
            })
            return true
        }

        // --- TV: data = "<page>|season=<s>|ep=<e>"
        if (page.startsWith("/tv/")) {
            val parts = data.split("|")
            val season = parts.firstOrNull { it.startsWith("season=") }?.substringAfter("=")?.toIntOrNull() ?: 1
            val ep = parts.firstOrNull { it.startsWith("ep=") }?.substringAfter("=")?.toIntOrNull() ?: 1
            val slug = page.removePrefix("/tv/").substringBeforeLast("/")
            val text = rsc(html("$mainUrl$page") ?: return false) ?: return false
            val idx = text.strAfter("\"idx\":\"").decode() ?: text.strAfter("\"playerSrc\":\"").decode()
            if (idx.isNullOrBlank()) return false
            val embed = embedFilm(idx, "tvseries", slug, season, ep) ?: return false
            return loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
        }

        // --- movie / sflix: RSC "sources" + "subtitles", fallback ke playerSrc
        val text = rsc(html("$mainUrl$page") ?: return false) ?: return false

        val subs = text.jsonArray("\"subtitles\":")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                val u = s.optString("url")
                if (u.isBlank()) continue
                val lang = s.optString("label").ifBlank { s.optString("lang").ifBlank { "Indonesian" } }
                subtitleCallback(SubtitleFile(lang, abs(u)))
            }
        }

        val sources = text.jsonArray("\"sources\":")
        if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val s = sources.getJSONObject(i)
                val u = s.optString("url")
                if (u.isBlank()) continue
                val q = s.optInt("quality", 0)
                callback(newExtractorLink(name, s.optString("name").ifBlank { "Server ${i + 1}" }, abs(u)) {
                    this.referer = mainUrl
                    this.quality = when {
                        q >= 1080 -> Qualities.P1080.value
                        q >= 720 -> Qualities.P720.value
                        q >= 480 -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                })
            }
            return true
        }

        val playerSrc = text.strAfter("\"playerSrc\":\"").decode() ?: return false
        val slug = page.substringAfterLast("/")
        val embed = embedFilm(playerSrc, "movie", slug, null, null) ?: return false
        return loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
    }

    /** embedfilm.com/idx/{type}/{slug}[/{season}/{ep}] */
    private fun embedFilm(src: String, kind: String, slug: String, season: Int?, ep: Int?): String? {
        val origin = src.substringBefore("/idx/").trimEnd('/')
        if (origin.isBlank() || origin == src) return null
        val tail = if (season != null && ep != null) "$kind/$slug/$season/$ep" else "$kind/$slug"
        return "$origin/idx/$tail"
    }

    // ------------------------------------------------------------ RSC helpers

    /** Gabungkan seluruh chunk `self.__next_f.push([1,"..."])` jadi satu payload. */
    private fun rsc(html: String): String? {
        val sb = StringBuilder()
        val re = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)</script>""", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(html)) sb.append(m.groupValues[1])
        if (sb.isEmpty()) return null
        val raw = sb.toString()
        return try {
            JSONTokener(QUOTE + raw + QUOTE).nextValue() as? String ?: raw
        } catch (e: Exception) {
            raw
        }
    }

    /** Teks literal (masih ter-escape) tepat setelah [key]. */
    private fun String.strAfter(key: String): String? {
        var i = indexOf(key)
        if (i < 0) return null
        i += key.length
        if (i >= length || this[i] != '"') return null
        val sb = StringBuilder()
        i++
        while (i < length) {
            val c = this[i]
            if (c == '\\') {
                sb.append(c)
                if (i + 1 < length) { sb.append(this[i + 1]); i++ }
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
            }
            i++
        }
        return null
    }

    private fun String?.decode(): String? = try {
        val s = this ?: return null
        JSONTokener(QUOTE + s + QUOTE).nextValue() as? String
    } catch (e: Exception) {
        null
    }

    /** Parse array/object yang mulai tepat setelah [key]. */
    private fun String?.jsonArray(key: String): JSONArray? {
        val raw = this?.balancedAfter(key) ?: return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }

    private fun String?.numAfter(key: String): Double? {
        val self = this ?: return null
        var i = self.indexOf(key)
        if (i < 0) return null
        i += key.length
        val start = i
        while (i < self.length && (self[i] == '.' || self[i] == '-' || self[i].isDigit())) i++
        return if (i > start) self.substring(start, i).toDoubleOrNull() else null
    }

    private fun String.balancedAfter(key: String): String? {
        var i = indexOf(key)
        if (i < 0) return null
        i += key.length
        if (i >= length) return null
        val open = this[i]
        if (open != '[' && open != '{') return null
        var depth = 0
        var inStr = false
        var esc = false
        val sb = StringBuilder()
        while (i < length) {
            val c = this[i]
            sb.append(c)
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
                        if (depth == 0) return sb.toString()
                    }
                }
            }
            i++
        }
        return null
    }

    private fun String?.iframeSrc(): String? =
        this?.let { Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(it)?.groupValues?.get(1) }

    private fun String?.sourceSrc(): String? =
        this?.let { Regex("""<source[^>]+src=["']([^"']+)["']""").find(it)?.groupValues?.get(1) }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
