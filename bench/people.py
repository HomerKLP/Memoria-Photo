"""People like the phone makes them (groupFaces in People.kt), and how sure each face is.

uv run bench/people.py testdata/out/db/memoria.db [min quality to start a person, -1 = none] [min photos]  -> testdata/out/people.html
"""

import html
import sqlite3
import sys
from collections import Counter
from pathlib import Path

import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage

from bench import CSS, google_people

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'testdata/out'
CUT = 0.68  # same as People.kt
MIN_Q = float(sys.argv[2]) if len(sys.argv) > 2 else 0.4  # same as People.kt
MIN_PHOTOS = int(sys.argv[3]) if len(sys.argv) > 3 else 6  # same as People.kt


def f32(b):
    return np.frombuffer(b, '>f4')  # Room stores FloatArray as big-endian float32


rows = sqlite3.connect(sys.argv[1]).execute(
    'SELECT f.id, p.path, p.kind, f.box, f.score, f.landmarks, f.vector, f.quality, f.thumb, f.personId FROM face f JOIN photo p ON p.id = f.photoId')
faces = []
for fid, path, kind, box, score, lm, vec, q, thumb, pid in rows:
    box = f32(box)
    if min(box[2] - box[0], box[3] - box[1]) >= 40:
        faces.append({'id': fid, 'rel': path.removeprefix('Pictures/MemoriaTest/'), 'kind': kind, 'box': box, 'score': score,
                      'lm': f32(lm).reshape(5, 2), 'vec': f32(vec), 'q': q, 'thumb': thumb, 'phone': pid})

for f in faces:
    (le, re), nose = f['lm'][:2], f['lm'][2]
    f['turn'] = min(1.0, abs((nose[0] - le[0]) / max(re[0] - le[0], 1) - 0.5) * 2)  # 0 frontal, 1 profile
    f['size'] = int(min(f['box'][2] - f['box'][0], f['box'][3] - f['box'][1]))

# Same grouping as the phone: good camera faces start people, other faces join under the cut
seeds = [f for f in faces if f['kind'] == 'camera' and f['q'] >= MIN_Q]
clusters = {}
for label, f in zip(fcluster(linkage(np.stack([f['vec'] for f in seeds]), 'average', metric='cosine'), CUT, 'distance'), seeds):
    clusters.setdefault(label, []).append(f)
people = [c for c in clusters.values() if len({f['rel'] for f in c}) >= MIN_PHOTOS]
member = {f['id']: k for k, c in enumerate(people) for f in c}
# Average distance from each face to each person's seed faces, without the face itself
sim = np.stack([f['vec'] for f in faces]) @ np.stack([f['vec'] for f in seeds]).T
col = {f['id']: j for j, f in enumerate(seeds)}
dist = np.stack([(1 - sim[:, [col[f['id']] for f in c]]).sum(1) for c in people], 1)
sizes = np.array([len(c) for c in people], float)
for i, f in enumerate(faces):
    own = member.get(f['id'])
    d = dist[i] / (sizes - (np.arange(len(people)) == own))
    if own is None and d.min() <= CUT and f['id'] not in col:
        own = int(d.argmin())
    f['person'] = own
    f['dist'] = float(d[own]) if own is not None else float(d.min())
    f['gap'] = float(np.delete(d, own).min() - d[own]) if own is not None else 0.0

# Check against the phone: same faces in each person
by_phone = {}
for f in faces:
    if f['phone'] is not None:
        by_phone.setdefault(f['phone'], set()).add(f['id'])
mine = {}
for f in faces:
    if f['person'] is not None:
        mine.setdefault(f['person'], set()).add(f['id'])
same = sum(s in by_phone.values() for s in mine.values())
print(f'{len(faces)} faces 40px+, {len(mine)} people ({same} same as the phone, phone has {len(by_phone)})')

# Google tags: a person's name is the top tag of its photos. A face is right or wrong only in a photo with one tag and one face.
tags = google_people(ROOT / 'testdata/takeout')
per_photo = Counter(f['rel'] for f in faces)
names = {}
for k in mine:
    top = Counter(n for rel in {f['rel'] for f in faces if f['person'] == k} for n in tags.get(rel, [])).most_common(1)
    names[k] = top[0][0] if top else None
for f in faces:
    t = tags.get(f['rel'], [])
    f['check'] = None
    if f['person'] is not None and names[f['person']] and len(t) == 1 and per_photo[f['rel']] == 1:
        f['check'] = t[0] == names[f['person']]
    f['suspect'] = f['person'] is not None and bool(t) and names[f['person']] is not None and names[f['person']] not in t

placed = [f for f in faces if f['person'] is not None]
joined = [f for f in placed if f['id'] not in member]  # faces that started a person are always sure
print(f'{len(placed)} faces in people, {len(joined)} joined, {sum(f["check"] is not None for f in joined)} joined checked by a one-tag photo')
print('joined rule (sure if q >= Q, dist <= D, gap >= G)  sure  unsure  checked right / wrong  photo tags miss the name')
for q, d, g in [(0, CUT, 0), (0.3, CUT, 0), (0.4, CUT, 0), (0, 0.6, 0), (0, 0.55, 0), (0, CUT, 0.05), (0, CUT, 0.1), (0.3, 0.6, 0.05)]:
    sure = [f for f in joined if f['q'] >= q and f['dist'] <= d and f['gap'] >= g]
    print(f'Q {q:.1f}  D {d:.2f}  G {g:.2f}  {len(sure):40}  {len(joined) - len(sure):6}  '
          f'{sum(f["check"] is True for f in sure):12} / {sum(f["check"] is False for f in sure):<6}  {sum(f["suspect"] for f in sure):6}')

# Page: each person, least sure faces first
(OUT / 'people').mkdir(exist_ok=True)
for f in faces:
    (OUT / 'people' / f'{f["id"]}.webp').write_bytes(f['thumb'])


def tile(f):
    style = 'border:3px solid red' if f['check'] is False else 'border:3px dashed orange' if f['suspect'] else ''
    info = f'd {f["dist"]:.2f} g {f["gap"]:.2f} q {f["q"]:.2f}'
    title = f'{f["rel"]}\n{info} turn {f["turn"]:.2f} size {f["size"]} score {f["score"]:.2f} {f["kind"]}\ntags: {", ".join(tags.get(f["rel"], []))}'
    other = ' (o)' if f['kind'] != 'camera' else ''
    return (f'<figure title="{html.escape(title)}"><img loading=lazy src="people/{f["id"]}.webp" style="{style}">'
            f'<figcaption>{info}{other}</figcaption></figure>')


parts = [CSS, '<style>figure{display:inline-block;margin:2px;width:76px;vertical-align:top}'
         'figure img{width:72px;height:72px;object-fit:cover}figcaption{font-size:9px;line-height:1.2}</style>',
         '<h1>People like the phone makes them</h1><p>Least sure faces first. d: distance to the person (lower is closer), '
         'g: gap to the next closest person (higher is surer), q: EDifFIQA quality (higher is better). (o): download or messenger photo. '
         'Red: one-tag photo says it is someone else. Orange dashed: photo tags miss the person name. Hover for more.</p>']
for k in sorted(mine, key=lambda k: -len(mine[k])):
    c = sorted((f for f in faces if f['person'] == k), key=lambda f: -f['dist'])
    parts.append(f'<h4>Person {k + 1} ({len(c)}): {html.escape(names[k] or "no tags")}</h4>' + ''.join(map(tile, c)))
rest = sorted((f for f in faces if f['person'] is None), key=lambda f: f['dist'])
parts.append(f'<h4>Not in a person ({len(rest)}), closest first. d: distance to the closest person</h4>' + ''.join(map(tile, rest)))
(OUT / 'people.html').write_text(''.join(parts))
print(f'open {OUT / "people.html"}')
