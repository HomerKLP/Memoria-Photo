# Third-party credits

What Memoria uses that others made. Memoria itself is GPL-3.0 (`LICENSE`).

## In the app

| What | Used for | License |
|---|---|---|
| [GeoNames](https://www.geonames.org) | Offline town names (`assets/towns.tsv`) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| [Bricolage Grotesque](https://github.com/ateliertriay/bricolage) | Titles and headings | [SIL OFL 1.1](https://openfontlicense.org) |
| [Roboto](https://github.com/googlefonts/roboto-classic) | All other text | [SIL OFL 1.1](https://openfontlicense.org) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (`onnxruntime-android-qnn`) | Runs the face models | MIT |
| Qualcomm QNN libraries (inside the ONNX Runtime package) | Runs the models on the phone's NPU | Qualcomm's own license |
| AndroidX (Room, WorkManager, Activity, Profile Installer), Jetpack Compose, Material 3 and Material icons | App code and UI | Apache 2.0 |
| Kotlin | Language and runtime | Apache 2.0 |

### GeoNames

Town names and coordinates come from GeoNames, licensed under CC BY 4.0.
We changed them: only towns of 1000+ people are kept, and districts are left out.

## Face models

The APK does not ship these today. They are pushed to the phone (`bench/fp16.py` makes the half-size files).

| Model | Used for | Source | License |
|---|---|---|---|
| YOLOv5m-face | Finds faces | [deepcam-cn/yolov5-face](https://github.com/deepcam-cn/yolov5-face) | GPL-3.0 |
| AdaFace IR101 (WebFace12M) | Face vector | [mk-minchul/AdaFace](https://github.com/mk-minchul/AdaFace) | Code MIT, weights: no license text found |
| eDifFIQA-T | Face quality score | [LSIbabnikz/DifFIQA](https://github.com/LSIbabnikz/DifFIQA) | Weights: no license text found |

The ONNX files come from the [UniFace](https://github.com/yakhyo/uniface) mirrors (`yakhyo/*-onnx`, MIT code).
Where no license text exists, we use the weights as is for this non-commercial, open-source app (`decisions.md`, 2026-10-19).
