# 🎬 SFlix — CloudStream Plugin

Personal CloudStream extension untuk streaming film & TV series Indonesia.

## Instalasi

Tambahkan repository URL ini ke CloudStream:

```
https://raw.githubusercontent.com/diioradhitya/CS3xHermes/main/repo.json
```

Lalu install plugin **SFlix** dari repo tersebut.

## Struktur Repo

```
.
├── repo.json          # Repo manifest (pointing ke builds/plugins.json)
├── builds/
│   ├── plugins.json   # Daftar plugin + URL download
│   ├── Sflix-latest.cs3
│   ├── v2026.08.30.1/
│   │   └── Sflix.cs3
│   └── ...
├── source/            # Source code plugin (Kotlin)
│   └── ...
└── README.md
```

## Versi

Menggunakan **CalVer** (`vYYYY.MM.DD.NN`):

- `v2026.08.30.1` = build pertama pada 30 Agustus 2026
- NN naik tiap release di hari yang sama

## Rollback

Untuk revert ke versi sebelumnya di CloudStream:

1. Buka `https://github.com/diioradhitya/CS3xHermes/tree/main/builds/v<versi>`
2. Copy URL `Sflix.cs3` di tag tersebut
3. Install manual lewat CloudStream → Extensions → "Install from URL"

## Changelog

Lihat [Releases](https://github.com/diioradhitya/CS3xHermes/releases) untuk catatan tiap versi.

---

Built with ❤️ by Dio R • Powered by CloudStream + TMDB