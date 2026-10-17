"""Compare the phone test screen results with the Mac bench (yolov5m_640 + adaface_ir101, fp32).

adb pull /sdcard/Android/data/app.memoria/files/results_cpu.json testdata/out/phone_cpu.json
uv run bench/compare_phone.py testdata/out/phone_cpu.json

Or the index DB (pull memoria.db, -wal and -shm with `adb exec-out run-as app.memoria cat databases/<file>`):
uv run bench/compare_phone.py testdata/out/db/memoria.db
"""

import json
import sqlite3
import sys
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent.parent / 'testdata/out'


def iou(a, b):
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def load_db(path):
    f32 = lambda b: np.frombuffer(b, '>f4').tolist()  # Room stores FloatArray as big-endian float32
    rows = sqlite3.connect(path).execute(
        "SELECT p.path, f.box, f.score, f.landmarks, f.vector, f.quality FROM photo p LEFT JOIN face f ON f.photoId = p.id WHERE p.kind != 'skip'")
    faces = {}
    for path, box, score, lm, vec, q in rows:
        faces.setdefault(path.removeprefix('Pictures/MemoriaTest/'), [])
        if box:
            faces[path.removeprefix('Pictures/MemoriaTest/')].append({'box': f32(box), 'score': score, 'lm': f32(lm), 'vec': f32(vec), 'q': q})
    return {rel: v for rel, v in faces.items() if rel in mac}


mac = json.loads((OUT / 'detect.json').read_text())
phone = load_db(sys.argv[1]) if sys.argv[1].endswith('.db') else json.loads(Path(sys.argv[1]).read_text())
vectors = dict(np.load(OUT / 'emb_yolov5m_640_adaface_ir101.npz'))
same = lost = new = 0
shift, sims, qd = [], [], []
for rel, faces in phone.items():
    old = mac[rel]['det']['yolov5m_640']
    for f in old:
        match = [p for p in faces if iou(f['box'], p['box']) > 0.7]
        if not match:
            lost += 1
            continue
        same += 1
        p = match[0]
        shift.append(np.abs(np.array(f['lm']).ravel() - np.array(p['lm'])).max() / (f['box'][2] - f['box'][0]))
        if 'q' in p:
            qd.append(abs(f['q'] - p['q']))
        if f['crop'] in vectors:  # only faces 40px+ have Mac vectors
            sims.append(float(vectors[f['crop']] @ np.array(p['vec'])))
    new += sum(not any(iou(f['box'], p['box']) > 0.7 for f in old) for p in faces)
print(f'{len(phone)} photos: {same} same faces, {lost} lost, {new} new')
print(f'landmark shift: median {np.median(shift):.2%}, max {max(shift):.2%} of face width')
print(f'vector similarity to Mac ({len(sims)} faces): min {min(sims):.4f}, 1% low {np.quantile(sims, 0.01):.4f}, median {np.median(sims):.4f}')
if qd:
    print(f'quality diff to Mac ({len(qd)} faces): median {np.median(qd):.4f}, 1% high {np.quantile(qd, 0.99):.4f}, max {max(qd):.4f}')
