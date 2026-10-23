# /// script
# requires-python = "==3.12.*"
# dependencies = [
#     "ai-edge-litert",
#     "ai-edge-quantizer==0.10.0",
#     "numpy",
#     "onnx",
#     "onnx2tf==2.6.9",
#     "onnxruntime",
#     "onnxsim",
#     "opencv-python",
# ]
#
# [tool.uv]
# override-dependencies = ["ai-edge-litert==2.3.0"]  # onnx2tf pins 2.1.2, the quantizer needs 2.3.0 (the app's LiteRT)
# ///
"""Make the fp16 .tflite files the app runs with LiteRT, and check them against the fp32 ONNX files.

uv run bench/tflite.py   (own env: onnx2tf pins tool versions the bench can't share; run bench.py once first, it downloads the models)
"""

import argparse
import random
import tempfile
from pathlib import Path

import cv2
import numpy as np
import onnx
import onnx2tf
import onnxruntime as ort
import onnxsim
from ai_edge_litert.interpreter import Interpreter
from ai_edge_quantizer import qtyping, quantizer, recipe_manager
from ai_edge_quantizer.algorithm_manager import AlgorithmName
from onnx import helper
from onnxruntime.tools.onnx_model_utils import fix_output_shapes, make_input_shape_fixed

ROOT = Path(__file__).resolve().parent.parent
SRC = Path.home() / '.uniface/models'
MODELS = {'yolov5m': 640, 'adaface_ir_101': 112, 'ediffiqa_t': 112}


def convert(name, size, work):
    model = onnx.load(SRC / f'{name}.onnx')
    g = model.graph
    if name == 'ediffiqa_t':
        expand = next(n for n in g.node if n.op_type == 'Expand')  # same patch as fp16.py
        for n in g.node:
            n.input[:] = [expand.input[0] if i == expand.output[0] else i for i in n.input]
        g.node.remove(expand)
        g.node.remove(next(n for n in g.node if n.op_type == 'Shape'))
        for l2 in [n for n in g.node if n.op_type == 'ReduceL2']:  # onnx2tf can't map it
            at = list(g.node).index(l2)
            keep = next((a.i for a in l2.attribute if a.name == 'keepdims'), 1)
            g.node.remove(l2)
            g.node.insert(at, helper.make_node('Sqrt', [l2.output[0] + '_sq'], list(l2.output)))
            g.node.insert(at, helper.make_node('ReduceSumSquare', list(l2.input), [l2.output[0] + '_sq'], keepdims=keep))
    # Fixed shapes, then onnxsim: without them YOLOv5m fails to convert and AdaFace keeps shape ops that break on the NPU
    make_input_shape_fixed(g, 'input', [1, 3, size, size])
    fix_output_shapes(model)
    model, ok = onnxsim.simplify(model)
    assert ok, name
    onnx.save(model, work / f'{name}.onnx')
    onnx2tf.convert(input_onnx_file_path=str(work / f'{name}.onnx'), output_folder_path=str(work / name),
                    keep_shape_absolutely_input_names=['input'], non_verbose=True)  # input stays NCHW, as in the app
    # fp16 weights, fp32 compute. onnx2tf's own float16 file is fp16 end to end, which the CPU and LiteRT's Kotlin API can't feed.
    recipe = recipe_manager.RecipeManager()
    recipe.add_weight_only_config(regex='.*', operation_name=qtyping.TFLOperationName.ALL_SUPPORTED, num_bits=16,
                                  algorithm_key=AlgorithmName.FLOAT_CASTING)
    dst = ROOT / 'models' / f'{name}_fp16.tflite'
    quantizer.Quantizer(str(work / name / f'{name}_float32.tflite'), recipe.get_quantization_recipe()).quantize() \
        .export_model(str(dst), overwrite=True)
    print(f'{name}: {(SRC / f"{name}.onnx").stat().st_size >> 20} MB -> {dst.stat().st_size >> 20} MB')
    return dst


def tflite(path):
    it = Interpreter(model_path=str(path))
    it.allocate_tensors()
    i, o = it.get_input_details()[0]['index'], it.get_output_details()[0]['index']

    def run(x):
        it.set_tensor(i, x)
        it.invoke()
        return it.get_tensor(o)
    return run


def fp32(name):
    s = ort.InferenceSession(SRC / f'{name}.onnx', providers=['CPUExecutionProvider'])
    return lambda x: s.run(None, {'input': x})[0]


def letterbox(path):
    """640x640, gray 114 padding, RGB, 0..1, like FacePipeline.detect."""
    im = cv2.imread(str(path))[:, :, ::-1]
    s = min(640 / im.shape[0], 640 / im.shape[1])
    h, w = int(im.shape[0] * s), int(im.shape[1] * s)
    out = np.full((640, 640, 3), 114, np.uint8)
    out[(640 - h) // 2:(640 - h) // 2 + h, (640 - w) // 2:(640 - w) // 2 + w] = cv2.resize(im, (w, h))
    return (out.transpose(2, 0, 1)[None] / 255).astype(np.float32)


def face(rgb, bgr):
    """AdaFace wants BGR, EDifFIQA RGB, both (v - 127.5) / 127.5."""
    return ((rgb[:, :, ::-1] if bgr else rgb).transpose(2, 0, 1)[None].astype(np.float32) - 127.5) / 127.5


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--photos', type=Path, default=ROOT / 'testdata/takeout')
    ap.add_argument('--n', type=int, default=40, help='photos to check on')
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as work:
        out = {name: convert(name, size, Path(work)) for name, size in MODELS.items()}

    random.seed(1)
    photos = random.sample(sorted(args.photos.rglob('*.jpg')), args.n)
    det, det_lite = fp32('yolov5m'), tflite(out['yolov5m'])
    rows = rows_lite = 0
    shift = 0.0
    crops = []  # boxes cut from the letterboxed photo, not aligned: enough to compare two runtimes
    for p in photos:
        x = letterbox(p)
        a, b = det(x)[0], det_lite(x)[0]
        keep = a[:, 4] >= 0.6
        rows, rows_lite = rows + keep.sum(), rows_lite + (b[:, 4] >= 0.6).sum()
        if keep.any():
            shift = max(shift, np.abs(a[keep] - b[keep]).max())
        im = (x[0].transpose(1, 2, 0) * 255).astype(np.uint8)
        for cx, cy, w, h in a[keep, :4]:
            c = im[max(int(cy - h / 2), 0):int(cy + h / 2), max(int(cx - w / 2), 0):int(cx + w / 2)]
            if c.size and len(crops) < 300:
                crops.append(cv2.resize(c, (112, 112)))
    print(f'detector fp16: rows >= 0.6 onnx/tflite {rows}/{rows_lite}, max diff on them {shift:.3f} px')

    rec, rec_lite = fp32('adaface_ir_101'), tflite(out['adaface_ir_101'])
    sims = []
    for c in crops:
        a, b = rec(face(c, True))[0], rec_lite(face(c, True))[0]
        sims.append(a @ b / np.linalg.norm(a) / np.linalg.norm(b))
    print(f'adaface fp16: {len(sims)} faces, similarity to fp32 min {min(sims):.5f} median {np.median(sims):.5f}')

    q, q_lite = fp32('ediffiqa_t'), tflite(out['ediffiqa_t'])
    diffs = [abs(q(face(c, False))[0, 0] - q_lite(face(c, False))[0, 0]) for c in crops]
    print(f'ediffiqa fp16: {len(diffs)} faces, quality diff to fp32 max {max(diffs):.4f} median {np.median(diffs):.5f}')
