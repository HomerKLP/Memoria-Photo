# /// script
# requires-python = "==3.12.*"
# dependencies = [
#     "numpy",
#     "onnx",
#     "onnxscript",
#     "open-clip-torch==3.3.0",
#     "opencv-python-headless",
#     "pillow",
#     "rfdetr[onnx]==1.11.2",
#     "scipy",
#     "timm==1.0.30",
#     "torch==2.14.1",
#     "tqdm",
# ]
# ///
"""Who is this pet: one vector per cat or dog box, and how well each model tells pets apart.

uv run bench/pets.py crops    -> RF-DETR-N cat and dog boxes (score 0.5+) from testdata/out/objects.json, cut to testdata/out/pets/crops/
uv run bench/pets.py embed    -> a vector per crop and model, cached in testdata/out/pets/emb_<model>.npz
uv run bench/pets.py sheets   -> numbered contact sheets to label, similar crops (by dinov2_s) next to each other
uv run bench/pets.py label "1-12:a 13:b 14:-" -> which pet each tile is: any name, the same name for the same pet, - to leave out (drawing, toy, two pets in one box, unsure)
uv run bench/pets.py score    -> per model: is the closest other crop the same pet, and grouping at a cut
uv run bench/pets.py onnx dinov2_s  -> ~/.uniface/models/dinov2_s.onnx, for bench/tflite.py (rfdetr_n, _s, _m alike, for it and bench/objects.py)
"""

import argparse
import json
import shutil
import tempfile
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
import open_clip
import timm
import torch
from PIL import Image
from scipy.cluster.hierarchy import fcluster, leaves_list, linkage
from tqdm import tqdm

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'testdata/out/pets'
CAT, DOG = 15, 16  # COCO 80, as bench/objects.py
DEV = 'mps' if torch.backends.mps.is_available() else 'cpu'
MODELS = ['siglip2_b16', 'dinov2_s', 'dinov2_b']  # the app's tagger (already on the phone), DINOv2 small and base (Apache-2.0)


def crops(photos_dir):
    (OUT / 'crops').mkdir(parents=True, exist_ok=True)
    db = json.loads((ROOT / 'testdata/out/objects.json').read_text())
    n = 0
    for rel, e in tqdm(db.items()):
        boxes = [b for b in e.get('det', {}).get('rfdetr_n', []) if b[0] in (CAT, DOG) and b[1] >= 0.5]
        if not boxes:
            continue
        img = cv2.imread(str(photos_dir / rel))
        s = 4096 / max(img.shape[:2])  # bench.load's cap, the boxes' pixels
        if s < 1:
            img = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        for i, b in enumerate(boxes):
            x1, y1, x2, y2 = (int(v) for v in b[2:])
            c = img[y1:y2, x1:x2]
            c = cv2.resize(c, None, fx=min(1, 512 / max(c.shape[:2])), fy=min(1, 512 / max(c.shape[:2])), interpolation=cv2.INTER_AREA)
            cv2.imwrite(str(OUT / 'crops' / f'{e["id"]}_{i}.jpg'), c, [cv2.IMWRITE_JPEG_QUALITY, 95])
            n += 1
    print(f'{n} crops in {OUT / "crops"}')


def dinov2(name):
    """DINOv2 at 224 (its own is 518): a phone runs it on every pet box. The class token is the vector."""
    return timm.create_model({'dinov2_s': 'vit_small_patch14_dinov2.lvd142m', 'dinov2_b': 'vit_base_patch14_dinov2.lvd142m'}[name],
                             pretrained=True, num_classes=0, img_size=224).eval()


def encoder(name):
    """Crop (PIL) -> normalized vector."""
    if name == 'siglip2_b16':
        m, _, pre = open_clip.create_model_and_transforms('ViT-B-16-SigLIP2', 'webli')
        m = m.eval().to(DEV)
        return lambda img: m.encode_image(pre(img)[None].to(DEV), normalize=True)[0].float().cpu().numpy()
    m = dinov2(name).to(DEV)
    pre = timm.data.create_transform(input_size=(3, 224, 224), crop_pct=1.0, interpolation='bicubic',
                                     mean=(0.485, 0.456, 0.406), std=(0.229, 0.224, 0.225))
    return lambda img: torch.nn.functional.normalize(m(pre(img)[None].to(DEV)), dim=1)[0].float().cpu().numpy()


@torch.no_grad()
def embed():
    names = sorted(p.name for p in (OUT / 'crops').glob('*.jpg'))
    for model in MODELS:
        run = encoder(model)
        v = np.stack([run(Image.open(OUT / 'crops' / n).convert('RGB')) for n in tqdm(names, desc=model)])
        np.savez(OUT / f'emb_{model}.npz', names=names, vecs=v)


def vectors(model):
    z = np.load(OUT / f'emb_{model}.npz')
    return dict(zip(z['names'], z['vecs']))


def sheets():
    v = vectors('dinov2_s')
    names = list(v)
    order = [names[i] for i in leaves_list(linkage(np.stack(list(v.values())), 'average', metric='cosine'))]
    for old in OUT.glob('sheet_*.png'):
        old.unlink()
    for s in range(0, len(order), 48):
        tiles = []
        for n, name in enumerate(order[s:s + 48], s + 1):
            img = cv2.imread(str(OUT / 'crops' / name))
            k = 160 / max(img.shape[:2])
            tile = np.zeros((160, 160, 3), np.uint8)
            img = cv2.resize(img, None, fx=k, fy=k)
            tile[:img.shape[0], :img.shape[1]] = img
            cv2.rectangle(tile, (0, 0), (40, 16), (0, 0, 0), -1)
            cv2.putText(tile, str(n), (2, 12), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)
            tiles.append(tile)
        tiles += [np.zeros_like(tiles[0])] * (-len(tiles) % 8)
        cv2.imwrite(str(OUT / f'sheet_{s // 48 + 1}.png'), np.vstack([np.hstack(tiles[k:k + 8]) for k in range(0, len(tiles), 8)]))
    (OUT / 'sheets.json').write_text(json.dumps(order))
    print(f'{len(order)} crops on {-(-len(order) // 48)} sheets in {OUT}')


def label(answer):
    order = json.loads((OUT / 'sheets.json').read_text())
    path = OUT / 'labels.json'
    labels = json.loads(path.read_text()) if path.exists() else {}
    for part in answer.split():
        tiles, pet = part.split(':')
        a, _, b = tiles.partition('-')
        for n in range(int(a), int(b or a) + 1):
            labels[order[n - 1]] = pet
    path.write_text(json.dumps(labels, indent=1, sort_keys=True))
    print(f'{len(labels)} of {len(order)} crops labeled')


def score():
    labels = {k: v for k, v in json.loads((OUT / 'labels.json').read_text()).items() if v != '-'}
    pets = {p: sum(v == p for v in labels.values()) for p in set(labels.values())}
    print(f'{len(labels)} crops of {len(pets)} pets, {sum(c > 1 for c in pets.values())} seen more than once')
    keys = list(labels)
    y = np.array([labels[k] for k in keys])
    iu = np.triu_indices(len(keys), 1)
    same = (y[:, None] == y[None, :])[iu]
    for model in MODELS:
        v = vectors(model)
        x = np.stack([v[k] for k in keys])
        sim = x @ x.T
        np.fill_diagonal(sim, -1)
        has_twin = np.array([pets[p] > 1 for p in y])
        nearest = (y[sim.argmax(1)] == y)[has_twin].mean()
        np.fill_diagonal(sim, 1)
        far1 = np.quantile(sim[iu][~same], 0.99)
        # group like the app does people: average linkage, cut where 1% of different-pet pairs would pass
        g = fcluster(linkage(x, 'average', metric='cosine'), 1 - far1, 'distance')
        grouped = (g[:, None] == g[None, :])[iu]
        print(f'{model:12} closest crop is the same pet {nearest:.1%}  TAR@FAR1% {(sim[iu][same] > far1).mean():.3f}  '
              f'groups at distance {1 - far1:.2f}: precision {(grouped & same).sum() / max(grouped.sum(), 1):.3f} '
              f'recall {(grouped & same).sum() / same.sum():.3f}')


def export(name):
    """DINOv2: ImageNet-normalized RGB (1, 3, 224, 224) -> the class token, not normalized. The rewrites are bench/tags.py
    export's, so the phone's GPU and NPU can take it. RF-DETR: Roboflow's own export, with the linear rewrite only."""
    dst = Path.home() / '.uniface/models' / f'{name}.onnx'

    def linear(self, x):  # 2-D, so onnx2tf makes a FULLY_CONNECTED and the fp16 cast covers it (RF-DETR-N: 100 MB -> half)
        return torch.nn.functional.linear(x.reshape(-1, x.shape[-1]), self.weight, self.bias).reshape(*x.shape[:-1], -1)

    if name.startswith('rfdetr'):
        from rfdetr import RFDETRMedium, RFDETRNano, RFDETRSmall
        model = {'rfdetr_n': RFDETRNano, 'rfdetr_s': RFDETRSmall, 'rfdetr_m': RFDETRMedium}[name]()
        with tempfile.TemporaryDirectory() as work, patch.object(torch.nn.Linear, 'forward', linear):
            shutil.copy(model.export(output_dir=work, verbose=False), dst)
        print(f'{dst}: {dst.stat().st_size >> 20} MB')
        return
    m = dinov2(name)

    def norm(self, x):  # same result on x / 32, so fp16 on the NPU can't overflow in (x - mean)^2
        return torch.nn.functional.layer_norm(x / 32, self.normalized_shape, self.weight, self.bias, self.eps / 32 ** 2)

    def attention(self, x, attn_mask=None, is_causal=False):  # q, k, v as 4-D tensors: the GPU takes no 5-D reshape
        B, N, C = x.shape
        q, k, v = (t.reshape(B, N, self.num_heads, self.head_dim).transpose(1, 2) for t in self.qkv(x).chunk(3, -1))
        x = ((q * self.scale) @ k.transpose(-2, -1)).softmax(-1) @ v
        return self.proj(x.transpose(1, 2).reshape(B, N, C))

    test = torch.randn(1, 3, 224, 224)
    with torch.no_grad(), ExitStack() as patches:
        want = m(test)
        for cls, fn in [(torch.nn.Linear, linear), (torch.nn.LayerNorm, norm), (timm.layers.LayerNorm, norm), (timm.layers.Attention, attention)]:
            patches.enter_context(patch.object(cls, 'forward', fn))
        sim = torch.nn.functional.cosine_similarity(m(test), want).item()
        assert sim > 0.9999, sim  # the rewrites must not change the result
        torch.onnx.export(m, test, dst, input_names=['input'], output_names=['vector'], opset_version=20, external_data=False)
    print(f'{dst}: {dst.stat().st_size >> 20} MB')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['crops', 'embed', 'sheets', 'label', 'score', 'onnx'])
    ap.add_argument('answer', nargs='?', help='label: "1-12:a 13:b 14:-"; onnx: dinov2_s, dinov2_b, rfdetr_n, rfdetr_s, rfdetr_m')
    ap.add_argument('--photos', type=Path, default=ROOT / 'testdata/takeout')
    a = ap.parse_args()
    if a.cmd == 'crops':
        crops(a.photos.resolve())
    elif a.cmd == 'embed':
        embed()
    elif a.cmd == 'sheets':
        sheets()
    elif a.cmd == 'label':
        label(a.answer)
    elif a.cmd == 'onnx':
        export(a.answer)
    else:
        score()
