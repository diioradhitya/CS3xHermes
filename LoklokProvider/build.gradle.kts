// Loklok (loklok.my.id) — aggregator drama/film/animasi subtitle Indonesia.
//
// v1 dibangun dari JS bundle situs (assets/index-*.js), bukan dari tebakan
// endpoint. Yang hidup hanya family "/api/indocast/*"; family "/api/drama/*"
// (dipakai homepage situs sendiri) mati karena upstream "api.dracinku.site"
// tidak resolve, dan "/api/sapi/*" + "/api/apinesia/*" semuanya disabled.
//
// Empat platform yang dipakai, semuanya sudah diverifikasi live (chain
// home -> detail -> play -> file video):
//
//   drakorid    GET  /indocast/drakorid/home?page=&type=terbaru   (30/halaman)
//               GET  /indocast/drakorid/kategori                  (63 genre)
//               GET  /indocast/drakorid/kategori/detail?slug=&page=
//               GET  /indocast/drakorid/detail?slug=
//               POST /indocast/drakorid/play {id,episode,quality} -> HLS langsung
//               GET  /indocast/drakorid/search?q=&page=
//
//   filmbox     GET  /indocast/filmbox/home                       (26 section)
//               GET  /indocast/filmbox/trending?page=&perPage=&subjectType=
//               POST /indocast/filmbox/search {keyword,page,perPage,subjectType}
//               GET  /indocast/filmbox/detail?detailPath=
//               GET  /indocast/filmbox/play?subjectId=&detailPath=&se=&ep=&lang=
//
//   animekompi  GET  /indocast/animekompi/home?page=
//               GET  /indocast/animekompi/detail?path=
//               GET  /indocast/animekompi/play?episode_id=
//
//   iqiyi       GET  /indocast/iqiyi/home
//               GET  /indocast/iqiyi/trending
//               GET  /indocast/iqiyi/search?q=
//               GET  /indocast/iqiyi/detail?pathplay=&lang=
//               GET  /indocast/iqiyi/play?slug=&lang=
//
// CATATAN FILMBOX: vid_url / hls[].url menunjuk CDN langsung (bcdnxw.*) yang
// membalas 429 ke klien luar. Yang bisa diunduh adalah *_proxy-nya
// (indocast.site/api/proxy?...), jadi link wajib pakai url_proxy.
//
// CATATAN IQIYI: response play dibungkus dua lapis -- data.data berisi string
// JSON, jadi harus di-parse dua kali. URL streaming=hls_url (proxy
// indocast-stream), field m3u8 berisi playlist inline.
//
// v2 (perubahan dari user's test di perangkat):
// 1. Resolusi tidak lagi ditulis di nama link (nama jadi kembar semua), tapi
//    di field ExtractorLink.quality -> jadi track video di player.
// 2. Beranda akhirnya berpaginasi: tiap halaman scroll mengambil halaman
//    berikutnya dari tiap platform (sebelumnya cuma 5 baris lalu berhenti).
//    Semua tab juga dicap MAX_PAGES karena pager.hasMore filmbox selalu true.
// 3. Semua poster dinormalisasi jadi potrait 300x420 lewat resize proxy
//    convert.d-cdn.me. Sebelumnya campur: filmbox 0.56-0.80 (300x450 s/d
//    3900x5700), drakorid 180x200 (nyaris persegi), iqiyi bahkan bisa
//    landscape (480x270), animekompi 424x600. Fallback landscape iqiyi dihapus.
version = 2

cloudstream {
    description = "Loklok - Drama, Film & Anime Sub Indo (loklok.my.id)"
    language = "id"
    authors = listOf("Dio R")
    // Beta sampai playback terkonfirmasi di perangkat.
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
}
