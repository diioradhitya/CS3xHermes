version = 9

cloudstream {
    description = "IDNMovie - Nonton Film, TV, Anime Sub Indo (idnmovie.com)"
    language = "id"
    authors = listOf("Dio R")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    // Genre TIDAK dideklarasikan di sini: DSL cloudstream tidak punya key
    // `genres`, dan FilterData dibuild dari daftar genre provider lain.
    // Genre IDNMovie dibaca dari FilterData.genres di getCatalog().
    iconUrl = "https://www.google.com/s2/favicons?domain=idnmovie.com&sz=64"
}
