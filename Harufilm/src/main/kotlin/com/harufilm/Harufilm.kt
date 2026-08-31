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
        @JsonProperty("type") val type: String? = null
    )

    data class HaruResponse(
        @JsonProperty("data") val data: List<HaruItem>? = null,
        @JsonProperty("results") val results: List<HaruItem>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = when(request.data) {
            "movies" -> "movies"
            "series" -> "series"
            "anime" -> "anime"
            else -> "movies"
        }
        val url = "$apiUrl/$endpoint?page=$page"
        val res = app.get(url).parsedSafe<HaruResponse>()
        val list = res?.data ?: res?.results ?: emptyList()

        val items = list.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val href = "$mainUrl/detail/${item.slug ?: item.id}"
            val poster = item.poster
            val isTv = item.type?.lowercase()?.contains("series") == true

            if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        // Placeholder detail dulu
        val title = "Harufilm Title"
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
