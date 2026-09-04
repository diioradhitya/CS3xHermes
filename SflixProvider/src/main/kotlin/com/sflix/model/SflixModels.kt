package com.sflix.model

import com.fasterxml.jackson.annotation.JsonProperty

// TMDB list response (search, trending, popular, etc.)
data class TmdbPaged(
    @JsonProperty("results") val results: List<TmdbItem>? = null,
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null
)

// Item inside TMDB list (movies + TV shared fields)
data class TmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
    @JsonProperty("popularity") val popularity: Double? = null
)

// Genre
data class TmdbGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

// Cast member
data class TmdbCastMember(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null
)

// Credits wrapper
data class TmdbCredits(
    @JsonProperty("cast") val cast: List<TmdbCastMember>? = null,
    @JsonProperty("crew") val crew: List<TmdbCrewMember>? = null
)

data class TmdbCrewMember(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("job") val job: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null
)

// Movie detail
data class TmdbMovieDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("tagline") val tagline: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("videos") val videos: TmdbVideos? = null,
    @JsonProperty("similar") val similar: TmdbPaged? = null
)

// TV detail
data class TmdbTvDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("last_air_date") val lastAirDate: String? = null,
    @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
    @JsonProperty("number_of_episodes") val numberOfEpisodes: Int? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("videos") val videos: TmdbVideos? = null,
    @JsonProperty("similar") val similar: TmdbPaged? = null
)

// Season
data class TmdbSeason(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null
)

// Season detail with episodes
data class TmdbSeasonDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
)

// Episode
data class TmdbEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("runtime") val runtime: Int? = null
)

// Videos (for trailers)
data class TmdbVideos(
    @JsonProperty("results") val results: List<TmdbVideoResult>? = null
)

data class TmdbVideoResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("official") val official: Boolean? = null
)
