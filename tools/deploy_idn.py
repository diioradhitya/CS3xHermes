#!/usr/bin/env python3
"""
Deploy satu artefak IdnMovieProvider ke semua jalur distribusi + metadata.

Kenapa script ini (bukan langkah manual):
  - metadata `version`/`fileSize`/`fileHash` dan byte artefak HARUS lagi-lagi
    match. Kalau tidak, CloudStream menolak install dengan "Extension hash
    mismatch" — persis yang terjadi saat v6 nyangkut.
  - artefak harus ditulis ke build output, builds/ di branch main, DAN root
    branch builds. Kalau satu lupa, app diam-diam pakai byte lama.
  - verifikasi lewat GitHub contents API (bypass raw CDN), bukan
    raw.githubusercontent.com, karena raw CDN telat beberapa menit dan
    `?cb=` tidak bisa bust cache-nya.

Usage:  python3 tools/deploy_idn.py <path-to-.cs3>
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
import zipfile

REPO = "diioradhitya/CS3xHermes"
INTERNAL_NAME = "IdnMovieProvider"
PLUGIN_URL = f"https://raw.githubusercontent.com/{REPO}/main/builds/IdnMovieProvider.cs3"
WORK = "/opt/data/work"
ARTIFACT_NAMES = ["IdnMovieProvider.cs3"]


def sh(cmd, cwd=WORK, env=None):
    e = dict(os.environ)
    e["GIT_ASKPASS"] = "/opt/data/tools/gh-askpass.sh"
    e["GIT_TERMINAL_PROMPT"] = "0"
    if env:
        e.update(env)
    return subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True,
                          text=True, env=e)


def manifest_version(path):
    """Baca version dari manifest.json DI DALAM zip .cs3.

    Penting: manifest tidak bisa di-grep dari byte mentah karena .cs3 itu zip
    (manifest.json + classes.dex). Grep byte mentah selalu gagal walau build sukses.
    """
    with zipfile.ZipFile(path) as z:
        m = json.loads(z.read("manifest.json").decode("utf-8"))
    return int(m["version"]) if m.get("version") is not None else None


def update_metadata(path, size, digest, version):
    with open(path) as f:
        data = json.load(f)
    hits = 0
    for entry in data:
        if entry.get("internalName") == INTERNAL_NAME:
            entry["fileSize"] = size
            entry["fileHash"] = f"sha256-{digest}"
            entry["version"] = version
            entry["url"] = PLUGIN_URL
            entry["status"] = 1
            hits += 1
    if hits != 1:
        raise SystemExit(f"FAIL: {path} punya {hits} entry {INTERNAL_NAME}, harusnya 1")
    with open(path, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return hits


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src = sys.argv[1]
    blob = open(src, "rb").read()
    size, digest = len(blob), hashlib.sha256(blob).hexdigest()
    version = manifest_version(src)
    if version is None:
        raise SystemExit("FAIL: 'version' tidak ada di manifest .cs3")

    print(f"artefak   : {src}")
    print(f"version   : {version}   <- dari manifest .cs3")
    print(f"size      : {size}")
    print(f"sha256    : {digest}\n")

    branch = sh("git rev-parse --abbrev-ref HEAD").stdout.strip()
    if branch != "main":
        raise SystemExit(f"FAIL: harus di branch main, sekarang {branch}")

    # 1. salin ke output build + jalur distribusi branch main
    for rel in [f"IdnMovieProvider/build/{INTERNAL_NAME}.cs3",
                f"builds/{INTERNAL_NAME}.cs3"]:
        dst = os.path.join(WORK, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "wb").write(blob)
        print(f"  wrote   {rel}  ({os.path.getsize(dst)}B)")

    # 2. metadata
    for rel in ["builds/plugins.json"]:
        update_metadata(os.path.join(WORK, rel), size, digest, version)
        print(f"  updated {rel}")

    # 3. commit + push branch main
    sh("git add -f IdnMovieProvider/build/IdnMovieProvider.cs3")
    sh("git add builds/IdnMovieProvider.cs3 builds/plugins.json")
    r = sh(f'git commit -m "Builds: IdnMovieProvider v{version} artifact {size}B '
           f'(episode stills per-episode w500)"')
    out = (r.stdout + r.stderr).strip()
    if r.returncode == 0:
        print("\ncommit:", out.splitlines()[-1])
    elif "nothing to commit" in out or "no changes added to commit" in out:
        # Artefak sudah identik dengan yang di main (mis. re-run deploy).
        # Ini KEBERHASILAN, bukan kegagalan: yang penting byte di remote cocok.
        print("\ncommit: sudah identik dengan main (tidak ada perubahan)")
    else:
        raise SystemExit(f"FAIL: commit gagal\n{out}")
    r = sh("git push origin main")
    if "main -> main" not in r.stderr and "Everything up-to-date" not in r.stderr:
        raise SystemExit(f"FAIL: push main gagal\n{r.stderr}")
    print("push main:", [l for l in r.stderr.splitlines() if "main" in l][-1:])

    # 4. branch builds (root-level artifact) — tetap disinkronkan
    # PAKAI worktree terpisah, bukan `git checkout`. Checkout di working tree
    # utama gagal (atau diam-diam damaging) kalau ada file uncommitted yang
    # tidak ada di branch tujuan, dan artifact build output ikut hilang.
    # Fetch DULU dengan refspec EKSPLISIT, baru buat worktree.
    # `git fetch origin builds` saja TIDAK memperbarui ref remote-tracking
    # origin/builds pada repo ini, jadi `git rev-parse origin/builds` mengembalikan
    # tip lama -> worktree dibangun dari base salah -> push ditolak
    # (non-fast-forward / lease "stale info") padahal remote sudah benar.
    sh("git fetch --force origin builds:refs/remotes/origin/builds")
    base = sh("git rev-parse origin/builds").stdout.strip()
    staged = f"/tmp/idn_v{version}.cs3"
    open(staged, "wb").write(blob)
    wt = "/tmp/idn_builds_wt"
    sh(f"git worktree remove --force {wt} 2>/dev/null; rm -rf {wt}")
    r = sh(f"git worktree add --detach {wt} {base}")
    if r.returncode != 0:
        raise SystemExit(f"FAIL: worktree builds gagal\n{r.stderr}")
    # helper ditulis ulang ke /tmp karena tidak ikut terbawa worktree
    helper = f"/tmp/sync_builds_{version}.py"
    open(helper, "w").write(open("/opt/data/work/tools/sync_builds_branch.py").read())
    r = sh(f"python3 {helper} {staged} {version}", cwd=wt)
    print("  branch builds:", (r.stdout + r.stderr).strip())
    if r.returncode != 0:
        sh(f"git worktree remove --force {wt}")
        raise SystemExit(f"FAIL: sync branch builds gagal\n{r.stderr}")
    r = sh(f"git add {INTERNAL_NAME}.cs3 plugins.json", cwd=wt)
    r = sh(f'git commit -m "Builds: IdnMovieProvider v{version} artifact {size}B (builds branch mirror)"', cwd=wt)
    out = (r.stdout + r.stderr).strip()
    if r.returncode != 0 and "nothing to commit" not in out and "no changes added to commit" not in out:
        sh(f"git worktree remove --force {wt}")
        raise SystemExit(f"FAIL: commit branch builds gagal\n{out}")
    # `--force-with-lease` (bukan --force): branch builds di-reset dari mirror
    # sehingga history diverge dari remote. Lease menolak kalau remote berubah
    # di luar deploy ini — jadi tetap aman.
    # `--force-with-lease=refs/heads/builds:<sha>` dengan SHA eksplisit yang kita
    # baca sendiri SEBELUM commit. Tanpa argumen eksplisit git melaporkan
    # "stale info" karena lease default-nya memakai ref cache yang basi.
    r = sh(f"git push --force-with-lease=refs/heads/builds:{base} origin HEAD:builds", cwd=wt)
    sh(f"git worktree remove --force {wt}")
    if r.returncode != 0:
        raise SystemExit(f"FAIL: push builds gagal (base {base[:12]})\n{r.stderr}")
    print(f"  push builds: ok (force-with-lease, base {base[:12]})")
    print("  main branch: tidak disentuh (worktree terpisah)")

    # 5. verifikasi lewat GitHub API (bukan raw CDN)
    print("\n=== verifikasi GitHub contents API ===")
    ok = True
    for ref, path in [("main", f"builds/{INTERNAL_NAME}.cs3"),
                      ("main", "builds/plugins.json"),
                      ("builds", INTERNAL_NAME),
                      ("builds", "plugins.json")]:
        url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={ref}"
        time.sleep(0.4)
        with urllib.request.urlopen(urllib.request.Request(
                url, headers={"User-Agent": "curl/8", "Accept": "application/vnd.github.raw+json"}),
                timeout=40) as rr:
            content = rr.read()
        if path.endswith(".cs3"):
            got = hashlib.sha256(content).hexdigest()
            good = got == digest and len(content) == size
            print(f"  {ref:6}/{path:28} {len(content):>6}B  {'MATCH' if good else 'MISMATCH ' + got[:16]}")
            ok &= good
        else:
            e = [x for x in json.loads(content) if x.get("internalName") == INTERNAL_NAME][0]
            good = (e["fileHash"] == f"sha256-{digest}" and e["fileSize"] == size
                    and e["version"] == version and e["url"] == PLUGIN_URL)
            print(f"  {ref:6}/{path:28} v{e['version']} {e['fileSize']}B "
                  f"{'MATCH' if good else 'MISMATCH'}  {e['url']}")
            ok &= good
    print("\n" + ("ALL PATHS CONSISTENT" if ok else "INCONSISTENT -- jangan andalkan install"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
