package com.harufilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class HarufilmProvider : MainAPI() {
    override var mainUrl = "https://harufilm.my.id"
    private val apiBase = "https://harufilm-api.romadonilham4.workers.dev/api"
    override var name = "Harufilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "movies" to "Series",
        "movies" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = when (request.name) {
            "Series" -> "series"
            "Anime" -> "anime"
            else -> "movie"
        }
        val url = "$apiBase/movies?category=$category&page=$page"
        val body = app.get(url).text
        val items = parseHaruList(body).mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val href = "$mainUrl/movies/${item.id}"
            val isSeries = item.category?.lowercase() in listOf("series", "anime")
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = item.poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = item.poster
                }
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = app.get("$apiBase/movies?page=1").text
        return parseHaruList(body)
            .filter { it.title?.contains(query, ignoreCase = true) == true }
            .mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val href = "$mainUrl/movies/${item.id}"
                val isSeries = item.category?.lowercase() in listOf("series", "anime")
                if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = item.poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = item.poster }
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        // Support both /movies/<id> (UUID) and /movies/<slug> or /anime/<slug>
        val id = Regex("/movies/([^/]+)").find(url)?.groupValues?.get(1)
        val slug = if (id == null) Regex("/(?:movies|anime|series)/([^/]+)").find(url)?.groupValues?.get(1) else null
        val detail: HaruDetailLite?
        val resolvedApiUrl: String
        if (id != null) {
            detail = parseHaruDetail(app.get("$apiBase/movies/$id").text)
            resolvedApiUrl = "$apiBase/movies/$id"
        } else if (slug != null) {
            detail = parseHaruDetail(app.get("$apiBase/movies/$slug").text)
            resolvedApiUrl = "$apiBase/movies/$slug"
        } else {
            return newMovieLoadResponse("Unknown", url, TvType.Movie, url)
        }
        detail ?: return newMovieLoadResponse("Error", url, TvType.Movie, url)
        val poster = detail.poster ?: detail.backdrop
        val playbackData = resolvedApiUrl
        val epRegex = Regex("""S(\d+)\s*Eps\s*(\d+)""", RegexOption.IGNORE_CASE)
        // Series are identified by per-episode sources (titles like "S1 Eps 1"),
        // NOT by category — anime films have the same category "anime" but a single
        // "HaruStream" source and must be treated as movies (else 0 episodes -> "coming soon").
        val isSeries = detail.sources?.any { epRegex.find(it.title ?: "") != null } == true
        if (isSeries) {
            // Group sources into per-season episode lists
            val seasons = mutableMapOf<String, MutableList<Episode>>()
            detail.sources?.forEach { src ->
                val epNum = epRegex
                    .find(src.title ?: "")?.groupValues?.let { (_, s, e) -> Pair(s.toIntOrNull(), e.toIntOrNull()) }
                val epTitle = src.title ?: return@forEach
                if (epNum == null) return@forEach
                val (sn, en) = epNum
                val seasonKey = "Season $sn"
                val ep = newEpisode(playbackData) {
                    name = epTitle
                    episode = en
                    season = sn
                    posterUrl = poster
                }
                val list = seasons.getOrPut(seasonKey) { mutableListOf() }
                list.add(ep)
            }
            // Build sorted episode list respecting season order
            val episodes = mutableListOf<Episode>()
            val orderedKeys = seasons.keys.sortedWith(compareBy({ it.substringAfter("Season ").toIntOrNull() ?: 0 }))
            orderedKeys.forEach { k ->
                seasons[k]?.let { episodes.addAll(it) }
            }
            return newTvSeriesLoadResponse(detail.title ?: "", url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = detail.plot
                year = detail.year
                addTrailer(detail.trailer)
            }
        } else {
            return newMovieLoadResponse(detail.title ?: "", url, TvType.Movie, playbackData) {
                posterUrl = poster
                plot = detail.plot
                year = detail.year
                addTrailer(detail.trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = API detail URL (seperti /movies/<id>)
        // 1. Normalize: strip extra query param kalau ada (?src=...)
        val apiUrl = data.substringBefore("?")
        val detail = parseHaruDetail(app.get(apiUrl).text)
            ?: return false
        val directRef = "$mainUrl/"

        // Ambil semua embed iframe URLs dari video_sources
        var found = false
        detail.sources?.forEach { src ->
            val iframeSrc = src.embed?.let { e ->
                Regex("src=\"([^\"]+)\"").find(e)?.groupValues?.get(1)
            }
            val url = iframeSrc?.let { httpsify(it) }
            if (!url.isNullOrBlank()) {
                loadExtractor(url, directRef, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }

    private fun parseHaruList(body: String): List<HaruItemLite> {
        val list = mutableListOf<HaruItemLite>()
        try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    HaruItemLite(
                        id = o.optString("id").ifBlank { null },
                        title = o.optString("title").ifBlank { null },
                        category = o.optString("category").ifBlank { null },
                        poster = o.optString("thumbnail_url").ifBlank {
                            o.optString("poster").ifBlank { null }
                        }
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    private fun parseHaruDetail(body: String): HaruDetailLite? {
        return try {
            val o = JSONObject(body)
            val sources = mutableListOf<HaruSourceLite>()
            val srcArr = o.optJSONArray("video_sources") ?: return null
            for (i in 0 until srcArr.length()) {
                val s = srcArr.getJSONObject(i)
                val embed = s.optString("embed_code").ifBlank { null } ?: s.optString("video_url").ifBlank { null }
                sources.add(
                    HaruSourceLite(
                        title = s.optString("title").ifBlank { null },
                        embed = embed
                    )
                )
            }
            HaruDetailLite(
                title = o.optString("title").ifBlank { null },
                category = o.optString("category").ifBlank { null },
                poster = o.optString("thumbnail_url").ifBlank { o.optString("poster").ifBlank { null } },
                backdrop = o.optString("backdrop_url").ifBlank { null },
                plot = o.optString("description").ifBlank { null },
                year = o.optInt("year", 0).takeIf { it > 0 },
                trailer = o.optString("trailer_url").ifBlank { null },
                sources = sources
            )
        } catch (_: Exception) { null }
    }
}

data class HaruItemLite(
    val id: String?,
    val title: String?,
    val category: String?,
    val poster: String?
)

data class HaruSourceLite(
    val title: String?,
    val embed: String?
)

data class HaruDetailLite(
    val title: String?,
    val category: String?,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val year: Int?,
    val trailer: String?,
    val sources: List<HaruSourceLite>
)
