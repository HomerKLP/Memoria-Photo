"""Object detectors: cat and dog boxes for pets, and a person box around every real face for People.

uv run bench/objects.py run     -> the app's faces (YOLOv5m-face + quality) and each detector's boxes per photo, cached in testdata/out/objects.json
uv run bench/objects.py score   -> per detector: faces outside every person box, numbered sheets in testdata/out/objects/,
                                   photos where --det leaves a face out in testdata/out/objects.html
uv run bench/objects.py pets    -> per detector: cat or dog boxes on photos the tagger bench labeled pet / not pet,
                                   labeled pet photos --det misses in testdata/out/pets.html
uv run bench/objects.py phone testdata/out/objects_npu.json -> the app's Dev tools boxes and pet vectors against YOLO26-M and DINOv2-S here

YOLOX downloads itself. RF-DETR needs an ONNX export once: uv run bench/pets.py onnx rfdetr_s (n, m alike).
YOLO26 too, without its end-to-end step (TopK, which phone GPUs lack), then a copy to ~/.uniface/models/yolo26_s.onnx (m alike):
uv run --no-project --python 3.12 --with 'ultralytics==8.4.191' --with onnx --with onnxslim yolo export model=yolo26s.pt format=onnx
"""

import argparse
import hashlib
import html
import json
import os
import time
import urllib.request
from pathlib import Path
from urllib.parse import quote

import cv2
import numpy as np
import onnxruntime as ort
from tqdm import tqdm
from uniface import YOLOv5Face, face_alignment
from uniface.constants import EDifFIQAWeights, YOLOv5FaceWeights
from uniface.quality import EDifFIQA

from bench import CPU, CSS, EXTS, google_people, load

DETECTORS = ['yolox_s', 'yolox_m', 'yolox_l', 'rfdetr_n', 'rfdetr_s', 'rfdetr_m', 'yolo26_s', 'yolo26_m']
# YOLOX (2021) and RF-DETR (2025) Apache-2.0, YOLO26 (2026) AGPL-3.0, all COCO
PERSON, ANIMALS = 0, range(14, 24)  # COCO: bird, cat, dog, horse, sheep, cow, elephant, bear, zebra, giraffe
CAT, DOG = 15, 16


class Yolox:
    """Same steps as YOLOX's demo/ONNXRuntime: 640 letterbox padded with 114, BGR 0-255, per-class NMS 0.45."""

    def __init__(self, name):
        path = Path.home() / '.uniface/models' / f'{name}.onnx'
        if not path.exists():
            urllib.request.urlretrieve(f'https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/{name}.onnx', path)
        self.session = ort.InferenceSession(str(path), providers=CPU)
        grids = [np.stack(np.meshgrid(np.arange(640 // s), np.arange(640 // s)), 2).reshape(-1, 2) for s in (8, 16, 32)]
        self.grid = np.concatenate(grids)
        self.stride = np.concatenate([np.full((len(g), 1), s) for g, s in zip(grids, (8, 16, 32))])

    def detect(self, img, min_score=0.05):
        """[class, score, x1, y1, x2, y2] in image pixels."""
        r = 640 / max(img.shape[:2])
        h, w = int(img.shape[0] * r), int(img.shape[1] * r)
        pad = np.full((640, 640, 3), 114, np.uint8)
        pad[:h, :w] = cv2.resize(img, (w, h))
        x = pad.transpose(2, 0, 1)[None].astype(np.float32)
        out = self.session.run(None, {self.session.get_inputs()[0].name: x})[0][0]
        xy = (out[:, :2] + self.grid) * self.stride / r
        wh = np.exp(out[:, 2:4]) * self.stride / r
        scores = out[:, 4:5] * out[:, 5:]
        a, c = np.nonzero(scores >= min_score)
        boxes = np.concatenate([xy[a] - wh[a] / 2, wh[a]], 1)
        keep = cv2.dnn.NMSBoxesBatched(boxes.tolist(), scores[a, c].tolist(), c.tolist(), min_score, 0.45)
        return [[int(c[k]), round(float(scores[a[k], c[k]]), 3), *(round(float(v), 1) for v in (*boxes[k, :2], *(boxes[k, :2] + boxes[k, 2:])))] for k in keep]


COCO_IDS = [i for i in range(1, 91) if i not in (12, 26, 29, 30, 45, 66, 68, 69, 71, 83)]  # the 80 used of COCO's 90 ids


class RfDetr:
    """Same steps as rfdetr's ONNX decode: RGB stretched to the input (bilinear, no antialias), ImageNet mean/std,
    a sigmoid per class, boxes as cx, cy, w, h shares of the photo. No NMS: one box per query."""

    def __init__(self, name):
        path = Path.home() / '.uniface/models' / f'{name}.onnx'
        if not path.exists():
            raise SystemExit(f'{path} missing, export it first (see the docstring)')
        self.session = ort.InferenceSession(str(path), providers=CPU)
        self.size = self.session.get_inputs()[0].shape[2]

    def detect(self, img, min_score=0.05):
        """[class, score, x1, y1, x2, y2] in image pixels, classes numbered like YOLOX's."""
        x = cv2.resize(img, (self.size, self.size))[:, :, ::-1] / 255
        x = ((x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32)
        boxes, logits = self.session.run(['dets', 'labels'], {'input': x})
        scores = 1 / (1 + np.exp(-logits[0][:, COCO_IDS]))
        q, c = np.nonzero(scores >= min_score)
        h, w = img.shape[:2]
        cx, cy, bw, bh = (boxes[0][q] * [w, h, w, h]).T
        xyxy = np.clip(np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], 1), 0, [w, h, w, h])
        return [[int(k), round(float(scores[a, k]), 3), *(round(float(v), 1) for v in b)] for a, k, b in zip(q, c, xyxy)]


class Yolo26:
    """Same steps as Ultralytics predict: 640 letterbox, centered, padded with 114, RGB 0-1, per-class NMS 0.7.
    The raw 8400 rows (cx, cy, w, h, 80 scores), since the export leaves out the end-to-end step."""

    def __init__(self, name):
        path = Path.home() / '.uniface/models' / f'{name}.onnx'
        if not path.exists():
            raise SystemExit(f'{path} missing, export it first (see the docstring)')
        self.session = ort.InferenceSession(str(path), providers=CPU)

    def detect(self, img, min_score=0.05):
        """[class, score, x1, y1, x2, y2] in image pixels."""
        r = 640 / max(img.shape[:2])
        h, w = round(img.shape[0] * r), round(img.shape[1] * r)
        top, left = (640 - h) // 2, (640 - w) // 2
        pad = np.full((640, 640, 3), 114, np.uint8)
        pad[top:top + h, left:left + w] = cv2.resize(img, (w, h))
        x = (pad[:, :, ::-1].transpose(2, 0, 1)[None] / 255).astype(np.float32)
        out = self.session.run(None, {'images': x})[0][0].T
        scores = out[:, 4:]
        a, c = np.nonzero(scores >= min_score)
        wh = out[a, 2:4] / r
        boxes = np.concatenate([(out[a, :2] - [left, top]) / r - wh / 2, wh], 1)
        keep = cv2.dnn.NMSBoxesBatched(boxes.tolist(), scores[a, c].tolist(), c.tolist(), min_score, 0.7)
        return [[int(c[k]), round(float(scores[a[k], c[k]]), 3), *(round(float(v), 1) for v in (*boxes[k, :2], *(boxes[k, :2] + boxes[k, 2:])))] for k in keep]


def run(photos_dir, out):
    (out / 'objects/faces').mkdir(parents=True, exist_ok=True)
    db_path = out / 'objects.json'
    db = json.loads(db_path.read_text()) if db_path.exists() else {}
    people = google_people(photos_dir)
    faces = quality = None
    models, ms = {}, {d: [] for d in DETECTORS}
    photos = sorted(str(p.relative_to(photos_dir)) for p in photos_dir.rglob('*') if p.suffix.lower() in EXTS)
    for n, rel in enumerate(tqdm(photos, desc='photos')):
        e = db.setdefault(rel, {'id': hashlib.sha1(rel.encode()).hexdigest()[:12], 'det': {}})
        e['people'] = people.get(rel, [])
        todo = [d for d in DETECTORS if d not in e['det']]
        if not todo and 'faces' in e:
            continue
        img = load(photos_dir / rel)
        if img is None:
            tqdm.write(f'cannot read, skipped: {rel}')
            continue
        e['size'] = [img.shape[1], img.shape[0]]
        if 'faces' not in e:
            if faces is None:
                faces = YOLOv5Face(model_name=YOLOv5FaceWeights.YOLOV5M, providers=CPU)
                quality = EDifFIQA(model_name=EDifFIQAWeights.T, providers=CPU)
            e['faces'] = []
            for i, f in enumerate(faces.detect(img)):
                crop, _ = face_alignment(img, f.landmarks, image_size=112)
                cv2.imwrite(str(out / 'objects/faces' / f'{e["id"]}_{i}.jpg'), crop)
                e['faces'].append({
                    'box': [round(float(v), 1) for v in f.bbox],
                    'score': round(float(f.confidence), 3),
                    'q': round(float(quality.score_aligned(crop).score), 3),
                })
        for d in todo:
            if d not in models:
                models[d] = {'yolox': Yolox, 'rfdetr': RfDetr, 'yolo26': Yolo26}[d.split('_')[0]](d)
            t = time.perf_counter()
            e['det'][d] = models[d].detect(img)
            ms[d].append((time.perf_counter() - t) * 1000)
        if n % 20 == 0:
            db_path.write_text(json.dumps(db))
    db_path.write_text(json.dumps(db))
    for d in DETECTORS:
        if ms[d]:
            print(f'{d:8} {np.median(ms[d]):5.0f} ms/photo on CPU')


def cover(face, box):
    """Share of the face box inside the other box."""
    w = min(face[2], box[2]) - max(face[0], box[0])
    h = min(face[3], box[3]) - max(face[1], box[1])
    return max(w, 0) * max(h, 0) / ((face[2] - face[0]) * (face[3] - face[1]))


def score(photos_dir, out, det, cut, min_size):
    db = {rel: e for rel, e in json.loads((out / 'objects.json').read_text()).items() if 'faces' in e}
    faces = [(rel, i, f) for rel, e in db.items() for i, f in enumerate(e['faces'])
             if min(f['box'][2] - f['box'][0], f['box'][3] - f['box'][1]) >= min_size]
    print(f'{len(db)} photos, {len(faces)} faces from {min_size} px')
    print('detector  person score  faces outside every person box (of them inside an animal box)')
    lost = {}
    for d in DETECTORS:
        for t in sorted({0.1, 0.25, 0.5, cut}):
            out_faces = []
            for rel, i, f in faces:
                boxes = [b for b in db[rel]['det'][d] if b[1] >= t]
                if not any(cover(f['box'], b[2:]) >= 0.5 for b in boxes if b[0] == PERSON):
                    animal = any(cover(f['box'], b[2:]) >= 0.5 for b in boxes if b[0] in ANIMALS)
                    out_faces.append((rel, i, animal))
            print(f'{d:8}  {t:12}  {len(out_faces):5} ({sum(a for *_, a in out_faces)})')
            if t == cut:
                lost[d] = {(rel, i) for rel, i, _ in out_faces}
    # One sheet entry per face any detector leaves out; the codes say which ones (Xs = yolox_s, Rn = rfdetr_n, 6s = yolo26_s)
    keys = sorted(set().union(*lost.values()), key=lambda k: db[k[0]]['faces'][k[1]]['q'])
    sheet_dir = out / 'objects'
    for old in sheet_dir.glob('sheet_*.png'):
        old.unlink()
    index = {}
    for s in range(0, len(keys), 64):
        tiles = []
        for n, (rel, i) in enumerate(keys[s:s + 64], s + 1):
            e = db[rel]
            tile = cv2.resize(cv2.imread(str(sheet_dir / 'faces' / f'{e["id"]}_{i}.jpg')), (160, 160))
            who = ''.join(d.split('_')[0][-1].upper() + d[-1] for d in DETECTORS if (rel, i) in lost[d])
            cv2.rectangle(tile, (0, 0), (160, 18), (0, 0, 0), -1)
            cv2.putText(tile, f'{n} {who} q{e["faces"][i]["q"]:.2f}', (2, 13), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1)
            tiles.append(tile)
            index[n] = [rel, i]
        tiles += [np.zeros_like(tiles[0])] * (-len(tiles) % 8)
        rows = [np.hstack(tiles[k:k + 8]) for k in range(0, len(tiles), 8)]
        cv2.imwrite(str(sheet_dir / f'sheet_{s // 64 + 1}.png'), np.vstack(rows))
    (sheet_dir / 'sheets.json').write_text(json.dumps(index))
    print(f'{len(keys)} faces left out by any detector at person score {cut}: {sheet_dir}/sheet_*.png')

    counted = {(rel, i) for rel, i, _ in faces}
    rels = sorted({rel for rel, _ in lost[det]}, key=lambda r: -max(db[r]['faces'][i]['q'] for rr, i in lost[det] if rr == r))
    parts = [f'<h1>{det}, person score {cut}: {len(lost[det])} faces left out, {len(rels)} photos</h1>'
             '<p>Red: face outside every person box. Green: face inside one. Grey: face under the size limit. '
             'Blue: person box. Orange: animal box. Hover a box for its score.</p><div class=row>']
    for rel in rels:
        e = db[rel]
        w, h = e['size']

        def mark(box, color, title):
            x1, y1, x2, y2 = box
            return (f'<i title="{title}" style="border-color:{color};left:{x1 / w:.2%};top:{y1 / h:.2%};'
                    f'width:{(x2 - x1) / w:.2%};height:{(y2 - y1) / h:.2%}"></i>')

        marks = [mark(b[2:], 'blue' if b[0] == PERSON else 'orange', f'{"person" if b[0] == PERSON else "animal"} {b[1]}')
                 for b in e['det'][det] if b[1] >= cut and (b[0] == PERSON or b[0] in ANIMALS)]
        marks += [mark(f['box'], 'red' if (rel, i) in lost[det] else 'lime' if (rel, i) in counted else 'grey', f'face q {f["q"]}')
                  for i, f in enumerate(e['faces'])]
        left = ', '.join(f'q {e["faces"][i]["q"]}' for r, i in lost[det] if r == rel)
        tags = f' · Google tags: {html.escape(", ".join(e["people"]))}' if e['people'] else ''
        link = quote(os.path.relpath(photos_dir / rel, out))
        parts.append(f'<figure><div class=ph><a href="{link}"><img loading=lazy src="{link}"></a>{"".join(marks)}</div>'
                     f'<figcaption>{html.escape(rel)} · left out: {left}{tags}</figcaption></figure>')
    (out / 'objects.html').write_text(CSS + ''.join(parts) + '</div>')
    print(f'open {out / "objects.html"}')


def pets(photos_dir, out, det, cut):
    db = {rel: e for rel, e in json.loads((out / 'objects.json').read_text()).items() if 'size' in e}
    labels = json.loads((out / 'tags/labels.json').read_text())  # from bench/tags.py label
    truth = {rel: labels['pool']['pet'][rel] if rel in labels['pool'].get('pet', {}) else
             'pet' in labels['sample'][rel] if rel in labels['sample'] else None for rel in db}
    yes = [r for r, v in truth.items() if v]
    no = [r for r, v in truth.items() if v is False]
    print(f'{len(db)} photos, {len(yes)} labeled pet, {len(no)} labeled not pet')
    print('detector  score  pet photos found  boxes on not-pet photos  photos with a box (all)')
    for d in DETECTORS:
        for t in (0.3, 0.5, 0.7):
            def boxed(r):
                return any(b[0] in (CAT, DOG) and b[1] >= t for b in db[r]['det'][d])
            print(f'{d:8}  {t:5}  {sum(map(boxed, yes)):4} / {len(yes)}  {sum(map(boxed, no)):11} / {len(no)}  {sum(map(boxed, db)):10}')

    missed = [r for r in yes if not any(b[0] in (CAT, DOG) and b[1] >= cut for b in db[r]['det'][det])]
    parts = [f'<h1>{det}, pet score {cut}: {len(missed)} of {len(yes)} labeled pet photos with no cat or dog box</h1>'
             '<p>Orange: cat or dog boxes under the score. Blue: other animals (0.3+). Hover a box for its score.</p><div class=row>']
    for rel in missed:
        e = db[rel]
        w, h = e['size']
        marks = ''.join(f'<i title="{"cat" if b[0] == CAT else "dog" if b[0] == DOG else "animal"} {b[1]}" style="border-color:{"orange" if b[0] in (CAT, DOG) else "blue"};'
                        f'left:{b[2] / w:.2%};top:{b[3] / h:.2%};width:{(b[4] - b[2]) / w:.2%};height:{(b[5] - b[3]) / h:.2%}"></i>'
                        for b in e['det'][det] if b[0] in (CAT, DOG) or (b[0] in ANIMALS and b[1] >= 0.3))
        link = quote(os.path.relpath(photos_dir / rel, out))
        parts.append(f'<figure><div class=ph><a href="{link}"><img loading=lazy src="{link}"></a>{marks}</div>'
                     f'<figcaption>{html.escape(rel)}</figcaption></figure>')
    (out / 'pets.html').write_text(CSS + ''.join(parts) + '</div>')
    print(f'open {out / "pets.html"}')


def phone(photos_dir, path):
    """Same input as the app: the photo at 640 px, YOLO26-M boxes, cat and dog boxes 0.5+ cut from it for DINOv2-S (dinov2_s.onnx, see bench/pets.py)."""
    det = Yolo26('yolo26_m')
    dino = ort.InferenceSession(str(Path.home() / '.uniface/models/dinov2_s.onnx'), providers=CPU)
    same = lost = new = gate = 0
    score_diff, box_diff, sims = [], [], []
    data = json.loads(path.read_text())
    by_name = {p.name: p for p in photos_dir.rglob('*')}  # older test photos sit on the phone without their folder
    data = {rel: e for rel, e in data.items() if (photos_dir / rel).exists() or Path(rel).name in by_name}
    for rel, e in tqdm(data.items()):
        img = load(photos_dir / rel if (photos_dir / rel).exists() else by_name[Path(rel).name])
        small = cv2.resize(img, tuple(e['size']), interpolation=cv2.INTER_AREA)
        mac, app = [b for b in det.detect(small) if b[0] in (PERSON, CAT, DOG)], [[b['c'], b['s'], *b['box']] for b in e['boxes']]
        gate += any(b[0] == PERSON for b in mac) != any(b[0] == PERSON for b in app)
        for a in app:
            m = [b for b in mac if b[0] == a[0] and iou(a[2:], b[2:]) > 0.7]
            if m:
                score_diff.append(abs(a[1] - m[0][1]))
                box_diff.append(max(abs(u - v) for u, v in zip(a[2:], m[0][2:])))
            if a[1] >= 0.25:
                same, new = same + bool(m), new + (not m)
        lost += sum(b[1] >= 0.25 and not any(a[0] == b[0] and iou(a[2:], b[2:]) > 0.7 for a in app) for b in mac)
        for b in e['boxes']:
            if 'vec' not in b:
                continue
            x1, y1, x2, y2 = (int(v) for v in b['box'])
            c = small[max(y1, 0):y2, max(x1, 0):x2, ::-1]
            k = 224 / min(c.shape[:2])
            c = cv2.resize(c, (max(224, round(c.shape[1] * k)), max(224, round(c.shape[0] * k))), interpolation=cv2.INTER_AREA if k < 1 else cv2.INTER_LINEAR)  # AREA enlarges like nearest
            top, left = (c.shape[0] - 224) // 2, (c.shape[1] - 224) // 2
            x = ((c[top:top + 224, left:left + 224] / 255 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None]
            v = dino.run(None, {'input': x.astype(np.float32)})[0][0]
            sims.append(float(v @ b['vec'] / np.linalg.norm(v)))
    print(f'{len(data)} photos, boxes 0.25+: {same} same, {lost} lost, {new} new; the gate (a person 0.05+) differs on {gate} photos')
    print(f'matched boxes: score diff median {np.median(score_diff):.4f} max {max(score_diff):.4f}, box diff median {np.median(box_diff):.2f} max {max(box_diff):.2f} px of 640')
    if sims:
        print(f'pet vectors ({len(sims)}): similarity to Mac min {min(sims):.4f} median {np.median(sims):.4f}')


def iou(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    inter = max(w, 0) * max(h, 0)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['run', 'score', 'pets', 'phone'])
    ap.add_argument('json', nargs='?', type=Path, help='phone: the objects_<mode>.json pulled from the phone')
    ap.add_argument('--photos', type=Path, default=root / 'testdata/takeout')
    ap.add_argument('--out', type=Path, default=root / 'testdata/out')
    ap.add_argument('--det', default='yolox_s', choices=DETECTORS, help='score: detector for the page')
    ap.add_argument('--cut', type=float, default=0.1, help='score: person score for the sheets and the page')
    ap.add_argument('--pet-cut', type=float, default=0.5, help='pets: cat or dog score for the page')
    ap.add_argument('--min-size', type=int, default=40, help='score: skip faces smaller than this many px, as the app')
    a = ap.parse_args()
    if a.cmd == 'run':
        run(a.photos.resolve(), a.out.resolve())
    elif a.cmd == 'score':
        score(a.photos.resolve(), a.out.resolve(), a.det, a.cut, a.min_size)
    elif a.cmd == 'pets':
        pets(a.photos.resolve(), a.out.resolve(), a.det, a.pet_cut)
    else:
        phone(a.photos.resolve(), a.json)
