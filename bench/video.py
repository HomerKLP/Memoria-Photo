"""Video recognition bench: frames from the videos, then faces in the frames.

uv run bench/video.py frames [--limit N]  -> testdata/out/video/frames/, frames.json, frames.html
uv run bench/video.py faces [--limit N]   -> faces/, faces.json, faces.npz, faces.html
uv run bench/video.py tracks [--cut 0.68] -> tracks.json, tracks.npz, tracks.html

Frames: one every max(3 s, length / 10) from the start, at most 10.
Faces: the app's models (YOLOv5m 640, EDifFIQA T, AdaFace IR101) on every frame.
Tracks: inside one video, faces 40 px+ joined by vector (average linkage), best frame per track.
"""

import argparse
import hashlib
import html
import json
import time
from pathlib import Path

import cv2
import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage
from tqdm import tqdm
from uniface import AdaFace, YOLOv5Face, face_alignment
from uniface.constants import AdaFaceWeights, EDifFIQAWeights, YOLOv5FaceWeights
from uniface.quality import EDifFIQA

from bench import CPU, CSS, google_people

EXTS = {'.mp4', '.mov', '.3gp', '.m4v'}


def frames(videos_dir, out, limit):
    (out / 'frames').mkdir(parents=True, exist_ok=True)
    db_path = out / 'frames.json'
    db = json.loads(db_path.read_text()) if db_path.exists() else {}
    people = google_people(videos_dir)
    videos = sorted(str(p.relative_to(videos_dir)) for p in videos_dir.rglob('*') if p.suffix.lower() in EXTS)
    for n, rel in enumerate(tqdm(videos[:limit], desc='videos')):
        if rel in db:
            continue
        vid = hashlib.sha1(rel.encode()).hexdigest()[:12]
        entry = db[rel] = {'id': vid, 'length': 0, 'fps': 0, 'size': [0, 0], 'rotation': 0, 'people': people.get(rel, []), 'frames': []}
        cap = cv2.VideoCapture(str(videos_dir / rel))
        fps, count = cap.get(cv2.CAP_PROP_FPS), cap.get(cv2.CAP_PROP_FRAME_COUNT)
        if not cap.isOpened() or fps <= 0 or count <= 0:
            entry['error'] = 'cannot open, or no fps or length'
        else:
            length = count / fps
            step = max(3.0, length / 10)
            entry.update(length=round(length, 2), fps=round(fps, 2), rotation=int(cap.get(cv2.CAP_PROP_ORIENTATION_META)))
            for t in [i * step for i in range(10) if i * step < length]:
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


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['frames', 'faces', 'tracks'])
    ap.add_argument('--videos', type=Path, default=root / 'testdata/takeout')
    ap.add_argument('--out', type=Path, default=root / 'testdata/out/video')
    ap.add_argument('--limit', type=int, help='only this many videos')
    ap.add_argument('--cut', type=float, default=0.68, help='tracks: cosine distance cut, the app\'s people cut')
    a = ap.parse_args()
    if a.cmd == 'frames':
        frames(a.videos.resolve(), a.out.resolve(), a.limit)
    elif a.cmd == 'faces':
        faces(a.out.resolve(), a.limit)
    else:
        tracks(a.out.resolve(), a.cut)
