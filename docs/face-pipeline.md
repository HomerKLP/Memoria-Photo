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
| YOLO11s-pose face (zjykzj/YOLO11Face) | 95.72 / 94.19 / 85.24 | 9.7M params, 22.3 GFLOPs | Repo Apache-2.0, trained with Ultralytics (AGPL-3.0). Keypoints from RetinaFace labels. |
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
- Checked 2026-10-19 (public GitHub pages only). The UniFace weights come from `yakhyo/adaface-onnx` (code MIT, exported from the official AdaFace weights, IR101 trained on WebFace12M) and `yakhyo/face-image-quality-assessment` (code MIT, exported from the official eDifFIQA weights). Neither page gave a license for the weights then. Update 2026-10-21 (the user): the eDifFIQA and AdaFace weights are both MIT. The AdaFace repo (code MIT) gives none either. I found no official WebFace terms or eDifFIQA weights license online (face-benchmark.org would not load), so the weights are unconfirmed. Decision 2026-10-19: no license means free to use, for a non-commercial open-source app. Use as is.

### Best on paper
- Detectors: on faces big enough to recognize (WIDER easy/medium) all candidates are within 1-2%. Paper leaders (TinaFace, multi-scale RetinaFace) win only on tiny faces and are too heavy for a phone. Bigger YOLOv5-face (l6, x6) are a bit better than m, not in UniFace.
- YOLO11s-pose face vs our YOLOv5m-face (95.30 / 93.76 / 85.28, 21.1M params): same AP, half the params. Compute is likely ~2x lower (Ultralytics counts YOLOv5m at 49 GFLOPs at 640; the YOLOv5-face repo's 18.1 is measured another way), not checked on the phone. Checked 2026-10-28.
- Recognizers: LVFace ViT-L is top (IJB-C 98.06), but huge and non-commercial. AdaFace, ArcFace R100, TopoFR sit around 97.5. AdaFace is best on low-quality faces (TinyFace, IJB-S), the closest match to personal photos.

### Model updates
- New models ship with app updates. A new detector only needs new detection on new photos.
- A new recognizer needs all face vectors recomputed (background, minutes). Vectors from two models do not mix.
- So store person names and user fixes per face (photo + box), not only as vectors. Then a model swap keeps them.
- No retraining on the phone. Learning from user fixes = per-person vector sets: confirmed faces join the set, rejected ones become "not this person" examples, each person can get their own threshold. Fast and easy to undo.
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

### 2026-10-17: saved NPU builds
- Every run built all three models for the NPU before the first photo: 10.5 s, while one photo takes ~0.2 s (decode 92 ms, detect 53 ms, embed 27 ms per face).
- ONNX Runtime's QNN EP context cache saves the built models (`npuSession` in `Faces.kt`). Load 10.5 s -> 0.55 s. 20 photos, 37 faces: boxes, vectors and quality identical to the plain load.

### 2026-10-20: Yes faces as references (`groupFaces`)
- Before: a Yes kept its own face with the person, but other faces still matched only the person's starting faces, so one Yes taught nothing.
- Now: a face within cosine distance 0.5 of a Yes face (quality 0.3+) joins that person, even when its average distance to the person is over the cut. Only the user's Yes faces count. Faces that joined this way never become references, and a no still rules a person out. The person's own face set, main face and cover do not change.
- Measured on the phone DB copy (3356 faces, 1513 with no person, 31 Yes answers, all quality 0.26-0.39): at 0.4 one new face joins, at 0.5 eleven join and no sure face of another person is touched, at 0.6 twenty-four join but five sure faces of other people are touched. So 0.5. The floor barely matters here (2 of 31 Yes faces are under 0.3), 0.3 is a guess.
- The new faces are not checked by eye, and 31 Yes answers is a small set. Tests: `aFaceNearAYesFaceJoinsItsPerson`, `aYesFaceUnderTheQualityFloorIsNoReference`, `aNoAnswerStopsTheJoinThroughAYesFace` (`PeopleLogicTest`). `bench/people.py` still does not read answers.
- Phone run (regroup through Dev tools > Index test folder, 2 s): 10 faces joined a person, 0 left, 0 moved to another person, all 38 people kept their id and all 7 names stayed. All 10 are unsure (quality 0.23-0.35): 9 went to a person with 21 Yes answers, 1 to a person with 11. Nine of the ten were over the 0.68 average, so only the Yes rule brought them in. Library "faces to check" went 81 to 91. You then answered all 91 unsure faces Yes: all right (see the next entry).

### 2026-10-20: sure by neighbours (`groupFaces`, `FaceVec.nearSure`)
- Why: you answered all 91 unsure faces Yes and none No, and many were near copies of each other. Quality 0.4 was asking for faces that were right. On those 91, the closest sharp or Yes face was always the right person's (gap to the closest other person: min 0.08, median 0.32). Only 0.6% of sharp faces have another person's sharp face under 0.4, and 1.2% under 0.5.
- Rule: a joined face under quality 0.4 is sure when its closest sharp (0.4+) or Yes (0.3+) face is its own person's within 0.5 and no other person's is 0.1 closer, or a Yes face of its person is within 0.3. Faces made sure this way are never anchors, so it cannot chain. A rejected face is never made sure. The person's face set, average, main face and cover do not change.
- Simulation on the 91 (no gap rule): within 0.4: 36 would not need a click, 0.45: 57, 0.5: 83, 0.55: 90. Plus "a Yes covers faces within 0.3": 7 clicks left at 0.5.
- Saved at regroup in `face.nearSure` (DB v10, migration 9 to 10). Help recognize: a Yes also sets `nearSure` on the answered face's look-alikes (unsure, same person, within 0.3) and skips them in the queue (`Person.twinsOf`).
- Real code on the emulator, loaded with the DB from before your answers (33 Yes anchors): faces to check 91 to 8. 84 faces made sure: 83 are among your 91 and all 83 are in the person you picked, 0 different. 1 is a new face you did not check. 8 of your 91 would still be asked.
- Limits: 0.5, 0.1 and 0.3 come from one library. The 1 new face and the 3 new sure faces on the phone are not checked by eye.
- Tests: six new `PeopleLogicTest` cases (17 pass), `MigrationTest` and `DaoTest` pass on the emulator.

### 2026-10-23: NPUs from other makers (research only, nothing built)
- ONNX Runtime has no MediaTek, Samsung or Tensor EP. Its NNAPI EP reaches them through the phone's driver, but Google dropped NNAPI in Android 15 and ORT marked the EP deprecated (1.21); driver quality and fp16 support differ per phone.
- LiteRT (`CompiledModel` API) covers all of them, with GPU/CPU fallback: Qualcomm (V69-V81), MediaTek Dimensity 7300-9500, Exynos 2500 and 2600 only, Google Tensor (beta, models built on a PC per chip only). Needs `.tflite` models, so a conversion and a new accuracy check; Google ships the NPU libs through Play, but since 2.3.0 they are also Maven packages.
- ExecuTorch has Qualcomm, MediaTek and Exynos backends but wants PyTorch models, ours are ONNX.
- Most apps run on the GPU (LiteRT GPU delegate works on almost every phone) and use an NPU only per vendor deal; Immich runs ML on a server.
- No phone with these chips to test on, and the emulator has no NPU, so face quality can't be checked yet.

### 2026-10-23: LiteRT on the NPU (ROADMAP LiteRT steps 1-2)
- Conversion: `onnx2tf` on the fp32 uniface ONNX files. Fix the input shape and run `onnxsim` first: without it YOLOv5m fails (5-D head) and AdaFace keeps run-time shape ops that break on the NPU ("Failed to invoke", a Reshape gets 0). EDifFIQA-T also needs the Expand patch from `fp16.py` and `ReduceL2` swapped for `Sqrt(ReduceSumSquare)`. `-kat input` keeps inputs NCHW, so the pixel loops stay.
- Mac, tflite vs fp32 ONNX (40 photos, 300 faces): fp32 files identical; fp16 files: boxes within 0.05 px, vectors 0.99993+, quality within 0.0004.
- Test phone, LiteRT 2.3.0 NPU vs ONNX Runtime QNN on the same inputs (20 photos, 14 faces): same 161 detections, boxes within 0.13 px, vectors 1.00000, quality within 0.0005. Median run: detect 10.0 vs 11.4 ms, embed 3.6 vs 4.1 ms, quality 0.6 vs 0.7 ms.
- Load: the first build takes ~4 s for all three, then LiteRT's compile cache (`Environment.create(..., true)`, app cache dir, ~171 MB) loads them in ~0.25 s (ONNX Runtime's saved builds: 0.55 s).
- fp32 and fp16 files run the same on the NPU (it computes in fp16 either way), so ship fp16: half the size.
- LiteRT 2.3.0 needs QNN 2.50 (`com.qualcomm.qti:qnn-runtime:2.50.0`). On ONNX Runtime's 2.42 it ran everything on the CPU, ~100x slower, with no error and no log. ONNX Runtime 1.29 runs fine on 2.50.
- Debug APK 103 MB -> 123 MB with both runtimes.
- Is a model on the NPU? Read logcat tag `litert`. "Replacing 1 out of 1 node(s) with delegate" plus "Creating new QNN context for bytecode (size ~ the model file)" means all of it. More partitions or a tiny bytecode mean parts run on the CPU. No `litert` lines at all mean the NPU never started (the QNN mismatch above).
- Bumping LiteRT: the QNN version it needs is in `third_party/qairt/workspace.bzl` at that release tag of `google-ai-edge/LiteRT` (v2.3.0 -> 2.50.0). Set `qnn-runtime` to match.
- Kotlin setup: `Environment.create(context, BuiltinNpuAcceleratorProvider(context), emptyMap(), true)` (last arg turns the compile cache on), `CompiledModel.Options(Accelerator.NPU)` with `QualcommOptions(htpPerformanceMode = BURST)`. The NPU libs ship in the APK (`litert-npu-runtime-qualcomm` + `qnn-runtime`), no Play needed.
- The recipe is `bench/tflite.py`, in its own pinned env (inline metadata + `bench/tflite.py.lock`).
- No TensorFlow (same day): `onnx2tf` 2.x writes `.tflite` directly. Its own float16 file is fp16 end to end (fp16 inputs, which the CPU and LiteRT's Kotlin API can't feed), so the script takes its fp32 file and casts the weights to fp16 with `ai-edge-quantizer`. Mac: boxes within 0.075 px, vectors 0.99998+, quality within 0.0003, as good as or better than the TensorFlow files. Phone: all three fully on the NPU, detect 9.1-9.7 ms, same results.
- Cost: the detector's first NPU build takes ~40 s instead of 2.3 s (its output part becomes many small 5-D reshapes). The first index run waits for it; it comes back after an OS update, a LiteRT update, or when Android clears the app's cache (the Kotlin API can't move the cache out of `cacheDir`). Otherwise the cache loads it in 0.17 s. If it ever matters, cut the output decode out of the model and do it in Kotlin.

### 2026-10-23: Faces.kt on LiteRT (ROADMAP LiteRT step 4)
- `FacePipeline(context)` reads `files/models/{detector,recognizer,quality}.tflite`. Each model opens on the first of NPU, GPU, CPU that the phone has and that builds it; logcat tag `Memoria` says which ("detector.tflite on NPU"). Dev tools Run on CPU passes CPU only.
- Test phone, 20 test photos: all three on the NPU, one QNN partition each. First build of all three 70.6 s, then 0.27 s from the compile cache. Detect 38 ms per photo, embed 26 ms per face (ONNX Runtime 2026-10-17: 53 and 27 ms).
- CPU: LiteRT's default thread count made it ~3x slower than ONNX Runtime (detect 1211 ms, embed 591 ms). With 4 threads: detect 385 ms, embed 204 ms (ONNX Runtime: 401 and 410).
- Same 14 faces everywhere. Phone NPU vs phone CPU: boxes within 1.76 px, vectors median 0.9986, min 0.982, quality within 0.009. Emulator GPU vs CPU: vectors 0.99999+. Emulator CPU = phone CPU.
- Emulator: no OpenCL, so LiteRT's GPU uses OpenGL. The detector never builds there and falls back to the CPU. The other two build on the GPU only on the first try in a process ("Can't create program" after that), then fall back to the CPU. An index run of 169 photos finished in 172 s.
- Phone index runs (4 runs of 1-2 new camera photos): all three on the NPU, one QNN partition each, models loaded from the cache in 0.13-0.41 s, ~1 s per run.
- The phone's main log buffer is 256 KB, and LiteRT's build logs push the `litert` lines out. `adb logcat -G 16M` first.

### 2026-10-23: LiteRT vs the Mac and the old DB (ROADMAP LiteRT step 5)
- `compare_phone.py` on the 20 test photos (Mac: `bench.py detect --photos <test folder>`, then `group`): NPU 14 of 14 faces, 0 lost, 0 new, landmarks within 0.95% of face width, vectors median 0.9981, min 0.9653, quality within 0.017. CPU: vectors median 0.9994, min 0.9697. ONNX Runtime on the NPU was median 0.995-0.997 (363 faces, 2026-10-17).
- The same photos are in the phone's DB, indexed with ONNX Runtime (16 faces). Old DB vectors vs LiteRT: median 0.9959, min 0.9517; old DB vs Mac: median 0.9946, min 0.9565. The gap is the index's small decode, not the runtime. 0.05 cosine distance at worst, far under the 0.68 group cut, so old and new vectors mix and no re-index is needed.

### 2026-10-23: ONNX Runtime out of the app (ROADMAP LiteRT step 7)
- Gone: `onnxruntime-android-qnn`, its telemetry provider and INTERNET removal in the manifest (no library adds INTERNET now), its 26 baseline profile lines, the `LiteRtSpike` test and the debug app's old NPU builds (`files/npu`, 171 MB). Only arm64 libs ship.
- Debug APK 132.5 to 111.3 MB. Run on NPU after: all three on the NPU, 14 faces, models loaded in 0.42 s.

### 2026-10-23: LiteRT on the test phone's GPU
- What MediaTek and Samsung phones get today. A test build ran Dev tools on GPU, then CPU (OpenCL, unlike the emulator).
- The detector fails on the GPU ("Failed to compile model": its output decode has 5-D CONCATENATION and LOGISTIC ops, the GPU takes at most 4-D) and falls back to the CPU: 395 ms per photo. Recognizer and quality build fully on the GPU: embed 47 ms per face (NPU 26, CPU 204). Models load in 1.3 s.
- A second run in the same process builds on the GPU again (the emulator's OpenGL could not).
- Same results: 14 of 14 faces, vectors 0.9994+ to the phone CPU run, median 0.9993 to the Mac.
- Cutting the decode out of the detector (ROADMAP LiteRT step 8, below) would let it run on the GPU too.

### 2026-10-23: detector decode in Kotlin (ROADMAP LiteRT step 8)
- `bench/tflite.py` cuts YOLOv5m after its 3 head convs. `yolov5m_raw_fp16.tflite` (`detector_raw.tflite` on the phone) outputs 3 grids, NHWC (1,80,80,48), (1,40,40,48), (1,20,20,48): each cell 3 anchors x 16. Only 4-D ops are left.
- The decode, read from the ONNX graph: sigmoid on x, y, w, h, score and class, not on the landmarks. cx = (2 sig - 0.5 + grid x) * stride, w = (2 sig)^2 * anchor w, landmark x = raw * anchor w + grid x * stride (y the same with h). `decode()` in `bench/tflite.py` and `FacePipeline.detect` keep the ONNX row order, so NMS ties don't change.
- Mac, tflite + Python decode vs fp32 ONNX (40 photos): 502 of 502 rows at 0.6+, max diff 0.075 px, as before the cut.
- Test phone NPU: one QNN partition, first build 2.9 s (was ~40 s), then 0.15-0.28 s from the cache. Detect 42-47 ms per photo (debug build; step 4: 38 ms), embed 29-37 ms per face. vs the Mac: 14 of 14 faces, landmarks within 0.92% of face width, vectors median 0.9979, min 0.9672, quality within 0.013 (step 5: 0.9981, 0.9653).
- CPU: detect 380 ms, embed 201 ms, vectors median 0.9994, min 0.9697 to the Mac.
- GPU (test build, Run on NPU set to GPU then CPU): the detector builds on the GPU now, detect 52 ms per photo (CPU fallback: 395 ms), embed 45 ms, load 3.0 s. 14 of 14 faces, vectors median 0.9983, min 0.9582 to the Mac.
- Old DB vectors (ONNX Runtime) vs the new NPU run: 16 faces, median 0.9952, worst 0.045 cosine distance (GPU run: 0.057), far under the 0.68 group cut, so no re-index.
- Emulator: all three on its GPU, the detector for the first time. Its GPU is software (SwiftShader), so an index run of 169 photos took 436 s (172 s with the detector on the CPU).

### 2026-10-23: Speed by chip (for the README)
- Snapdragon 8 Gen 1 to 8 Elite Gen 5: NPU (only the 8 Elite checked). Other Snapdragons, MediaTek, Exynos, Tensor and the rest: GPU, then CPU. MediaTek and Exynos NPUs need LiteRT's packages for them (ROADMAP Later).
- Test phone, debug build, 20 test photos, ~0.7 faces per photo. Whole photo = open (~60 ms) + detect + embed for its faces.

| | NPU | GPU | CPU |
|---|---|---|---|
| Detect, per photo | 42-47 ms | 52 ms | 380 ms |
| Embed, per face | 29-37 ms | 45 ms | 201 ms |
| Recognition, per photo (detect + embed) | ~70 ms | ~85 ms | ~520 ms |
| Whole photo | ~130 ms | ~145 ms | ~580 ms |

- A whole photo is ~4.5x faster on the NPU than on the CPU, ~4x on the GPU. The GPU is only 10-30% behind the NPU here, but this is a flagship GPU; mid-range MediaTek and Exynos GPUs are likely slower, and none is measured. Battery use is not measured.

### 2026-10-23: phone GPU on the demo photos (bug check)
- Dev tools has a Run on GPU row now (GPU only, no CPU fallback). 113 photos: the 20 test photos plus the 93 demo photos in `files/test/demo/`.
- Two GPU runs in one app process, then a CPU run. The second run: 106 faces, 106 different vectors, no repeats; the 14 old faces match the first run exactly.
- GPU vs phone CPU, 106 faces: vectors median 0.9982, min 0.80 (one demo face, quality fine on both, next worst 0.980); quality within 0.035, median 0.002, no face crosses the 0.4 cut.
- So the stuck results in `bugs.md` are the emulator's OpenGL GPU only. The phone's GPU (OpenCL) is fine.

### 2026-10-30: full re-index with object boxes (debug build, NPU)
- Whole library (~4.4k photos): faces, YOLO26-M boxes and pet vectors; tag vectors kept from before. 936 s, 213 ms per photo on average.
- Median ~208 ms per photo, from 15 s windows (no per-photo log); windows ranged 125-564 ms.
- About 2 min with no progress at ~60%: a notification tap had dropped the foreground service, so Android froze the app (`bugs.md`, index notification). Without it, ~185 ms per photo.
