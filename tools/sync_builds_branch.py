#!/usr/bin/env python3
"""Sinkronkan artefak + metadata di branch builds (artefak ada di ROOT, bukan builds/)."""
import hashlib
import json
import os
import sys

REPO = "diioradhitya/CS3xHermes"
INTERNAL_NAME = "IdnMovieProvider"
PLUGIN_URL = f"https://raw.githubusercontent.com/{REPO}/main/builds/IdnMovieProvider.cs3"

src, version = sys.argv[1], int(sys.argv[2])
blob = open(src, "rb").read()
digest = hashlib.sha256(blob).hexdigest()

open("IdnMovieProvider.cs3", "wb").write(blob)

data = json.load(open("plugins.json"))
hits = 0
for e in data:
    if e.get("internalName") == INTERNAL_NAME:
        e["fileSize"] = len(blob)
        e["fileHash"] = f"sha256-{digest}"
        e["version"] = version
        e["url"] = PLUGIN_URL
        e["status"] = 1
        hits += 1
if hits != 1:
    raise SystemExit(f"FAIL: {hits} entry {INTERNAL_NAME} di plugins.json, harusnya 1")
with open("plugins.json", "w") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
    f.write("\n")

print(f"builds-branch synced: v{version} {len(blob)}B sha256-{digest[:16]}…")
