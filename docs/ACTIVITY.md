# Activity Log

Catatan aktivitas terakhir di repo `CS3xHermes`. Dipakai sebagai ringkasan saat
commit/push ke GitHub, dan sebagai konteks buat session berikutnya.

Format satu entri per batch kerja, terbaru di atas.

---

## 2026-10-22 — Dua bug global plugin (install error + plugin tak terbaca di Beranda)

### Gejala di TV box (B860H V2.1, Android 6.0.1 / API 23)

Semua plugin gagal install (pesan error) dan tidak ada provider di Beranda.

### Akar masalah #1 — format dex 038 tidak bisa dibaca Android 6

Dari log CloudStream di box:

```
W art    : Failed to open dex file '.../OploverzProvider.617563532.cs3' from memory:
           Unrecognized version number in ...cs3: 0 3 8
E PluginManager: Failed to load ...: ClassNotFoundException:
           Didn't find class "com.oploverz.OploverzPlugin"
```

Semua plugin dibangun dengan `minSdk = 26`, jadi D8 mengeluarkan format dex
**038** yang hanya dipahami ART Android 8.0+ (API 26). ART Android 6 maximum
dex 037 -> **semua** plugin gagal dimuat (bukan cuma Loklok; Oploverz,
Filmlokal, Filmkita error identik).

Fix:
- `build.gradle.kts`: `minSdk 26 -> 21` (D8 memetakan minApi >= 26 ke dex 038,
  minApi < 26 ke dex 035/037). Semua 20 modul kini dex **035**.
- `LayarAsiaProvider` + `LoklokProvider`: `java.util.Base64` (API 26) ->
  `android.util.Base64` (API 8+). Padding ditambahkan manual untuk
  `Base64.decode` karena android butuh padding lengkap.
- **Version semua 20 modul dinaikkan 1** (Dutamovie 1->2 ... Loklok 2->3,
  IdnMovie 9->10, Oploverz 6->7, dst). Wajib: CloudStream menaruh file plugin
  sebagai `<internalName>.<hashCode(internalName)>.cs3` -- angka itu BUKAN
  hash isi file (sudah dihitung ulang: persis `String.hashCode(nama)`), jadi
  file lama tidak otomatis tergantikan. CloudStream hanya mengunduh ulang
  kalau `version` di manifest naik (`isOutdated = online.version > saved.version`).

### Akar masalah #2 — cache jsDelivr 12 jam bikin hash mismatch

CloudStream punya setting "jsDelivr CDN": kalau aktif, URL
`raw.githubusercontent.com/...` diubah jadi `cdn.jsdelivr.net/gh/...@<ref>/...`.
jsDelivr meng-cache file GitHub dengan `s-maxage=43200` (12 JAM) dan
**mengabaikan query string** (query ?v=1 tetap served basi). Akibatnya:

- manifest yang dibaca app masih versi lama (hash file lama),
- file .cs3 di CDN sebagian sudah baru (hash baru),
- -> `fileHash` tidak cocok -> **install gagal** untuk semua plugin.

Diperiksa juga: jsDelivr dalam keadaan "campur" -- `plugins.json` masih v2
Loklok, tapi Dutamovie/IdnMovie sudah dex 035, Oploverz masih dex 038.

### Fix permanen — pin semua URL ke tag immutable

`builds/repo.json` dan `builds/plugins.json` sekarang memakai tag release
sebagai "ref", bukan branch `main`:

```
https://raw.githubusercontent.com/diioradhitya/CS3xHermes/<TAG>/builds/plugins.json
https://raw.githubusercontent.com/diioradhitya/CS3xHermes/<TAG>/builds/<P>.cs3
```

jsDelivr merepresentasikan tag sebagai `@<TAG>`, dan konten sebuah tag tidak
pernah berubah -> cache CDN tidak bisa basi -> hash selalu cocok -> install
tidak pernah gagal karena hash. Manifest yang basi (client masih pegang
manifest versi lama) menunjuk file versi lama yang masih ada, jadi tetap
konsisten.

`build.sh` sekarang:
- `RELEASE_TAG` (default `build-<UTC>`) dipakai untuk rewrite URL di
  `builds/plugins.json` + `builds/repo.json`,
- saat `--publish`, tag dibuat dan di-push **setelah** commit, jadi isi tag
  persis dengan artifact yang di-publish.

Release berjalan: tag `build-20261022-1330`, commit `49b463f`.

### Verifikasi di perangkat (box, Android 6.0.1)

- Repo didaftarkan ulang di app dengan URL tag
  `.../build-20261022-1330/builds/repo.json` (repo `main` lama dihapus,
  `Freemium_Repo` milik user utuh).
- Manifest di app langsung shows versi baru (Dutamovie v2, IdnMovie v10,
  Kawanfilm v8, Harufilm v15, Loklok v3).
- **20/20 plugin repo ini** ter-install dan `Loaded plugin ... successfully`,
  **0** error `Unrecognized version` / `ClassNotFoundException`.
- Daftar provider berisi semua plugin kita; Loklok aktif: Beranda termuat
  ("Drakor Terbaru", "Film Trending"), kartu poster seragam (tidak campur
  potrait/landscape), detail page jalan (10 Episode, badge "Loklok").
- Poster: 30/30 item drakorid di API convert -> 300x420 (dicek live lewat
  `convert.d-cdn.me`).

### Catatan untuk user

Kalau install plugin gagal dengan pesan hash/error setelah release baru:
hapus repo lama di `Settings > Ekstensi`, lalu tambah ulang dengan URL versi
TAG (dari commit release terbaru, contoh
`https://raw.githubusercontent.com/diioradhitya/CS3xHermes/build-20261022-1330/builds/repo.json`).
Atau tunggu <= 12 jam sampai cache jsDelivr kedaluwarsa.

## 2026-10-21 (sore) — Perbaikan dex: plugin tidak bisa dimuat di Android 6

### Gejala

Di TV box (B860H V2.1, Android 6.0.1 / API 23) **semua** plugin gagal
dimuat dan tidak ada provider di beranda. Dari log CloudStream di box:

```
Failed to open dex file '.../LoklokProvider.768872753.cs3' from memory:
  Unrecognized version number in ...cs3: 0 3 8
ClassNotFoundException: Didn't find class "com.loklok.LoklokPlugin"
```

### Akar masalah

- Semua plugin dibangun dengan `minSdk = 26` -> D8 keluarkan format dex
  **038**, yang hanya dipahami ART Android 8.0+ (API 26).
- ART Android 6.0.1 (API 23) maximum dex 037/035 -> **semua** plugin gagal,
  bukan cuma Loklok (Oploverz, Filmlokal, Filmkita, dll. error identik).
- `java.util.Base64` (dipakai LayarAsia + Loklok) butuh API 26 -> harus
  diganti `android.util.Base64` (API 8+) kalau minSdk diturunkan.

### Perbaikan

1. `build.gradle.kts`: `minSdk 26 -> 21`. Semua 20 modul kini menghasilkan
   dex **035** (dicek satu per satu lewat magic bytes).
2. `LayarAsiaProvider` + `LoklokProvider`: `java.util.Base64` ->
   `android.util.Base64` (URL_SAFE|NO_WRAP|NO_PADDING), padding ditambahkan
   manual untuk `Base64.decode`.
3. **Version semua 20 modul dinaikkan 1.** Ini wajib: CloudStream menaruh
   file plugin sebagai `<internalName>.<hashCode(internalName)>.cs3` --
   angka itu BUKAN hash isi file (dihitung ulang: persis
   `String.hashCode(nama)`), jadi file lama tidak otomatis tergantikan.
   CloudStream hanya mengunduh ulang kalau `version` di manifest naik.
   Tanpa bump ini, box tetap memakai file dex 038 yang rusak.

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
