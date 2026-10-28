# Object detector

A COCO object detector boxes cats, dogs and people. Two uses: pets (find each cat or dog, then group them like people) and People (a face outside every person box is often not human).

## Plan (2026-10-28)
- Every photo, as now: YOLOv5m-face, AdaFace, EDifFIQA-T for people; SigLIP2 for tags.
- Every photo, new: the detector, then a DINOv2-S vector per cat or dog crop, then groups with names. Its person boxes keep non-human faces out of People. (First plan: only photos with the Pets tag; the bench below found more pets without it.)
- The tagger can't say which box is which: one vector per photo, no person tag.

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
- On Google-tagged photos, YOLOX-L at 0.1 leaves out 10 of 1450 faces with quality 0.4+ (0.7%). 8 are real people. In each of these photos YOLOX boxed the main person (score ~0.93) and missed a second one: partly hidden, cut off at the photo's edge, or small in the back.
- YOLOX-S at 0.1 leaves out 14 of the same 1450: 10 real people (6 from one burst), 4 junk (statue, painting, cartoon, carpet). Close to L at a sixth of the size.
- Only 16-22 of the left-out faces sit inside an animal box (the cats).
- RF-DETR-S at 0.1, checked by eye. The 77 it leaves out: about 42 junk (17 cats, rocks, statues, food), about 30 faces in pictures (one picture repeated across many photos gives 23), about 5 real people (2 frames of one partly hidden person, 2 blurry). The 77 it keeps and YOLOX-S left out: about 45 real people, but also about 25 cartoons, drawings and statues: RF-DETR boxes a drawn person as a person.
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
- Bench and phone GPU: running.
