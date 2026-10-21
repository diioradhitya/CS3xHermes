# Activity Log

Catatan aktivitas terakhir di repo `CS3xHermes`. Dipakai sebagai ringkasan saat
commit/push ke GitHub, dan sebagai konteks buat session berikutnya.

Format satu entri per batch kerja, terbaru di atas.

---

## 2026-10-21 (siang) — Loklok v2: perbaikan dari test di perangkat

### Yang dikoreksi

1. **Nama source video duplikat.** Filmbox emitting link "Loklok 360" /
   "Loklok 480" / "Loklok 1080" — nama bedanya cuma resolusi, jadi daftar
   source di app jadi kacau. Resolusi sekarang ditulis di field
   `ExtractorLink.quality` (angka resolusi, mis. 720/480/360) supaya jadi
   **track video** di player, bukan bagian dari nama. Semua link bernama sama
   "Loklok". `Qualities.Unknown.value` = 400 (bukan 0) — itu default SDK,
   jadi link yang tidak bisa ditentukan resolusinya tetap 400.
2. **Pagination Beranda tidak jalan.** `homeSections()` selalu fetch halaman 1
   dan `hasNext=false`, jadi scroll berhenti setelah 5 baris. Sekarang
   `homeSections(page)` — tiap halaman scroll mengambil halaman BERIKUTNYA
   dari tiap platform. iQIYI tidak punya paginasi (halaman 1 = halaman 2,
   122 duplikat), jadi barisnya cuma di halaman 1.
   Semua tab juga dicap `MAX_PAGES = 20` karena `pager.hasMore` filmbox selalu
   `true` (totalCount 1.000.000) — tanpa cap, scroll tak berujung.
3. **Poster bercampur potrait & landscape.** Terukur:
   - filmbox 0.56–0.80, resolusi 300×450 s/d 3900×5700 (CDN tanpa resize)
   - drakorid 180×200 (0.90, nyaris persegi) — `convert.d-cdn.me` bisa di-crop
   - iqiyi `cover.potrait` 260×360 (0.72) **dan** `cover.landscape` 480×270 (1.78)
   - animekompi 424×600 (0.71)
   Solusi: semua poster dilewatkan resize proxy `convert.d-cdn.me/convert/
   <base64url(url)>/300x420/1.jpg` → seragam potrait 300×420. Fallback
   landscape iqiyi dihapus; item tanpa potrait dilewati.

### Verifikasi

- Harness live: **72 PASS / 0 FAIL** (`/root/loklok-verify/run11.log`).
  - Beranda: p1=240, p2=85, duplikat antar halaman 0, hasNext=true.
  - 325 poster, semua URL berakhir `/300x420/1.jpg`; 6 sample di-download dan
    diukur Header JPEG → persis 300×420.
  - Link: nama tanpa angka resolusi; quality filmbox `[720, 480]`, drakorid
    `[720]`, iqiyi `[480]`, animekompi `[400, 400]` (2 server beda).
- Artifact: `LoklokProvider.cs3` 35.458 B, `classes.dex` 80.956 B,
  sha256 `f0eb8a561700f3f81106dfce371242f11dcc199dace5c1bdc1df52497351d4ff`.
  Manifest: Loklok `version: 2`.

---

## 2026-10-21 — Plugin baru: Loklok (loklok.my.id)

### Yang dikerjakan

- **Tracing API.** Situs `loklok.my.id` adalah SPA Vite. Semua endpoint diambil
  dari bundle `assets/index-*.js` (bukan ditebak). Base URL API = `/api`,
  base platform = `/api/indocast/<platform>`.
  - 15 platform terdaftar; family lain (`/api/drama/*`, `/api/sapi/*`,
    `/api/apinesia/*`) mati/disabled. Homepage situs sendiri memanggil
    `/api/drama/shortmax/tablist` yang balas
    `Upstream API error: Could not resolve host: api.dracinku.site` — itu sebabnya
    beranda situs自己也 "Belum ada konten".
  - Platform hidup yang dipakai: `drakorid`, `filmbox`, `animekompi`, `iqiyi`.
- **Empat platform, semua chain home → detail → play → file terverifikasi live:**
  - `drakorid` — 63 genre, 30 item/halaman, HLS langsung dari `hls.drakor.cc`.
  - `filmbox` — MovieBox-style, 26 section; **harus lewat `*_proxy`** karena CDN
    `bcdnxw.hakunaymatata.com` balas 429 ke klien luar.
  - `animekompi` — 96 genre, proxy `indocast.site/api/animekompi/proxy`.
  - `iqiyi` — respons play dilapis dua (`data.data` = string JSON).
- **Plugin ditulis** di `LoklokProvider/` (Kotlin, `com.loklok`):
  `LoklokPlugin.kt` + `LoklokProvider.kt` + `build.gradle.kts` (version 1,
  status 1/beta) + `AndroidManifest.xml`.
  - 12 tab: Beranda (5 section), Drakor, 6 genre drakorid, Film, Series, Anime, iQIYI.
  - Semua `newHomePageResponse` memakai `hasNext` eksplisit.
  - `playData` iqiyi di-parse dua lapis.
- **Harness live-test** (`/root/loklok-verify`, di luar repo karena `settings.gradle.kts`
  auto-include semua direktori ber-`build.gradle.kts`): provider source asli
  dikompilasi sebagai `kotlin("jvm")` + stub `android.util.Log`/`android.content.Context`,
  lalu `getMainPage`/`load`/`loadLinks` dijalankan terhadap situs live.

### Bug yang ditemukan harness (bukan ketahuan dari compile)

1. **Subtitle iqiyi 404.** `subUrl_proxy` (`/api/iqiyi/subtitle`) balas 404;
   file aslinya di `meta.video.iqiyi.com` balas 200 + `application/x-subrip`.
   → pakai `subUrl` langsung.
2. **Penomoran filmbox berbeda per tipe.** Film (`subjectType=1`) 0-based
   (`episodes:[0]`, play `se=0&ep=0`); series (`subjectType=2`) 1-based
   (`episodes:[1..N]`, play `se=1&ep=1`). Dengan 1-based, semua film gagal
   "H5 Fetch Error: Stream video kosong dari upstream".
   → `se` diturunkan dari nomor episode + fallback ke penomoran lawanan.
3. **Link filmbox kembar.** `vid_url_proxy` dan `hls[0].url_proxy` bisa sama →
   dedupe per URL.
4. **Server animekompi tanpa proxy** hanya punya `embed_url` → tetap dikeluarkan
   sebagai cadangan.
5. Tab `iqiyi` sempat diberi `%d` padahal endpoint-nya tidak berpaginasi
   (halaman 2 = halaman 1, 122 duplikat) → dihapus placeholder-nya.

### Verifikasi

- `./gradlew :LoklokProvider:make` → BUILD SUCCESSFUL,
  `LoklokProvider.cs3` 34.504 B, `classes.dex` 79.396 B,
  sha256 `1114a64b13d20def5e8f2c329bcf379912262d99eb90502d43535d60efa12b11`.
  `dexdump` mengonfirmasi `Lcom/loklok/LoklokPlugin;` dan
  `Lcom/loklok/LoklokProvider;` ada di dalam artifact.
- **Harness live: 78 PASS / 0 FAIL** (`/root/loklok-verify/run9.log`):
  - 12 tab homepage terisi (Beranda 225 item di 5 section, Drakor 30, 6 genre
    drakorid 30, Film 20, Series 20, Anime 15, iQIYI 140).
  - Halaman 2 ≠ duplikat halaman 1 untuk **semua** tab berpaginasi (duplikat=0).
  - Search "revenge" → 55 hasil.
  - `load` + `loadLinks` untuk 5 skenario, dan URL hasil fetch benar-benar
    diunduh: HLS `#EXTM3U` + segment 385 KB (drakorid) dan 400 KB (iQIYI);
    MP4 `206 Partial Content` + signature `ftyp` (filmbox movie & series,
    animekompi); 13 subtitle filmbox (Indonesia/English/Malay/Arab/Thai/…)
    dan 6 subtitle iQIYI, semuanya HTTP 200.

### Catatan

- File MP4 filmbox 8–34 MB, file animekompi sampai 65 MB. Proxy `indocast.site`
  membalas 429/500 kalau request-nya bertumpuk → harness pakai
  `Range: bytes=0-65535` dan jeda antar-request.
- Playback **belum** dikonfirmasi di perangkat. Yang terbukti: request chain
  benar dan provider mengeluarkan URL yang benar-benar bisa diunduh.
