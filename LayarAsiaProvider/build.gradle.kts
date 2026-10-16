// v1 shipped selectors for a theme LayarAsia no longer serves: mainPage used
// /genre/... (the site moved to /genres/...) and load/loadLinks looked for
// div.gmr-* wrappers that are absent from every page. Home page came up empty.
// v2 fixed that but listed 18 alphabet-derived genre slugs; the site's own
// home page shows only seven sections, so v3 mirrors those and keeps the
// site's ordering instead of a slug browse. v3 percent-encoded genre[] as
// %5B%5D, which String.format read as a conversion specifier and threw on, so
// five of the seven rows never rendered. v5 classifies by the page's own
// "Tipe" field instead of the href, because movies also live under /series/
// and were being loaded as one-episode series ("Coming soon"). v5 still shows
// "Coming soon" on device, so v6 adds temporary instrumentation: the parsed
// Tipe value, resolved tvType, episode count and chosen watch URL are logged
// under the "LayarAsia" tag so logcat shows what load() actually produced.
// That log kept showing type='Tipe:' because the map was built from
// allElements(), which also yields the <b> labels themselves; a <b>'s
// selectFirst("b") is itself, so its ownText() is the bare label and
// toMap()'s last-wins dropped every real value. v8 iterates the <span>
// wrappers instead. v9 adds ByseExtractor for the bysesukior.com /
// bysetayico.com embeds, which carry a PoW challenge and an AES-GCM payload;
// the old UPNShare/HydraX/StreamP2P/etc registrations never matched anything
// this site serves.
version = 9

cloudstream {
    description = "LayarAsia - Drakor, Drachin, Movie Sub Indo streaming & download"
    language = "id"
    authors = listOf("Dio R")
    status = 2
    // Sampling all seven home sections found only "Tipe: Movie" and
    // "Tipe: Drama"; the site publishes no anime or AsianDrama titles.
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
}