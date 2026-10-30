# Object detector

A COCO object detector boxes cats, dogs and people. Two uses: pets (find each cat or dog, then group them like people) and People (a face outside every person box is often not human).

## Pipeline (decided and built 2026-10-29)
Per new photo in `IndexWorker`. New parts in brackets. Without `objects.tflite` and `pets.tflite` the index runs as before (no gate, no rule, no boxes).

```
Photo kind (EXIF) ── skip ─► SigLIP2 only
   │ camera / other
Small decode 640 px → one 640 letterbox (gray 114, RGB 0-1: YOLOv5m-face and YOLO26 take the same input)
   ├─► SigLIP2 ─────────────► photo vector (13 categories, search)
   └─► [YOLO26-M] ─► person boxes (score 0.05+)? ── none ─► no face detection
           │                               └─ yes ─► YOLOv5m-face
           │                                           ├─ face less than half inside every person box → dropped
           │                                           └─ else → full decode → AdaFace, EDifFIQA-T, thumb
           └─► [cat / dog boxes, score 0.5+] ─► [crop from the 640 decode → DINOv2-S → pet vector]
Save: photo, vector, faces, [boxes], [pet vectors]
Every ~30 s and at the end: group people, [group pets], places. [Pets category = photos with a cat / dog box.]
```

- Models: YOLO26-M (Ultralytics, AGPL-3.0, 39 MB fp16), DINOv2-S (Meta, Apache-2.0, 41 MB fp16), on top of today's four.
- Gate: no person box, no face detection. On the bench 33% of photos have none; only 28 of 3401 faces sit on them (3 with quality 0.4+), all dropped by the rule below anyway.
- Strict person-box rule: drop a face less than half inside every person box, before AdaFace. Of 32 faces with quality 0.4+ it drops, 1 is real. It also keeps cat faces out, so there is no separate cat/dog rule.
- No meme rule: the tagger marks many screenshots as memes.
- Pets: DINOv2-S plus Noodle Gallery's pet layer (`pets.tflite`, 512 vector) on the whole box squashed to 224x224, from cat and dog boxes 0.7+, average linkage, cut 0.68, then pets under 0.76 apart join (a cat's split groups): no crop in another cat's group, a quarter of the crops left out (the user's call: better left out than mixed; see "real labels"; cut 0.6 on the middle square mixed cats). The Pets category comes from the boxes (~40% more pet photos than the tag); the other 13 stay with the tagger.
- No extra decode: pets crop from the 640 decode (79% of pet boxes are 224+ px there, DINOv2's input). Region decodes for faces and pets are ROADMAP Later.
- Existing installs: a one-time catch-up pass, merged with the tagger's untagged pass (one 640 decode for both): YOLO26-M, drop old faces less than half inside every person box, pet vectors on pet boxes, regroup. A new `ObjectDetector.VERSION` runs it again (2: the Noodle pet vectors; 3: pets from boxes 0.7+, and the old boxes deleted first: version 2 had doubled them); until it reaches a photo, `groupPets` skips that photo's older, shorter vectors. It never drops a face the user answered (yes or no), a merge face, a cover, or a Not-a-face mark.
- On the test phone the catch-up dropped 115 old faces (3341 → 3226), 42 of them quality 0.4+: 2 real faces; the rest artworks, statues, a mural, monkeys, cartoons, old prints, a coin, a cat. None was named or answered; 10 sat in one person, a painting the user later hid.
- DB (v14): `object_box` holds every person, cat and dog box (score 0.05+, x1, y1, x2, y2 as fractions of the photo); the Pets category reads its cat and dog boxes 0.5+. Each cat or dog box 0.5+ is also a `face` row with `pet` set: DINOv2 vector, quality = box score, a picture cut from the 640 decode. So pets get names, covers, hiding and merges like people; `groupPets` groups them at each regroup (average linkage, cut 0.68, 6+ photos; pet rows only from boxes 0.7+), never `groupFaces`, and they have no Help recognize. `photo.objects` is the model version that boxed the photo (`ObjectDetector.VERSION`), null = not yet. v13 kept the pet vectors in `object_box`; v14 drops them and boxes every photo again.
- Speed on the test phone's NPU: YOLO26-M 18 ms per photo, about what the face detector takes, so the gate saves more than it costs. DINOv2-S runs on the GPU (15 ms per pet), not the NPU: see "In the app".

## Candidates
| Model | Params | fp16 `.tflite` | COCO mAP | Mac CPU | License |
|---|---|---|---|---|---|
| YOLOX-S | 9 M | ~18 MB | 40.5 | 71 ms | Apache-2.0 |
| YOLOX-M | 25 M | ~50 MB | 46.9 | 165 ms | Apache-2.0 |
| YOLOX-L | 54 M | ~108 MB | 49.7 | 331 ms | Apache-2.0 |
| RF-DETR-N (384 px) | 30 M | ~54 MB | 48.4 | 70 ms | Apache-2.0 |
| RF-DETR-S (512 px) | 32 M | ~58 MB | 53.0 | 131 ms | Apache-2.0 |
| RF-DETR-M (576 px) | 34 M | ~60 MB | 54.7 | 169 ms | Apache-2.0 |

Qualcomm AI Hub has YOLOX-S as a ready `.tflite`, 3.8 ms on the 8 Elite NPU. D-FINE nano is ready too but takes 22 ms. RTMDet-M and RF-DETR-S have no ready `.tflite`. EfficientDet-Lite dropped: YOLOX-S is as ready and more accurate.

RF-DETR (Roboflow, 2025) has its own `.tflite` export (`export(format='litert')`, PyTorch straight to LiteRT). RF-DETR-S runs in LiteRT on the Mac CPU (XNNPACK), 496 ms with 4 threads while the bench ran, boxes within 0.0003 of the ONNX file. So it runs on any phone's CPU. It has `TOPK_V2`, `GATHER_ND`, `REDUCE_ANY` and logic ops, which LiteRT's GPU path usually lacks: on GPU or NPU the last step likely falls back to the CPU. Not tried on a phone yet.

## Bench
`uv run bench/objects.py run`, then `score`. 3428 Takeout photos, 3072 faces from 40 px, 2320 of them with quality 0.4+. A face is inside a box when half of it is.

Faces outside every person box:

| Person score | YOLOX-S | YOLOX-M | YOLOX-L | RF-DETR-N | RF-DETR-S | RF-DETR-M |
|---|---|---|---|---|---|---|
| 0.1 | 149 | 135 | 124 | 62 | 77 | 65 |
| 0.25 | 219 | 206 | 182 | 181 | 159 | 141 |
| 0.5 | 316 | 296 | 287 | 376 | 374 | 368 |

Faces with quality 0.4+ on Google-tagged photos left out at 0.1 (of 1450): YOLOX S/M/L 14/9/10, RF-DETR N/S/M 3/3/2.

- All 272 faces any model leaves out at 0.25, checked by eye: about 100 not human (cats, cartoons, memes, statues, food, rocks), about 170 human.
- Most human ones are faces in pictures (old prints, posters, ID photos, memes) or blurry crowd faces.
- On Google-tagged photos, YOLOX-L at 0.1 leaves out 10 of 1450 faces with quality 0.4+ (0.7%). 3 are real people (cut off at the photo's edge, small in the back), 5 are frames of one painting, 2 are junk. (First counted as 8 real: the painting was taken for a partly hidden person.)
- YOLOX-S at 0.1 leaves out 14 of the same 1450: 4 real people, 6 frames of that painting, 4 junk (statue, painting, cartoon, carpet). Close to L at a sixth of the size.
- Only 16-22 of the left-out faces sit inside an animal box (the cats).
- RF-DETR-S at 0.1, checked by eye. The 77 it leaves out: about 42 junk (17 cats, rocks, statues, food), about 30 faces in pictures (one picture repeated across many photos gives 23), about 3 real people (2 blurry), 2 frames of that painting. The 77 it keeps and YOLOX-S left out: about 45 real people, but also about 25 cartoons, drawings and statues: RF-DETR boxes a drawn person as a person.
- So RF-DETR keeps far more real faces and still drops every cat. Cartoon faces get through; they are mostly memes, which the Phase 3 meme tag is meant to keep out.

## Pets
`uv run bench/objects.py pets`: a cat or dog box on photos the tagger bench labeled (`testdata/out/tags/labels.json`, 168 pet, 328 not pet).

| Detector | Box score | Pet photos found | Box on a not-pet photo | Photos with a box (of 3428) |
|---|---|---|---|---|
| YOLOX-S | 0.3 | 153 / 168 | 11 / 328 | 242 |
| YOLOX-L | 0.3 | 167 / 168 | 8 / 328 | 261 |
| RF-DETR-N | 0.3 | 163 / 168 | 6 / 328 | 262 |
| RF-DETR-S | 0.3 | 164 / 168 | 8 / 328 | 256 |
| RF-DETR-S | 0.5 | 158 / 168 | 7 / 328 | 238 |

RF-DETR-S at 0.3, checked by eye:
- The 4 missed are hard: close-ups of a body part, a cat far away.
- The 8 boxes on not-pet photos: 5 are real cats or dogs in memes, screenshots or drawings. 3 are wrong (another animal, small objects).
- 84 photos have a box but no label. In a sample of 24, 17 are real cats the Pets tag doesn't show, 7 are wrong or memes (fabric, a cartoon, tiny far-off spots).
- Boxes are tight, and photos with two or three cats get one box each.
- So the detector finds more pets than the Pets tag (about 60 more photos here). Run it on every photo, not only the tagged ones.

## Which pet is which (2026-10-28)
`bench/pets.py`: RF-DETR-N cat and dog boxes (score 0.5+), one vector per crop, hand labels from contact sheets. 263 crops, 204 labeled as 11 pets (5 seen often, one a dog, and a few cats seen once); left out: drawings, memes, two pets in one box, kitten photos (same cat or not is unsure).

| Model | Params | Closest crop is the same pet | Grouping at cut 0.6 (pair precision / recall) |
|---|---|---|---|
| SigLIP2 B/16 (the tagger) | 93 M | 97.5% | precision 0.46 already at cut 0.2 |
| DINOv2-S, 224 px | 22 M | 99.0% | 0.94 / 0.92 |
| DINOv2-B, 224 px | 86 M | 98.5% | 1.00 / 0.89 |

- SigLIP2 finds the closest crop, but all cats sit close together in its space: any cut that joins one cat's photos also joins different cats. It sees "a cat", not which cat.
- DINOv2 keeps different pets apart. B at 0.6 puts 94-95% of each patterned cat in one group; a black cat splits into a few groups (80% in the biggest).
- An easy set: one cat of each coat. Two black cats or two tabbies would be harder for any model.
- Picked DINOv2-S (the user's call: B is not worth 4x the size for pets).

## Which pet is which, real labels (2026-10-29)
The phone's pet groups (cut 0.6) mixed cats: the two black cats in one group, a calico with a street cat. The labels above were by coat, so they never tested two cats of one coat. The user then marked all 322 app crops by the real cat: 279 kept, 4 own cats seen often (136, 66, 28, 18 crops) and ~10 seen a few times. Crops cut from the full photo by the `object_box` fractions; pair precision / recall with average linkage; groups need 6+ photos, as for people.

| Model, how the box becomes a square | Closest crop is the same cat | 0.4 | 0.5 | 0.6 |
|---|---|---|---|---|
| DINOv2-S, middle square (the app until now) | 97.1% | 0.97 / 0.18 | 0.98 / 0.36 | 0.90 / 0.79 |
| DINOv2-S, padded | 96.7% | 0.99 / 0.21 | 0.95 / 0.70 | 0.73 / 0.80 |
| **DINOv2-S, squashed** | 96.3% | 0.97 / 0.20 | **0.98 / 0.66** | 0.80 / 0.85 |
| DINOv2-S, squashed, from the 640 decode | 96.7% | 0.99 / 0.21 | 0.99 / 0.64 | 0.88 / 0.85 |
| DINOv2-S, squashed, 336 px | 97.1% | 1.00 / 0.20 | 1.00 / 0.61 | 0.92 / 0.79 |
| DINOv2-B, padded | 97.8% | 0.99 / 0.32 | 0.99 / 0.63 | 0.85 / 0.75 |
| DINOv3-S / B, padded | 92.6% / 94.9% | all vectors close: precision 0.31 from cut 0.3 | | |
| MegaDescriptor-T-224 / L-384 (CC BY-NC) | 85.3% / 89.3% | recall under 0.1 at any cut with precision 0.97+ | | |
| MiewID-msv3 (no license stated) | 91.9% | best 0.97 / 0.26 at cut 0.67 | | |

- The middle square cuts off the head and tail of a lying cat. Squashing the whole box into 224x224 keeps them and doubles recall at the same precision.
- Squashed at cut 0.5: one group per own cat (one cat in two), 2 stray crops in all; the two black cats apart. The middle square at 0.5 splits each own cat in two; at 0.6 it mixes them.
- 0.3 and 0.4 are too tight: each cat breaks into small groups, most under 6 photos, so they never show.
- The animal re-ID models (trained on wildlife) and DINOv3 do worse than DINOv2 on these cats; MegaDescriptor is also non-commercial. DINOv3 with the average vector taken off first (2026-10-30) is still far worse: 0 mixed only at 3% right, 44-56% at best with ~90 mixed; the same step hurts DINOv2 and Noodle too.

Pair precision hides where each crop ends up, so every crop counted: right (its cat's biggest group), split (another group of its cat), mixed (another cat's group), left out (no group, though its cat has one). DINOv2-S, squashed, 640 decode:

| Setup | Right | Split | Mixed | Left out |
|---|---|---|---|---|
| The app until now (middle square, cut 0.6) | 85% | 5 | 19 | 19 |
| Squashed, cut 0.3 / 0.4 / 0.5 | 22% / 35% / 70% | | | |
| Squashed, cut 0.6 | 88% | 7 | 14 | 12 |
| Squashed, cut 0.56, the rest join the closest group within 0.66 (like people) | 90% | 8 | 18 | 3 |
| Squashed, cut 0.56, join within 0.2 | 87% | 7 | 6 | 23 |
| Squashed, 3 mixed at most (cut 0.48) | 73% | 16 | 2 | 56 |

Tried on top, none better: the crop and its mirror averaged (same 90%, more left out), the background cut out with YOLO26-M-seg masks (79-81%: the home around a cat helps more than it hurts), DINOv2 on the cat's face from YOLOv5m-face added to the body (a face in only 74 of 279 crops; no gain), a left-out crop joining the group of its closest grouped crop instead of the closest average (same). The other models scored this way: DINOv2-B 84%, MiewID 75%, DINOv3 59%, MegaDescriptor 61% at best. Noodle Gallery's `open-noodle/pet-recognition` (Apache-2.0, ONNX; frozen DINOv2 plus a linear projection to 512 trained on CC0 / CC BY cat and dog ID sets; the box squashed to 224, as here): small 89% at best (cut 0.78: 15 split, 9 mixed), 87% with 8 mixed at most, the same as plain DINOv2-S; base 92% at best but 19 mixed, 80% with 8 mixed at most. Its distances jump: 0.8 groups well, 1.0 joins everything. With 8 mixed at most, small leaves out fewer crops than DINOv2-S (14 vs 23) but splits more (15 vs 7); a split shows (Merge fixes it), a left-out crop never does. Noodle small is DINOv2-S's normalized class token, one linear layer 384 -> 512 and a normalize: our vectors through its layer match its output (cosine 0.997), so it costs one small layer in `pets.tflite`. Letting a small cluster (under 6 photos) join the closest big group as a whole did not help: the join that pulls in a cat's stray shots pulls in other cats too.
- In the app (2026-10-30, the user's call): Noodle small, the box squashed, cut 0.76. `bench/pets.py onnx noodle_s` adds Noodle's layer to our DINOv2-S export (my export vs Noodle's own ONNX: cosine 0.997 median, 0.977 min: timm and Hugging Face fit the position embedding to 224 a little differently); fp16 `pets.tflite` 44.1 MB (was 43.7), fp16 vs fp32 1.0000. With that exact model the cut curve: 0.74 81% right / 6 mixed, 0.76 84% / 9, 0.78 84% / 11, 0.80 82% / 23 (two cats' groups join). 0.78 sat on that edge, so 0.76.
- Then stricter (2026-10-30, the user's call: better a photo left out than two cats in one group). The pet model gives no trust score of its own, so the knobs are the box score floor and the cut. The app model on all 322 crops:

| Box floor | Cut | Right | Split | Mixed | Left out |
|---|---|---|---|---|---|
| 0.5 | 0.76 | 81% | 31 | 11 | 11 |
| 0.7 | 0.72 | 70% | 37 | 2 | 45 |
| 0.5 | 0.68 | 68% | 29 | 1 | 58 |
| **0.7** | **0.68** | **62%** | **33** | **0** | **72** |
| 0.5 | 0.64 | 60% | 29 | 0 | 82 |

- Not cats at all: YOLO26 boxes monkeys, goats, tigers, a cow, people's hair and feet as cat or dog, as surely as real cats (the monkeys up to 0.93), so no floor removes them; the monkeys look alike, so they form their own "pet" at every cut. Hide pet handles that group. SigLIP2 on the crop would drop them (every marked cat scores above, nearly all junk below "cat or dog minus the best other word" -0.01), but the user chose not to add it. What stays wrong: a second calico in the main calico's group, and the two black cats.
- On the phone (2026-10-30, box 0.7+, cut 0.68, 277 pictures): 10 pets shown, none mixed, but 3 own cats split (one in 3 groups, two in 2) and one group is junk. A second step (now in the app) that joins shown groups whose average distance is under 0.76 joins 3 of the 4 splits (7 pets, 66% → 75% right, still 0 mixed); the closest two different cats (the black ones) sit at 0.78, so 0.74–0.78 is the whole safe range. Letting left-out pictures join the closest group adds 1-3 at most: they are far from every group. Merge with already lists the right cat first for all 4 splits.
- First pick: DINOv2-S as now, the box squashed to 224x224, cut 0.56, the rest join within 0.2: fewest mixed crops for little loss. Then Noodle small won (below).

## `.tflite` (2026-10-28)
`uv run bench/pets.py onnx dinov2_s` and `onnx rfdetr_n`, then `uv run bench/tflite.py --models dinov2_s rfdetr_n`.

| Model | fp16 `.tflite` | Check against fp32 ONNX | Mac CPU, 4 threads |
|---|---|---|---|
| DINOv2-S | 41 MB | 40 pet crops, similarity 1.00000 | 43 ms per crop |
| RF-DETR-N | 54 MB | 40 photos: the same 99 boxes (score 0.5+), score within 0.013, boxes within 0.3 px of 384 | 113 ms per photo |

- RF-DETR-N from Roboflow's plain export stayed 100 MB: its linear layers become `BATCH_MATMUL`, which the fp16 cast skips. Exporting them 2-D (the SigLIP2 trick) halves it, same output.
- DINOv2-S has only GPU-friendly ops. RF-DETR-N still has `TOPK_V2`, `GATHER_ND`, `SELECT_V2`, `NOT_EQUAL`, `CAST` in its last step: on the GPU or NPU that part will likely run on the CPU. Phone check not done.

## On the phone (2026-10-29)
TFLite's `benchmark_model` (Google's nightly Android build) on the test phone, from `/data/local/tmp`, removed after. Its GPU is the old TFLite GPU delegate, not the app's LiteRT 2.3.0 `CompiledModel`; no NPU number yet.

| Model | CPU, 4 threads | GPU |
|---|---|---|
| DINOv2-S | 84 ms per crop | fails: `CONCATENATION` of the class token is 3-D (1x1x384), the GPU wants 4-D |
| RF-DETR-N | 220 ms per photo | 170 ms: only 648 of 1390 ops on the GPU, the rest on the CPU |

- RF-DETR-N ops the GPU refuses: `TOPK_V2`, `GATHER_ND`, `CAST` int32/int64, `GATHER` with 2-D indices, a 5-D `SLICE`, and shape mismatches on `ADD`, `MUL`, `RESHAPE`, `TRANSPOSE`, `TILE`, `SPLIT`, `CONCATENATION`.
- RF-DETR-N runs on every photo: at 220 ms that is about 70 min more indexing per 20k photos on the CPU. The NPU number decides whether that is OK.
- DINOv2-S runs only on pet boxes, so 84 ms on the CPU is fine. The GPU fix is likely small: do the class-token concat in 4-D at export.

## YOLO26 (2026-10-29)
RF-DETR-N is out: its last step can't run on a phone GPU. YOLO26 (Ultralytics, AGPL-3.0, fine under our GPL-3.0) is plain convolutions. It is exported without its end-to-end step (that has `TopK`), so the app removes duplicate boxes itself, as for YOLOv5m-face.

| Model | Params | COCO mAP | fp16 `.tflite` | Mac CPU |
|---|---|---|---|---|
| YOLO26-S | 9.5 M | 48.6 | 18 MB | 59 ms |
| YOLO26-M | 20.4 M | 53.1 | 39 MB | 164 ms |

- `uv run bench/tflite.py --models yolo26_s yolo26_m`: rows with a score 0.5+ match ONNX (714/715 for S, 848/847 for M), scores within 0.03, boxes within 3.5 px (S) and 0.3 px (M) of 640.

Faces inside a person box (of 3072), and faces with quality 0.4+ lost on Google-tagged photos (of 1450):

| Detector | Person score 0.05 | 0.1 | 0.25 |
|---|---|---|---|
| YOLOX-S | | 95.1%, 14 lost | 92.9%, 24 |
| YOLOX-L | | 96.0%, 10 | 94.1%, 22 |
| RF-DETR-N | | 98.0%, 3 | 94.1%, 11 |
| YOLO26-S | 96.3%, 11 | 95.2%, 16 | 92.5%, 30 |
| YOLO26-M | 97.3%, 7 | 96.4%, 11 | 93.8%, 29 |

Pets (`objects.py pets`, box score 0.3 / 0.5): YOLO26-S 159 / 151 of 168 found, boxes on 4 / 4 of 328 not-pet photos; YOLO26-M 163 / 159, 7 / 5. RF-DETR-N 163 / 156, 6 / 6.

- On the face check YOLO26-S is YOLOX-S again and YOLO26-M is YOLOX-L; RF-DETR stays best. A lower person score (0.05) brings M to 97.3%.
- The 11 good faces M loses at 0.1: 6 frames of the painting, 2 real faces, 3 junk (a statue, a cartoon, a carpet).
- At 0.05 M drops 32 faces with quality 0.4+ (the ones that can start a person): 1 real face. The rest: the painting (9 frames), an old print (4), statues (4), cartoons, drawings and memoji (7), a cat and a cat ear, a carpet, food, 2 photos of people inside memes or stickers.
- So the strict rule (drop a face outside every person box) fits, at person score 0.05. It also covers cats: a cat face has no person box. A separate cat/dog rule is not needed, and it could drop the face of someone holding a cat.
- On pets M matches RF-DETR-N; S misses a few more but has the fewest wrong boxes.

On the test phone (`benchmark_model`, removed after):

| Model | GPU | CPU, 4 threads (warm-up runs) |
|---|---|---|
| YOLO26-S | 49 ms per photo | ~62 ms |
| YOLO26-M | 79 ms per photo | ~246 ms |

- First try: the GPU refused both. onnx2tf turns each ONNX `Split` (11 of them, all with fixed sizes) into a `SLICE` of unknown size, and 377 tensors after it lost their sizes. `bench/tflite.py` now swaps each `Split` for plain `Slice` nodes before converting: no unknown sizes left, same output.
- On the GPU now: 387 of 493 ops (S). Left on the CPU: the fp16 `DEQUANTIZE` ops and the last box decode (`MUL` with broadcasting, `[1,4,8400] x [1,8400]`). Cutting the decode out of the model, as for YOLOv5m-face, would leave it all on the GPU.
- The tool's CPU run stops after warm-up ("Benchmarking failed"); the Mac runs the same file fine, so the CPU numbers are from the warm-up runs. Check again in the app.
- For comparison, RF-DETR-N: 170 ms GPU, 220 ms CPU. 20k photos on the GPU: ~16 min more indexing with S, ~26 min with M. The NPU should be faster still (AI Hub: YOLO models in single-digit ms).

## In the app (2026-10-29)
`ObjectDetector` and `PetVectors` (`Objects.kt`), run by Dev tools' Run on CPU / GPU / NPU when `models/objects.tflite` (YOLO26-M) and `models/pets.tflite` (DINOv2-S) are there. Input as planned: the 640 decode, the face detector's letterbox (`Letterbox`), pets cut from the 640 decode. The run writes `objects_<mode>.json`; `uv run bench/objects.py phone testdata/out/objects_<mode>.json` runs the same models on the Mac on the same 640 px photo. 132 test photos (39 found in the bench), 20 pet boxes.

| | YOLO26-M per photo | DINOv2-S per pet | Boxes 0.25+ vs Mac | Pet vectors vs Mac (median / min) |
|---|---|---|---|---|
| NPU | 18 ms | 10 ms | 58 same, 0 lost, 1 new | 0.92 / 0.74 |
| GPU | 40 ms | 15 ms | 57 same, 0 lost, 1 new | 0.98 / 0.91 |
| CPU, 4 threads | 488 ms | 99 ms | 57 same, 0 lost, 1 new | 0.98 / 0.91 |

- Times include the letterbox and the box decode in Kotlin; the 640 decode adds 25-30 ms per photo (the tagger already reads it).
- The gate (a person box 0.05+) gives the Mac's answer on every photo, on all three.
- DINOv2-S runs on the app's LiteRT GPU; the class-token fix for the old GPU delegate is not needed.
- On the NPU its vectors are off: NPU vs CPU on the same crop, median 0.935, min 0.80; GPU vs CPU median 0.996, min 0.988. Not an overflow (the largest value in the model is 349 on the Mac), so fp16 math on the NPU. `PetVectors` skips the NPU.
- Phone vs Mac 0.98 is the input, not the model: the 640 decode and the crop resize differ (Android's decoder and bilinear vs OpenCV). The bench's pet groups came from 4096-decode crops; whether 640-decode crops group as well is not checked.

