"""Town names for photo GPS: which GeoNames cut gives sane Places tiles.

uv run bench/places.py  -> testdata/out/places.html
Reads GPS from the phone's JPEGs (first 64 KB only, nothing saved but coordinates) into testdata/out/gps.tsv,
downloads GeoNames cities500 into testdata/geonames/. Delete gps.tsv to read the phone again.
"""

import base64
import html
import struct
import subprocess
import sys
import urllib.request
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

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


def main():
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
