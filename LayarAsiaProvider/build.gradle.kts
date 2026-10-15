// v1 shipped selectors for a theme LayarAsia no longer serves: mainPage used
// /genre/... (the site moved to /genres/...) and load/loadLinks looked for
// div.gmr-* wrappers that are absent from every page. Home page came up empty.
// This release targets the current markup, so bump to 2 to push the fix.
version = 2

cloudstream {
    description = "LayarAsia - Drakor, Drachin, Movie Sub Indo streaming & download"
    language = "id"
    authors = listOf("Dio R")
    status = 2
    tvTypes = listOf(
        "Movie",
        "AsianDrama",
        "TvSeries",
        "Anime"
    )
}