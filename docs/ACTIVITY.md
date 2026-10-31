# Activity Log

Catatan aktivitas terakhir di repo `CS3xHermes`. Dipakai sebagai ringkasan saat
commit/push ke GitHub, dan sebagai konteks buat session berikutnya.

Format satu entri per batch kerja, terbaru di atas.

---

## 2026-10-23 (siang) — Oploverz v14: SUMBER UTAMA pindah ke v2.samehadaku.how

### Situs oploverz.am migrasi lagi (9-10 AM)

Diagnosis pagi ini (curl dari server diora-nawa):
- Listing `/anime/?status=&type=&order=update` yang semalam masih hidup → **404**
  (link nav homepage sendiri tetap menunjuk URL itu: situs sedang rusak).
- Permalink halaman series massal rusak: `/anime/bleach/` **404**
  (padahal link-nya ada di nav), `/anime/ao-ashi-s2/` **301 ke post episode**
  (slug lama), `/anime/naruto-kecil-movie-3/` 301 → slug panjang → 404 juga.
- Pencarian `/?s=` balas 200 tapi berisi 10 `<article>` skeleton tanpa link
  (hasil dirender JS).
- `wp-json/` masih hidup dan `"name"` sekarang **"oploverz.best | Situs
  Oploverz yang asli"** → mereka mengganti identitas domain ke `.best`;
  TLS ke `oploverz.best` dari server gagal semua (curl 000, Broken pipe).
- Kesimpulan: situs sedang MIGRASI BESAR; plugin tidak bisa mengandalkan
  oploverz untuk browse.
- Bonus masalah: Cloudflare mulai mematikan handshake TLS okhttp/JVM
  (SSLHandshakeException "Remote host terminated the handshake") secara
  intermittent — curl dari IP sama tetap 200.

### Keputusan: primary source = v2.samehadaku.how (perinta user)

Plugin browse-seluruhnya (home/search/load) sekarang dari samehadaku;
Oploverz tetap dipakai **hanya sebagai fallback video** di loadLinks
(post episode `/slug-episode-N-subtitle-indonesia/` masih hidup).

Struktur new theme samehadaku (Tailwind, BUKAN Madara — classes
eplister/bs/bixbox tidak ada):
- Listing kartu: `div.thumb > a[href=post-episode]` (poster) + `div.dtla >
  h2.entry-title > a[href=/anime/<slug>/]` — halaman series langsung,
  tidak perlu lagi strip-slug episode.
- Listing path: `/anime-terbaru/` (+ `/page/N/`), root `/` (+ `/page/N/`)
  (CATATAN: `/page/1/` kosong — pakai path dasar untuk halaman 1).
- Search: `/?s=<q>` → `article.animepost` (kartu ke post episode; slug
  episode = slug series → normalisasi ke `/anime/<slug>/`), halaman 2 via
  `/page/N/?s=<q>`. Ditambah tebakan slug langsung `/anime/<query>/`
  (result paling relevan muncul duluan).
- Halaman series: `h1[itemprop=headline]` ("<Judul> Sub Indo"), info rows
  `div.flex.justify-between` (label `span.w-28`, ambil Type/Status),
  sinopsis `div.entry-content p`, poster img di kartu detail, genre
  `a[href*=/genre/]`, baris episode `<a href="<host>/<slug>-episode-N/">`
  disaring prefix slug series (widget episode anime lain di sidebar
  bisa ikut kalau tidak disaring), rekomendasi dari widget `a.series`.
- Baris episode link nomor & link judul menunjuk URL sama → dedupe per URL.
- Bug parse: jsoup `substringAfterLast('/')` pada href ber-trailing-slash
  menghasilkan string kosong → pakai `removeSuffix("/")` dulu.
- Film samehadaku: sebagian halaman film TIDAK punya baris episode
  (gintama-movie-3) = tidak ada player; yang punya (dragon-ball-super-beerus)
  serangannya lewat `MovieLoadResponse.dataUrl` = post episode.

### loadLinks (sumber video)

- data = URL episode samehadaku → **langsung** `Samehadaku.episodeServers()`
  (tidak ada lagi mapping judul+episode antar situs):
  server Blogspot (blogger.com multi-itag 360p/720p/1080p via extractor
  Blogger) + Mega 480p/720p/1080p/4K (validator `Mega.isFileAlive`).
- Fallback tetap: kalau data oploverz ATAU server samehadaku kosong →
  parse post episode oploverz (`/slug-episode-N-subtitle-indonesia/`)
  → mapping samehadaku lama (guess/resolution) + iframe embedder bawaan
  (upbolt/blogger/filedon) dengan host failover lama.
- Validasi link masih paralel (v13): hidup ≥ gugur, Mega hidup terakhir.

### Verifikasi (harness `/root/opl-verify`, run26.log)

**35 PASS / 0 FAIL** — semua skenario hijau:
- Main page 2 tab (Terbaru 14, Terpopuler 16) semua menunjuk `/anime/`,
  judul bersih, poster 14/14; pagination overlap=0.
- Search "naruto" → result `/anime/naruto/` + `/anime/naruto-kecil/`.
- Load series (Hyouken S2): judul/plot/genre 22/rekomendasi 7/episode
  1-2-3 terurut/poster.
- loadLinks episode langsung samehadaku → Blogger 360p valid
  (googlevideo, no escape); `[5a]` 5 server langsung (Blogspot + Mega
  480/720/1080/4K); `[5a2]` film punya 5+ server termasuk Mega 1080p & 4K.
- Fallback oploverz: BLOGGER → 2 link (720p+360p), UPBOLT → 1 link 480p,
  urutan link hidup > mati tetap benar (`[5c]`).
- UpBolt langsung: iframe + 1 link HLS 480p + 1 subtitle.

### Lain-lain

- `OploverzProvider/build.gradle.kts`: `version 13 → 14`, description
  diperbarui (sumber utama samehadaku).
- Verifikasi penuh di server (diora-nawa), bukan cuma lokal.
- CATATAN: migrasi tema samehadaku ini sendiri baru-baru ini (v2.*);
  kalau markup berubah lagi, pola markup di atas yang harus dicek dulu.

---

## 2026-10-23 — Oploverz v13: fix root-cause Blogger 403/400, validasi paralel, validator Mega.nz

### 1. Root-cause link Blogger "flaky" (403/400) — URL terpotong di '&'

Extractor Blogger lama scan URL GoogleVideo dengan pola `[^"&]*`, lalu decode
`\u0026` per-link. Masalahnya: halaman video.g Blogger TIDAK konsisten — kadang
URL ter-escape (`\u0026`), kadang terbalas mentah dengan `&` asli / `&amp;`.
Kalau mentah, regex berhenti di `&` PERTAMA sehingga semua parameter setelahnya
(signature, mime, expire) hilang → googlevideo membalas 403/400. Inilah kenapa
link Blogger "kadang jalan kadang mati": tergantung bentuk escape halaman itu.

Fix (`Extractors.kt`, `Blogger.getUrl`): **decode SEMUA escape terlebih dahulu**
(`\u003d`, `\u0026`, `\u0022`, `\/`, `&quot;`, `&amp;`), baru scan URL utuh
dengan pola `[^"'\s\\<>]+`. URL kini selalu sampai `"` penutup, lengkap semua
parameternya, untuk SEMUA variasi halaman.

### 2. Validasi link paralel (was: berurutan ~N x round-trip)

`loadLinks` dulu memeriksa 1 HEAD/GET-Range per link BERURUTAN (link Blogger ~3
itag + UpBolt + fallback → bisa >5 detik di box Android sebelum daftar sumber
muncul). Sekarang semua pemeriksaan (`isMediaLinkAlive` + validator Mega) jalan
`async { } / awaitAll()` dalam satu `coroutineScope` (Oploverz.kt s.3).

Fix tambahan `isMediaLinkAlive` (Http.kt):
- retry 2x dengan jeda 300 ms (googlevideo kadang balas 403 sementara saat
  throttle entry) sebelum menyatakan link mati,
- HTTP 416 ikut dianggap hidup (server tahu file-nya; range yang diminta cuma
  1 KB),
- timeout 10 s eksplisit.

### 3. Validator Mega.nz (kurangi Error 3003 dari entry mati)

Mega.nz **tidak bisa diputar native** CloudStream (file di-serve terenkripsi
AES-CBC, dekrip di client) — status itu tidak berubah. Yang dibuang:
setiap gelar episode Samehadaku memasukkan link Mega TANPA cek filenya masih
ada, jadi file yang sudah dihapus/dinonaktifkan tetap muncul dan pasti Error
3003 saat dipilih.

Module baru `Mega.kt`:
- `fileHandle()` — parse handle dari format `/embed/<h>#<key>`,
  `/embed/<h>!<key>`, `/file/<h>#...`, `/#!<h>!<key>`.
- `isFileAlive()` — 1 POST ke `g.api.mega.co.nz/cs` dengan body
  `[{"a":"g","p":<handle>}]` (NiceHttp `json = listOf(mapOf(...))`, `data=null`
  — di NiceHttp map `data` menimpa `json`). Balasan objek (`{"g":...}`) =
  hidup; kode negatif `[-9]`/`[-11]` = mati → entry dibuang.
- Kalau API tidak bisa dihubungi → anggap hidup (perilaku lama), jangan buang
  link yang mungkin valid.

Fungsi: mega links yang masih hidup tetap di urutan terakhir (untuk copy link
ke downloader/aplikasi Mega), yang mati hilang dari daftar.

### 4. Lain-lain

- `OploverzProvider/build.gradle.kts`: `version 12 → 13`, description
  diperbarui (tidak lagi menyebut backapi.oploverz.ac yang sudah mati).
- Verifikasi: BELUM dijalankan di harness/box Android (server dibawah
  diora-nawa punya harness `/root/opl-verify`); build & publish artefak
  (`.cs3` + plugins.json pin tag) dilakukan oleh release.sh di server.
- CATATAN: di workflow server, `Extractors.kt` disuntik dari
  `shared-extractors/Extractors.kt` master (`/DATA/csbuild/repo`) oleh
  release.sh — suntingan Blogger di sini HARUS dicerminkan ke master shared
  extractors, kalau tidak akan ditimpa saat release berikutnya.

---

## 2026-10-22 (malam) — Oploverz pindah ke domain baru (oploverz.am/.ch/.site)

### Gejala

Domain lama `anime.oploverz.ac` **mati**: DNS-nya resolve ke `43.173.57.48`
(`blockpage.xlaxiata.id`) -> connection timeout. `oploverz.site` resolve ke IP
yang sama, jadi juga tidak bisa dipakai. Plugin tidak bisa ambil data apa pun.

### Cek ketiga domain

| Domain | Hasil |
|---|---|
| oploverz.site | IP sama dgn situs lama (blockpage) -> HTTP 000, tidak bisa dipakai |
| oploverz.am | HTTP 200, situs Madara aktif |
| oploverz.ch | HTTP 200, situs Madara aktif |

Fallback berurutan: `oploverz.am` -> `oploverz.ch` -> `oploverz.site`.

### Situs baru = tema Madara (struktur beda total)

- Listing: `/anime/?status=&type=&order=update[&page=N]`
- Halaman anime: `/anime/<slug>/`
- Halaman episode: `/<slug>-episode-<N>-subtitle-indonesia/`
- Cari: `/?s=<query>`
- Item: `<article class="bs">` > `div.bsx` > `a[href]` + `img[itemprop=image]`
  + `div.typez` (tipe) + `span.epx` (status) + `h2` (judul)
- Episode: `div.eplister` > `li[data-index]` > `a` > `div.epl-num`
- Info box: `<span><b>Type:</b> TV</span>`, `Status`, `Released` (tahun)
- Player: `<div id="embed_holder"><div id="pembed"><iframe src=...>`

### Perubahan

- `mainUrl` -> daftar host + failover di `fetchDoc()`: hanya menerima response
  yang memuat marker DOM yang diharapkan (jadi halaman blokir/404 tidak
  dianggap sukses). URL absolut **tidak** ditukar host.
- `getMainPage` -> `/anime/?...&order=<order>&page=N`; `search` -> `/?s=`
- `load` -> judul dari `h1.entry-title`, tipe/status/tahun dari info box,
  poster `div.thumb > img`, episode dari `div.eplister` (urut naik)
- Film (`Type: Movie`) -> `MovieLoadResponse` dengan `dataUrl` = post episode
  pertama (halaman `/anime/<movie>/` cuma daftar, player-nya di post episode).
  Tanpa ini film jadi 0 episode.
- `loadLinks` -> semua iframe dicoba (tiap episode beda embedder: upbolt.to,
  blogger.com, filedon.co) + referer = URL halaman episode
- Extractor **UpBolt** (format baru): POST ke `/dl`
  (`op=embed&file_code=..&auto=1&referer=..`) -> master.m3u8 + `.vtt` +
  kualitas di `<title>`; ekstraktor StreamWish bawaan tidak bisa
- Extractor **Blogger** (baru): URL GoogleVideo dibungkus escape unicode
  (`\u003d`, `\u0026`) dan delimiter `&quot;`; pola
  `https://[^"&]*googlevideo\.com/videoplayback[^"&]*` + decode
- `addMalId`/`addAniListId` dibungkus `runCatching` (id dari tracker kadang
  bukan JSON string -> SDK lempar `JsonDecodingException` yang menggagalkan load)

### Verifikasi

- Harness JVM live (`/root/opl-verify`): **pass=31 fail=0** — 4 tab beranda
  (10 item), pagination tanpa duplikat, search 20 hasil, SERI Bleach 413
  episode, FILM MovieLoadResponse + dataUrl, loadLinks -> link Blogger itag=18
  (URL valid), HLS upbolt probe OK
- Box (Android 6.0.1): Oploverz **v8** ter-install & `Loaded plugin
  OploverzProvider successfully`, 0 error. Dipilih jadi provider aktif ->
  beranda termuat "JoJo's Bizarre Adventure 6 : Steel Ball Run" (2026) dengan
  genre Action/Adventure/Historical/Mystery/Seinen/Shounen
- Publish: tag `build-20261022-1745`, commit `18ebed7`; artifact 42663 B,
  sha256 `32600872edab4646...`, dex 035, string baru ada & string lama hilang
- TIDAK DIVERIFIKASI: playback akhir di perangkat (butuh remote). Episode
  film contoh (Naruto Kecil Movie 3) sumbernya `filedon.co` yang balas
  Cloudflare 522 (server embedder mati, bukan masalah plugin)

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

Hanya **URL artifact di dalam manifest** yang di-pin ke tag release. URL
manifest sendiri (`builds/repo.json` -> `pluginLists`) sengaja dibiarkan
stabil di `.../main/builds/plugins.json`, jadi user cukup daftar repo
sekali dan tidak perlu daftar ulang tiap release:

```
builds/repo.json      -> .../main/builds/plugins.json     (STABIL, sekali daftar)
builds/plugins.json   -> .../<TAG>/builds/<P>.cs3          (pin ke tag, immutable)
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
