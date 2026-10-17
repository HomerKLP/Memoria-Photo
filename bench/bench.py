"""Compare face detectors and recognizers on our own photos.

uv run bench/bench.py detect                  -> testdata/out/detect.html
uv run bench/bench.py group                   -> testdata/out/group.html
uv run bench/bench.py eval                    -> scores recognizers with Google Photos tags
"""

import argparse
import hashlib
import html
import json
import os
import re
import time
from collections import Counter
from pathlib import Path
from urllib.parse import quote

import cv2
import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage
from tqdm import tqdm
from uniface import SCRFD, AdaFace, ArcFace, EdgeFace, RetinaFace, YOLOv5Face, face_alignment
from uniface.constants import (
    AdaFaceWeights,
    ArcFaceWeights,
    EdgeFaceWeights,
    EDifFIQAWeights,
    RetinaFaceWeights,
    SCRFDWeights,
    YOLOv5FaceWeights,
)
from uniface.quality import EDifFIQA

CPU = ['CPUExecutionProvider']  # CoreML runs in fp16 and gives slightly different numbers
DETECTORS = {
    'retinaface_r50_1280': lambda: RetinaFace(
        model_name=RetinaFaceWeights.RESNET50, input_size=(1280, 1280), providers=CPU
    ),
    'retinaface_r50_640': lambda: RetinaFace(model_name=RetinaFaceWeights.RESNET50, providers=CPU),
    'scrfd_10g_640': lambda: SCRFD(model_name=SCRFDWeights.SCRFD_10G_KPS, providers=CPU),
    'yolov5m_640': lambda: YOLOv5Face(model_name=YOLOv5FaceWeights.YOLOV5M, providers=CPU),  # model only takes 640
}
RECOGNIZERS = {
    'adaface_ir101': lambda: AdaFace(model_name=AdaFaceWeights.IR_101, providers=CPU),
    'arcface_r50': lambda: ArcFace(model_name=ArcFaceWeights.RESNET, providers=CPU),
    'edgeface_base': lambda: EdgeFace(model_name=EdgeFaceWeights.BASE, providers=CPU),
}
EXTS = {'.jpg', '.jpeg', '.png', '.webp'}  # ponytail: no HEIC, add pillow-heif when we get one
SIDECAR = re.compile(r'(.+?)(?:\.s[a-z-]*)?(\(\d+\))?\.json')  # Takeout: x.jpg.supplemental-metadata(1).json
MAX_SIDE = 4096  # ponytail: caps 50 MP photos to save RAM, raise if tiny faces get lost
CSS = """<style>
body{font:14px sans-serif;margin:16px;background:#fff;color:#111}
.row{display:grid;grid-template-columns:repeat(auto-fill,minmax(420px,1fr));gap:8px}
figure{margin:0}.ph{position:relative}.ph img{width:100%;display:block}
.ph i{position:absolute;border:2px solid #0f0;box-sizing:border-box}
.ph b{position:absolute;width:3px;height:3px;background:red}
.faces img{width:72px;height:72px;margin:1px}
</style>"""


def load(path):
    img = cv2.imread(str(path))  # applies EXIF rotation
    if img is None:
        return None
    s = MAX_SIDE / max(img.shape[:2])
    return cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA) if s < 1 else img


def google_people(photos_dir):
    """Names Google Photos tagged in each photo, from Google Takeout json sidecars."""
    people = {}
    for j in photos_dir.rglob('*.json'):
        base, dup = SIDECAR.fullmatch(j.name).groups()
        stem, ext = os.path.splitext(base)
        photo = j.parent / (f'{stem}{dup}{ext}' if dup else base)  # (1) moves before the extension
        if photo.exists():  # missing when the photo is in another Takeout zip part
            people[str(photo.relative_to(photos_dir))] = [p['name'] for p in json.loads(j.read_text()).get('people', [])]
    return people


def detect(photos_dir, out, limit):
    (out / 'thumbs').mkdir(parents=True, exist_ok=True)
    (out / 'faces').mkdir(exist_ok=True)
    db_path = out / 'detect.json'
    db = json.loads(db_path.read_text()) if db_path.exists() else {}
    for e in db.values():  # drop results of detectors removed from DETECTORS
        e['det'] = {d: f for d, f in e['det'].items() if d in DETECTORS}
    models = {}
    quality = EDifFIQA(model_name=EDifFIQAWeights.T, providers=CPU)
    people = google_people(photos_dir)
    photos = sorted(str(p.relative_to(photos_dir)) for p in photos_dir.rglob('*') if p.suffix.lower() in EXTS)
    if limit:  # tagged photos first, hash order mixes the years
        photos = sorted(photos, key=lambda r: (not people.get(r), hashlib.sha1(r.encode()).hexdigest()))[:limit]
    for n, rel in enumerate(tqdm(photos, desc='photos')):
        path = photos_dir / rel
        entry = db.setdefault(rel, {'id': hashlib.sha1(rel.encode()).hexdigest()[:12], 'det': {}, 'ms': {}})
        entry['people'] = people.get(rel, [])
        todo = [d for d in DETECTORS if d not in entry['det']]
        if not todo:
            continue
        img = load(path)
        if img is None:
            tqdm.write(f'cannot read, skipped: {rel}')
            continue
        h, w = img.shape[:2]
        entry['size'] = [w, h]
        s = 1024 / max(h, w)
        cv2.imwrite(str(out / 'thumbs' / f'{entry["id"]}.jpg'), cv2.resize(img, None, fx=min(s, 1), fy=min(s, 1)))
        for d in todo:
            if d not in models:
                models[d] = DETECTORS[d]()
            t = time.perf_counter()
            found = models[d].detect(img)
            entry['ms'][d] = round((time.perf_counter() - t) * 1000)
            faces = []
            for i, f in enumerate(found):
                crop, _ = face_alignment(img, f.landmarks, image_size=112)
                name = f'{entry["id"]}_{d}_{i}.png'  # png: embeddings must see the exact crop
                cv2.imwrite(str(out / 'faces' / name), crop)
                faces.append({
                    'box': [round(float(v), 1) for v in f.bbox],
                    'score': round(float(f.confidence), 3),
                    'lm': f.landmarks.round(1).tolist(),
                    'q': round(float(quality.score_aligned(crop).score), 3),
                    'crop': name,
                })
            entry['det'][d] = faces
        if n % 20 == 0:
            db_path.write_text(json.dumps(db))
    db_path.write_text(json.dumps(db))

    db = {rel: e for rel, e in db.items() if 'size' in e}
    for d in DETECTORS:
        faces = sum(len(e['det'].get(d, [])) for e in db.values())
        ms = [e['ms'][d] for e in db.values() if d in e['ms']]
        print(f'{d:22} {faces:6} faces  {np.median(ms) if ms else 0:6.0f} ms/photo')

    def spread(e):
        counts = [len(v) for v in e['det'].values()]
        return max(counts) - min(counts)

    parts = ['<h1>Detectors</h1><p>Photos where detectors disagree come first. Hover a box for score and quality.</p>']
    for rel, e in sorted(db.items(), key=lambda kv: -spread(kv[1])):
        w, h = e['size']
        link = quote(os.path.relpath(photos_dir / rel, out))
        tags = html.escape(', '.join(e.get('people', [])))
        parts.append(f'<h3><a href="{link}">{html.escape(rel)}</a> {tags}</h3><div class=row>')
        for d, faces in e['det'].items():
            marks = []
            for f in faces:
                x1, y1, x2, y2 = f['box']
                marks.append(
                    f'<i title="score {f["score"]} q {f["q"]}" style="left:{x1 / w:.2%};top:{y1 / h:.2%};'
                    f'width:{(x2 - x1) / w:.2%};height:{(y2 - y1) / h:.2%}"></i>'
                )
                marks += [f'<b style="left:{x / w:.2%};top:{y / h:.2%}"></b>' for x, y in f['lm']]
            parts.append(
                f'<figure><div class=ph><img loading=lazy src="thumbs/{e["id"]}.jpg">{"".join(marks)}</div>'
                f'<figcaption>{d}: {len(faces)} faces, {e["ms"].get(d, "?")} ms</figcaption></figure>'
            )
        parts.append('</div>')
    (out / 'detect.html').write_text(CSS + ''.join(parts))
    print(f'open {out / "detect.html"}')


def group(out, det, threshold, min_size):
    db = json.loads((out / 'detect.json').read_text())
    faces = [
        (rel, f)
        for rel, e in db.items()
        for f in e['det'].get(det, [])
        if min(f['box'][2] - f['box'][0], f['box'][3] - f['box'][1]) >= min_size
    ]
    if len(faces) < 2:
        raise SystemExit(f'only {len(faces)} faces from {det}, run detect first')
    parts = [f'<h1>Groups: {det}, threshold {threshold}, faces from {min_size}px</h1>']
    for r, make in RECOGNIZERS.items():
        cache = out / f'emb_{det}_{r}.npz'
        known = dict(np.load(cache)) if cache.exists() else {}
        todo = [f for _, f in faces if f['crop'] not in known]
        if todo:
            model = make()
            for f in tqdm(todo, desc=r):
                v = model.get_embedding(cv2.imread(str(out / 'faces' / f['crop']))).ravel()
                known[f['crop']] = v / np.linalg.norm(v)
            np.savez(cache, **known)
        x = np.stack([known[f['crop']] for _, f in faces])
        # ponytail: one threshold for all models, tune per model once we label people
        labels = fcluster(linkage(x, 'average', metric='cosine'), threshold, 'distance')
        clusters = {}
        for label, face in zip(labels, faces):
            clusters.setdefault(label, []).append(face)
        groups = sorted((c for c in clusters.values() if len(c) > 1), key=len, reverse=True)
        singles = [face for c in clusters.values() if len(c) == 1 for face in c]
        print(f'{r:15} {len(groups):5} groups  {len(singles):5} single faces')
        parts.append(f'<h2>{r}: {len(groups)} groups, {len(singles)} single faces</h2>')
        for i, c in enumerate(groups + [singles]):
            title = f'Group {i + 1}' if i < len(groups) else 'Not grouped'
            tags = Counter(n for rel in {rel for rel, _ in c} for n in db[rel].get('people', []))
            if tags and i < len(groups):
                title += ', Google tags: ' + ', '.join(f'{n} {k}' for n, k in tags.most_common(3))
            imgs = ''.join(
                f'<a href="thumbs/{db[rel]["id"]}.jpg"><img loading=lazy src="faces/{f["crop"]}" '
                f'title="{html.escape(rel)} q {f["q"]} {html.escape(", ".join(db[rel].get("people", [])))}"></a>'
                for rel, f in c
            )
            parts.append(f'<h4>{html.escape(title)} ({len(c)})</h4><div class=faces>{imgs}</div>')
    (out / 'group.html').write_text(CSS + ''.join(parts))
    print(f'open {out / "group.html"}')


def evaluate(out, det, min_size):
    """Photos with one tagged person and one face give labeled faces. Needs `group` run first."""
    db = json.loads((out / 'detect.json').read_text())
    labels = {}
    for e in db.values():
        faces = [f for f in e['det'].get(det, []) if min(f['box'][2] - f['box'][0], f['box'][3] - f['box'][1]) >= min_size]
        if len(e.get('people', [])) == 1 and len(faces) == 1:
            labels[faces[0]['crop']] = e['people'][0]
    keys = list(labels)
    y = np.array([labels[k] for k in keys])
    iu = np.triu_indices(len(keys), 1)
    same = (y[:, None] == y[None, :])[iu]
    print(f'{len(keys)} labeled faces, {len(set(y))} people, {same.sum()} same-person pairs')
    for r in RECOGNIZERS:
        emb = dict(np.load(out / f'emb_{det}_{r}.npz'))
        x = np.stack([emb[k] for k in keys])
        sim = (x @ x.T)[iu]
        far1, far01 = np.quantile(sim[~same], [0.99, 0.999])
        # group all faces at the FAR 0.1% cut, then check labeled pairs
        crops = list(emb)
        cut = dict(zip(crops, fcluster(linkage(np.stack(list(emb.values())), 'average', metric='cosine'), 1 - far01, 'distance')))
        c = np.array([cut[k] for k in keys])
        grouped = (c[:, None] == c[None, :])[iu]
        print(
            f'{r:15} TAR@FAR1% {(sim[same] > far1).mean():.3f}  TAR@FAR0.1% {(sim[same] > far01).mean():.3f}  '
            f'groups at distance {1 - far01:.2f}: precision {(grouped & same).sum() / max(grouped.sum(), 1):.3f} '
            f'recall {(grouped & same).sum() / same.sum():.3f}'
        )


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['detect', 'group', 'eval'])
    ap.add_argument('--photos', type=Path, default=root / 'testdata/photos')
    ap.add_argument('--out', type=Path, default=root / 'testdata/out')
    ap.add_argument('--det', default='yolov5m_640', choices=list(DETECTORS))
    ap.add_argument('--threshold', type=float, default=0.65, help='max cosine distance inside a group')
    ap.add_argument('--limit', type=int, help='detect: only this many photos, people-tagged first')
    ap.add_argument('--min-size', type=int, default=40, help='skip faces smaller than this many px when grouping')
    a = ap.parse_args()
    if a.cmd == 'detect':
        detect(a.photos.resolve(), a.out.resolve(), a.limit)
    elif a.cmd == 'group':
        group(a.out.resolve(), a.det, a.threshold, a.min_size)
    else:
        evaluate(a.out.resolve(), a.det, a.min_size)
