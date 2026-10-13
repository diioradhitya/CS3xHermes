// Continue from the published v3: the module used to be built as "Kuronime"
// while builds/ shipped "KuronimeSBS" v3, so a rebuild looked like a brand-new
// extension. Renaming the module to KuronimeSBS restores the published identity;
// this build is v4 so existing installs get an update instead of a duplicate.
version = 4

cloudstream {
    description = "KuroNime SBS - Anime Indonesia (kuronime.sbs)"
    language = "id"
    authors = listOf("Dio R")
    status = 1
    tvTypes = listOf(
        "Anime"
    )
}