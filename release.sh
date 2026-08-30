#!/bin/bash
# SFLIX-DIO release script
# Usage: ./release.sh "commit message"
#
# Workflow:
#  1. Build plugin
#  2. Generate CalVer tag
#  3. Stage & commit changes
#  4. Push to main + push tag
#  5. (Optional) Create GitHub release via `gh` CLI

set -e

REPO_DIR="/DATA/csbuild/github/CS3xHermes"
PLUGIN_DIR="/DATA/csbuild/repo/Sflix"
DIST_DIR="/DATA/csbuild/dist"

MSG="${1:-Update plugin}"
COMMIT_ONLY=0

if [[ "$1" == "--commit-only" ]]; then
  COMMIT_ONLY=1
  MSG="${2:-Update plugin}"
fi

cd "$REPO_DIR"

# === 1. Build (unless commit-only) ===
if [[ $COMMIT_ONLY -eq 0 ]]; then
  echo "=== Building Sflix plugin ==="
  cd "$PLUGIN_DIR/.."
  export ANDROID_HOME=/DATA/android-sdk
  export ANDROID_SDK_ROOT=/DATA/android-sdk
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export PATH=$JAVA_HOME/bin:$PATH
  bash ./gradlew :Sflix:assembleRelease :Sflix:make --no-daemon -q
  cd "$REPO_DIR"

  # Copy artifacts
  cp "$PLUGIN_DIR/build/Sflix.cs3" "$REPO_DIR/Sflix.cs3"
  cp "$PLUGIN_DIR/build/Sflix.cs3" "$REPO_DIR/builds/Sflix-latest.cs3"

  # Copy source
  rm -rf "$REPO_DIR/source"
  mkdir -p "$REPO_DIR/source"
  cp -r "$PLUGIN_DIR/src" "$REPO_DIR/source/"
  cp "$PLUGIN_DIR/build.gradle.kts" "$REPO_DIR/source/"

  # Generate CalVer tag
  DATE_PART=$(date -u +%Y.%m.%d)
  # Find next available NN for today
  NN=1
  while [[ -d "$REPO_DIR/builds/v${DATE_PART}.${NN}" ]]; do
    NN=$((NN+1))
  done
  VERSION="v${DATE_PART}.${NN}"
  echo "=== New version: $VERSION ==="

  mkdir -p "$REPO_DIR/builds/$VERSION"
  cp "$PLUGIN_DIR/build/Sflix.cs3" "$REPO_DIR/builds/$VERSION/Sflix.cs3"

  # Update plugins.json with new version URL
  cat > "$REPO_DIR/builds/plugins.json" <<EOF
[
  {
    "iconUrl": "https://i.imgur.com/example.png",
    "apiVersion": 1,
    "repositoryUrl": "https://github.com/diioradhitya/CS3xHermes",
    "fileSize": $(stat -c '%s' "$REPO_DIR/builds/$VERSION/Sflix.cs3"),
    "status": 1,
    "language": "id",
    "authors": ["Dio R"],
    "tvTypes": ["Movie", "TvSeries"],
    "version": 1,
    "internalName": "SFlix",
    "description": "🎬 SFlix — Movie and TV Series streaming via TMDB metadata + 6 iframe hosts",
    "url": "https://raw.githubusercontent.com/diioradhitya/CS3xHermes/${VERSION}/builds/${VERSION}/Sflix.cs3",
    "name": "SFlix"
  }
]
EOF

  echo "$VERSION" > "$REPO_DIR/.latest_version"
fi

# === 2. Stage & commit ===
echo "=== Committing ==="
git add -A
if git diff --cached --quiet; then
  echo "Nothing to commit."
  exit 0
fi

VERSION=$(cat "$REPO_DIR/.latest_version" 2>/dev/null || echo "")
if [[ -z "$VERSION" ]]; then
  VERSION=$(ls -1 "$REPO_DIR/builds" | grep "^v" | sort -V | tail -1)
fi

git commit -m "[$VERSION] $MSG"

# === 3. Push ===
echo "=== Pushing ==="
git push origin main

if [[ -f "$REPO_DIR/.latest_version" ]]; then
  VERSION=$(cat "$REPO_DIR/.latest_version")
  git tag -f "$VERSION"
  git push origin "$VERSION" --force
  rm "$REPO_DIR/.latest_version"
  echo "=== Tagged $VERSION ==="
fi

echo "=== Release done: $VERSION ==="