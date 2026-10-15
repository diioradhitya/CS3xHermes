// v1 shipped selectors for a theme LayarAsia no longer serves: mainPage used
// /genre/... (the site moved to /genres/...) and load/loadLinks looked for
// div.gmr-* wrappers that are absent from every page. Home page came up empty.
// v2 fixed that but listed 18 alphabet-derived genre slugs; the site's own
// home page shows only seven sections, so v3 mirrors those and keeps the
// site's ordering instead of a slug browse. v3 percent-encoded genre[] as
// %5B%5D, which String.format read as a conversion specifier and threw on, so
// five of the seven rows never rendered.
version = 4

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