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

TOOLS="$HOME/tools"
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
ls -la builds/*.cs3 builds/plugins.json

if [ "${1:-}" = "--publish" ]; then
  step "commit + push to origin/main"
  git add -A builds/
  if git diff --staged --quiet; then
    echo "  tidak ada perubahan artifact, skip commit"
  else
    git commit -m "build: publish artifacts"
    git push origin main
  fi
else
  step "selesai (jalankan dengan --publish untuk commit + push)"
fi
