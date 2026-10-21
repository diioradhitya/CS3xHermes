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
version = 1

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
