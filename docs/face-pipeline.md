# Face pipeline

## Steps
1. Skip by metadata: screenshots, messenger folders, no camera EXIF, GIFs, tiny images.
2. Decode the photo, apply EXIF rotation. Modern phone photos can be 50 MP, so pick the decode size with care: too small loses faces in group photos.
3. Detect: face box, 5 landmarks (eyes, nose, mouth corners), score.
4. Quality check: face size, blur, head turn (from landmarks), score. Bad faces stay detected but do not define a person.
5. Align: warp the face to 112x112 using the 5 landmarks.
6. Face vector: run the recognition model, normalize the vector.
7. Group: cluster face vectors by cosine distance.
8. User fixes: per-person vector sets, "not this person" examples, per-person threshold.

## How we measure quality
- Detection: missed faces, fake faces, small faces in group photos, side faces, kids.
- Grouping: wrong person in a group, one person split into many groups.
- Speed on the phone matters less. Indexing runs in the background.

## Model candidates
Numbers are from papers and model cards, checked 2026-10-14. "~" means from memory, check before trusting.
Papers test on crowds and celebrities, not family albums. Final pick comes from our own photos.

### Detectors (need 5 landmarks for alignment)
WIDER FACE val AP, easy / medium / hard. "Hard" is mostly tiny crowd faces, which we may skip anyway.

| Model | AP | Size | License |
|---|---|---|---|
| RetinaFace R50 | ~95.5 / 94.0 / 84.4 | ~27M params | Code MIT (biubug6), InsightFace weights non-commercial |
| SCRFD-10G-kps | ~95.4 / 94.0 / 82.8 | ~4M params | InsightFace, non-commercial |
| YOLOv5-face (s to x6) | ~94.3-96.7 / 92.6-95.5 / 82.8-86.6 | small to big | GPL-3.0 |
| YOLOv8/11-face | YOLO11n: 94.2 / 92.1 / 81.0 | small | AGPL-3.0. Some versions have no landmarks. |
| YuNet | ~88 / 87 / 75 | 75K params | MIT. Baseline only. |
| BlazeFace | weak on small faces | tiny | Apache-2.0. Skip. |

### Face vector models
IJB-C TAR at FAR=1e-4 (higher is better).

| Model | IJB-C | Size | License |
|---|---|---|---|
| LVFace ViT-L (WebFace42M) | 98.06 | very big, too heavy for phone; ViT-B maybe | Code MIT, weights non-commercial |
| AdaFace IR101 (WebFace12M) | ~97.7 | ~65M params | Weights tagged MIT, but WebFace data is non-commercial. Strong on blurry faces. |
| ArcFace R50 (buffalo_l, Immich default) | ~97 | ~43M params | InsightFace, non-commercial |
| EdgeFace | 94.85 (small one) | 1-18M params | Idiap, unclear, ask on request |
| MobileFaceNet | ~93-95 | ~1M params | Depends on source |
| SFace | lower | small | Apache-2.0 |

### License reality
- Almost every good face model learned from research-only photo sets (WIDER FACE, MS1M, WebFace, Glint360K). Gray zone across the whole field.
- Immich, the biggest open-source photo app, uses non-commercial InsightFace models (buffalo_l) by default.
- Model files are separate from our GPL code and keep their own license note.
- For private testing, license does not matter. Decide how to ship after the pick.

### Best on paper
- Detectors: on faces big enough to recognize (WIDER easy/medium) all candidates are within 1-2%. Paper leaders (TinaFace, multi-scale RetinaFace) win only on tiny faces and are too heavy for a phone. Bigger YOLOv5-face (l6, x6) are a bit better than m, not in UniFace.
- Recognizers: LVFace ViT-L is top (IJB-C 98.06), but huge and non-commercial. AdaFace, ArcFace R100, TopoFR sit around 97.5. AdaFace is best on low-quality faces (TinyFace, IJB-S), the closest match to personal photos.

### Model updates
- New models ship with app updates. A new detector only needs new detection on new photos.
- A new recognizer needs all face vectors recomputed (background, minutes). Vectors from two models do not mix.
- So store person names and user fixes per face (photo + box), not only as vectors. Then a model swap keeps them.
- No retraining on the phone. Learning from user fixes = per-person vector sets (see `features.md`).
- How often they change: YOLO gets new versions yearly, but face versions are community ports that lag. RetinaFace (2019), SCRFD (2021), AdaFace (2022) weights do not change. New recognizer papers come yearly, few release weights.

### Tools
- UniFace (`pip install uniface`, MIT code): RetinaFace, SCRFD, YOLOv5-face, ArcFace, MobileFaceNet as ONNX. Good start for the bench.
- Qualcomm AI Hub: many models ready for the Snapdragon 8 Elite NPU.

## Test results

### 2026-10-14: smoke test, 4 public-domain photos
Solvay 1911 (24 people), Solvay 1927 (29), two Einstein portraits. Old b/w scans. Mac, CPU.

| Detector | Faces (of 55) | ms/photo |
|---|---|---|
| retinaface_r50_1280 | 55 | 814 |
| scrfd_10g_640 | 55 | 72 |
| scrfd_10g_1280 | 54, missed a big close-up face | 278 |
| yolov5m_640 | 54, missed one small face | 121 |

- Big input (1280) helps small faces but SCRFD then misses very big ones. Fix: run 640 and 1280, merge.
- Grouping at threshold 0.65: AdaFace and ArcFace both matched Einstein (3 photos), Curie and Lorentz across 16 years, no wrong groups seen.
- EdgeFace made 3 more pairs at the same threshold. Models have different distance scales, so one threshold is not fair. Need labeled people to compare.

### 2026-10-14: 187 people-tagged photos from Google Takeout (2013-2025)
Mac, CPU. Recognizers use RetinaFace R50 1280 faces, 40px and up.

| Detector | Faces | Missed (3 others found it) | Photos with fewer faces than tags | ms/photo |
|---|---|---|---|---|
| retinaface_r50_1280 | 394 | 1 | 0 | 786 |
| scrfd_10g_640 | 344 | 8, small faces | 2 | 68 |
| scrfd_10g_1280 | 362 | 10, all big selfie faces | 7 | 258 |
| yolov5m_640 | 363 | 3 | 2 | 118 |

- RetinaFace extras (found only by it) are real small or turned-away faces: kids, back of heads. Size and quality filters handle them.
- scrfd_10g_1280 removed from the bench.
- RetinaFace at 1280 missed a face filling a 1080x2400 screenshot. At 640 it finds it. Fix: run 640 and 1280, merge.

Recognizers, `bench.py eval`: 93 labeled faces (one tag + one face), 8 people, 1988 same-person pairs. Two people make up 81 faces, so treat as a first look.

| Recognizer | TAR@FAR1% | TAR@FAR0.1% | Groups at FAR 0.1% cut: precision / recall |
|---|---|---|---|
| adaface_ir101 | 0.916 | 0.886 | 1.000 / 0.913 (distance 0.68) |
| edgeface_base | 0.875 | 0.814 | 1.000 / 0.783 (distance 0.65) |
| arcface_r50 | 0.847 | 0.805 | 1.000 / 0.775 (distance 0.66) |

- Hardest faces: sleeping sideways, phone over the face, profiles, childhood photo, behind glass. Most have quality under 0.45.
- Childhood photo still reached 0.38 similarity with AdaFace.
- One or two Google tags look wrong (sim below 0).

### 2026-10-14: the three 640 detectors, same 187 photos
"Real" face = 2+ detectors agree (RetinaFace 1280 counted as a reference). Size = short side of the box, photo capped at 4096 px.

| Detector | <40px | 40-80px | 80-160px | 160px+ | Only this one, 40px+ | ms/photo |
|---|---|---|---|---|---|---|
| retinaface_r50_640 | 1/6 | 17/30 | 58/63 | 260/261 | 1 (fake) | 219 |
| scrfd_10g_640 | 1/6 | 21/30 | 63/63 | 257/261 | 2 (real) | 68 |
| yolov5m_640 | 5/6 | 29/30 | 61/63 | 259/261 | 5 (1 fake: rocks) | 118 |

AdaFace on the same 87 labeled faces with each detector's own crop: TAR@FAR1% 0.963 / 0.945 / 0.956. Same within noise, so landmarks are good enough on all three.

YOLOv5m-640 finds the most faces at every size, is faster than RetinaFace, and is GPL-3.0.

### 2026-10-14: fp16 models (`bench/fp16.py`)
- YOLOv5m: 81 -> 40 MB. Same 363 faces on 187 photos, none lost or new. Landmarks move at most 3.2% of face width.
- AdaFace IR101: 248 -> 124 MB. Similarity to fp32 vectors: min 0.99992 over 354 faces.
- Ship the fp16 files. They live in `models/` (git-ignored). AdaFace is over GitHub's 100 MB file limit, so pick how to ship it in phase 2 (Git LFS or a release download at build time).

### 2026-10-14: test phone, ONNX Runtime CPU, fp16 models (`bench/compare_phone.py`)
- Same 187 photos as the bench. 362 of 363 faces match the Mac, 2 new.
- Landmarks: median shift 0.44% of face width, max 5.4%.
- Vector similarity to Mac fp32: median 0.995, min 0.864. The low ones are low-quality faces (q 0.17-0.30), where a tiny landmark shift changes the crop. Not from photo resizing or face size.
- Recognition is the same: TAR@FAR1% 0.956 phone vs 0.958 Mac, TAR@FAR0.1% 0.939 vs 0.932 (91 labeled faces).
- Speed on CPU: decode 154 ms, detect 401 ms per photo, embed 410 ms per face. Too slow for a big library (20k photos ~7 h), so the NPU must work.
- Loading a 130 MB model as a byte array crashes (Java heap cap 256 MB). Map it from the APK instead.

### 2026-10-14: test phone, NPU (QNN HTP), fp16 models
- Both models run fully on the NPU (308 of 308 nodes each). First load prepares the NPU graphs, about half a minute.
- Speed: detect 68 ms per photo (CPU 401), embed 39 ms per face (CPU 410). Decode 157 ms is now the slowest step.
- 363 of 363 faces match the Mac, 1 new. Vector similarity median 0.997, min 0.866 (low-quality faces again).
- Recognition: TAR@FAR1% 0.958 (Mac 0.958), TAR@FAR0.1% 0.941 (Mac 0.932), 91 labeled faces.
- Needed: `<uses-native-library android:name="libcdsprpc.so">` in the manifest, and models with every shape fixed (batch 1, `bench/fp16.py`).
- Rough estimate: ~0.3 s per photo with 2 faces, so 20k photos ~1.5 h, 1700 photos ~8 min.

### 2026-10-14: test phone, background index (WorkManager + Room, NPU), all 1708 testdata images
- 337 s for 1708 photos (~0.2 s per photo, NPU warm-up included). Under the 10 min job limit, so no foreground service yet.
- Types: 1395 camera, 172 other, 141 skip. Same as the Mac EXIF check. One photo with broken EXIF reads as other.
- 1466 faces. On the 185 tagged photos it indexed: 362 of 362 Mac faces match, 1 new, vector similarity median 0.997, min 0.866. Same as the NPU test screen.
- 17 of 187 tagged photos are "other" (Telegram saves, `IMG_YYYYMMDD_HHMMSS_mmm.jpg`), 2 are skipped screenshots. Those 17 only join people, as planned.

### 2026-10-14: grouping on the phone (`People.kt`)
- Same as `bench.py group` (average linkage, cosine distance cut 0.68, faces 40 px+), but only camera faces start a person. Other faces join the person with the lowest average distance, if under the cut.
- Matches scipy on the same DB: 109 groups, 1035 of 1466 faces, same group sizes. 2-3 s on the phone, O(n³), fine for a few thousand faces.
- 6 groups had all faces in one photo. Worst: 40 identical statue heads in one photo. Rule added: a person needs faces from 2+ photos. Now 103 people, 984 faces.
- Biggest person: 296 faces, all the same person on the first screen.
- 68 faces from downloads and messengers joined people.

### 2026-10-15: face pictures saved while indexing
- 160 px WebP per face, cut from the decoded photo. 1466 faces = 5.8 MB (avg 4 KB), so ~70 MB for 20k photos.
- Index speed the same (~0.2 s per photo). Faces still match the Mac: 362 of 362, 1 new, vector similarity median 0.997.
- The job was stopped 7 times mid-run (stop reason 11, background restriction; maybe also an OEM heat limit for jobs). WorkManager waits longer after each stop (30 s x 2^n, 32 min after 7 stops), and tapping Index does nothing then (`KEEP`). Fix: foreground worker.
- Foreground worker (`dataSync`, progress notification): 1708 photos in 333 s from scratch, no stops. Tap Index now uses `REPLACE`, so it always restarts a waiting job.

### 2026-10-15: small decode for detection (640 px), full decode only if faces
- 430 s for 1708 photos, but 2 min of it was a freeze: the OEM's background manager froze the app ~30 s after screen off (freeze scene: LcdOff), even with the foreground service. Speed per 100 photos with screen on: ~6% faster than full decode (sum 2822 vs 2998 ms per photo over 16 blocks), within noise and heat (third run in 25 min, CPU 45°C).
- Results drift from the Mac: 355 same faces, 7 lost, 4 new (mostly small or borderline, one clear 89 px face lost). Landmark shift median 1.20% (was 0.36%), vector similarity median 0.987 (was 0.997), worst on low-quality faces. The detector now sees a smooth 640 picture instead of a bilinear cut from 4096.
- Recognition on 90 tagged faces same as the Mac: TAR@FAR1% 0.958 (Mac 0.957), TAR@FAR0.1% 0.931 (Mac 0.931).
- Kept for now. Recheck later on a cool phone with step timers, and make the bench use the same smooth resize so the Mac compare is fair again.

### 2026-10-15: people saved in Room
- Grouping runs at the end of each index run (also with no new photos) and replaces all people. People screen only reads them.
- 1708 photos in 287 s, grouping 3.5 s: 98 people, 972 of 1437 faces. Fewer than the 103 people / 1466 faces before small decode.

### 2026-10-15: sure-only matching, step 1 (`bench/people.py`)
- Reads the phone DB and groups like `People.kt`: same 98 people, same faces. Adds EDifFIQA quality, distance to the person, gap to the next person.
- AdaFace ONNX outputs unit-length vectors (norm 1.00), so its norm can't be a free quality score.
- Google tags can't judge it: 89 faces checked by one-tag photos, 0 wrong under every rule. Tagged photos are mostly good faces.
- Low-quality people stand out: person 18 (27 faces, median q 0.18, median distance 0.59), person 26 (10 faces, q 0.12). All faces: median q 0.64, 10% under 0.23.
- Person 18 is junk: cats, cartoon fist, Homer, Iron Man mask, mannequin, shoe, food, helmets, backs of heads. Detector score still 0.61-0.84. Person 26: real people, but backs of heads, side views, hand over face.
- Distance (0.45-0.73) and gap (0-0.2) look normal for junk, so they can't catch it. Quality can: junk faces are almost all under 0.36.
- Min quality to start a person (`people.py <db> 0.4`), low-quality camera faces may still join: 0.3 gives 73 people, 0.4 gives 64, 0.5 gives 57 (was 98). Tagged faces: 87 of 88 still placed, 0 wrong.
- At 0.4 the 32 dropped people are cats, masks (Vader, Skyrim helmet, skull meme), helmets, backs of heads, hand-over-face bursts, blurry faces, plus ~15 small real people (2-3 faces, mostly burst shots). One dropped pair was two different women. At 0.3 cat and mask groups survive.
- The 14 lowest-quality people kept at 0.4 are all real and clean.
- Cheap signals can't do it: cats have head turn ~0 (frontal), Iron Man mask has detector score 0.84. EDifFIQA-T is needed on the phone.

### 2026-10-15: EDifFIQA-T quality on the phone
- `bench/fp16.py` makes `ediffiqa_t_fp16.onnx` (6 -> 3 MB). Its L2 normalize ends in Shape -> Expand -> Div; the script drops Shape and Expand (Div broadcasts) so every shape is fixed for the NPU. fp16 vs fp32: max quality diff 0.0013 on 363 faces.
- Runs in `embed` on the same aligned crop as AdaFace (RGB instead of BGR), saved per face in Room (DB version 4).
- NPU test vs Mac: quality diff median 0.0035, max 0.074 (363 faces). Index DB (small decode): median 0.0068, max 0.10.
- NPU test: 33 ms per face for AdaFace + quality (39 ms for AdaFace alone before), so quality costs ~nothing.
- Index 1708 photos in 312 s (287 s before, runs vary 287-333 s). People unchanged (98), no start gate yet.

### 2026-10-15: start gate on the phone (quality 0.4+)
- Only camera faces with quality 0.4+ start a person, the rest only join. 64 people (was 98), same faces as `bench/people.py`. Grouping 1.8 s (was 3.5 s).
- On the phone: no more junk groups (cats, masks, helmets, backs of heads).
- Tagged faces: 87 placed, 0 wrong. Biggest tagged person: 53 of 55 faces in one person, 1 split off, 1 in no person. Second: 20 of 21.
- Still open: low-quality faces join people with no check (hand-over-face faces with q 0.12 joined a person). Bench: a sure join at q 0.3+ leaves 27 faces unsure, at 0.4+ 54.

### 2026-10-15: 6+ photos per person, sure joins
- A person needs starting faces from 6+ photos (was 2+). Test set: 64 -> 21 people, 844 -> 694 faces in people. Lost: mostly real people with 2-5 photos; a full library should push more of them over 6.
- Looked at all 92 joined faces next to each person. Under quality 0.4: profiles, backs of heads, hand or phone over the face, looking down; a few look like other people (old man in a skullcap joined an older woman). From 0.4 up: right, except 3 near-black photos.
- Distance and gap add nothing: every low-gap join was already under 0.4.
- Rule: a face in a person is sure if quality >= 0.4 (all starting faces are). 54 sure, 38 unsure joins. No new column: sure is read from quality.

### 2026-10-16: first full-phone index (whole library, NPU)
- Scope: all MediaStore images, no folder filter, videos out (`libraryPhotos()`). On a full phone: ~4.6k images (Takeout, Camera, screenshots, messengers, rest small).
- Result: 3555 faces, 40 people (6+ photos each), 1751 sure faces, 122 unsure. People screen filled, no names yet.
- The 6+ photo rule now has enough data: on the small test folder it left 21 people; the full library gives 40.
- Run was paused/resumed several times (reinstalls, pause button) and resumed with no lost photos: progress is the DB itself, done ids are skipped.
- Not yet: naming, per-person tuning, Help recognize for the 122 unsure faces, scope excludes.

### 2026-10-17: Help recognize answers
- Answers live in an `answer` table (face id, person's main face, yes/no) plus `face.notFace`. The main face is a starting face, so it stays put across regroups; person ids do not.
- Grouping: a yes keeps the face with the person that holds that main face (no distance check). A no rules that person out; the face may join the next closest one under the cut, and is asked about again there. Not a face is left out of `faceVecs()` everywhere.
- Sure = a yes, or quality 0.4+ with no "no" answer (`FaceVec.sure`). A face moved off a person by a no lands somewhere by distance, so it is asked again even when sharp (seen on the phone: Remove tag on a 0.78 face put it in another person as sure).
- A face with any answer never starts a person (a moved sure face would snap back) and is kept even under 40 px (a tagged small face would be lost).
- Viewer details: People row, every face left to right. Remove tag = a no for that person.
- Someone else (Who is this? sheet): a no for the old person and a yes for the picked one; one yes per face.
- Phone test 2 (Who is this?, viewer People row, 1 regroup after): New person "Test A" from a Help recognize face, then the next face picked into it; Remove tag in the viewer, then put back (the yes replaced the no for the same person); New person "Test B" from a face with no person; "Add name" named a whole unnamed person "Test C" (6 faces); Remove tag on a Alex starting face (camera, q 0.78). After the regroup all three names held, Test A/B kept their faces with no 6+ rule, and the removed starting face did not snap back to Alex (it went to the next closest person, 0.589, and is asked there). New person: a new person row whose main face is this face, and a yes to the face's own id. On regroup such a face starts its own person (no 6+ photo rule) that grows only by the user's yes, never by distance: one low-quality face would pull in junk.
- `bench/people.py` does not read answers yet, so it drifts from the app once answers exist.
- Phone test (real DB, 3 regroups via Dev tools > Index all photos, which only regroups when nothing is new):
  - Yes (face 921): stays with Alex, counts as sure ("40 may be Alex" -> 37 with the next two).
  - No (684): left Alex at once; after a regroup it joined no one, which is right (next person 0.686, over the cut).
  - No (922, Alex 0.57, next person 0.646): moved to that person and was asked there ("Is this the same person?"). A second No left it with no one (third person 0.76).
  - Not a face (686): gone from every count (People 3555 -> 3554 faces), stays out after regroups.
  - Skip: nothing saved, the face is asked again next time. The name survived all 3 regroups.

## Chosen models (2026-10-14)
- Detector: YOLOv5m-face at 640 (GPL-3.0). Finds the most recognizable faces, 118 ms on Mac CPU. RetinaFace dropped: slower, misses 40-80 px faces at 640.
- Recognizer: AdaFace IR101 (WebFace12M), 512-d. Start grouping at cosine distance 0.68.
- Quality: EDifFIQA-T score on each face. Low-quality faces join groups but do not start them (threshold to tune, ~0.3).
- Not tested: LVFace. Try only if AdaFace falls short on more data.

### 2026-10-17: Merge with and Hide person
- Merge: a `merged` row (main face of the merged-away person, main face of the kept one), keyed by main faces like answers. In the app the faces move at once and the other person row goes. On regroup, the groups that hold both main faces become one before distance joins; a part that grouped by distance keeps joining by distance, two New persons stay yes-only.
- A no against either half now rules out the merged person, which is right (they are the same person).
- Hide: `person.hidden`, carried over on regroup like the name (old person that keeps half its faces). Hidden people still get their faces, so those faces never drift to someone else; they are left out of People, Library, Help recognize (all faces) and Who is this?.
- Merge sheet order: other people by cosine of their average face to this person's, so split halves come first. On the phone the top picks for Test B were unnamed people; looked sane, not measured.
- Phone test: Test A (2 faces) merged into Test B (1 face) -> 3 faces, 2 photos. Hid Test B, regrouped (41 people, 15 s): still hidden, still 3 faces, name kept. Show person brought it back. DB v6 -> v7 migration kept all 42 people, 4 names, 35 answers (backup in `testdata/out/db/backup-v6/`).
- Limit: like answers, a merge holds only while both main faces stay starting faces of some group.
