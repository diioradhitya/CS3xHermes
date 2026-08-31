package com.harufilm

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Harufilm : MainAPI() {
    override var mainUrl = "https://harufilm.my.id"
    private val apiUrl = "https://harufilm-api.romadonilham4.workers.dev/api"
    override var name = "Harufilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "series" to "Series",
        "anime" to "Anime"
    )

    data class HaruItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("thumbnail_url") val thumbnail: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("video_sources") val sources: List<HaruSource>? = null
    )

    data class HaruSource(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("video_url") val video_url: String? = null,
        @JsonProperty("embed_code") val embed_code: String? = null
    )

    data class HaruResponse(
        @JsonProperty("data") val data: List<HaruItem>? = null,
        @JsonProperty("results") val results: List<HaruItem>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = request.data
        val url = "$apiUrl/$endpoint?page=$page"
        val res = app.get(url).parsedSafe<HaruResponse>()
        val list = res?.data ?: res?.results ?: emptyList()

        val items = list.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val href = "$apiUrl/movies/${item.id}"
            
            if (item.type?.contains("series", true) == true) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = item.poster ?: item.thumbnail
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = item.poster ?: item.thumbnail
                }
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val item = app.get(url).parsed<HaruItem>()
        
        return if (item.type?.contains("series", true) == true) {
            newTvSeriesLoadResponse(item.title ?: "", url, TvType.TvSeries, emptyList()) {
                posterUrl = item.poster ?: item.thumbnail
                plot = item.description
                year = item.year
            }
        } else {
            newMovieLoadResponse(item.title ?: "", url, TvType.Movie, url) {
                posterUrl = item.poster ?: item.thumbnail
                plot = item.description
                year = item.year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = app.get(data).parsed<HaruItem>()
        item.sources?.forEach { source ->
            source.embed_code?.let { code ->
                val src = Regex("src=\"(.*?)\"").find(code)?.groupValues?.get(1)
                if (src != null) {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
