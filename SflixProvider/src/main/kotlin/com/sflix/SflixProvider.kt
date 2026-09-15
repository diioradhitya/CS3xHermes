package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * SFlix — full scraper untuk insflix.biz / animesflix.biz
 * Movie: /movies/{slug}-x{tmdbId}z/  → play page berisi <video><source label=480p/720p/1080p>
 * TV:    /shows/{tmdbId}/{slug}/      → season select + episode list (per-season page)
 * Search: /?s={query}                 → li.TPostMv (tab movie & tv otomatis)
 * Tidak lagi pakai TMDB API maupun iframe mirror.
 */
class SflixProvider : MainAPI() {
    override var mainUrl = "https://animesflix.biz"
    override var name = "SFlix"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "popular-movies/page/%d/" to "Popular Movies",
        "popular-tv-shows/page/%d/" to "Popular TV Shows",
        "top-rated-movies/page/%d/" to "Top Rated Movies",
        "upcoming-movies/page/%d/" to "Upcoming Movies",
        "featured-movies/page/%d/" to "Featured Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data.format(page)}").document
        val items = doc.select("li.TPostMv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("li.TPostMv").mapNotNull { it.toSearchResult() }
    }

    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href")
        if (href.isBlank()) return null
        val title = selectFirst("h2.Title")?.text()?.trim() ?: return null
        val poster = selectFirst("img[data-src]")?.attr("data-src")
            ?: selectFirst("img[src]")?.attr("src")
        val year = selectFirst(".Year")?.text()?.trim()?.toIntOrNull()

        val isTv = href.contains("/shows/")
        val type = if (isTv) TvType.TvSeries else TvType.Movie
        return if (isTv) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ============================================================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.Title")?.text()?.trim()
            ?: doc.selectFirst("h2.Title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst(".TPost .Image img[data-src]")?.attr("data-src")
            ?: doc.selectFirst("img[data-src]")?.attr("data-src")
        val plot = doc.selectFirst(".Description")?.text()?.trim()
            ?.replace(Regex("""Online Free at sflix\.""", RegexOption.IGNORE_CASE), "")
            ?.trim()
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }
        val rating = doc.selectFirst("span[class*=post-ratings]")?.text()?.trim()

        return if (url.contains("/shows/")) {
            loadTv(url, doc, title, poster, plot, tags, rating)
        } else {
            val playUrl = url.trimEnd('/') + "/play/"
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private suspend fun loadTv(
        url: String,
        doc: Element,
        title: String,
        poster: String?,
        plot: String?,
        tags: List<String>,
        rating: String?
    ): LoadResponse {
        // Season list dari <select> — halaman utama biasanya menampilkan season terakhir
        val seasonUrls = doc.select("select[name=Seasons] option").mapNotNull { opt ->
            val v = opt.attr("value")
            if (v.isBlank()) null else v
        }
        val episodes = mutableListOf<Episode>()

        // Season yang sedang aktif (halaman ini) — ambil episode-nya langsung
        doc.select("ul.all-episodes li.TPostMv").forEach { li ->
            val a = li.selectFirst("a[href]") ?: return@forEach
            val epUrl = a.attr("href")
            if (epUrl.isBlank()) return@forEach
            val epTitle = li.selectFirst("h2.Title")?.text()?.trim()
            val playUrl = epUrl.trimEnd('/') + "/play/"
            // "ATM (2x1)" → season=2, ep=1 kalau bisa di-extract, else fallback
            val m = Regex("""\((\d+)x(\d+)\)""").find(epTitle ?: "")
            episodes.add(newEpisode(playUrl) {
                this.name = epTitle
                if (m != null) {
                    this.season = m.groupValues[1].toInt()
                    this.episode = m.groupValues[2].toInt()
                }
            })
        }

        // Season lain — fetch per season page untuk episode-nya
        if (seasonUrls.isNotEmpty()) {
            seasonUrls.forEach { seasonUrl ->
                if (seasonUrl == url) return@forEach
                try {
                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select("ul.all-episodes li.TPostMv").forEach { li ->
                        val a = li.selectFirst("a[href]") ?: return@forEach
                        val epUrl = a.attr("href")
                        if (epUrl.isBlank()) return@forEach
                        val epTitle = li.selectFirst("h2.Title")?.text()?.trim()
                        val playUrl = epUrl.trimEnd('/') + "/play/"
                        val m = Regex("""\((\d+)x(\d+)\)""").find(epTitle ?: "")
                        episodes.add(newEpisode(playUrl) {
                            this.name = epTitle
                            if (m != null) {
                                this.season = m.groupValues[1].toInt()
                                this.episode = m.groupValues[2].toInt()
                            }
                        })
                    }
                } catch (_: Exception) {}
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val sources = doc.select("video#my-video source, #my-video source")
        var found = false
        val seen = HashSet<String>()

        sources.forEach { src ->
            val videoUrl = src.attr("src")
            if (videoUrl.isBlank() || !seen.add(videoUrl)) return@forEach
            val label = src.attr("label").lowercase().trim()
            val quality = when {
                label.contains("1080") || label.contains("4k") -> Qualities.P1080.value
                label.contains("720") -> Qualities.P720.value
                label.contains("480") -> Qualities.P480.value
                label.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            callback(
                newExtractorLink(
                    name,
                    label.ifBlank { name },
                    videoUrl,
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                }
            )
            found = true
        }
        return found
    }
}