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
| [LiteRT](https://github.com/google-ai-edge/LiteRT) (`litert`, `litert-gpu`, `litert-npu-runtime-qualcomm`) | Runs the face models | Apache 2.0 |
| Qualcomm QNN libraries (`qnn-runtime`) | Runs the models on the phone's NPU | [Qualcomm AI Stack License](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf), linked under the permission above; its notices in [`licenses/qnn-NOTICE.txt`](licenses/qnn-NOTICE.txt) |
| AndroidX (Room, WorkManager, Activity, Profile Installer), Jetpack Compose, Material 3 and Material icons | App code and UI | Apache 2.0 |
| Kotlin | Language and runtime | Apache 2.0 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | Drag to reorder people | Apache 2.0 |

### GeoNames

Town names and coordinates come from GeoNames, licensed under CC BY 4.0.
We changed them: only towns of 1000+ people are kept, and districts are left out.

## Face models

The APK does not ship these. The app downloads them once from the [`models-v1` release](https://github.com/HomerKLP/Memoria-Photo/releases/tag/models-v1) (`bench/tflite.py` makes the half-size `.tflite` files).

| Model | Used for | Source | License |
|---|---|---|---|
| YOLOv5m-face | Finds faces | [deepcam-cn/yolov5-face](https://github.com/deepcam-cn/yolov5-face) | GPL-3.0 |
| AdaFace IR101 (WebFace12M) | Face vector | [mk-minchul/AdaFace](https://github.com/mk-minchul/AdaFace) | MIT |
| eDifFIQA-T | Face quality score | [LSIbabnikz/DifFIQA](https://github.com/LSIbabnikz/DifFIQA) | MIT |

They are converted from the ONNX files of the [UniFace](https://github.com/yakhyo/uniface) mirrors (`yakhyo/*-onnx`, MIT code).
