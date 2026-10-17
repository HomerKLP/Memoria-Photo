"""Make half-size (fp16) copies of the chosen models and check they match fp32 on our photos.

uv run bench/fp16.py   (run bench.py detect and group with yolov5m_640 first)
"""

import json
from pathlib import Path

import cv2
import numpy as np
import onnx
from onnxconverter_common.float16 import convert_float_to_float16
from onnxruntime.tools.onnx_model_utils import fix_output_shapes, make_input_shape_fixed
from onnxruntime.tools.symbolic_shape_infer import SymbolicShapeInference
from uniface import AdaFace, YOLOv5Face, get_cache_dir
from uniface.constants import AdaFaceWeights, EDifFIQAWeights, YOLOv5FaceWeights
from uniface.quality import EDifFIQA

from bench import CPU, load

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'testdata/out'
DET = 'yolov5m_640'


def iou(a, b):
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def half(name, size):
    src, dst = Path(get_cache_dir()) / f'{name}.onnx', ROOT / 'models' / f'{name}_fp16.onnx'
    dst.parent.mkdir(exist_ok=True)
    model = onnx.load(src)
    if name == 'ediffiqa_t':  # its L2 normalize ends in Shape -> Expand -> Div; Div broadcasts, so drop the run-time shape ops
        g = model.graph
        expand = next(n for n in g.node if n.op_type == 'Expand')
        for n in g.node:
            n.input[:] = [expand.input[0] if i == expand.output[0] else i for i in n.input]
        g.node.remove(expand)
        g.node.remove(next(n for n in g.node if n.op_type == 'Shape'))
    model = convert_float_to_float16(model, keep_io_types=True)
    make_input_shape_fixed(model.graph, 'input', [1, 3, size, size])  # the NPU (QNN) needs every shape fixed
    fix_output_shapes(model)
    if name == 'ediffiqa_t':  # the ORT tool below can't read ReduceL2 at opset 20
        model = onnx.shape_inference.infer_shapes(model, strict_mode=True)
    else:
        model = SymbolicShapeInference.infer_shapes(model, auto_merge=True)  # also folds run-time Shape ops
    onnx.save(model, dst)
    print(f'{name}: {src.stat().st_size >> 20} MB -> {dst.stat().st_size >> 20} MB')
    return dst


if __name__ == '__main__':
    det_path, rec_path, q_path = half('yolov5m', 640), half('adaface_ir_101', 112), half('ediffiqa_t', 112)

    detector = YOLOv5Face(model_name=YOLOv5FaceWeights.YOLOV5M, providers=CPU)
    detector._initialize_model(str(det_path))
    db = json.loads((OUT / 'detect.json').read_text())
    same = missed = extra = 0
    shift = []
    for rel, e in db.items():
        if DET not in e['det']:
            continue
        old = e['det'][DET]
        new = detector.detect(load(ROOT / 'testdata/photos' / rel))
        for f in old:
            match = [n for n in new if iou(f['box'], n.bbox) > 0.9]
            if match:
                same += 1
                side = f['box'][2] - f['box'][0]
                shift.append(np.abs(np.array(f['lm']) - match[0].landmarks).max() / side)
            else:
                missed += 1
        extra += len(new) - sum(any(iou(f['box'], n.bbox) > 0.9 for f in old) for n in new)
    print(f'detector fp16: {same} same faces, {missed} lost, {extra} new, '
          f'max landmark shift {max(shift):.2%} of face width')

    recognizer = AdaFace(model_name=AdaFaceWeights.IR_101, providers=CPU)
    recognizer.model_path = str(rec_path)
    recognizer._initialize_model()
    old = dict(np.load(OUT / f'emb_{DET}_adaface_ir101.npz'))
    sims = []
    for crop, v in old.items():
        w = recognizer.get_embedding(cv2.imread(str(OUT / 'faces' / crop))).ravel()
        sims.append(float(v @ (w / np.linalg.norm(w))))
    print(f'adaface fp16: {len(sims)} faces, similarity to fp32 min {min(sims):.5f} median {np.median(sims):.5f}')

    quality = EDifFIQA(model_name=EDifFIQAWeights.T, providers=CPU)
    quality.model_path = str(q_path)
    quality._initialize_model()
    diffs = [abs(quality.score_aligned(cv2.imread(str(OUT / 'faces' / f['crop']))).score - f['q'])
             for e in db.values() for f in e['det'].get(DET, [])]
    print(f'ediffiqa fp16: {len(diffs)} faces, quality diff to fp32 max {max(diffs):.4f} median {np.median(diffs):.4f}')
