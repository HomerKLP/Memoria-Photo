"""Video recognition bench: frames from the videos, then faces in the frames.

uv run bench/video.py frames [--limit N] [--every SEC] [--tagged] -> testdata/out/video/frames/, frames.json, frames.html
uv run bench/video.py faces [--limit N]   -> faces/, faces.json, faces.npz, faces.html
uv run bench/video.py pets [--limit N]    -> pets/, petboxes.json, petvecs.npz, pets.html
uv run bench/video.py tracks [--cut 0.68] -> tracks.json, tracks.npz, tracks.html
uv run bench/video.py score [--db testdata/out/db/memoria.db] -> score.json, score.html, covers/
uv run bench/video.py petscore [--db ...] -> petscore.json, petscore.html, covers/

Frames: one every max(3 s, length / 10) from the start, at most 10.
Faces: the app's models (YOLOv5m 640, EDifFIQA T, AdaFace IR101) on every frame.
Pets: the app's pet models (YOLO26-M on the frame at 640 px, cats and dogs 0.5+, noodle_s vector per box) on every frame.
Tracks: inside one video, faces 40 px+ joined by vector (average linkage), best frame per track.
Score: each track joins the phone's people like a non-seed face does (groupFaces in People.kt: yes faces within 0.5, else average distance within 0.68), checked against Google tags.
Petscore: pet tracks (boxes 0.7+, cut 0.68) join the phone's pets like a photo joins a group (groupPets: average distance within 0.68), a sheet to check by eye.
"""

import argparse
import hashlib
import html
import json
import sqlite3
import time
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from scipy.cluster.hierarchy import fcluster, linkage
from tqdm import tqdm
from uniface import AdaFace, YOLOv5Face, face_alignment
from uniface.constants import AdaFaceWeights, EDifFIQAWeights, YOLOv5FaceWeights
from uniface.quality import EDifFIQA

from bench import CPU, CSS, google_people
from objects import CAT, DOG, Yolo26, pet_vector

EXTS = {'.mp4', '.mov', '.3gp', '.m4v'}


def frames(videos_dir, out, limit, every, tagged):
    (out / 'frames').mkdir(parents=True, exist_ok=True)
    db_path = out / 'frames.json'
    db = json.loads(db_path.read_text()) if db_path.exists() else {}
    people = google_people(videos_dir)
    videos = sorted(str(p.relative_to(videos_dir)) for p in videos_dir.rglob('*') if p.suffix.lower() in EXTS)
    for n, rel in enumerate(tqdm(videos[:limit], desc='videos')):
        if rel in db or (tagged and not people.get(rel)):
            continue
        vid = hashlib.sha1(rel.encode()).hexdigest()[:12]
        entry = db[rel] = {'id': vid, 'length': 0, 'fps': 0, 'size': [0, 0], 'rotation': 0, 'people': people.get(rel, []), 'frames': []}
        cap = cv2.VideoCapture(str(videos_dir / rel))
        fps, count = cap.get(cv2.CAP_PROP_FPS), cap.get(cv2.CAP_PROP_FRAME_COUNT)
        if not cap.isOpened() or fps <= 0 or count <= 0:
            entry['error'] = 'cannot open, or no fps or length'
        else:
            length = count / fps
            step = every or max(3.0, length / 10)
            entry.update(length=round(length, 2), fps=round(fps, 2), rotation=int(cap.get(cv2.CAP_PROP_ORIENTATION_META)))
            for t in [i * step for i in range(int(length / step) + 1 if every else 10) if i * step < length]:
                # ponytail: exact frame, the phone reads the nearest keyframe (OPTION_CLOSEST_SYNC), so its frames are a bit off; seek keyframes if it matters
                cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
                ok, img = cap.read()  # OpenCV turns the frame upright by the rotation tag
                if not ok:  # seeking fails near the end of some variable frame rate videos, so read from the start
                    cap.release()
                    cap = cv2.VideoCapture(str(videos_dir / rel))
                    while cap.grab() and cap.get(cv2.CAP_PROP_POS_MSEC) < t * 1000:
                        pass
                    ok, img = cap.retrieve()
                if not ok:
                    continue
                name = f'{vid}_{round(t * 1000)}.jpg'
                cv2.imwrite(str(out / 'frames' / name), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
                entry['size'] = [img.shape[1], img.shape[0]]
                entry['frames'].append({'t': round(t, 2), 'file': name})
            if not entry['frames']:
                entry['error'] = 'no frame could be read'
        cap.release()
        if 'error' in entry:
            tqdm.write(f'{entry["error"]}: {rel}')
        if n % 20 == 19:
            db_path.write_text(json.dumps(db))
    db_path.write_text(json.dumps(db))

    parts = [CSS, '<style>.v{margin:0 0 10px}.v img{width:160px;margin:1px}</style>',
             '<h1>Video frames</h1><p>Check the frames are upright. Hover a frame for its time.</p>']
    for rel, e in sorted(db.items()):
        title = f'{rel}, {e["length"]:.0f} s, rotation {e["rotation"]}, tags: {", ".join(e["people"]) or "none"}'
        imgs = ''.join(f'<img loading=lazy src="frames/{f["file"]}" title="{f["t"]} s">' for f in e['frames'])
        parts.append(f'<div class=v><b>{html.escape(title)}</b> {html.escape(e.get("error", ""))}<br>{imgs}</div>')
    (out / 'frames.html').write_text(''.join(parts))
    print(f'{len(db)} videos, {sum(len(e["frames"]) for e in db.values())} frames, open {out / "frames.html"}')


def faces(out, limit):
    (out / 'faces').mkdir(exist_ok=True)
    db_path, vec_path = out / 'faces.json', out / 'faces.npz'
    db = json.loads(db_path.read_text()) if db_path.exists() else {}
    vecs = dict(np.load(vec_path)) if vec_path.exists() else {}
    videos = json.loads((out / 'frames.json').read_text())
    todo = [f for e in list(videos.values())[:limit] for f in e['frames'] if f['file'] not in db]
    if todo:
        detector = YOLOv5Face(model_name=YOLOv5FaceWeights.YOLOV5M, providers=CPU)  # keeps score >= 0.6 by default, like the app
        quality = EDifFIQA(model_name=EDifFIQAWeights.T, providers=CPU)
        recognizer = AdaFace(model_name=AdaFaceWeights.IR_101, providers=CPU)
    ms = []
    for n, frame in enumerate(tqdm(todo, desc='frames')):
        t = time.perf_counter()
        img = cv2.imread(str(out / 'frames' / frame['file']))
        found = []
        for i, f in enumerate(detector.detect(img)):
            crop, _ = face_alignment(img, f.landmarks, image_size=112)
            name = f'{Path(frame["file"]).stem}_{i}.png'  # png: embeddings must see the exact crop
            cv2.imwrite(str(out / 'faces' / name), crop)
            v = recognizer.get_embedding(crop).ravel()
            vecs[name] = (v / np.linalg.norm(v)).astype(np.float32)
            found.append({
                'box': [round(float(x), 1) for x in f.bbox],
                'score': round(float(f.confidence), 3),
                'lm': f.landmarks.round(1).tolist(),
                'q': round(float(quality.score_aligned(crop).score), 3),
                'crop': name,
            })
        db[frame['file']] = found
        ms.append((time.perf_counter() - t) * 1000)
        if n % 50 == 49:
            db_path.write_text(json.dumps(db))
            np.savez(vec_path, **vecs)
    db_path.write_text(json.dumps(db))
    np.savez(vec_path, **vecs)

    def size(f):
        return min(f['box'][2] - f['box'][0], f['box'][3] - f['box'][1])

    parts = [CSS, '<style>.faces img.s{box-sizing:border-box;border:3px solid #999}.faces span{margin-right:14px}</style>',
             '<h1>Video faces</h1><p>Crops by frame time. Grey border: under 40 px, the app skips it when grouping. Hover for t, q, score, size.</p>']
    for rel, e in sorted(videos.items()):
        imgs = ''
        for fr in e['frames']:
            crops = ''.join(
                f'<img loading=lazy src="faces/{f["crop"]}" class="{"s" if size(f) < 40 else ""}" '
                f'title="{fr["t"]} s, q {f["q"]}, score {f["score"]}, {size(f):.0f} px">'
                for f in db.get(fr['file'], [])
            )
            imgs += f'<span>{crops}</span>' if crops else ''
        if imgs:
            parts.append(f'<h4>{html.escape(rel)}, tags: {html.escape(", ".join(e["people"]) or "none")}</h4><div class=faces>{imgs}</div>')
    (out / 'faces.html').write_text(''.join(parts))
    all_faces = [f for fs in db.values() for f in fs]
    print(f'{len(db)} frames, {len(all_faces)} faces, {sum(size(f) >= 40 for f in all_faces)} from 40 px, '
          f'{np.median(ms) if ms else 0:.0f} ms/frame (this run), open {out / "faces.html"}')


def pets(out, limit):
    (out / 'pets').mkdir(exist_ok=True)
    db_path, vec_path = out / 'petboxes.json', out / 'petvecs.npz'
    db = json.loads(db_path.read_text()) if db_path.exists() else {}
    vecs = dict(np.load(vec_path)) if vec_path.exists() else {}
    videos = json.loads((out / 'frames.json').read_text())
    todo = [f for e in list(videos.values())[:limit] for f in e['frames'] if f['file'] not in db]
    if todo:
        detector = Yolo26('yolo26_m')
        dino = ort.InferenceSession(str(Path.home() / '.uniface/models/noodle_s.onnx'), providers=CPU)
    ms = []
    for n, frame in enumerate(tqdm(todo, desc='frames')):
        t = time.perf_counter()
        img = cv2.imread(str(out / 'frames' / frame['file']))
        s = 640 / max(img.shape[:2])  # the app's 640 px decode
        small = cv2.resize(img, (int(img.shape[1] * s), int(img.shape[0] * s)), interpolation=cv2.INTER_AREA) if s < 1 else img
        found = []
        for i, (cls, score, *box) in enumerate(b for b in detector.detect(small) if b[0] in (CAT, DOG) and b[1] >= 0.5):  # the app keeps 0.7+
            name = f'{Path(frame["file"]).stem}_{i}.jpg'
            x1, y1, x2, y2 = (int(v) for v in box)
            cv2.imwrite(str(out / 'pets' / name), small[max(y1, 0):y2, max(x1, 0):x2], [cv2.IMWRITE_JPEG_QUALITY, 95])
            vecs[name] = pet_vector(dino, small, box)
            found.append({'cls': cls, 'score': score, 'box': [round(float(x), 1) for x in box], 'crop': name})
        db[frame['file']] = found
        ms.append((time.perf_counter() - t) * 1000)
        if n % 50 == 49:
            db_path.write_text(json.dumps(db))
            np.savez(vec_path, **vecs)
    db_path.write_text(json.dumps(db))
    np.savez(vec_path, **vecs)

    def size(b):
        return min(b['box'][2] - b['box'][0], b['box'][3] - b['box'][1])

    parts = [CSS, '<style>.pets img{height:110px;margin:1px;box-sizing:border-box}.pets img.s{border:3px dashed #999}.pets span{margin-right:14px}</style>',
             '<h1>Video pets</h1><p>Cat and dog boxes 0.5+ by frame time. Dashed grey border: under 0.7, the app skips it. Hover for t, cat or dog, score, short side.</p>']
    for rel, e in sorted(videos.items()):
        imgs = ''
        for fr in e['frames']:
            crops = ''.join(
                f'<img loading=lazy src="pets/{b["crop"]}" class="{"s" if b["score"] < 0.7 else ""}" '
                f'title="{fr["t"]} s, {"cat" if b["cls"] == CAT else "dog"}, score {b["score"]}, {size(b):.0f} px">'
                for b in db.get(fr['file'], [])
            )
            imgs += f'<span>{crops}</span>' if crops else ''
        if imgs:
            parts.append(f'<h4>{html.escape(rel)}, tags: {html.escape(", ".join(e["people"]) or "none")}</h4><div class=pets>{imgs}</div>')
    (out / 'pets.html').write_text(''.join(parts))
    boxes = [b for bs in db.values() for b in bs]
    hit = [b for b in boxes if b['score'] >= 0.7]
    in_video = {rel for rel, e in videos.items() if any(b['score'] >= 0.7 for fr in e['frames'] for b in db.get(fr['file'], []))}
    print(f'{len(db)} frames, boxes 0.5+: {len(boxes)} ({sum(b["cls"] == CAT for b in boxes)} cats, {sum(b["cls"] == DOG for b in boxes)} dogs), '
          f'0.7+: {len(hit)} ({sum(b["cls"] == CAT for b in hit)} cats, {sum(b["cls"] == DOG for b in hit)} dogs), '
          f'{len(in_video)} videos with a 0.7+ box, {np.median(ms) if ms else 0:.0f} ms/frame (this run), open {out / "pets.html"}')


def tracks(out, cut):
    videos = json.loads((out / 'frames.json').read_text())
    db = json.loads((out / 'faces.json').read_text())
    vecs = np.load(out / 'faces.npz')
    result, tvecs, at = {}, {}, {}
    for rel, e in videos.items():
        found = [(fr['t'], f) for fr in e['frames'] for f in db.get(fr['file'], [])
                 if min(f['box'][2] - f['box'][0], f['box'][3] - f['box'][1]) >= 40]
        if not found:
            continue
        x = np.stack([vecs[f['crop']] for _, f in found])
        labels = fcluster(linkage(x, 'average', metric='cosine'), cut, 'distance') if len(found) > 1 else [1]
        groups = {}
        for label, tf in zip(labels, found):
            groups.setdefault(label, []).append(tf)
        result[rel] = []
        for k, g in enumerate(sorted(groups.values(), key=lambda g: min(t for t, _ in g))):
            g.sort(key=lambda tf: tf[0])
            top = sorted(g, key=lambda tf: -tf[1]['q'])  # stable: a tie keeps the earlier frame
            avg3 = sum(vecs[f['crop']] for _, f in top[:3])
            tvecs[f'{e["id"]}_{k}_best'] = vecs[top[0][1]['crop']]
            tvecs[f'{e["id"]}_{k}_avg3'] = (avg3 / np.linalg.norm(avg3)).astype(np.float32)
            at.update({f['crop']: (t, f['q']) for t, f in g})
            result[rel].append({'crops': [f['crop'] for _, f in g], 'best': top[0][1]['crop'], 'best_q': top[0][1]['q'], 'frames': len({t for t, _ in g})})
    (out / 'tracks.json').write_text(json.dumps(result))
    np.savez(out / 'tracks.npz', **tvecs)

    parts = [CSS, '<style>.faces img.b{box-sizing:border-box;border:4px solid #0a0}.faces{margin-bottom:6px}</style>',
             f'<h1>Video tracks, cut {cut}</h1><p>One line per track, crops in time order. Green border: the best one. Hover for t, q.</p>']
    for rel, ts in sorted(result.items(), key=lambda kv: (not videos[kv[0]]['people'], kv[0])):
        parts.append(f'<h4>{html.escape(rel)}, tags: {html.escape(", ".join(videos[rel]["people"]) or "none")}</h4>')
        for tr in ts:
            imgs = ''.join(f'<img loading=lazy src="faces/{c}" class="{"b" if c == tr["best"] else ""}" title="{at[c][0]} s, q {at[c][1]}">' for c in tr['crops'])
            parts.append(f'<div class=faces>{imgs}</div>')
    (out / 'tracks.html').write_text(''.join(parts))
    all_tracks = [tr for ts in result.values() for tr in ts]
    print(f'{len(result)} of {len(videos)} videos have faces, {len(all_tracks)} tracks, '
          f'{sum(tr["frames"] == 1 for tr in all_tracks)} in 1 frame, {sum(tr["best_q"] >= 0.4 for tr in all_tracks)} with best q >= 0.4, '
          f'{sum(len(tr["crops"]) > tr["frames"] for tr in all_tracks)} with two faces in one frame, open {out / "tracks.html"}')


def score(out, db_path, videos_dir):
    videos = json.loads((out / 'frames.json').read_text())
    tracks = json.loads((out / 'tracks.json').read_text())
    tv = np.load(out / 'tracks.npz')
    con = sqlite3.connect(db_path)

    def vec(b):
        v = np.frombuffer(b, '>f4')  # Room stores FloatArray as big-endian float32
        return v / np.linalg.norm(v)

    # The phone's people: seed faces like People.kt (camera, q 0.4+, 40 px+, never answered), yes faces as refs
    answered = {r[0] for r in con.execute('SELECT faceId FROM answer')}
    faces = {r[0]: r for r in con.execute(
        'SELECT f.id, f.photoId, p.path, p.kind, f.box, f.vector, f.quality, f.personId FROM face f JOIN photo p ON p.id = f.photoId WHERE f.notFace = 0 AND f.pet = 0')}
    seeds = {}
    for fid, _, _, kind, box, v, q, pid in faces.values():
        b = np.frombuffer(box, '>f4')
        if pid is not None and kind == 'camera' and q >= 0.4 and min(b[2] - b[0], b[3] - b[1]) >= 40 and fid not in answered:
            seeds.setdefault(pid, []).append(vec(v))
    persons = sorted(seeds)
    col = {p: i for i, p in enumerate(persons)}
    S, n = np.stack([sum(seeds[p]) for p in persons]), np.array([len(seeds[p]) for p in persons])
    refs = [(faces[f][7], vec(faces[f][5])) for f, pf in con.execute('SELECT faceId, personFace FROM answer WHERE yes = 1')
            if f in faces and faces[f][6] >= 0.3 and pf in faces and faces[pf][7] in seeds]
    R, rp = np.stack([v for _, v in refs]), [p for p, _ in refs]

    # Google names: the top tag of the photos where the person is the only face (40 px+) and the photo has one tag. Needs 2+ votes and 60%.
    takeout = {}
    for rel, t in google_people(videos_dir).items():
        takeout.setdefault(Path(rel).name, set()).add(tuple(t))
    tags = {b: list(t.pop()) for b, t in takeout.items() if len(t) == 1}
    big, count = {}, Counter()
    for f in faces.values():
        b = np.frombuffer(f[4], '>f4')
        big[f[0]] = min(b[2] - b[0], b[3] - b[1]) >= 40
        count[f[1]] += big[f[0]]
    votes = {}
    for fid, photo, path, _, _, _, _, pid in faces.values():
        t = tags.get(Path(path).name, [])
        if pid is not None and big[fid] and count[photo] == 1 and len(t) == 1:
            votes.setdefault(pid, []).append(t[0])
    gname, share = {}, {}
    for p in {f[7] for f in faces.values()} - {None}:
        top = Counter(votes.get(p, [])).most_common(1)
        share[p] = (top[0][1], len(votes[p])) if top else (0, 0)
        gname[p] = top[0][0] if top and top[0][1] >= 2 and top[0][1] >= 0.6 * len(votes[p]) else None
    idx = {n: i for i, n in enumerate(sorted(set(gname.values()) - {None}))}
    info = {r[0]: r for r in con.execute('SELECT id, name, hidden FROM person')}

    def chk(rel, p):
        t = videos[rel]['people']
        return 'unknown' if not t or not gname.get(p) else 'right' if gname[p] in t else 'wrong'

    def place(v):
        d = 1 - S @ v / n  # average cosine distance to each person's seeds
        r = 1 - R @ v
        if r.min() <= 0.5:
            return {'person': rp[r.argmin()], 'd': float(r.min()), 'via': 'yes'}, d
        return {'person': persons[d.argmin()], 'd': float(d.min()), 'via': 'avg' if d.min() <= 0.68 else None}, d

    rows = []
    for rel, ts in tracks.items():
        for k, tr in enumerate(ts):
            r = {'rel': rel, 'vid': videos[rel]['id'], 'k': k, 'tr': tr}
            for key in ('best', 'avg3'):
                r[key], r['d_' + key] = place(tv[f'{r["vid"]}_{k}_{key}'])
                r[key]['joined'] = r[key]['via'] is not None
                r[key]['check'] = chk(rel, r[key]['person']) if r[key]['joined'] else None
            rows.append(r)
    (out / 'score.json').write_text(json.dumps({rel: [{'best_q': r['tr']['best_q'], 'best': r['best'], 'avg3': r['avg3']} for r in rows if r['rel'] == rel] for rel in tracks}))

    def tally(rs, key, cut):  # a yes join has d <= 0.5, so d <= cut covers it too
        j = [r for r in rs if r[key]['d'] <= cut]
        c = Counter(chk(r['rel'], r[key]['person']) for r in j)
        return f'{len(rs)} tracks, {len(j)} joined, {c["right"]} right, {c["wrong"]} wrong'

    print(f'{len(rows)} tracks, {len(persons)} people with seeds (phone has {len({f[7] for f in faces.values()} - {None})}), {len(refs)} yes refs, {len(info)} person rows')
    for p in persons:
        print(f'person {p}: {len(seeds[p])} seeds, top tag {share[p][0]} of {share[p][1]} one-face one-tag photos, ' + (f'name #{idx[gname[p]]}' if gname[p] else 'no name'))
    print(f'{sum(1 for p in gname if p not in seeds)} people without seeds, {sum(1 for p in persons if not gname[p])} with seeds and no Google name')
    for key in ('best', 'avg3'):
        j = [r for r in rows if r[key]['joined']]
        pairs = {}
        for r in j:
            pairs.setdefault((r['vid'], r[key]['person']), []).append(r)
        multi = [g for g in pairs.values() if len(g) > 1]
        same_frame = sum(Counter(s for r in g for s in {c.rsplit('_', 1)[0] for c in r['tr']['crops']}).most_common(1)[0][1] > 1 for g in multi)
        print(f'\n[{key}] {len(j)} joined ({sum(r[key]["via"] == "yes" for r in j)} yes / {sum(r[key]["via"] == "avg" for r in j)} avg), '
              f'{len({r["vid"] for r in j})} videos with 1+ join, {len({r[key]["person"] for r in j})} people with 1+ video, '
              f'{len(multi)} (video, person) pairs with 2+ tracks, {same_frame} of them with two faces in one frame')
    both = [r for r in rows if r['best']['joined'] and r['avg3']['joined']]
    print(f'\nbest vs avg3: {sum(r["best"]["person"] == r["avg3"]["person"] for r in both)} same person, '
          f'{sum(r["best"]["person"] != r["avg3"]["person"] for r in both)} different person, '
          f'{sum(r["best"]["joined"] and not r["avg3"]["joined"] for r in rows)} only best joins, '
          f'{sum(r["avg3"]["joined"] and not r["best"]["joined"] for r in rows)} only avg3 joins, '
          f'{sum(not r["best"]["joined"] and not r["avg3"]["joined"] for r in rows)} neither')
    named = set(gname.values()) - {None}
    for key in ('best', 'avg3'):
        c = Counter(r[key]['check'] for r in rows if r[key]['joined'])
        found, missed = 0, []
        for rel, e in videos.items():
            rs = [r for r in rows if r['rel'] == rel]
            for t in set(e['people']) & named:
                if any(r[key]['joined'] and gname[r[key]['person']] == t for r in rs):
                    found += 1
                else:
                    ds = [(min(r['d_' + key][col[p]] for r in rs), p) for p in gname if gname[p] == t and p in col and rs]
                    missed.append((e['id'], *(min(ds)[::-1] if ds else (None, None))))
        print(f'\n[{key}] tag check: {c["right"]} right, {c["wrong"]} wrong, {c["unknown"]} unknown. Tag recall: {found} found, {len(missed)} missed')
        for vid, p, d in sorted(missed, key=lambda m: (m[2] is None, m[2])):
            print(f'  missed video {vid} person {p} closest d ' + (f'{d:.2f}' if d is not None else 'none (no track or no seeds)'))
    for key in ('best', 'avg3'):
        blurry = [r for r in rows if r['tr']['best_q'] < 0.4]
        sharp = [r for r in rows if r['tr']['best_q'] >= 0.4]
        print(f'\n[{key}] blurry (best q < 0.4): {tally(blurry, key, 0.68)}\n[{key}] sharp: {tally(sharp, key, 0.68)}')
        print(f'[{key}] blurry joined via yes: {sum(r[key]["via"] == "yes" for r in blurry)}')
        for cut in (0.68, 0.62, 0.58, 0.55, 0.5):
            print(f'  blurry, cut {cut}: {tally(blurry, key, cut)}')
        for cut in (0.62, 0.58):
            print(f'  sharp, cut {cut}: {tally(sharp, key, cut)}')

    # Page: each person with joined tracks (best vector), then the disagreements and the near misses
    (out / 'covers').mkdir(exist_ok=True)
    for p, b in con.execute('SELECT p.id, f.thumb FROM person p JOIN face f ON f.id = COALESCE(p.coverFaceId, p.mainFaceId)'):
        (out / 'covers' / f'{p}.webp').write_bytes(b)

    def fig(src, size, cap, style='', title=''):
        return (f'<figure title="{html.escape(title)}"><img loading=lazy src="{src}" style="width:{size}px;height:{size}px;{style}">'
                f'<figcaption>{cap}</figcaption></figure>')

    def crop(r):
        x, q = r['best'], r['tr']['best_q']
        color = {'right': 'green', 'wrong': 'red'}.get(x['check'], '#999' if q < 0.4 else 'transparent')
        title = f'{r["rel"]}\ntags: {", ".join(videos[r["rel"]]["people"])}\ntrack {r["k"]}'
        return fig(f'faces/{r["tr"]["best"]}', 72, f'd {x["d"]:.2f} q {q:.2f} {x["via"] or "out"}', f'border:3px {"solid" if q >= 0.4 else "dashed"} {color}', title)

    def cover(r, key):
        x = r[key]
        return fig(f'covers/{x["person"]}.webp', 40, f'{key} d {x["d"]:.2f} {x["via"] or "out"}', title=f'person {x["person"]}, {gname.get(x["person"])}')

    parts = [CSS, '<style>figure{display:inline-block;margin:2px;width:76px;vertical-align:top}img{box-sizing:border-box;object-fit:cover}figcaption{font-size:9px;line-height:1.2}.r{display:flex;align-items:flex-start;border-bottom:1px solid #ddd;padding:4px 0}.r>div{width:170px;flex:none}</style>',
             '<h1>Video tracks joined to the phone people</h1><p>Best vector, cut 0.68 (yes faces 0.5). Caption: d, best q, yes or avg. Green: the video tags have the person name. Red: the video has tags and not this name. Dashed: best q under 0.4. Hover for the video.</p>']
    joined = {}
    for r in rows:
        if r['best']['joined']:
            joined.setdefault(r['best']['person'], []).append(r)
    for p, rs in sorted(joined.items(), key=lambda kv: -len({r['vid'] for r in kv[1]})):
        i = info.get(p, (p, None, 0))
        text = f'<div>person {p}<br>Google: {html.escape(gname[p] or "none")} ({share[p][0]}/{share[p][1]})<br>phone: {html.escape(i[1] or "none")}{"<br>hidden" if i[2] else ""}<br>{len(rs)} tracks, {len({r["vid"] for r in rs})} videos</div>'
        parts.append(f'<div class=r>{fig(f"covers/{p}.webp", 72, "", "border:5px solid #333")}{text}<div style="width:auto">{"".join(map(crop, rs))}</div></div>')
    off = [r for r in rows if r['best']['joined'] != r['avg3']['joined'] or (r['best']['joined'] and r['best']['person'] != r['avg3']['person'])]
    parts.append(f'<h2>Best and avg3 disagree ({len(off)})</h2>' + ''.join(f'<div class=r><div style="width:auto">{crop(r)}</div>{cover(r, "best")}{cover(r, "avg3")}</div>' for r in off))
    near = sorted((r for r in rows if not r['best']['joined'] and r['best']['d'] <= 0.8), key=lambda r: r['best']['d'])
    parts.append(f'<h2>Close but out ({len(near)}), best vector, closest first</h2>' + ''.join(f'<div class=r><div style="width:auto">{crop(r)}</div>{cover(r, "best")}</div>' for r in near))
    (out / 'score.html').write_text(''.join(parts))
    print(f'\nopen {out / "score.html"}')


def petscore(out, db_path):
    videos = json.loads((out / 'frames.json').read_text())
    boxes = json.loads((out / 'petboxes.json').read_text())
    pv = np.load(out / 'petvecs.npz')
    con = sqlite3.connect(db_path)

    def unit(v):
        return (v / np.linalg.norm(v)).astype(np.float32)

    # Tracks: boxes 0.7+ (the app floor) joined inside one video, like tracks()
    rows = []
    for rel, e in videos.items():
        found = [(fr['t'], b) for fr in e['frames'] for b in boxes.get(fr['file'], []) if b['score'] >= 0.7]
        if not found:
            continue
        x = np.stack([pv[b['crop']] for _, b in found])
        labels = fcluster(linkage(x, 'average', metric='cosine'), 0.68, 'distance') if len(found) > 1 else [1]
        groups = {}
        for label, tb in zip(labels, found):
            groups.setdefault(label, []).append(tb)
        for k, g in enumerate(sorted(groups.values(), key=lambda g: min(t for t, _ in g))):
            g.sort(key=lambda tb: tb[0])
            top = sorted(g, key=lambda tb: -tb[1]['score'])  # stable: a tie keeps the earlier frame
            rows.append({'rel': rel, 'vid': e['id'], 'k': k, 'crops': [b['crop'] for _, b in g], 'crop': top[0][1]['crop'], 'best_score': top[0][1]['score'],
                         'v': {'best': unit(pv[top[0][1]['crop']]), 'avg3': unit(sum(pv[b['crop']] for _, b in top[:3]))}})

    # The phone's pets like groupPets: pictures of a pet, the answered ones left out
    answered = {r[0] for r in con.execute('SELECT faceId FROM answer')}
    sums, count, sizes = {}, Counter(), Counter()
    for fid, pid, b in con.execute('SELECT id, personId, vector FROM face WHERE pet = 1 AND notFace = 0 AND personId IS NOT NULL'):
        if fid in answered:
            continue
        v = np.frombuffer(b, '>f4')  # Room stores FloatArray as big-endian float32
        sizes[len(v)] += 1
        sums[pid] = sums.get(pid, 0) + unit(v)
        count[pid] += 1
    info = {r[0]: r for r in con.execute('SELECT p.id, p.name, p.hidden, COALESCE(p.coverFaceId, p.mainFaceId) FROM person p')}
    pets_ = sorted(sums)
    print(f'pet vector sizes {dict(sizes)}, pets {len(pets_)}, person rows missing: {[p for p in pets_ if p not in info]}')
    assert set(sizes) == {512} and all(p in info for p in pets_)
    S, n = np.stack([sums[p] for p in pets_]), np.array([count[p] for p in pets_])

    for r in rows:
        for key in ('best', 'avg3'):
            d = 1 - S @ r['v'][key] / n  # average cosine distance to each pet's pictures
            r[key] = {'pet': pets_[d.argmin()], 'd': float(d.min())}
            r[key]['joined'] = r[key]['d'] <= 0.68
    (out / 'petscore.json').write_text(json.dumps({rel: [
        {'crops': r['crops'], 'best_crop': r['crop'], 'best_score': r['best_score'], 'best': r['best'], 'avg3': r['avg3']} for r in rows if r['rel'] == rel]
        for rel in dict.fromkeys(r['rel'] for r in rows)}))

    two = sum(len(r['crops']) > len({c.rsplit('_', 1)[0] for c in r['crops']}) for r in rows)
    print(f'{len(rows)} tracks in {len({r["vid"] for r in rows})} videos, {two} with two boxes from one frame')
    print('pet: pictures | best tracks / videos | avg3 tracks / videos (0.68)')
    for p in pets_:
        c = [[r for r in rows if r[key]['joined'] and r[key]['pet'] == p] for key in ('best', 'avg3')]
        print(f'  pet {p}: {count[p]} | {len(c[0])} / {len({r["vid"] for r in c[0]})} | {len(c[1])} / {len({r["vid"] for r in c[1]})}')
    for key in ('best', 'avg3'):
        for cut in (0.68, 0.72, 0.76):
            j = [r for r in rows if r[key]['d'] <= cut]
            print(f'[{key}] cut {cut}: {len(j)} joined, {len({r["vid"] for r in j})} videos, {len({r[key]["pet"] for r in j})} pets')
    both = [r for r in rows if r['best']['joined'] and r['avg3']['joined']]
    off = [r for r in rows if r['best']['joined'] != r['avg3']['joined'] or (r['best']['joined'] and r['best']['pet'] != r['avg3']['pet'])]
    print(f'best vs avg3: {sum(r["best"]["pet"] == r["avg3"]["pet"] for r in both)} same pet, {sum(r["best"]["pet"] != r["avg3"]["pet"] for r in both)} different pet, '
          f'{sum(r["best"]["joined"] and not r["avg3"]["joined"] for r in rows)} only best joins, {sum(r["avg3"]["joined"] and not r["best"]["joined"] for r in rows)} only avg3 joins')

    # Page: each pet with joined tracks (avg3 vector), then the disagreements and the near misses
    (out / 'covers').mkdir(exist_ok=True)
    for p in pets_:
        (out / 'covers' / f'{p}.webp').write_bytes(con.execute('SELECT thumb FROM face WHERE id = ?', (info[p][3],)).fetchone()[0])

    def fig(src, size, cap, style='', title=''):
        return (f'<figure title="{html.escape(title)}"><img loading=lazy src="{src}" style="width:{size}px;height:{size}px;{style}">'
                f'<figcaption>{cap}</figcaption></figure>')

    def track(r, key):
        imgs = ''.join(f'<img loading=lazy src="pets/{c}" style="{"border:4px solid #0a0" if c == r["crop"] else ""}">' for c in r['crops'])
        return (f'<div class=t title="{html.escape(r["rel"])}\ntrack {r["k"]}">{imgs}'
                f'<br><small>{key} d {r[key]["d"]:.2f}, best {r["best_score"]:.2f}, pet {r[key]["pet"]}</small></div>')

    parts = [CSS, '<style>figure{display:inline-block;margin:2px;width:76px;vertical-align:top}.t{display:inline-block;margin:0 10px 6px 0;vertical-align:top}.t img{height:90px;margin:1px;box-sizing:border-box}'
             'img{box-sizing:border-box;object-fit:cover}figcaption{font-size:9px;line-height:1.2}.r{border-bottom:1px solid #ddd;padding:6px 0}</style>',
             '<h1>Video pet tracks joined to the phone pets</h1><p>Avg3 vector, joins at 0.68 like a photo joins a group. Each pet: its cover (dark border) and its 5 best other pictures, then its tracks. Green border: the best crop. Hover for the video and track.</p>']
    for p in sorted({r['avg3']['pet'] for r in rows if r['avg3']['joined']}, key=lambda p: -sum(r['avg3']['joined'] and r['avg3']['pet'] == p for r in rows)):
        rs = [r for r in rows if r['avg3']['joined'] and r['avg3']['pet'] == p]
        others = con.execute('SELECT id, quality, thumb FROM face WHERE pet = 1 AND notFace = 0 AND personId = ? AND id != ? ORDER BY quality DESC LIMIT 5', (p, info[p][3])).fetchall()
        for fid, _, b in others:
            (out / 'covers' / f'pet_{fid}.webp').write_bytes(b)
        pics = fig(f'covers/{p}.webp', 72, '', 'border:5px solid #333') + ''.join(fig(f'covers/pet_{fid}.webp', 72, f'q {q:.2f}') for fid, q, _ in others)
        parts.append(f'<div class=r><b>pet {p}</b>, {count[p]} pictures, {len(rs)} tracks, {len({r["vid"] for r in rs})} videos<br>{pics}<br>{"".join(track(r, "avg3") for r in rs)}</div>')
    near = sorted((r for r in rows if not r['avg3']['joined'] and r['avg3']['d'] <= 0.8), key=lambda r: r['avg3']['d'])
    parts.append(f'<h2>Close but out ({len(near)}), avg3 vector, closest first</h2>' + ''.join(
        f'<div class=r>{fig(f"covers/{r["avg3"]["pet"]}.webp", 72, "closest", "border:5px solid #333")}{track(r, "avg3")}</div>' for r in near))
    parts.append(f'<h2>Best and avg3 disagree ({len(off)})</h2>' + ''.join(
        f'<div class=r>{fig(f"covers/{r["best"]["pet"]}.webp", 72, f"best d {r["best"]["d"]:.2f}", "border:5px solid #333")}'
        f'{fig(f"covers/{r["avg3"]["pet"]}.webp", 72, f"avg3 d {r["avg3"]["d"]:.2f}", "border:5px solid #333")}{track(r, "best")}</div>' for r in off))
    (out / 'petscore.html').write_text(''.join(parts))
    print(f'\nopen {out / "petscore.html"}')


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['frames', 'faces', 'pets', 'tracks', 'score', 'petscore'])
    ap.add_argument('--videos', type=Path, default=root / 'testdata/takeout')
    ap.add_argument('--out', type=Path, default=root / 'testdata/out/video')
    ap.add_argument('--limit', type=int, help='only this many videos')
    ap.add_argument('--every', type=float, help='frames: a fixed step in seconds, no 10 frame cap')
    ap.add_argument('--tagged', action='store_true', help='frames: only videos with Google people tags')
    ap.add_argument('--cut', type=float, default=0.68, help='tracks: cosine distance cut, the app\'s people cut')
    ap.add_argument('--db', type=Path, default=root / 'testdata/out/db/memoria.db', help='score, petscore: copy of the phone DB')
    a = ap.parse_args()
    if a.cmd == 'frames':
        frames(a.videos.resolve(), a.out.resolve(), a.limit, a.every, a.tagged)
    elif a.cmd == 'faces':
        faces(a.out.resolve(), a.limit)
    elif a.cmd == 'pets':
        pets(a.out.resolve(), a.limit)
    elif a.cmd == 'tracks':
        tracks(a.out.resolve(), a.cut)
    elif a.cmd == 'score':
        score(a.out.resolve(), a.db.resolve(), a.videos.resolve())
    else:
        petscore(a.out.resolve(), a.db.resolve())
