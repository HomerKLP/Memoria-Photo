# Photo tagger

One CLIP-style model gives each photo a vector. Tags (pet, document, beach...) and later free-text search compare that vector with text vectors. It replaces the Phase 3 classifier and covers the Phase 10 labels.

## Candidates (2026-10-25)
Immich uses SigLIP2 for smart search; Ente and Queryable use CLIP-style models on the phone.

| Model | Vision | Text | Input | License | Languages |
|---|---|---|---|---|---|
| SigLIP2 B/16 (Google) | 93 M, 177 MB fp16 | 282 M | 224 | Apache-2.0 | many, Russian too |
| PE-Core B/16 (Meta) | 94 M, 179 MB | 354 M | 224 | Apache-2.0 | English |
| PE-Core S/16-384 (Meta) | 24 M, 45 MB | 63 M | 384 | Apache-2.0 | English |
| MobileCLIP2-S2 (Apple) | 36 M, 68 MB | 63 M | 256 | research only, can't ship | English |

Most of SigLIP2's text size is its 256k-word vocabulary. Fixed tags need no text model on the phone (their vectors ship as a file); free-text search does.

## Bench
`uv run bench/tags.py embed | sheets | label | score` (see its docstring). 3441 Takeout photos, 17 tags. Labels: a random 320 photos checked for every tag, plus every model's top 20 per tag (English) and top 10 (Russian) checked for that tag, ~1000 checks. Labeled by Claude from contact sheets; spot-check `testdata/out/tags.html`.

P@20 = share of right photos in the first 20 results. R-precision = share of all known right photos found in the first N, N = how many there are.

| tag | right photos | SigLIP2 B/16 | PE-Core B/16 | PE-Core S/16 | MobileCLIP2-S2 |
|---|---|---|---|---|---|
| pet | 75 | 1.00 / 0.57 | 0.80 / 0.45 | 0.95 / 0.53 | 1.00 / 0.53 |
| document | 58 | 1.00 / 0.57 | 0.95 / 0.60 | 0.95 / 0.66 | 0.95 / 0.64 |
| receipt | 5 | 0.20 / 0.60 | 0.25 / 0.80 | 0.25 / 0.80 | 0.25 / 0.40 |
| screenshot | 97 | 1.00 / 0.56 | 1.00 / 0.55 | 1.00 / 0.59 | 1.00 / 0.53 |
| meme | 67 | 1.00 / 0.58 | 1.00 / 0.69 | 0.95 / 0.61 | 1.00 / 0.60 |
| selfie | 78 | 0.85 / 0.41 | 0.90 / 0.44 | 0.80 / 0.45 | 0.75 / 0.42 |
| group | 47 | 1.00 / 0.74 | 0.95 / 0.68 | 1.00 / 0.79 | 1.00 / 0.68 |
| food | 45 | 1.00 / 0.73 | 1.00 / 0.67 | 0.95 / 0.71 | 1.00 / 0.71 |
| beach | 25 | 0.95 / 0.88 | 0.90 / 0.88 | 0.95 / 0.92 | 0.95 / 0.92 |
| mountains | 34 | 0.85 / 0.68 | 0.70 / 0.56 | 0.80 / 0.65 | 0.80 / 0.68 |
| snow | 33 | 0.95 / 0.79 | 0.95 / 0.79 | 0.85 / 0.73 | 0.95 / 0.79 |
| sunset | 32 | 1.00 / 0.91 | 1.00 / 0.88 | 1.00 / 0.88 | 1.00 / 0.84 |
| night | 55 | 1.00 / 0.65 | 1.00 / 0.58 | 1.00 / 0.64 | 1.00 / 0.62 |
| car | 36 | 0.95 / 0.72 | 1.00 / 0.75 | 1.00 / 0.81 | 1.00 / 0.83 |
| gym | 21 | 1.00 / 0.95 | 0.90 / 0.90 | 0.95 / 0.90 | 1.00 / 1.00 |
| child | 18 | 0.60 / 0.61 | 0.55 / 0.56 | 0.65 / 0.61 | 0.60 / 0.61 |
| flowers | 30 | 0.95 / 0.87 | 0.95 / 0.83 | 0.95 / 0.87 | 0.95 / 0.90 |
| **mean, English** | | **0.90 / 0.70** | 0.87 / 0.68 | 0.88 / 0.71 | 0.89 / 0.69 |
| mean, Russian (P@10) | | **0.75** | 0.10 | 0.09 | 0.39 |

Speed on the Mac CPU, 4 threads, one photo: SigLIP2 53 ms, PE-Core B 65 ms, PE-Core S 86 ms (384 px input), MobileCLIP2 314 ms (not reparameterized). The phone NPU will differ; not measured yet.

## Findings
- In English all four are within noise: first-screen results are 87-90% right. The tags differ more than the models.
- Weak tags: receipt (only 5 in the library, P@20 can't pass 0.25), child (~0.6: passport photos and memes rank high), selfie (0.75-0.90).
- Only SigLIP2 works with Russian words (0.75 vs 0.10 for PE). This matters only for free-text search; fixed tags use English prompts whatever the UI language.
- PE-Core S/16 matches the big models with a quarter of the vision size (45 MB vs 177 MB).
- A model change later means a new tag vector for every photo (one index pass, faces untouched).

## Pick (2026-10-26)
SigLIP2 B/16, for Russian free-text search later. Its text side is 282 M params (~565 MB fp16), 196 M of them the 256k-word vocabulary table; that table can live outside the model, and be cut to the words we need, when search is built.

## Conversion (2026-10-26)
`uv run bench/tags.py onnx`, then `uv run bench/tflite.py --models siglip2_b16` -> `models/siglip2_b16_fp16.tflite`, 175 MB (vision side only). The export swaps in rewritten timm parts that give the same result (checked on export); each is there for one runtime:
- In: RGB `(1, 3, 224, 224)`, the whole photo squashed to 224x224 (no crop), bicubic, `v / 127.5 - 1`. Out: the 768 vector, not normalized (normalize in Kotlin).
- Linear layers export with a 2-D input, so they become `Gemm` and then `FULLY_CONNECTED`. A 3-D `MatMul` becomes `BATCH_MATMUL`, which the quantizer's fp16 cast skips (the file stayed 351 MB).
- Opset 20, so GELU is LiteRT's own `GELU` op, not an erf approximation of 7 ops.
- LayerNorm runs on x / 32 (eps / 32²), which gives the same result. The NPU computes in fp16, and from block 10 on (x - mean)² reaches ~470,000 (|x| ~700), past fp16's 65,504. Without it the NPU vectors were wrong (similarity to CPU ~0.6-0.7). timm has its own `LayerNorm` class, so the export patches that one.
- Attention splits q, k, v into three 4-D tensors: timm reshapes to 5-D, which the GPU refuses.
- The pooling head's query is a learned constant, and the GPU's matmul refuses a constant on the left. It is folded into the key weights, so the scores are one 768 -> 12 linear layer, then softmax and a matmul of two live tensors.
- Ops: FULLY_CONNECTED, BATCH_MATMUL (attention), GELU, SOFTMAX, layer norm as MEAN/SUB/MUL/SQRT/DIV, RESHAPE, TRANSPOSE, SLICE, GATHER, one CONV_2D. At most 4-D.
- Check: similarity to fp32 ONNX 1.00000 on 40 photos; to the original PyTorch model on all 3441 photos min 0.99998. Every tag scores the same as PyTorch (`siglip2_b16_fp16` in `tags.py score`), and the top 20 of all 65 prompts are the same photos.

## Phone (2026-10-26, test phone, debug build, Dev tools run)
`Tagger.kt`; Dev tools runs it on the test photos when `models/tagger.tflite` is there and writes `tags_<mode>.json`; `uv run bench/tags.py phone testdata/out/tags_npu.json` compares with the bench.

| | NPU | GPU | CPU (4 threads) |
|---|---|---|---|
| model, per photo | 12 ms | 18 ms | 286 ms |
| first load | 6.5 s (NPU build), then ~0.25 s from the cache | 1.4 s | 0.5 s |

- Resize to 224 from the 4096 decode: 13 ms per photo on the NPU run.
- Memory: an NPU run peaks at 1.44 GB PSS with the tagger, 1.11 GB without (+335 MB: the file plus its ~190 MB NPU build). Measured before the attention rewrite.
- Same math: GPU vs CPU on the phone, 113 photos, similarity min 0.9993; NPU vs GPU min 0.9982.
- Against the bench (20 photos it has): 0.957-0.989. That gap is the resize (halving with bilinear vs the bench's bicubic); the Mac gives the same with the app's steps.
- The NPU cache is per model name and rebuilds when the file changes (the fixed file rebuilt in 6 s).

## Open
- Rescore all 3441 photos with the app's resize from the indexer's 640 decode; if tags drop, resize closer to bicubic.
- Videos: same model on a few frames, not benched.
