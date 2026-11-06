"""Town names for photo GPS: which GeoNames cut gives sane Places tiles.

uv run bench/places.py  -> testdata/out/places.html
uv run bench/places.py towns  -> app/src/main/assets/towns.tsv, the app's town list (id, name, lat, lon; 1000+ people)
uv run bench/places.py picks  -> testdata/out/picks_<prompt>.jpg, top cover picks per town (run tags.py cover first)
Reads GPS from the phone's JPEGs (first 64 KB only, nothing saved but coordinates) into testdata/out/gps.tsv,
downloads GeoNames cities500 into testdata/geonames/. Delete gps.tsv to read the phone again.
"""

import base64
import hashlib
import html
import json
import struct
import subprocess
import sys
import urllib.request
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

from bench import CSS

ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
OUT = Path("testdata/out")
GEO = Path("testdata/geonames")
DUMP = "https://download.geonames.org/export/dump/"
TIERS = [500, 1000, 5000, 15000]  # min population; cities500 holds all of them
NEAR_KM = 5  # hybrid rule: a town of 1000+ this close wins, else the nearest town of 15000+


def gps_from_jpeg_head(b):
    """(lat, lon) from the EXIF in the first bytes of a JPEG, or None."""
    i = 2
    while i + 4 <= len(b) and b[i] == 0xFF:
        marker, size = b[i + 1], struct.unpack(">H", b[i + 2:i + 4])[0]
        if marker == 0xE1 and b[i + 4:i + 10] == b"Exif\0\0":
            g = Image.Exif()
            g.load(b[i + 4:i + 2 + size])
            g = g.get_ifd(0x8825)
            if 2 not in g or 4 not in g:
                return None
            lat = sum(float(x) / 60**k for k, x in enumerate(g[2])) * (-1 if g.get(1) == "S" else 1)
            lon = sum(float(x) / 60**k for k, x in enumerate(g[4])) * (-1 if g.get(3) == "W" else 1)
            return None if lat == 0 and lon == 0 else (lat, lon)
        if marker == 0xDA:  # image data starts, no EXIF before it
            return None
        i += 2 + size
    return None


def read_phone():
    rows = subprocess.run([ADB, "-d", "shell", "content query --uri content://media/external/images/media --projection _data"],
                          capture_output=True, text=True, check=True).stdout.splitlines()
    paths = [r.split("_data=", 1)[1] for r in rows if "_data=" in r]
    paths = [p for p in paths if p.lower().endswith((".jpg", ".jpeg"))]
    lst = OUT / "paths.txt"
    lst.write_text("\n".join(paths) + "\n")
    subprocess.run([ADB, "-d", "push", str(lst), "/data/local/tmp/memoria_paths.txt"], check=True, capture_output=True)
    lst.unlink()
    loop = 'while IFS= read -r f; do echo "@@ $f"; head -c 66000 "$f" | base64 -w 0; echo; done < /data/local/tmp/memoria_paths.txt'
    p = subprocess.Popen([ADB, "-d", "exec-out", loop], stdout=subprocess.PIPE, text=True)
    out, path, n = [], None, 0
    for line in p.stdout:
        line = line.rstrip("\n")
        if line.startswith("@@ "):
            path = line[3:]
            continue
        n += 1
        try:
            ll = gps_from_jpeg_head(base64.b64decode(line))
        except Exception:
            ll = None
        if ll:
            out.append((path, *ll))
        if n % 500 == 0:
            print(f"  {n}/{len(paths)} read, {len(out)} with GPS", file=sys.stderr)
    p.wait()
    subprocess.run([ADB, "-d", "shell", "rm", "/data/local/tmp/memoria_paths.txt"])
    if p.returncode:
        sys.exit(f"adb failed ({p.returncode})")
    (OUT / "gps.tsv").write_text("".join(f"{a}\t{b:.6f}\t{c:.6f}\n" for a, b, c in out))
    print(f"{len(paths)} JPEGs, {len(out)} with GPS", file=sys.stderr)


def fetch(name):
    f = GEO / name
    if not f.exists():
        GEO.mkdir(parents=True, exist_ok=True)
        print(f"downloading {name}", file=sys.stderr)
        urllib.request.urlretrieve(DUMP + name, f)
    return f


def load_towns():
    regions = {}
    for line in fetch("admin1CodesASCII.txt").read_text().splitlines():
        code, name = line.split("\t")[:2]
        regions[code] = name
    with zipfile.ZipFile(fetch("cities500.zip")) as z:
        text = z.read("cities500.txt").decode()
    t = defaultdict(list)
    for line in text.splitlines():
        c = line.split("\t")
        if c[7] == "PPLX":  # a district of a city (GeoNames lists them as places), not a town
            continue
        t["id"].append(int(c[0]))
        t["name"].append(c[1])
        t["lat"].append(float(c[4]))
        t["lon"].append(float(c[5]))
        t["code"].append(c[7])
        t["where"].append(", ".join(x for x in (regions.get(f"{c[8]}.{c[10]}"), c[8]) if x))
        t["pop"].append(int(c[14] or 0))
    return {k: np.array(v) for k, v in t.items()}


def km(lat, lon, lats, lons):
    p1, p2 = np.radians(lat), np.radians(lats)
    a = np.sin((p2 - p1) / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(np.radians(lons - lon) / 2) ** 2
    return 6371 * 2 * np.arcsin(np.sqrt(a))


def write_towns():
    towns = load_towns()
    keep = towns["pop"] >= 1000
    out = Path("app/src/main/assets/towns.tsv")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(f"{i}\t{n}\t{a:.4f}\t{b:.4f}\n" for i, n, a, b in
                           zip(towns["id"][keep], towns["name"][keep], towns["lat"][keep], towns["lon"][keep])))
    print(f"wrote {keep.sum()} towns to {out}", file=sys.stderr)


def takeout_gps():
    """(rel path, lat, lon) of the takeout photos with GPS, cached in testdata/out/gps_takeout.tsv."""
    cache = OUT / "gps_takeout.tsv"
    if not cache.exists():
        root = Path("testdata/takeout")
        rows = [(str(p.relative_to(root)), *ll) for p in sorted(root.rglob("*"))
                if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp", ".heic")
                and (ll := gps_from_jpeg_head(p.read_bytes()[:66000]))]
        cache.write_text("".join(f"{a}\t{b:.6f}\t{c:.6f}\n" for a, b, c in rows))
        print(f"{len(rows)} takeout photos with GPS", file=sys.stderr)
    return [(a, float(b), float(c)) for a, b, c in (l.split("\t") for l in cache.read_text().splitlines())]


def app_towns():
    """The app's shipped town list: id, name, lat, lon."""
    rows = [l.split("\t") for l in Path("app/src/main/assets/towns.tsv").read_text().splitlines()]
    return (np.array([int(r[0]) for r in rows]), np.array([r[1] for r in rows]),
            np.array([float(r[2]) for r in rows]), np.array([float(r[3]) for r in rows]))


def pick_thumb(rel, side):
    path = OUT / "picks" / f"{hashlib.sha1(rel.encode()).hexdigest()[:12]}_{side}.jpg"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        img = Image.open(Path("testdata/takeout") / rel)
        img.draft("RGB", (side * 2, side * 2))
        img = ImageOps.exif_transpose(img).convert("RGB")
        img.thumbnail((side, side))
        img.save(path, quality=85)
    return path


def pick_sheets():
    """Top cover picks per town on the Mac: takeout GPS, the app's vectors and the cover prompts (tags.py cover)."""
    cov = json.loads((OUT / "tags/cover.json").read_text())
    z = np.load(OUT / "tags/emb_siglip2_b16_app.npz")
    vec = dict(zip(z["rels"], z["vecs"]))
    tid, tname, tlat, tlon = app_towns()
    photos = [(r, la, lo) for r, la, lo in takeout_gps() if r in vec]
    town = np.array([tid[km(la, lo, tlat, tlon).argmin()] for _, la, lo in photos])
    big = [t for t, n in Counter(town).most_common(8)]
    print(", ".join(f"{tname[np.where(tid == t)[0][0]]} ({n})" for t, n in Counter(town).most_common(8)), file=sys.stderr)
    tile, cols = 240, 5
    for k, (prompt, pv) in enumerate(zip(cov["prompts"], cov["vectors"])):
        pv = np.asarray(pv, np.float32)
        img = Image.new("RGB", (cols * tile, 26 + len(big) * tile), "#222")
        d = ImageDraw.Draw(img)
        d.text((6, 5), prompt, fill="white", font=ImageFont.load_default(size=18))
        for r, t in enumerate(big):
            idx = np.where(town == t)[0]
            top = sorted(((float(vec[photos[i][0]] @ pv), i) for i in idx), reverse=True)[:cols]
            for c, (s, i) in enumerate(top):
                x, y = c * tile, 26 + r * tile
                thumb = Image.open(pick_thumb(photos[i][0], tile - 6))
                img.paste(thumb, (x + (tile - thumb.width) // 2, y + (tile - thumb.height) // 2))
                d.rectangle([x + 1, y + 1, x + 58, y + 20], fill="black")
                d.text((x + 4, y + 2), f"{s:.2f}", fill="yellow", font=ImageFont.load_default(size=16))
            name = tname[np.where(tid == t)[0][0]]
            d.rectangle([tile * cols - 210, 26 + r * tile + 1, tile * cols - 1, 26 + r * tile + 20], fill="black")
            d.text((tile * cols - 205, 26 + r * tile + 2), name, fill="white", font=ImageFont.load_default(size=16))
        img.save(OUT / f"picks_{k}.jpg", quality=85)
        print(f"wrote {OUT / f'picks_{k}.jpg'}", file=sys.stderr)


def main():
    if sys.argv[1:] == ["towns"]:
        return write_towns()
    if sys.argv[1:] == ["picks"]:
        return pick_sheets()
    OUT.mkdir(parents=True, exist_ok=True)
    if not (OUT / "gps.tsv").exists():
        read_phone()
    photos = [(float(b), float(c)) for a, b, c in (l.split("\t") for l in (OUT / "gps.tsv").read_text().splitlines())]
    towns = load_towns()
    print(f"{len(towns['name'])} places in cities500", file=sys.stderr)
    # ~1 km cells: photos in one cell get the same answer
    cells = Counter((round(a, 2), round(b, 2)) for a, b in photos)
    masks = {p: towns["pop"] >= p for p in TIERS}
    cols = [f"{p}+" for p in TIERS] + ["hybrid"]
    picks = {}  # cell -> {col: (town index, km)}
    for cell in cells:
        d = km(*cell, towns["lat"], towns["lon"])
        row = {}
        for p in TIERS:
            i = np.where(masks[p], d, np.inf).argmin()
            row[f"{p}+"] = (i, d[i])
        row["hybrid"] = row["1000+"] if row["1000+"][1] <= NEAR_KM else row["15000+"]
        picks[cell] = row

    def label(i):
        return f"{towns['name'][i]} ({towns['where'][i]})"

    tiles = {c: Counter() for c in cols}
    for cell, n in cells.items():
        for c in cols:
            tiles[c][label(picks[cell][c][0])] += n
    print(f"{len(photos)} photos with GPS, {len(cells)} cells of ~1 km")
    for c in cols:
        print(f"\n{c}: {len(tiles[c])} Places tiles")
        for name, n in tiles[c].most_common(8):
            print(f"  {n:5}  {name}")

    th = "".join(f"<th>{c}</th>" for c in cols)
    grids = "".join(
        f"<td style='vertical-align:top'><b>{c}: {len(tiles[c])} tiles</b><br>"
        + "<br>".join(f"{n} · {html.escape(name)}" for name, n in tiles[c].most_common()) + "</td>"
        for c in cols)
    # cells where the cuts disagree first: that is where the rule matters
    order = sorted(cells, key=lambda k: (len({picks[k][c][0] for c in cols}) == 1, -cells[k]))
    body = "".join(
        f"<tr><td>{cells[k]}</td><td>{k[0]:.2f}, {k[1]:.2f}</td>"
        + "".join(f"<td>{html.escape(towns['name'][i])} <small>{towns['code'][i]} · {towns['pop'][i]:,} · {d:.1f} km</small></td>"
                  for i, d in (picks[k][c] for c in cols))
        + "</tr>" for k in order)
    (OUT / "places.html").write_text(
        f"<!doctype html><meta charset=utf-8><title>Places</title><style>{CSS} td{{padding:4px 8px}}</style>"
        f"<h2>Places tiles per cut</h2><p>{len(photos)} photos with GPS. hybrid = a town of 1000+ within {NEAR_KM} km, else the nearest of 15000+.</p>"
        f"<table><tr>{grids}</tr></table>"
        f"<h2>Each ~1 km cell (cuts disagree first)</h2><table><tr><th>photos</th><th>cell</th>{th}</tr>{body}</table>")
    print(f"\nwrote {OUT / 'places.html'}", file=sys.stderr)


if __name__ == "__main__":
    main()
