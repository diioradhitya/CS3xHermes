#!/usr/bin/env bash
# Build the CS3xHermes CloudStream extensions on this Linux machine.
#
# Mirrors what .github/workflows/build.yml does, so local and CI produce the
# same result:
#   1. ./gradlew make makePluginsJson
#   2. copy every */build/*.cs3 plus the generated manifest into builds/
#   3. clear stale .cs3 first so removed plugins cannot linger
#
# Usage:
#   ./build.sh              # build only
#   ./build.sh --publish    # build, then commit + push builds/ to origin/main
#
# JitPack auth (build.gradle.kts pulls com.github.recloudstream:gradle:-SNAPSHOT)
# is read from the environment. Export JITPACK_USERNAME / JITPACK_PASSWORD first.
set -euo pipefail

# Toolchain lokasi. Di mesin ini tools ada di /home/diora/tools, tapi $HOME
# bisa berbeda (root punya HOME=/root), jadi "$HOME/tools" kadang menunjuk
# ke tempat yang tidak ada -> "JAVA_HOME is set to an invalid directory".
# Pilih direktori yang benar-benar berisi jdk21.
pick_tools() {
    for d in "$HOME/tools" /home/diora/tools /root/tools /opt/tools; do
        [ -x "$d/jdk21/bin/java" ] && { echo "$d"; return; }
    done
    echo "$HOME/tools"
}

TOOLS="$(pick_tools)"
if [ ! -x "$TOOLS/jdk21/bin/java" ]; then
    echo "ERROR: tidak menemukan jdk21 di \$HOME/tools, /home/diora/tools, /root/tools, /opt/tools" >&2
    exit 1
fi
export JAVA_HOME="$TOOLS/jdk21"
export ANDROID_HOME="$TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$TOOLS/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-diioradhitya/CS3xHermes}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

step() { printf '\n\033[1;36m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$1"; }

step "toolchain"
java -version 2>&1 | head -1

step "build (make makePluginsJson)"
chmod +x ./gradlew
./gradlew make makePluginsJson --console=plain

step "publish artifacts into builds/"
rm -f builds/*.cs3 builds/plugins.json
shopt -s nullglob
for f in */build/*.cs3; do
  cp "$f" "builds/$(basename "$f")"
done
cp build/plugins.json builds/plugins.json

# makePluginsJson menulis URL artifact tanpa segmen ref:
#   https://raw.githubusercontent.com/<repo>/builds/<P>.cs3
# Semua URL artifact DIPIN ke tag release (RELEASE_TAG), bukan ke branch
# "main". Alasannya: CloudStream (kalau setting "jsDelivr CDN" aktif)
# mengubah URL raw.githubusercontent.com menjadi
#   https://cdn.jsdelivr.net/gh/<repo>@<ref>/...
# dan jsDelivr meng-cache file GitHub dengan s-maxage=43200 (12 JAM).
# File yang kontennya berubah di URL yang sama akanPresentation basi ->
# manifest lama + hash lama + file baru = hash mismatch = INSTALL GAGAL.
# Dengan URL yang dipin ke TAG, isi file tidak pernah berubah, jadi cache
# jsDelivr selalu benar dan hash selalu cocok.
RELEASE_TAG="${RELEASE_TAG:-build-$(date -u +%Y%m%d-%H%M)}"
sed -i "s#https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/builds/#https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/${RELEASE_TAG}/builds/#g" builds/plugins.json
# builds/repo.json SENGAJA dibiarkan menunjuk path STABIL
#   .../main/builds/plugins.json
# Jadi user cukup daftar repo sekali, tidak perlu daftar ulang tiap release.
# Yang di-pin ke tag hanya URL ARTIFACT di dalam manifest: supaya file yang
# diunduh app tidak pernah berubah isinya (hash selalu cocok) walau CDN
# (jsDelivr) cache 12 jam.
ls -la builds/*.cs3 builds/plugins.json
echo "RELEASE_TAG=$RELEASE_TAG"

if [ "${1:-}" = "--publish" ]; then
  step "commit + push to origin/main"
  git add -A builds/
  if git diff --staged --quiet; then
    echo "  tidak ada perubahan artifact, skip commit"
  else
    git commit -m "build: publish artifacts"
    git push origin main
  fi
  # Tag release dipakai sebagai "ref" immutable di URL manifest + artifact.
  # Tag dibuat setelah commit supaya isinya persis artifact yang di-publish.
  step "publish tag $RELEASE_TAG"
  git tag -f "$RELEASE_TAG"
  git push origin "refs/tags/$RELEASE_TAG"
else
  step "selesai (jalankan dengan --publish untuk commit + push)"
fi
