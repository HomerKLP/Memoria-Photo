"""Library unification: index Takeout + phone files, find exact duplicates.

Merge happens on the Mac. Only exact content duplicates (sha256) are removed;
near-dupes are report-only. See docs/library-unification.md.

Run: uv run bench/unify.py index  <DIR> --out <index.json>
     uv run bench/unify.py report <index.json> [<index.json> ...]
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path

from tqdm import tqdm
from PIL import Image, ExifTags

MEDIA = {'.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif', '.gif',
         '.bmp', '.tif', '.tiff', '.mp4', '.mov', '.3gp', '.mkv', '.avi', '.webm'}
# Takeout sidecar: x.jpg.supplemental-metadata(1).json -> base='x.jpg', dup='(1)'
SIDECAR = re.compile(r'(.+?)(?:\.s[a-z-]*)?(\(\d+\))?\.json')
EXIF_DATE = {ExifTags.Base.DateTimeOriginal, ExifTags.Base.DateTime,
             ExifTags.Base.DateTimeDigitized}
BUF = 1 << 20


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(BUF), b''):
            h.update(chunk)
    return h.hexdigest()


def exif_date(path):
    """DateTimeOriginal etc. from EXIF, as 'YYYY-MM-DD HH:MM:SS' or None."""
    try:
        exif = Image.open(path).getexif()
    except Exception:
        return None
    for tag in (ExifTags.Base.DateTimeOriginal, ExifTags.Base.DateTimeDigitized,
                ExifTags.Base.DateTime):
        if tag in exif:
            return str(exif[tag]).replace(':', '-', 2)
    return None


def sidecar_index(root):
    """media filename -> sidecar json path. Handles (1) and Takeout truncation."""
    exact, trunc = {}, {}
    for j in Path(root).rglob('*.json'):
        m = SIDECAR.fullmatch(j.name)
        if not m:
            continue
        base, dup = m.groups()
        stem, ext = os.path.splitext(base)
        photo = f'{stem}{dup}{ext}' if dup else base
        exact.setdefault(photo.lower(), j)
        trunc.setdefault(photo.lower()[:46], j)
    return exact, trunc


def find_sidecar(name, exact, trunc):
    k = name.lower()
    return exact.get(k) or trunc.get(k[:46])


def sidecar_date(jpath):
    try:
        d = json.loads(Path(jpath).read_text())
        t = int(d['photoTakenTime']['timestamp'])
        import datetime as dt
        return dt.datetime.fromtimestamp(t, dt.timezone.utc).astimezone().strftime('%Y-%m-%d %H:%M:%S')
    except Exception:
        return None


def cmd_index(a):
    root = Path(a.dir)
    exact, trunc = sidecar_index(root)
    files = sorted(p for p in root.rglob('*') if p.is_file() and p.suffix.lower() in MEDIA)
    if a.limit:
        files = files[:a.limit]
    out = {}
    for p in tqdm(files, desc='index'):
        rel = str(p.relative_to(root))
        j = find_sidecar(p.name, exact, trunc)
        sd = sidecar_date(j) if j else None
        ed = exif_date(p) if p.suffix.lower() not in ('.mp4', '.mov', '.mkv', '.webm', '.avi') else None
        mt = os.path.getmtime(p)
        import datetime as dt
        mts = dt.datetime.fromtimestamp(mt).strftime('%Y-%m-%d %H:%M:%S')
        taken, src = ed or sd, 'exif' if ed else ('sidecar' if sd else None)
        out[rel] = {
            'size': p.stat().st_size,
            'sha256': sha256(p),
            'taken': taken,
            'taken_src': src,
            'sidecar': str(j.relative_to(root)) if j else None,
            'mtime': mts,
        }
    Path(a.out).write_text(json.dumps(out, indent=0, ensure_ascii=False))
    dated = sum(1 for v in out.values() if v['taken'])
    print(f'{len(out)} files -> {a.out}  (with date: {dated}, no date: {len(out) - dated})')


def load(path, label):
    d = json.loads(Path(path).read_text())
    for rel, v in d.items():
        v['_root'] = label
        v['_rel'] = rel
    return d


def cmd_report(a):
    rows = {}
    for spec in a.indexes:
        label, _, path = spec.partition('=') if '=' in spec else (Path(spec).stem, '', spec)
        for rel, v in load(path, label).items():
            rows[f'{label}/{rel}'] = v
    byhash = {}
    for key, v in rows.items():
        byhash.setdefault(v['sha256'], []).append(key)
    dups = {h: ks for h, ks in byhash.items() if len(ks) > 1}
    wasted = sum(rows[ks[0]]['size'] * (len(ks) - 1) for ks in dups.values())
    print(f'files: {len(rows)}   unique content: {len(byhash)}   duplicate groups: {len(dups)}')
    print(f'reclaimable: {wasted / 1e9:.2f} GB')
    nodate = sum(1 for v in rows.values() if not v['taken'])
    print(f'no date: {nodate}')
    groups = sorted(dups.values(), key=lambda ks: -rows[ks[0]]['size'])
    for ks in groups[:a.show]:
        print(f"\n  {rows[ks[0]]['size'] / 1e6:.1f} MB  taken={rows[ks[0]]['taken']}")
        for k in ks:
            print(f"    [{rows[k]['_root']}] {k}")
    if a.json:
        Path(a.json).write_text(json.dumps({'dups': dups, 'rows': rows}, indent=0, ensure_ascii=False))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd', required=True)
    i = sub.add_parser('index')
    i.add_argument('dir')
    i.add_argument('--out', required=True)
    i.add_argument('--limit', type=int, default=0)
    i.set_defaults(fn=cmd_index)
    r = sub.add_parser('report')
    r.add_argument('indexes', nargs='+')
    r.add_argument('--show', type=int, default=40)
    r.add_argument('--json')
    r.set_defaults(fn=cmd_report)
    a = p.parse_args()
    a.fn(a)


if __name__ == '__main__':
    main()
