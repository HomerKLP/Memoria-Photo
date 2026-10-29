# Third-party credits

What Memoria uses that others made. Memoria itself is GPL-3.0 (`LICENSE`), with this permission:

> **Additional permission under GNU GPL version 3 section 7**
>
> If you modify this Program, or any covered work, by linking or combining it with the Qualcomm QNN runtime libraries (`com.qualcomm.qti:qnn-runtime`, or a modified version of those libraries), containing parts covered by the terms of the [Qualcomm AI Stack License](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf), the licensors of this Program grant you additional permission to convey the resulting work.

## In the app

| What | Used for | License |
|---|---|---|
| [GeoNames](https://www.geonames.org) | Offline town names (`assets/towns.tsv`) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| [Bricolage Grotesque](https://github.com/ateliertriay/bricolage) | Titles and headings | [SIL OFL 1.1](https://openfontlicense.org) |
| [Roboto](https://github.com/googlefonts/roboto-classic) | All other text | [SIL OFL 1.1](https://openfontlicense.org) |
| [LiteRT](https://github.com/google-ai-edge/LiteRT) (`litert`, `litert-gpu`, `litert-npu-runtime-qualcomm`) | Runs the models | Apache 2.0 |
| Qualcomm QNN libraries (`qnn-runtime`) | Runs the models on the phone's NPU | [Qualcomm AI Stack License](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf), linked under the permission above; its notices in [`licenses/qnn-NOTICE.txt`](licenses/qnn-NOTICE.txt) |
| AndroidX (Room, WorkManager, Activity, Profile Installer), Jetpack Compose, Material 3 and Material icons | App code and UI | Apache 2.0 |
| Kotlin | Language and runtime | Apache 2.0 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | Drag to reorder people | Apache 2.0 |

### GeoNames

Town names and coordinates come from GeoNames, licensed under CC BY 4.0.
We changed them: only towns of 1000+ people are kept, and districts are left out.

## Models

The APK does not ship these. The app downloads them once from the [`models-v1`](https://github.com/HomerKLP/Memoria-Photo/releases/tag/models-v1) (face models), [`models-v2`](https://github.com/HomerKLP/Memoria-Photo/releases/tag/models-v2) (tagger) and [`models-v3`](https://github.com/HomerKLP/Memoria-Photo/releases/tag/models-v3) (people and pets) releases (`bench/tflite.py` makes the half-size `.tflite` files).

| Model | Used for | Source | License |
|---|---|---|---|
| YOLOv5m-face | Finds faces | [deepcam-cn/yolov5-face](https://github.com/deepcam-cn/yolov5-face) | GPL-3.0 |
| AdaFace IR101 (WebFace12M) | Face vector | [mk-minchul/AdaFace](https://github.com/mk-minchul/AdaFace) | MIT |
| eDifFIQA-T | Face quality score | [LSIbabnikz/DifFIQA](https://github.com/LSIbabnikz/DifFIQA) | MIT |
| SigLIP 2 B/16 (vision side) | Photo tags | [google-research/big_vision](https://github.com/google-research/big_vision), weights [timm/ViT-B-16-SigLIP2](https://huggingface.co/timm/ViT-B-16-SigLIP2) | Apache-2.0 |
| YOLO26-M | Person, cat and dog boxes | [ultralytics/ultralytics](https://github.com/ultralytics/ultralytics) | AGPL-3.0 |
| DINOv2-S | Tells pets apart | [facebookresearch/dinov2](https://github.com/facebookresearch/dinov2), weights [timm/vit_small_patch14_dinov2.lvd142m](https://huggingface.co/timm/vit_small_patch14_dinov2.lvd142m) | Apache-2.0 |

The face models are converted from the ONNX files of the [UniFace](https://github.com/yakhyo/uniface) mirrors (`yakhyo/*-onnx`, MIT code); YOLO26-M is Ultralytics' own export (`bench/objects.py`), DINOv2-S is exported from timm (`bench/pets.py`).
