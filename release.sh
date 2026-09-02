#!/bin/bash
# CS3xHermes release script
# Usage: ./release.sh "commit message"
#        ./release.sh --commit-only "commit message"
#        ./release.sh --list-plugins
#
# Workflow:
#   1. Build each plugin module (skip ones in DISABLED_PLUGINS)
#   2. Copy shared-extractors/Extractors.kt into each plugin module
#      (sed replaces package name to match plugin namespace)
#   3. Generate CalVer tag (vYYYY.MM.DD.NN)
#   4. Stage & commit changes
#   5. Push to main + push tag

set -e

REPO_DIR="/DATA/csbuild/github/CS3xHermes"
SOURCE_REPO="/DATA/csbuild/repo"
SHARED_EXTRACTORS="$REPO_DIR/shared-extractors/Extractors.kt"
MASTER_PACKAGE="com.cs3xhermes.extractors"

# Plugins to skip (mirrors settings.gradle.kts disabled list).
# When you add new disabled plugins in /DATA/csbuild/repo/settings.gradle.kts,
# add them here too so release.sh doesn't try to build them.
DISABLED_PLUGINS=(
    "Dutamovie"
    "Filmkita"
    "Filmlokal"
    "Indomax"
    "KlikXXi"
    "LayarWarna"
    "Ngefilm"
    "Nomat"
    "Pencurimovie"
    "Pusatfilm"
    "Pusatmovie"
    "Sarangfilm"
    "Savefilm"
    "WGFilm21"
)

MSG="${1:-Update plugin}"
COMMIT_ONLY=0
LIST_ONLY=0

if [[ "$1" == "--commit-only" ]]; then
  COMMIT_ONLY=1
  MSG="${2:-Update plugin}"
elif [[ "$1" == "--list-plugins" ]]; then
  LIST_ONLY=1
fi

cd "$REPO_DIR"

# === Discover enabled plugins ===
PLUGINS=()
for dir in "$SOURCE_REPO"/*/; do
  name=$(basename "$dir")
  if [[ -f "$dir/build.gradle.kts" && ! " ${DISABLED_PLUGINS[@]} " =~ " $name " ]]; then
    PLUGINS+=("$name")
  fi
done

if [[ $LIST_ONLY -eq 1 ]]; then
  echo "Enabled plugins:"
  for p in "${PLUGINS[@]}"; do echo "  - $p"; done
  exit 0
fi

# === 1. Build plugins (unless commit-only) ===
BUILT_PLUGINS=()
if [[ $COMMIT_ONLY -eq 0 ]]; then
  if [[ ! -f "$SHARED_EXTRACTORS" ]]; then
    echo "ERROR: $SHARED_EXTRACTORS not found."
    echo "Master Extractors.kt template is required for build."
    exit 1
  fi

  echo "=== Discovered ${#PLUGINS[@]} plugin(s) ==="
  for p in "${PLUGINS[@]}"; do echo "  - $p"; done

  export ANDROID_HOME=/DATA/android-sdk
  export ANDROID_SDK_ROOT=/DATA/android-sdk
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export PATH=$JAVA_HOME/bin:$PATH

  cd "$SOURCE_REPO"

  for plugin in "${PLUGINS[@]}"; do
    echo ""
    echo "=== Building $plugin ==="

    # 1a. Inject Extractors.kt into plugin module with correct package
    # Use lowercase plugin name for path (Kotlin package conventions are lowercase)
    plugin_lc=$(echo "$plugin" | tr '[:upper:]' '[:lower:]')
    plugin_pkg_dir="$SOURCE_REPO/$plugin/src/main/kotlin/com/$plugin_lc"
    mkdir -p "$plugin_pkg_dir"
    target="$plugin_pkg_dir/Extractors.kt"

    # Copy master file, then sed package line to match plugin namespace (lowercase)
    cp "$SHARED_EXTRACTORS" "$target"
    sed -i "s/^package ${MASTER_PACKAGE//./\\.}/package com.${plugin_lc}/" "$target"

    # 1b. Build
    bash ./gradlew ":${plugin}:assembleRelease" ":${plugin}:make" --no-daemon -q || {
      echo "ERROR: build failed for $plugin"
      exit 1
    }

    if [[ -f "$SOURCE_REPO/$plugin/build/${plugin}.cs3" ]]; then
      BUILT_PLUGINS+=("$plugin")
      echo "  ✓ Built ${plugin}.cs3"
    else
      echo "  ⚠ No ${plugin}.cs3 produced"
    fi
  done

  cd "$REPO_DIR"

  if [[ ${#BUILT_PLUGINS[@]} -eq 0 ]]; then
    echo "ERROR: no plugins built successfully"
    exit 1
  fi

  # === 2. Generate CalVer tag ===
  DATE_PART=$(date -u +%Y.%m.%d)
  NN=1
  while [[ -d "$REPO_DIR/builds/v${DATE_PART}.${NN}" ]]; do
    NN=$((NN+1))
  done
  VERSION="v${DATE_PART}.${NN}"
  echo ""
  echo "=== New version: $VERSION ==="

  mkdir -p "$REPO_DIR/builds/$VERSION"

  # === 3. Stage per-version artifacts ===
  for plugin in "${BUILT_PLUGINS[@]}"; do
    cp "$SOURCE_REPO/$plugin/build/${plugin}.cs3" "$REPO_DIR/builds/$VERSION/${plugin}.cs3"
  done

  # === 4. Generate plugins.json ===
  PLUGIN_ENTRIES=""
  first=1
  for plugin in "${BUILT_PLUGINS[@]}"; do
    cs3file="$REPO_DIR/builds/$VERSION/${plugin}.cs3"
    size=$(stat -c '%s' "$cs3file")

    # Read metadata from plugin's build.gradle.kts (optional)
    desc=$(grep -oP 'description\s*=\s*"\K[^"]+' "$SOURCE_REPO/$plugin/build.gradle.kts" 2>/dev/null | head -1 || echo "")
    lang=$(grep -oP 'language\s*=\s*"\K[^"]+' "$SOURCE_REPO/$plugin/build.gradle.kts" 2>/dev/null | head -1 || echo "id")
    tvtypes=$(grep -A3 'tvTypes' "$SOURCE_REPO/$plugin/build.gradle.kts" 2>/dev/null | grep -oP '"\K[A-Za-z]+(?=")' | tr '\n' ',' | sed 's/,$//')
    [[ -z "$tvtypes" ]] && tvtypes="Movie,TvSeries"

    if [[ $first -eq 0 ]]; then
      PLUGIN_ENTRIES+=","
    fi
    PLUGIN_ENTRIES+="
  {
    \"iconUrl\": \"https://www.google.com/s2/favicons?domain=$plugin&sz=%size%\",
    \"apiVersion\": 1,
    \"repositoryUrl\": \"https://github.com/diioradhitya/CS3xHermes\",
    \"fileSize\": $size,
    \"status\": 1,
    \"language\": \"$lang\",
    \"authors\": [\"Dio R\"],
    \"tvTypes\": [$(echo "$tvtypes" | sed 's/,/", "/g' | sed 's/.*/"&"/')],
    \"version\": ${NN},
    \"internalName\": \"$plugin\",
    \"description\": \"${desc:-\"🎬 $plugin - Streaming plugin\"}\",
    \"url\": \"https://raw.githubusercontent.com/diioradhitya/CS3xHermes/${VERSION}/builds/${VERSION}/${plugin}.cs3\",
    \"name\": \"${plugin}xHermes\"
  }"
    first=0
  done

  cat > "$REPO_DIR/builds/plugins.json" <<EOF
[${PLUGIN_ENTRIES}
]
EOF

  # === 5. Save source snapshot ===
  rm -rf "$REPO_DIR/source"
  mkdir -p "$REPO_DIR/source"
  for plugin in "${BUILT_PLUGINS[@]}"; do
    mkdir -p "$REPO_DIR/source/$plugin"
    cp -r "$SOURCE_REPO/$plugin/src" "$REPO_DIR/source/$plugin/"
    cp "$SOURCE_REPO/$plugin/build.gradle.kts" "$REPO_DIR/source/$plugin/"
  done

  echo "$VERSION" > "$REPO_DIR/.latest_version"
fi

# === 6. Stage & commit ===
echo ""
echo "=== Committing ==="
git add -A
if git diff --cached --quiet; then
  echo "Nothing to commit."
  exit 0
fi

VERSION=$(cat "$REPO_DIR/.latest_version" 2>/dev/null || echo "")
if [[ -z "$VERSION" ]]; then
  VERSION=$(ls -1 "$REPO_DIR/builds" 2>/dev/null | grep "^v" | sort -V | tail -1 || echo "")
fi

git commit -m "[$VERSION] $MSG"

# === 7. Push ===
echo ""
echo "=== Pushing ==="
git push origin main

if [[ -f "$REPO_DIR/.latest_version" ]]; then
  VERSION=$(cat "$REPO_DIR/.latest_version")
  git tag -f "$VERSION"
  git push origin "$VERSION" --force
  rm "$REPO_DIR/.latest_version"
  echo "=== Tagged $VERSION ==="
fi

echo ""
echo "=== Release done: $VERSION ==="
echo "Built: ${BUILT_PLUGINS[*]}"