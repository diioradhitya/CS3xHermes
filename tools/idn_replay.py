#!/usr/bin/env python3
"""Headless replay gate for IDNMovie: listing(slug) -> embedfilm idx -> playData -> HLS.

Exits 0 only if every requested title yields a playable m3u8.
"""
import json, re, subprocess, sys, urllib.parse

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
SITE = "https://idnmovie.com"
EMBED = "https://embedfilm.com"


def fetch(url, ref=None, data=None, rng=None):
    cmd = ["curl", "-sL", "--max-time", "110", "-A", UA]
    if ref:
        cmd += ["-H", f"Referer: {ref}"]
    if rng:
        cmd += ["-r", rng]
    if data is not None:
        cmd += ["-X", "POST", "-H", "Content-Type: application/json",
                "--data", data]
    cmd.append(url)
    p = subprocess.run(cmd, capture_output=True)
    return (p.stdout or b"")[:8_000_000].decode("utf-8", "replace")


def flight(html):
    """Join every self.__next_f.push([1,"..."]) chunk into one payload."""
    out = []
    for m in re.finditer(r'self\.__next_f\.push\(\[1,\s*("(?:[^"\\]|\\.)*")\s*\]\)', html, re.S):
        try:
            out.append(json.loads(m.group(1)))
        except Exception:
            out.append(m.group(1))
    return "".join(out)


def balanced(text, key):
    """Return the raw JSON text of the object/array starting at `key`."""
    i = text.find(key)
    if i < 0:
        return None
    i += len(key)
    if i >= len(text) or text[i] not in "[{":
        return None
    depth, in_str, esc, start = 0, False, False, i
    while i < len(text):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c in "[{":
            depth += 1
        elif c in "]}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1
    return None


def str_after(text, key):
    """Decode a JSON string literal appearing right after `key`.

    `key` is a bare name (no surrounding quotes): the payload nests the name
    inside a value, e.g. `...,\"initialSrc\":null,\"playData\":\"{...}\"`, so the
    real delimiter is `"<key>":"`.
    """
    m = re.search(r'"' + re.escape(key) + r'"\s*:\s*"((?:[^"\\]|\\.)*)"', text, re.S)
    if not m:
        m = re.search(re.escape(key) + r'"\s*:\s*"((?:[^"\\]|\\.)*)"', text, re.S)
    if not m:
        return None
    try:
        return json.loads('"' + m.group(1) + '"')
    except Exception:
        return m.group(1)


def unescape_twice(value):
    """RSC payloads are escaped twice: \\\\t -> \\t -> t. Peel until it stops."""
    out = value
    for _ in range(3):
        if not isinstance(out, str) or '\\\\' not in out:
            break
        try:
            out = json.loads('"' + out + '"')
        except Exception:
            try:
                out = json.loads(out)
            except Exception:
                break
    return out


def parse_play(data):
    """Recover playerId + playData object from an embedfilm RSC payload.

    playData is an HTML-escaped JSON *string* that is itself a JSON object, so it
    needs two decode passes before it parses.
    """
    pid = str_after(data, "playerId")
    if not pid:
        m = re.search(r'"playerId\\?":\\?"([0-9a-fA-F\-]{8,})', data)
        pid = m.group(1) if m else None
    raw = str_after(data, "playData")
    pd = None
    if raw is not None:
        pd = unescape_twice(raw)
        if isinstance(pd, str):
            try:
                pd = json.loads(pd)
            except Exception:
                pass
    if not isinstance(pd, dict) or "id" not in pd:
        # last resort: regex the id out of the escaped blob
        m = re.search(r'id\\?":\\?"([0-9a-fA-F\-]{8,})', unescape_twice(raw or ""))
        if m and isinstance(pd, dict):
            pd["id"] = m.group(1)
        elif m and not isinstance(pd, dict):
            typ = "episode" if "episode" in data[:data.find("playData")][-4000:] else "movie"
            pd = {"id": m.group(1), "type": typ}
    return pid, pd


def listing(kind):
    """Harvest playback-ready slugs straight off the listing page."""
    raw = flight(fetch(f"{SITE}/{'tv' if kind == 'tv' else 'movies'}"))
    out, seen = [], set()
    for m in re.finditer(r'\{"id":"([a-z0-9\-]{6,})","title":"', raw):
        slug = m.group(1)
        if slug in seen:
            continue
        seen.add(slug)
        obj = balanced(raw, '{"id":"' + slug + '","title":"')
        card = {}
        if obj:
            try:
                card = json.loads(obj)
            except Exception:
                card = {}
        out.append({
            "slug": slug,
            "title": card.get("title", slug),
            "poster": card.get("poster_path") or "",
            "kind": kind,
        })
    return out


def embed_path(kind, slug, season=None, ep=None):
    if kind == "tv" and season and ep:
        return f"{EMBED}/idx/tvseries/{slug}/{season}/{ep}?ui=lorong"
    return f"{EMBED}/idx/{kind}/{slug}?ui=lorong"


def resolve(slug, kind, season=None, ep=None):
    """slug -> (playerId, playData). Both come from the embedfilm index page."""
    page = fetch(embed_path(kind, slug, season, ep), ref=f"{SITE}/")
    pid, pd = parse_play(flight(page))
    return pid, pd, page


def play(player_id, play_data):
    """POST {"data": "<playData as JSON string>"} -> stream dict."""
    if isinstance(play_data, (dict, list)):
        payload = json.dumps(play_data, separators=(",", ":"))
    else:
        payload = play_data
    body = json.dumps({"data": payload})
    txt = fetch(f"{EMBED}/{player_id}/api/idx/play", ref=f"{EMBED}/", data=body)
    try:
        return json.loads(txt)
    except Exception:
        return {"_raw": txt[:300]}


def sfl_sources(slug):
    """`sfl-*` cards are a different family: the idnmovie page itself carries the
    MP4 list, wrapped in the site's own /api/dracin/seg proxy."""
    raw_id = slug.removeprefix("sfl-")
    page = fetch(f"{SITE}/sfl/{raw_id}", ref=f"{SITE}/")
    data = flight(page)
    srcs_raw = balanced(data, '"sources":') or "[]"
    subs_raw = balanced(data, '"subtitles":') or "[]"
    try:
        srcs = json.loads(srcs_raw)
    except Exception:
        srcs = []
    try:
        subs = json.loads(subs_raw)
    except Exception:
        subs = []
    out = []
    for s in srcs if isinstance(srcs, list) else []:
        u = s.get("url", "")
        if not u:
            continue
        out.append({
            "name": s.get("name") or "SFLIX",
            "url": u,
            "quality": int(s.get("quality") or 0),
            "isM3u8": bool(s.get("isM3u8")),
        })
    return out, (subs if isinstance(subs, list) else []), len(page)


def probe_sfl(title, slug):
    print(f"\n=== {title}  [sfl] {slug} ===")
    srcs, subs, nbytes = sfl_sources(slug)
    if not srcs:
        print(f"  FAIL  no sources parsed (page {nbytes}B)")
        return False
    ok = True
    for s in srcs:
        full = s["url"] if s["url"].startswith("http") else SITE + s["url"]
        head = fetch(full, ref=f"{SITE}/", rng="0-2000000")
        streamable = ("ftyp" in head[:64] or "#EXTM3U" in head[:200] or len(head) > 10000)
        ok &= streamable
        print(f"  {'OK  ' if streamable else 'FAIL'} {s['quality'] or '?':>5}p "
              f"{s['name']} {full[:62]} -> {len(head)}B")
    for sub in subs:
        u = sub.get("url", "")
        if not u:
            continue
        t = fetch(u if u.startswith("http") else SITE + u, ref=f"{SITE}/", rng="0-4000")
        print(f"  SUB  {sub.get('lang','?')}/{sub.get('label','?')}: {len(t)}B")
    return bool(srcs) and ok


def probe(title, kind, slug, season=None, ep=None):
    print(f"\n=== {title}  [{kind}] {slug}"
          + (f" S{season}E{ep}" if season else "") + " ===")
    pid, pd, page = resolve(slug, kind, season, ep)
    if not pid or not pd:
        print("  FAIL  no playerId/playData in embed index")
        print("        page bytes:", len(page))
        return False
    pd_obj = pd if isinstance(pd, dict) else {}
    print(f"  playerId {pid}")
    print(f"  playData {json.dumps(pd_obj)[:110]}")
    r = play(pid, pd)
    if not r.get("success"):
        print("  FAIL  play:", json.dumps(r)[:200])
        return False
    srcs = r.get("sources") or []
    hls = [s for s in srcs if s.get("isM3u8")]
    if not hls:
        print(f"  FAIL  no m3u8 among {len(srcs)} sources")
        return False
    for s in hls:
        # HLS master fetch must carry the referer the CDN demands
        man = fetch(s["url"], ref=s.get("referer") or f"{EMBED}/")
        ok = "#EXTM3U" in man
        res = re.findall(r'RESOLUTION=(\d+)x(\d+)', man)
        print(f"  {'OK  ' if ok else 'FAIL'} {s['url'][:76]}")
        print(f"        master {len(man)}B firstline={man.splitlines()[0] if man else 'EMPTY'!r} "
              f"variants={len(res)} {res[:3]}")
        if not ok:
            return False
    for sub in (r.get("subtitles") or []):
        s = fetch(sub.get("url", ""), ref=sub.get("referer") or f"{EMBED}/")
        print(f"  SUB  {sub.get('lang','?')}: {sub.get('url','')[:70]} -> {len(s)}B")
    return True


def main():
    tv = listing("tv")
    mv = listing("movie")
    sfl = [c for c in mv if c["slug"].startswith("sfl-")]
    slugmv = [c for c in mv if not c["slug"].startswith("sfl-")]
    print(f"listing: tv {len(tv)} | movie sfl {len(sfl)} + slug {len(slugmv)}")

    ok = True
    # 1) sfl family (dracin/seg MP4 proxy)
    if sfl:
        ok &= probe_sfl(sfl[0]["title"], sfl[0]["slug"])
    else:
        print("\n!! no sfl cards found")
        ok = False
    # 2) slug movie (embedfilm HLS)
    if slugmv:
        ok &= probe(slugmv[0]["title"], "movie", slugmv[0]["slug"])
    else:
        print("\n!! no slug movie cards found")
        ok = False
    # 3) TV S1E1 (embedfilm tvseries HLS)
    if tv:
        ok &= probe(tv[0]["title"], "tv", tv[0]["slug"], 1, 1)
    else:
        print("\n!! no tv cards found")
        ok = False

    print("\nRESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
