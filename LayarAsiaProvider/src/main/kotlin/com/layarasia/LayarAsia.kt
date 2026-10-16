package com.layarasia

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.util.Base64

class LayarAsia : MainAPI() {
    override var mainUrl = "https://server-1.layar.asia"
    override var name = "LayarAsia"
    override val hasMainPage = true
    override var lang = "id"
    // The site only distinguishes movies and dramas: every title reports
    // "Tipe: Movie" or "Tipe: Drama" in div.spe, and no listing returned Anime
    // or AsianDrama across the seven sections sampled.
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Mirrors the seven sections the site's own home page renders, using each
    // section's "View All" target so ordering matches what the site shows
    // (most recently updated / added) instead of an alphabetical slug browse.
    // Pagination is &page=<n>; the site returns a fresh set of 20 each time.
    //
    // The brackets in genre[]/country[] are percent-encoded on the wire, and
    // "%5B" is not a valid formatter conversion -- String.format would throw
    // UnknownFormatConversionException on every one of those rows. Escape them
    // as %%5B so the formatter emits the literal text.
    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update&page=%d" to "Baru Dirilis",
        "series/?type=movie&order=latest&page=%d" to "Movie",
        "series/?genre%%5B%%5D=drama-korea&status=&type=&order=update&page=%d" to "Drama Korea",
        "series/?country%%5B%%5D=china&status=&type=&order=update&page=%d" to "Drama China",
        "series/?genre%%5B%%5D=varshow&order=update&page=%d" to "Variety Show",
        "series/?genre%%5B%%5D=series-barat&order=update&page=%d" to "Series Barat",
        "series/?country%%5B%%5D=thailand&status=&type=&order=update&page=%d" to "Series Thailand"
    )

    companion object {
        private val EPISODE_IN_TEXT = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        // Captures the site's episode numbering out of a link label, including
        // the range form: "Episode 1-12" -> "1-12", "Episode 13-24 End" ->
        // "13-24 End". "Episode Baru" has no number and correctly misses.
        private val EPISODE_RANGE_IN_LABEL =
            Regex("Episode\\s*(\\d+\\s*(?:-\\s*\\d+)?(?:\\s*End)?)", RegexOption.IGNORE_CASE)

        // The upns family rotates hosts per title (layarasia.upns.live,
        // sf21.upns.online, layarasia.upns.xyz, nuna.upns.pro, ...) but always
        // serves the same /api/v1/video scheme behind the same AES key. The
        // site also hands out the identical software under the strp2p and
        // p2pstream domains, so those belong to the same handler.
        private val UPNS_HOST = Regex(
            "^https?://([a-z0-9\\-]*\\.)?(upns\\.(live|online|xyz|pro|site|top|vip)|strp2p\\.site|p2pstream\\.vip)$",
            RegexOption.IGNORE_CASE
        )

        fun getType(t: String): TvType = when (t.lowercase().trim()) {
            "movie", "film" -> TvType.Movie
            "tv series", "tv show", "drama", "kdrama", "cdrama", "thai drama",
            "jdrama", "dorama", "mini series", "series", "variety", "varshow" -> TvType.TvSeries
            else -> TvType.TvSeries
        }

        fun getStatus(t: String?): ShowStatus = when (t?.lowercase()?.trim()) {
            "ongoing", "currently airing", "airing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.selectFirst("div.listupd")?.select("article.bs")
            ?.mapNotNull { it.toSearchResult() }
            .orEmpty()
        return newHomePageResponse(request.name, items)
    }

    /**
     * Cards look like this on the live site:
     *   article.bs > .bsx > a.tip[href,title] > .limit(.countrybadge,.typez,.ply,.bt>span.epx,img) + .ttt>.tt
     * There is no h2.entry-title and no div.content-thumbnail.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.tip[href]") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = link.attr("title").trim().ifEmpty {
            selectFirst(".tt[itemprop=headline], .tt, h2[itemprop=headline], h2.entry-title")
                ?.text()?.trim().orEmpty()
        }
        if (title.isEmpty()) return null

        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())?.fixImageQuality()

        val ratingText = selectFirst(".upscore, span.rating, div.gmr-rating-item")?.ownText()?.trim()
        val rating = ratingText?.removeSuffix("/10")?.trim()?.toFloatOrNull()

        val epsText = selectFirst("span.epx")?.text()?.trim()
        val eps = epsText?.let { EPISODE_IN_TEXT.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // On listing pages div.typez carries the type ("Movie" / "Drama").
        // Prefer it over the href, since movies also live under /series/.
        val typeLabel = selectFirst("div.typez")?.text()?.trim()
        val tvType = when {
            typeLabel != null -> getType(typeLabel)
            eps != null -> TvType.TvSeries
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        } else {
            newAnimeSearchResponse(title, href, tvType) {
                posterUrl = poster
                if (eps != null) addSub(eps)
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/?s=$fixedQuery&post_type[]=post").document
        return document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = (document.selectFirst("h1.entry-title[itemprop=name], h1.entry-title")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: "")
            .replace(Regex("\\s*(Season|Episode)\\b.*$", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = fixUrlNull(document.selectFirst("div.thumbook img, figure img, div.tb img")?.getImageAttr())
            ?.fixImageQuality()

        // Metadata lives in div.spe as "<b>Label:</b> value" pairs.
        val info = document.selectFirst("div.info-content div.spe")
            ?.select("span")
            ?.mapNotNull { row ->
                // Iterate the <span> wrappers, not allElements(): that list also
                // contains the <b> elements themselves, and for a <b> the
                // selectFirst("b") returns itself, so its ownText() is the bare
                // label ("Tipe:"). toMap() keeps the last entry, so every value
                // was overwritten by its own label and info["Tipe"] resolved to
                // "Tipe:" instead of "Movie" -- which sent every movie down the
                // href fallback and rendered it as a zero-episode series.
                val label = row.selectFirst("b")?.text()
                    ?.trim()
                    ?.trimEnd(':')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                // ownText() on the whole row keeps the ", ," that separate the
                // links, so read the children after the label instead.
                val value = row.childNodes()
                    .filter { it !is Element || (it as Element).tagName() != "b" }
                    .joinToString("") {
                        when (it) {
                            is TextNode -> it.getWholeText().trim()
                            is Element -> it.text().trim()
                            else -> ""
                        }
                    }
                    .replace(Regex("\\s*,\\s*"), ", ")
                    .trim()
                label to value
            }?.toMap()
            .orEmpty()

        val tags = document.select("div.spe a[href*=/genres/], a[href*=/country/], a[href*=/network/]")
            .map { it.text().trim() }
            .distinct()

        val year = Regex("\\b(19|20)\\d{2}\\b").findAll(title)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()

        // The site serves movies and dramas from the same /series/<slug>/ path,
        // so the URL cannot tell them apart. The "Tipe" row is the only
        // reliable signal and must win: classifying by URL first turned every
        // movie into a TvSeries with one bogus episode, which Cloudstream then
        // rendered as "Coming soon".
        val typeLabel = info["Tipe"] ?: info["Tipe:"]
        val tvType = when {
            typeLabel != null -> getType(typeLabel)
            url.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        Log.i(
            "LayarAsia",
            "load url=$url type='$typeLabel' tvType=$tvType " +
                "info=${info.keys} title='$title' eplRows=${document.select("div.eplister ul li a[href]").size}"
        )

        val description = document.selectFirst("div.mindesc")?.text()?.trim()
        val rating = Regex("(\\d+(?:[.,]\\d+)?)\\s*/\\s*10").find(title)?.groupValues?.getOrNull(1)
            ?.replace(",", ".")?.toDoubleOrNull()?.toFloat()

        val actors = document.select("div.spe span:contains(Artis) a").map { it.text().trim() }

        val duration = info["Durasi"]?.let { raw ->
            Regex("(\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.times(60)
        } ?: info["Durasi"]?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val showStatusVal = getStatus(info["Status"])

        val recommendations = document.select("article.bs").mapNotNull { it.toSearchResult() }

        // A movie's /series/ page has no player; the single eplister row links
        // to the page that carries the embed. Series link their episodes
        // directly. .epl-num is a plain number for most series, a range like
        // "11-24" when several episodes share a page, and "HD" for movies.
        val watchUrl = if (tvType == TvType.Movie) {
            document.selectFirst("div.eplister ul li a[href], div.lastend div.inepcx a[href]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() && it != "#" }
                ?.let { fixUrl(it) }
                ?: url
        } else url

        val episodes = if (tvType == TvType.TvSeries) {
            // The "latest" strip (div.lastend) and the full table (div.eplister)
            // link the very same episode pages, and div.lastend comes first in
            // the document -- its rows carry no .epl-num, so deduping on
            // document order kept the poorer row and threw away the label.
            // Select eplister first so distinctBy keeps the labelled version.
            val pairs = (document.select("div.eplister ul li a[href]") +
                    document.select("div.lastend div.inepcx a[href]"))
                .mapNotNull { eps ->
                    val raw = eps.attr("href")
                    if (raw.isBlank() || raw == "#") return@mapNotNull null
                    val href = fixUrl(raw)
                    val label = eps.text()
                    // The site packs several episodes per post, so the label is
                    // a range: "1-12", "13-24 End", or a bare "7" for series
                    // that post one episode at a time.
                    val range = eps.selectFirst(".epl-num")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: EPISODE_RANGE_IN_LABEL.find(label)?.groupValues?.getOrNull(1)?.trim()
                        ?: return@mapNotNull null
                    val episode = Regex("\\d+").find(range)?.groupValues?.getOrNull(0)?.toIntOrNull()
                        ?: EPISODE_IN_TEXT.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                    val season = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("S(\\d+)(?:E\\d+)?$", RegexOption.IGNORE_CASE)
                            .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    EpisodeRow(href, range, episode, season)
                }
                .distinctBy { it.href }

            pairs.map { (href, range, episode, season) ->
                newEpisode(href) {
                    // Keep the site's own numbering so a range reads as
                    // "Episode 1-12" instead of collapsing to "Episode 1".
                    this.name = "Episode $range"
                    this.episode = episode
                    this.season = season
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }
        } else emptyList()

        Log.i(
            "LayarAsia",
            "result tvType=$tvType eps=${episodes.size} watch=$watchUrl " +
                "returns=${if (tvType == TvType.Movie) "MovieLoadResponse" else "TvSeriesLoadResponse"}"
        )

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString())
                addActors(actors)
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString())
                addActors(actors)
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
                showStatus = showStatusVal
            }
        }
    }

    /**
     * The embed is a plain iframe inside div.player-embed. Each additional
     * server is a <select class="mirror"> option whose value attribute is
     * base64 of the iframe HTML, so they are decoded here rather than left to
     * the extractors.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val iframeSrcs = document
            .select("div.player-embed iframe, div#pembed iframe, div.video-content iframe")
            .mapNotNull { it.getIframeAttr() }
        val mirrorSrcs = document
            .select("select.mirror option[value], select[name=mirror] option[value]")
            .mapNotNull { opt ->
                val encoded = opt.attr("value")
                if (encoded.length < 20) return@mapNotNull null
                runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
            }
            .filter { it.contains("iframe", ignoreCase = true) }
            .mapNotNull { Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }

        Log.i("LayarAsia", "loadLinks data=$data iframes=$iframeSrcs mirrors=$mirrorSrcs")

        val found = mutableSetOf<String>()

        // Suspend local function is not allowed, so take a suspend lambda.
        val emit: suspend (String) -> Unit = { iframeHtml ->
            val src = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(iframeHtml)?.groupValues?.getOrNull(1)?.trim()
            if (!src.isNullOrBlank() && found.add(src)) {
                val fixed = fixUrl(src)
                // Cloudstream routes extractors by mainUrl, which is a literal
                // prefix and then a Levenshtein similarity check
                // (ExtractorApi.kt:950 and :967). The upns hosts rotate per
                // title -- layarasia.upns.live on one, sf21.upns.online on the
                // next -- so no single mainUrl can ever match them all and they
                // ended up with no handler at all ("no links found"). Match the
                // family by name and call the extractor directly instead.
                if (isUpnsHost(fixed)) {
                    UpnsLive().getUrl(fixed, data, subtitleCallback, callback)
                } else {
                    loadExtractor(fixed, data, subtitleCallback, callback)
                }
            }
        }

        document.select("div.player-embed iframe, div#pembed iframe, div.video-content iframe")
            .forEach { it.getIframeAttr()?.let { src -> emit(src) } }

        document.select("select.mirror option[value], select[name=mirror] option[value]")
            .forEach { opt ->
                val encoded = opt.attr("value")
                if (encoded.length < 20) return@forEach
                val decoded = runCatching {
                    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                if (decoded.contains("iframe", ignoreCase = true)) emit(decoded)
            }

        val downloadSrcs = document
            .select("a[href*='terabox'], a[href*='filemoon'], a[href*='fileditch'], a[href*='mirror'], a[href$='.mp4'], a[href*='.m3u8']")
            .map { it.attr("href").trim() }
            .filter { it.isNotBlank() }

        downloadSrcs.forEach { loadExtractor(fixUrl(it), data, subtitleCallback, callback) }

        Log.i("LayarAsia", "loadLinks emitted=${found.size} downloads=$downloadSrcs")

        return true
    }

    /** True for the rotating `*.upns.*` embed family handled by [UpnsLive]. */
    private fun isUpnsHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return UPNS_HOST.matches(url) || UPNS_HOST.matches("https://$host")
    }

    /** One row of the episode table: the page plus how the site labels it. */
    private data class EpisodeRow(
        val href: String,
        val range: String,
        val episode: Int,
        val season: Int?
    )

    private fun Element.getImageAttr(): String? = when {
        hasAttr("data-lazy-src") && attr("data-lazy-src").isNotBlank() -> attr("abs:data-lazy-src")
        hasAttr("data-src") && attr("data-src").isNotBlank() -> attr("abs:data-src")
        hasAttr("srcset") && attr("srcset").isNotBlank() -> attr("abs:srcset").substringBefore(" ")
        hasAttr("src") && attr("src").isNotBlank() -> attr("abs:src")
        else -> null
    }

    private fun Element?.getIframeAttr(): String? =
        this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }

    private fun String.fixImageQuality(): String = replace(Regex("(-\\d+x\\d*)"), "")
}
