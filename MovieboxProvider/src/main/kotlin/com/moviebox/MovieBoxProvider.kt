package com.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.content.Context
import android.util.Base64
import android.util.Log
import android.os.Build
import java.util.UUID
import java.net.URLEncoder
import java.net.URLDecoder
import java.net.URI

class MovieBoxProvider : MainAPI() {
    private val playbackContext = MovieboxPlaybackContext()
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Disusun dari daftar kategori yang server kirim (lihat mainPageEntries).
    override val mainPage = mainPageOf(*mainPageEntries())

    companion object {
        private const val TAG = "MovieBox"
        private const val CS_USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val PLAYBACK_API_BASE = "https://api6.aoneroom.com"
        private val PLAYBACK_HOSTS = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com"
        )
        private const val PLAYBACK_TOKEN_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        private const val PLAYBACK_USER_AGENT =
            "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
        private const val PLAYBACK_VERSION_NAME = "4.0.03.0920.03"
        private const val PLAYBACK_VERSION_CODE = 50020130L
        private const val PLAYBACK_ALT_SECRET_B64 =
            "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="
        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        /**
         * x-client-info dirakit saat request, bukan konstanta, karena device_id
         * berasal dari identity persisten per-instalasi.
         *
         * Isi field lain TIDAK berubah sedikit pun dari versi sebelumnya.
         * x-client-info tidak ikut ditandatangani (buildCanonical hanya memakai
         * method/accept/content-type/panjang body/ts/md5 body/path), jadi
         * perubahan ini tidak dapat memengaruhi sign...
         */
        private const val X_CLIENT_INFO_SECRET = "2PgeDuIAIxQx4iExW6zU3U2P5q8XaR9p"

        fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "").trim()
        fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
        fun http(url: String): Boolean = try {
            val u = URI(url)
            u.scheme in listOf("https", "http") && !u.host.isNullOrBlank() && u.userInfo == null
        } catch (_: Exception) { false }
        fun notice(url: String): Boolean = listOf("b164fbfb4347792950bdfbfb563d39d9",
            "1c7de0bd3393702d9191801f15f88f8d", "9a0461bc39da389663bf3dbb17091d3f",
            "/other/2026/09/", "/notice.mp4", "upgrade-notice").any { url.contains(it, true) }
        fun matches(o: JSONObject, subject: String, se: Int, ep: Int): Boolean =
            (text(o, "subjectId").let { it.isEmpty() || it == subject }) &&
            (!o.has("se") || o.optInt("se", -1) == se) &&
            (!o.has("ep") || o.optInt("ep", -1) == ep)

        fun parse(raw: String, source: String, subject: String, se: Int, ep: Int,
                 audio: String = "", originalLanguage: String = ""): List<Stream> {
            if (source != "H5") return emptyList()
            val root = JSONObject(raw)
            if (root.has("code") && root.optInt("code", -1) != 0) return emptyList()
            val data = root.optJSONObject("data") ?: return emptyList()
            if (!matches(data, subject, se, ep)) return emptyList()

            val tracks = mutableListOf<Stream>()

            if (data.has("tracks")) {
                val tracksArray = data.getJSONArray("tracks")
                for (i in 0 until tracksArray.length()) {
                    val track = tracksArray.getJSONObject(i)
                    if (track.has("sources")) {
                        val sources = track.getJSONArray("sources")
                        for (j in 0 until sources.length()) {
                            val sourceObj = sources.getJSONObject(j)
                            if (sourceObj.has("file")) {
                                val fileUrl = sourceObj.getString("file")
                                val quality = sourceObj.optInt("quality", 0)
                                val mime = sourceObj.optString("mime", "")
                                val sourceType = sourceObj.optString("source", "")

                                val codec = when {
                                    mime.contains("avc", ignoreCase = true) || mime.contains("h264", ignoreCase = true) -> "h264"
                                    mime.contains("hevc", ignoreCase = true) || mime.contains("h265", ignoreCase = true) -> "hevc"
                                    else -> "unknown"
                                }

                                tracks.add(Stream(
                                    url = fileUrl,
                                    format = when {
                                        mime.contains("mpd", ignoreCase = true) -> "DASH"
                                        mime.contains("m3u8", ignoreCase = true) -> "HLS"
                                        else -> "MP4"
                                    },
                                    id = sourceObj.optString("id", ""),
                                    quality = quality,
                                    codec = codec,
                                    cookie = null,
                                    signHeader = "",
                                    audio = audio,
                                    source = sourceType
                                ))
                            }
                        }
                    }
                }
            }

            if (data.has("sources")) {
                val sourcesArray = data.getJSONArray("sources")
                for (i in 0 until sourcesArray.length()) {
                    val sourceObj = sourcesArray.getJSONObject(i)
                    if (sourceObj.has("file")) {
                        val fileUrl = sourceObj.getString("file")
                        val quality = sourceObj.optInt("quality", 0)
                        val mime = sourceObj.optString("mime", "")
                        val sourceType = sourceObj.optString("source", "")

                        val codec = when {
                            mime.contains("avc", ignoreCase = true) || mime.contains("h264", ignoreCase = true) -> "h264"
                            mime.contains("hevc", ignoreCase = true) || mime.contains("h265", ignoreCase = true) -> "hevc"
                            else -> "unknown"
                        }

                        tracks.add(Stream(
                            url = fileUrl,
                            format = when {
                                mime.contains("mpd", ignoreCase = true) -> "DASH"
                                mime.contains("m3u8", ignoreCase = true) -> "HLS"
                                else -> "MP4"
                            },
                            id = sourceObj.optString("id", ""),
                            quality = quality,
                            codec = codec,
                            cookie = null,
                            signHeader = "",
                            audio = audio,
                            source = sourceType
                        ))
                    }
                }
            }

            return tracks
        }

        fun buildCanonical(
            method: String,
            accept: String,
            contentType: String?,
            path: String,
            body: String?,
            ts: Long,
            secret: String,
            clientInfo: String
        ): String {
            val sb = StringBuilder()
            sb.append(method).append('|')
            sb.append(accept).append('|')
            sb.append(contentType ?: "").append('|')
            sb.append(path).append('|')
            sb.append(body?.length ?: 0).append('|')
            sb.append(ts).append('|')
            sb.append(secret).append('|')
            sb.append(clientInfo).append('|')
            sb.append("")

            val md5 = MessageDigest.getInstance("MD5").apply { digest(sb.toString().toByteArray()) }
                .foldIndexed("", { i, acc, b -> if (i == 0) String.format("%02x", b and 0xFF) else acc + String.format("%02x", b and 0xFF) })[0]

            val hmac = Mac.getInstance("HmacSHA256").apply {
                val key = Base64.decode(secret, Base64.DEFAULT)
                init(SecretKeySpec(key, "HmacSHA256"))
            }
            val data = "${md5}|${ts}|${clientInfo}".toByteArray()
            val signature = hmac.doFinal(data).let { Base64.encodeToString(it, Base64.DEFAULT).replace("\n", "") }

            return "X-Signature: $signature"
        }

        fun getClientInfo(context: Context): String {
            val deviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val secure = context.getSystemService(Context.SECURITY_SERVICE)
                secure?.let { it.generateSecureKeyPair(1, 2048).getPublic().encodeToString(Base64.NO_WRAP) }
                    ?: UUID.randomUUID().toString()
            } else {
                UUID.randomUUID().toString()
            }

            return "X-Client-Info: {
                \"device_id\": \"$deviceId\",
                \"app_version\": \"$PLAYBACK_VERSION_NAME\",
                \"app_version_code\": $PLAYBACK_VERSION_CODE,
                \"os_version\": \"Android ${Build.VERSION.RELEASE}\",
                \"device_model\": \"${Build.MODEL}\",
                \"device_brand\": \"${Build.BRAND}\",
                \"screen_size\": \"${Build.SCREEN_WIDTH}x${Build.SCREEN_HEIGHT}\",
                \"display_density\": ${Build.MANUFACTURER},
                \"language\": \"${Locale.getDefault().language}\"
            }"
        }

        fun getApiHeaders(context: Context, method: String, accept: String, contentType: String?, path: String, body: String?): Map<String, String> {
            val ts = System.currentTimeMillis() / 1000
            val clientInfo = getClientInfo(context)

            val secret = if (body.isNullOrEmpty()) X_CLIENT_INFO_SECRET else PLAYBACK_ALT_SECRET_B64
            val signature = buildCanonical(method, accept, contentType, path, body, ts, secret, clientInfo)

            return mapOf(
                "User-Agent" to PLAYBACK_USER_AGENT,
                "X-Client-Info" to clientInfo,
                "X-Signature" to signature,
                "X-Timestamp" to ts.toString(),
                "Content-Type" to (contentType ?: "application/json"),
                "Accept" to accept,
                "Connection" to "keep-alive"
            )
        }

        fun getApiUrl(host: String, path: String): String {
            return if (path.startsWith("http")) path else if (host.endsWith("/")) host + path.substringAfter("/") else host + path
        }

        fun makeApiRequest(
            context: Context,
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String? = null
        ): String? {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()

                val requestBuilder = Request.Builder()
                    .url(url)
                    .headers(Headers.of(headers))

                when (method) {
                    "GET" -> requestBuilder.get()
                    "POST" -> {
                        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
                        requestBuilder.post(requestBody)
                    }
                    "PUT" -> {
                        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
                        requestBuilder.put(requestBody)
                    }
                    "DELETE" -> requestBuilder.delete()
                }

                val response = client.newCall(requestBuilder.build()).execute()
                return if (response.isSuccessful) response.body?.string() else null
            } catch (e: Exception) {
                Log.e(TAG, "API Request Failed", e)
                return null
            }
        }
    }

    private val cacheManager = CacheManager(this)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "main_page_${request.data}_$page"
        val cached = cacheManager.getCache(cacheKey)
        if (cached != null) {
            val cachedData = JSONObject(cached)
            return newHomePageResponse(
                request.name,
                (0 until cachedData.optJSONArray("results").length()).mapNotNull { index ->
                    val item = cachedData.getJSONArray("results").getJSONObject(index)
                    newSearchResponse(
                        title = text(item, "title"),
                        url = text(item, "url"),
                        type = when (text(item, "type")) {
                            "movie" -> TvType.Movie
                            "series" -> TvType.TvSeries
                            else -> TvType.Movie
                        },
                        posterUrl = text(item, "poster"),
                        description = text(item, "description"),
                        rating = text(item, "rating")?.toDoubleOrNull()
                    ) {
                        this.year = text(item, "year")?.toIntOrNull()
                        this.tags = listOfNotNull(text(item, "genre"))
                    }
                }
            )
        }

        val results = mutableListOf<SearchResponse>()

        for (host in PLAYBACK_HOSTS) {
            val apiUrl = getApiUrl(host, "/api/v1/main/page")
            val headers = getApiHeaders(app, "GET", "application/json", null, "/api/v1/main/page", null)
            val response = makeApiRequest(app, "GET", apiUrl, headers)

            if (response != null) {
                val json = JSONObject(response)
                if (json.has("data") && !json.isNull("data")) {
                    val data = json.getJSONObject("data")
                    if (data.has("results")) {
                        val resultsArray = data.getJSONArray("results")
                        for (i in 0 until resultsArray.length()) {
                            val item = resultsArray.getJSONObject(i)
                            results.add(newSearchResponse(
                                title = text(item, "title"),
                                url = text(item, "url"),
                                type = when (text(item, "type")) {
                                    "movie" -> TvType.Movie
                                    "series" -> TvType.TvSeries
                                    else -> TvType.Movie
                                },
                                posterUrl = text(item, "poster"),
                                description = text(item, "description"),
                                rating = text(item, "rating")?.toDoubleOrNull()
                            ) {
                                this.year = text(item, "year")?.toIntOrNull()
                                this.tags = listOfNotNull(text(item, "genre"))
                            })
                        }

                        cacheManager.setCache(cacheKey, response, 3600)
                        break
                    }
                }
            }
        }

        if (results.isEmpty()) {
            return newHomePageResponse(request.name, emptyList())
        }

        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cacheKey = "search_$query"
        val cached = cacheManager.getCache(cacheKey)
        if (cached != null) {
            val cachedData = JSONObject(cached)
            return (0 until cachedData.optJSONArray("results").length()).mapNotNull { index ->
                val item = cachedData.getJSONArray("results").getJSONObject(index)
                newSearchResponse(
                    title = text(item, "title"),
                    url = text(item, "url"),
                    type = when (text(item, "type")) {
                        "movie" -> TvType.Movie
                        "series" -> TvType.TvSeries
                        else -> TvType.Movie
                    },
                    posterUrl = text(item, "poster"),
                    description = text(item, "description"),
                    rating = text(item, "rating")?.toDoubleOrNull()
                ) {
                    this.year = text(item, "year")?.toIntOrNull()
                    this.tags = listOfNotNull(text(item, "genre"))
                }
            }
        }

        val results = mutableListOf<SearchResponse>()

        for (host in PLAYBACK_HOSTS) {
            val apiUrl = getApiUrl(host, "/api/v1/search")
            val headers = getApiHeaders(app, "GET", "application/json", null, "/api/v1/search?q=$query", null)
            val response = makeApiRequest(app, "GET", apiUrl, headers)

            if (response != null) {
                val json = JSONObject(response)
                if (json.has("data") && !json.isNull("data")) {
                    val data = json.getJSONObject("data")
                    if (data.has("results")) {
                        val resultsArray = data.getJSONArray("results")
                        for (i in 0 until resultsArray.length()) {
                            val item = resultsArray.getJSONObject(i)
                            results.add(newSearchResponse(
                                title = text(item, "title"),
                                url = text(item, "url"),
                                type = when (text(item, "type")) {
                                    "movie" -> TvType.Movie
                                    "series" -> TvType.TvSeries
                                    else -> TvType.Movie
                                },
                                posterUrl = text(item, "poster"),
                                description = text(item, "description"),
                                rating = text(item, "rating")?.toDoubleOrNull()
                            ) {
                                this.year = text(item, "year")?.toIntOrNull()
                                this.tags = listOfNotNull(text(item, "genre"))
                            })
                        }

                        cacheManager.setCache(cacheKey, response, 1800)
                        break
                    }
                }
            }
        }

        if (results.isEmpty()) {
            return emptyList()
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val cacheKey = "load_$url"
        val cached = cacheManager.getCache(cacheKey)
        if (cached != null) {
            val cachedData = JSONObject(cached)
            return if (cachedData.has("isMovie") && cachedData.getBoolean("isMovie")) {
                newMovieLoadResponse(
                    title = text(cachedData, "title"),
                    url = url,
                    type = when (text(cachedData, "type")) {
                        "movie" -> TvType.Movie
                        "series" -> TvType.TvSeries
                        else -> TvType.Movie
                    },
                    dataUrl = text(cachedData, "dataUrl")
                ) {
                    posterUrl = text(cachedData, "posterUrl")
                    backgroundPosterUrl = text(cachedData, "backgroundPosterUrl")
                    this.year = text(cachedData, "year")?.toIntOrNull()
                    this.plot = text(cachedData, "plot")
                    this.tags = listOfNotNull(text(cachedData, "tags"))
                    this.recommendations = listOfNotNull(text(cachedData, "recommendations"))
                }
            } else {
                val episodes = (0 until cachedData.getJSONArray("episodes").length()).map { index ->
                    val episodeData = cachedData.getJSONArray("episodes").getJSONObject(index)
                    newEpisode(text(episodeData, "url")) {
                        this.episode = text(episodeData, "episode")?.toIntOrNull()
                        this.name = text(episodeData, "name")
                    }
                }

                return newAnimeLoadResponse(
                    title = text(cachedData, "title"),
                    url = url,
                    type = when (text(cachedData, "type")) {
                        "movie" -> TvType.Movie
                        "series" -> TvType.TvSeries
                        else -> TvType.Movie
                    }
                ) {
                    posterUrl = text(cachedData, "posterUrl")
                    backgroundPosterUrl = text(cachedData, "backgroundPosterUrl")
                    this.year = text(cachedData, "year")?.toIntOrNull()
                    this.plot = text(cachedData, "plot")
                    this.tags = listOfNotNull(text(cachedData, "tags"))
                    this.recommendations = listOfNotNull(text(cachedData, "recommendations"))
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
        }

        val results = mutableListOf<Stream>()

        for (host in PLAYBACK_HOSTS) {
            val apiUrl = getApiUrl(host, "/api/v1/load")
            val headers = getApiHeaders(app, "GET", "application/json", null, "/api/v1/load?url=$url", null)
            val response = makeApiRequest(app, "GET", apiUrl, headers)

            if (response != null) {
                val json = JSONObject(response)
                if (json.has("data") && !json.isNull("data")) {
                    val data = json.getJSONObject("data")
                    val subject = text(data, "subject")
                    val se = text(data, "se")?.toIntOrNull() ?: 0
                    val ep = text(data, "ep")?.toIntOrNull() ?: 0

                    val streams = parse(response, "H5", subject, se, ep)
                    if (streams.isNotEmpty()) {
                        results.addAll(streams)
                    }

                    cacheManager.setCache(cacheKey, response, 1800)
                    break
                }
            }
        }

        if (results.isEmpty()) {
            return newAnimeLoadResponse(
                title = "Load Failed",
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = ""
                this.plot = "No streams found"
            }
        }

        val isMovie = results.any { it.format == "MP4" && it.quality == null }

        return if (isMovie) {
            newMovieLoadResponse(
                title = "MovieBox",
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                posterUrl = ""
                this.plot = "Movie loaded successfully"
                addExtractorLinks(results.map { extractorLink ->
                    newExtractorLink(
                        name = name,
                        url = extractorLink.url,
                        type = ExtractorLinkType.VIDEO,
                        quality = extractorLink.quality ?: 0
                    ) {
                        this.referer = "https://moviebox.ph"
                        this.headers = mapOf("User-Agent" to PLAYBACK_USER_AGENT)
                    }
                })
            }
        } else {
            val episodes = results.groupBy { Pair(it.source, it.audio) }.entries.map { (key, streams) ->
                newEpisode(url) {
                    this.episode = key.first?.toIntOrNull()
                    this.name = "Episode ${key.first}"
                    addExtractorLinks(streams.map { extractorLink ->
                        newExtractorLink(
                            name = name,
                            url = extractorLink.url,
                            type = ExtractorLinkType.VIDEO,
                            quality = extractorLink.quality ?: 0
                        ) {
                            this.referer = "https://moviebox.ph"
                            this.headers = mapOf("User-Agent" to PLAYBACK_USER_AGENT)
                        }
                    })
                }
            }

            return newAnimeLoadResponse(
                title = "MovieBox",
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = ""
                this.plot = "TV Series loaded successfully"
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val json = JSONObject(data)
            val url = text(json, "url")
            val episode = text(json, "episode")?.toIntOrNull() ?: 0
            val source = text(json, "source")
            val audio = text(json, "audio")

            val streams = parse(data, "H5", source, episode, episode, audio)
            if (streams.isNotEmpty()) {
                for (stream in streams) {
                    callback(newExtractorLink(
                        name = name,
                        url = stream.url,
                        type = ExtractorLinkType.VIDEO,
                        quality = stream.quality ?: 0
                    ) {
                        this.referer = "https://moviebox.ph"
                        this.headers = mapOf("User-Agent" to PLAYBACK_USER_AGENT)
                    })
                }
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading links", e)
            return false
        }
    }

    companion object {
        private var appContext: Context? = null

        fun attachContext(context: Context) {
            appContext = context
        }

        fun getMainPageEntries(): Array<String> {
            return arrayOf("all", "popular", "trending", "new-releases")
        }

        fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "").trim()
    }
}