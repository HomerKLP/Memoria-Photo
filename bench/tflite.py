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
The tagger's ONNX comes from `uv run bench/tags.py onnx`. Not in the app yet (--models, see docs/objects.md):
noodle_s (the app's pets.tflite), dinov2_s and rfdetr_n from `uv run bench/pets.py onnx noodle_s` (and `onnx dinov2_s`, `onnx rfdetr_n`), yolo26_s / _m from bench/objects.py's docstring.
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
MODELS = {'yolov5m': 640, 'adaface_ir_101': 112, 'ediffiqa_t': 112, 'siglip2_b16': 224, 'dinov2_s': 224, 'noodle_s': 224, 'rfdetr_n': 384, 'yolo26_s': 640, 'yolo26_m': 640}
APP = ['yolov5m', 'adaface_ir_101', 'ediffiqa_t', 'siglip2_b16']


def convert(name, size, work):
    model = onnx.load(SRC / f'{name}.onnx')
    g = model.graph
    if name == 'ediffiqa_t':
        expand = next(n for n in g.node if n.op_type == 'Expand')  # L2 normalize ends in Shape -> Expand -> Div; Div broadcasts, so drop them
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
    if name == 'yolov5m':  # cut before the decode (5-D, the GPU fails on it, the NPU builds it in ~40 s): the 3 raw head grids
        heads = [n.output[0] for n in g.node if n.op_type == 'Conv'][-3:]
        del g.output[:]
        g.output.extend(helper.make_tensor_value_info(h, onnx.TensorProto.FLOAT, [1, 48, side, side]) for h, side in zip(heads, (80, 40, 20)))
    if name.startswith('yolo26'):  # Ultralytics names its input 'images'
        for n in g.node:
            n.input[:] = ['input' if i == 'images' else i for i in n.input]
        g.input[0].name = 'input'
        # Split -> a Slice per part: onnx2tf makes Split a SLICE of unknown size, and the GPU takes only fixed sizes
        consts = {i.name: onnx.numpy_helper.to_array(i) for i in g.initializer}
        for split in [n for n in g.node if n.op_type == 'Split']:
            at = list(g.node).index(split)
            axis = next(a.i for a in split.attribute if a.name == 'axis')
            g.node.remove(split)
            start = 0
            for k, (out, part) in enumerate(zip(split.output, consts[split.input[1]])):
                ends = [onnx.numpy_helper.from_array(np.array([v], np.int64), f'{out}_{w}') for w, v in (('start', start), ('end', start + part), ('axis', axis))]
                g.initializer.extend(ends)
                g.node.insert(at + k, helper.make_node('Slice', [split.input[0], *(e.name for e in ends)], [out]))
                start += part
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
    dst = ROOT / 'models' / f'{name}{"_raw" if name == "yolov5m" else ""}_fp16.tflite'
    quantizer.Quantizer(str(work / name / f'{name}_float32.tflite'), recipe.get_quantization_recipe()).quantize() \
        .export_model(str(dst), overwrite=True)
    print(f'{name}: {(SRC / f"{name}.onnx").stat().st_size >> 20} MB -> {dst.stat().st_size >> 20} MB')
    return dst


def tflite(path):
    it = Interpreter(model_path=str(path))
    it.allocate_tensors()
    i, outs = it.get_input_details()[0]['index'], [o['index'] for o in it.get_output_details()]

    def run(x):
        it.set_tensor(i, x)
        it.invoke()
        return [it.get_tensor(o) for o in outs]
    return run


ANCHORS = [[(4, 5), (8, 10), (13, 16)], [(23, 29), (43, 55), (73, 105)], [(146, 217), (231, 300), (335, 433)]]


def decode(heads):
    """FacePipeline.detect's decode: 3 head grids (1, side, side, 3 anchors x 16) -> the ONNX output rows, no class."""
    rows = []
    for out, stride, anchors in zip(sorted(heads, key=lambda h: -h.size), (8, 16, 32), ANCHORS):
        side = 640 // stride
        t = out.reshape(side, side, 3, 16).transpose(2, 0, 1, 3)  # anchor, y, x, field: the ONNX row order
        sig = 1 / (1 + np.exp(-t))
        y, x = np.mgrid[:side, :side]
        grid = np.stack([x, y], -1)
        wh = np.array(anchors, np.float32)[:, None, None]
        rows.append(np.concatenate([(sig[..., 0:2] * 2 - 0.5 + grid) * stride, (sig[..., 2:4] * 2) ** 2 * wh, sig[..., 4:5],
                                    t[..., 5:15] * np.tile(wh, 5) + np.tile(grid, 5) * stride], -1).reshape(-1, 15))
    return np.concatenate(rows)


def fp32(name):
    s = ort.InferenceSession(SRC / f'{name}.onnx', providers=['CPUExecutionProvider'])
    return lambda x: s.run(None, {s.get_inputs()[0].name: x})[0]


def letterbox(path):
    """640x640, gray 114 padding, RGB, 0..1, like FacePipeline.detect."""
    im = cv2.imread(str(path))[:, :, ::-1]
    s = min(640 / im.shape[0], 640 / im.shape[1])
    h, w = int(im.shape[0] * s), int(im.shape[1] * s)
    out = np.full((640, 640, 3), 114, np.uint8)
    out[(640 - h) // 2:(640 - h) // 2 + h, (640 - w) // 2:(640 - w) // 2 + w] = cv2.resize(im, (w, h))
    return (out.transpose(2, 0, 1)[None] / 255).astype(np.float32)


def squash(path):
    """224x224 RGB in -1..1, like the tagger's open_clip preprocess (which resizes bicubic): the same input to both runtimes."""
    im = cv2.resize(cv2.imread(str(path))[:, :, ::-1], (224, 224), interpolation=cv2.INTER_AREA)
    return (im.transpose(2, 0, 1)[None] / 127.5 - 1).astype(np.float32)


def imagenet(path, side):
    """side x side RGB, ImageNet mean and std, like DINOv2 and RF-DETR take it."""
    im = cv2.resize(cv2.imread(str(path))[:, :, ::-1], (side, side), interpolation=cv2.INTER_AREA) / 255
    return ((im - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32)


def face(rgb, bgr):
    """AdaFace wants BGR, EDifFIQA RGB, both (v - 127.5) / 127.5."""
    return ((rgb[:, :, ::-1] if bgr else rgb).transpose(2, 0, 1)[None].astype(np.float32) - 127.5) / 127.5


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--photos', type=Path, default=ROOT / 'testdata/takeout')
    ap.add_argument('--n', type=int, default=40, help='photos to check on')
    ap.add_argument('--models', nargs='+', default=APP, choices=list(MODELS), help='the face checks need all three face models')
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as work:
        out = {name: convert(name, MODELS[name], Path(work)) for name in args.models}

    random.seed(1)
    photos = random.sample(sorted(args.photos.rglob('*.jpg')), args.n)
    if 'siglip2_b16' in out:
        tag, tag_lite = fp32('siglip2_b16'), tflite(out['siglip2_b16'])
        sims = []
        for p in photos:
            a, b = tag(squash(p))[0], tag_lite(squash(p))[0][0]
            sims.append(a @ b / np.linalg.norm(a) / np.linalg.norm(b))
        print(f'siglip2 fp16: {len(sims)} photos, similarity to fp32 min {min(sims):.5f} median {np.median(sims):.5f}')
    for name in ('dinov2_s', 'noodle_s'):
        if name not in out:
            continue
        vec, vec_lite = fp32(name), tflite(out[name])
        sims = []
        for p in sorted((ROOT / 'testdata/out/pets/crops').glob('*.jpg'))[:args.n]:  # bench/pets.py crops
            a, b = vec(imagenet(p, 224))[0], vec_lite(imagenet(p, 224))[0][0]
            sims.append(a @ b / np.linalg.norm(a) / np.linalg.norm(b))
        print(f'{name} fp16: {len(sims)} pet crops, similarity to fp32 min {min(sims):.5f} median {np.median(sims):.5f}')
    if 'rfdetr_n' in out:
        s, det_lite = ort.InferenceSession(SRC / 'rfdetr_n.onnx', providers=['CPUExecutionProvider']), tflite(out['rfdetr_n'])
        found = found_lite = 0
        score_diff = box_diff = 0.0
        for p in photos:
            x = imagenet(p, 384)
            boxes, logits = s.run(['dets', 'labels'], {'input': x})
            boxes_lite, logits_lite = sorted(det_lite(x), key=lambda t: t.shape[-1])  # onnx2tf drops the names: (1, 300, 4), (1, 300, 91)
            sc, sc_lite = 1 / (1 + np.exp(-logits[0])), 1 / (1 + np.exp(-logits_lite[0]))
            keep = sc.max(1) >= 0.5
            found, found_lite = found + keep.sum(), found_lite + (sc_lite.max(1) >= 0.5).sum()
            if keep.any():
                score_diff = max(score_diff, np.abs(sc[keep] - sc_lite[keep]).max())
                box_diff = max(box_diff, np.abs(boxes[0][keep] - boxes_lite[0][keep]).max() * 384)
        print(f'rfdetr_n fp16: boxes >= 0.5 onnx/tflite {found}/{found_lite}, max diff on them: score {score_diff:.4f}, box {box_diff:.2f} px of 384')
    for name in ('yolo26_s', 'yolo26_m'):
        if name in out:
            det, det_lite = fp32(name), tflite(out[name])
            found = found_lite = 0
            score_diff = box_diff = 0.0
            for p in photos:
                x = letterbox(p)  # Ultralytics' input too
                a = det(x)[0].T  # 8400 rows: cx, cy, w, h, 80 scores
                b = det_lite(x)[0][0]
                b = b.T if b.shape[0] == 84 else b
                keep = a[:, 4:].max(1) >= 0.5
                found, found_lite = found + keep.sum(), found_lite + (b[:, 4:].max(1) >= 0.5).sum()
                if keep.any():
                    score_diff = max(score_diff, np.abs(a[keep, 4:] - b[keep, 4:]).max())
                    box_diff = max(box_diff, np.abs(a[keep, :4] - b[keep, :4]).max())
            print(f'{name} fp16: rows >= 0.5 onnx/tflite {found}/{found_lite}, max diff on them: score {score_diff:.4f}, box {box_diff:.2f} px of 640')
    if 'yolov5m' not in out:
        raise SystemExit
    det, det_lite = fp32('yolov5m'), tflite(out['yolov5m'])
    rows = rows_lite = 0
    shift = 0.0
    crops = []  # boxes cut from the letterboxed photo, not aligned: enough to compare two runtimes
    for p in photos:
        x = letterbox(p)
        a, b = det(x)[0][:, :15], decode(det_lite(x))
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
        a, b = rec(face(c, True))[0], rec_lite(face(c, True))[0][0]
        sims.append(a @ b / np.linalg.norm(a) / np.linalg.norm(b))
    print(f'adaface fp16: {len(sims)} faces, similarity to fp32 min {min(sims):.5f} median {np.median(sims):.5f}')

    q, q_lite = fp32('ediffiqa_t'), tflite(out['ediffiqa_t'])
    diffs = [abs(q(face(c, False))[0, 0] - q_lite(face(c, False))[0][0, 0]) for c in crops]
    print(f'ediffiqa fp16: {len(diffs)} faces, quality diff to fp32 max {max(diffs):.4f} median {np.median(diffs):.5f}')
