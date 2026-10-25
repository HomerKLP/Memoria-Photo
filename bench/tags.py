# /// script
# requires-python = "==3.12.*"
# dependencies = [
#     "ai-edge-litert==2.3.0",
#     "numpy",
#     "onnx",
#     "onnxscript",
#     "open-clip-torch==3.3.0",
#     "pillow",
#     "pillow-heif",
#     "timm==1.0.30",
#     "torch==2.14.1",
#     "tqdm",
#     "transformers==5.20.2",
# ]
# ///
"""Compare general photo taggers (CLIP-style models) on our own photos.

uv run bench/tags.py embed     -> image vectors per model, cached in testdata/out/tags/
uv run bench/tags.py info      -> size and CPU speed per model
uv run bench/tags.py sheets    -> numbered contact sheets to label: a random sample, then each model's top hits per tag
uv run bench/tags.py label SHEET ANSWER [SHEET ANSWER ...]
    sample sheet: "1:pet,food 4:selfie" (tiles not named have no tag); tag sheet: "3 5 9" (tiles that have it), "-" for none
uv run bench/tags.py score     -> P@20 and R-precision per tag and model, testdata/out/tags.html
uv run bench/tags.py onnx      -> the picked model's vision side to ~/.uniface/models/siglip2_b16.onnx, for bench/tflite.py
uv run bench/tags.py phone testdata/out/tags_npu.json   -> the app's vectors (Dev tools run) against the bench's, by file name
"""

import argparse
import hashlib
import html
import json
import os
import random
import time
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK', '1')

import numpy as np
import open_clip
import pillow_heif
import timm
import torch
from ai_edge_litert.interpreter import Interpreter
from PIL import Image, ImageDraw, ImageFont, ImageOps
from tqdm import tqdm

pillow_heif.register_heif_opener()
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'testdata/out/tags'
EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.heic', '.gif'}
MODELS = {  # name: (open_clip model, weights)
    'siglip2_b16': ('ViT-B-16-SigLIP2', 'webli'),
    'pe_core_b16': ('PE-Core-B-16', 'meta'),
    'pe_core_s16': ('PE-Core-S-16-384', 'meta'),
    'mobileclip2_s2': ('MobileCLIP2-S2', 'dfndr2b'),  # research-only weights: a yardstick, can't ship
    'siglip2_b16_fp16': ('ViT-B-16-SigLIP2', 'webli'),  # the app's .tflite from bench/tflite.py; open_clip only for the text side
}
# tag: (English prompts, Russian prompt). The comment is the labeling rule, so hand labels stay consistent.
TAGS = {
    'pet': (['a photo of a cat', 'a photo of a dog', 'a photo of a pet'], 'кошка или собака'),  # a real cat or dog, not a toy or drawing
    'document': (['a photo of a document', 'a paper with printed text', 'a passport or an ID card', 'a scanned document'], 'документ'),  # paper: forms, letters, IDs, receipts, tickets; not screens
    'receipt': (['a photo of a receipt', 'a shop receipt', 'a restaurant bill'], 'кассовый чек'),  # a till receipt or bill
    'screenshot': (['a screenshot of a phone screen', 'a screenshot of an app', 'a screenshot of a chat'], 'скриншот'),  # the file is a screen capture, not a camera photo of a screen
    'meme': (['a meme', 'a funny picture with a caption', 'an internet meme with text'], 'мем'),  # a joke picture made to share
    'selfie': (['a selfie', 'a selfie taken with a front camera', 'a mirror selfie'], 'селфи'),  # the person shoots themselves, arm's length or mirror
    'group': (['a group photo of people', 'many friends together', 'a group of people posing'], 'групповое фото'),  # 3+ people are the subject
    'food': (['a photo of food', 'a meal on a plate', 'a dish in a restaurant'], 'еда'),  # food or a drink is the subject
    'beach': (['a photo of a beach', 'the sea shore', 'people on a beach'], 'пляж'),  # a sea or lake shore
    'mountains': (['a photo of mountains', 'a mountain landscape'], 'горы'),  # mountains or big hills in view
    'snow': (['a photo of snow', 'a winter scene with snow'], 'снег'),  # snow on the ground or falling
    'sunset': (['a photo of a sunset', 'a photo of a sunrise', 'an orange sky at sunset'], 'закат'),  # sunset or sunrise sky
    'night': (['a photo taken at night', 'a city at night', 'street lights at night'], 'ночь'),  # outdoors after dark
    'car': (['a photo of a car', 'a car parked on the street'], 'машина'),  # a car is a main subject, not passing traffic
    'gym': (['a photo in a gym', 'workout equipment in a gym', 'a person lifting weights'], 'спортзал'),  # in a gym or exercising
    'child': (['a photo of a baby', 'a photo of a little child', 'a photo of kids'], 'ребёнок'),  # a baby or child under ~10 is a main subject
    'flowers': (['a photo of flowers', 'a bouquet of flowers'], 'цветы'),  # flowers are the subject
}
DEV = 'mps' if torch.backends.mps.is_available() else 'cpu'


def photos(photos_dir):
    return sorted(str(p.relative_to(photos_dir)) for p in photos_dir.rglob('*') if p.suffix.lower() in EXTS)


def load(path, side=1024):
    img = Image.open(path)
    s = side / max(img.size)
    img.draft('RGB', (int(img.width * s), int(img.height * s)))  # JPEG decodes at 1/2..1/8 size, much faster
    img = ImageOps.exif_transpose(img).convert('RGB')
    img.thumbnail((side, side))
    return img


def model(name):
    m, _, pre = open_clip.create_model_and_transforms(*MODELS[name])
    return m.eval(), pre


def encoder(name):
    """(a batch of inputs -> normalized vectors, preprocess). An _fp16 model runs its .tflite on the CPU, one photo at a time."""
    m, pre = model(name)
    if not name.endswith('_fp16'):
        m = m.to(DEV)
        return lambda x: m.encode_image(x.to(DEV), normalize=True).float().cpu().numpy(), pre
    it = Interpreter(model_path=str(ROOT / 'models' / f'{name}.tflite'), num_threads=4)
    it.allocate_tensors()
    i, o = it.get_input_details()[0]['index'], it.get_output_details()[0]['index']

    def run(x):
        out = []
        for one in x.numpy():
            it.set_tensor(i, one[None])
            it.invoke()
            out.append(it.get_tensor(o)[0])
        return np.array(out) / np.linalg.norm(out, axis=1, keepdims=True)
    return run, pre


def vectors(name):
    z = np.load(OUT / f'emb_{name}.npz')
    return dict(zip(z['rels'], z['vecs']))


def embed(photos_dir, names):
    OUT.mkdir(parents=True, exist_ok=True)
    rels = photos(photos_dir)
    for name in names:
        path = OUT / f'emb_{name}.npz'
        done = vectors(name) if path.exists() else {}
        todo = [r for r in rels if r not in done]
        if not todo:
            continue
        enc, pre = encoder(name)
        t = time.perf_counter()
        for i in tqdm(range(0, len(todo), 32), desc=name):
            batch, ok = [], []
            for r in todo[i:i + 32]:
                try:
                    batch.append(pre(load(photos_dir / r)))
                    ok.append(r)
                except Exception as e:
                    tqdm.write(f'cannot read, skipped: {r} ({e})')
            if batch:
                done.update(zip(ok, enc(torch.stack(batch))))
            if i % 640 == 0 or i + 32 >= len(todo):
                np.savez(path, rels=np.array(list(done)), vecs=np.stack(list(done.values())))
        print(f'{name}: {len(todo)} photos in {time.perf_counter() - t:.0f} s on {DEV}')


def info(names):
    torch.set_num_threads(4)  # like the app's CPU fallback
    rows = {}
    for name in names:
        if name.endswith('_fp16'):
            continue
        m, pre = model(name)
        x = pre(Image.new('RGB', (1024, 768), 'gray'))[None]
        vision = sum(p.numel() for p in m.visual.parameters())
        text = sum(p.numel() for p in m.parameters()) - vision
        with torch.no_grad():
            for _ in range(3):
                m.encode_image(x)
            ms = []
            for _ in range(10):
                t = time.perf_counter()
                m.encode_image(x)
                ms.append((time.perf_counter() - t) * 1000)
        rows[name] = {'input': x.shape[-1], 'vision_m': round(vision / 1e6, 1), 'text_m': round(text / 1e6, 1), 'cpu_ms': round(float(np.median(ms)))}
        print(f'{name:16} {x.shape[-1]} px  vision {vision / 1e6:5.1f} M ({vision * 2 / 2**20:4.0f} MB fp16)  text {text / 1e6:5.1f} M  CPU {rows[name]["cpu_ms"]} ms/photo')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'info.json').write_text(json.dumps(rows, indent=1))


def export(name):
    """The vision side as ONNX for bench/tflite.py: RGB (1, 3, side, side) in -1..1 -> the image vector, not normalized."""
    timm.layers.set_fused_attn(False)  # plain MatMul + Softmax for onnx2tf; read when the model is built
    m, pre = model(name)
    dst = Path.home() / '.uniface/models' / f'{name}.onnx'

    def linear(self, x):  # a 2-D input exports as Gemm, which onnx2tf makes a FULLY_CONNECTED; the fp16 cast skips BATCH_MATMUL
        return torch.nn.functional.linear(x.reshape(-1, x.shape[-1]), self.weight, self.bias).reshape(*x.shape[:-1], -1)

    def norm(self, x):  # same result on x / 32: the NPU runs fp16, where (x - mean)^2 of the last blocks (|x| ~700) passes 65504
        return torch.nn.functional.layer_norm(x / 32, self.normalized_shape, self.weight, self.bias, self.eps / 32 ** 2)

    def attention(self, x, attn_mask=None, is_causal=False):  # q, k, v as three 4-D tensors: the GPU takes no 5-D reshape
        B, N, C = x.shape
        q, k, v = (t.reshape(B, N, self.num_heads, self.head_dim).transpose(1, 2) for t in self.qkv(x).chunk(3, -1))
        x = ((q * self.scale) @ k.transpose(-2, -1)).softmax(-1) @ v
        return self.proj(x.transpose(1, 2).reshape(B, N, C))

    def pool(self, x, attn_mask=None):  # its query is a constant, which the GPU's matmul can't take: folded into the key weights
        N, C, H, D = x.shape[1], x.shape[2], self.num_heads, self.head_dim
        q = self.q(self.latent)[0, 0].reshape(H, D) * self.scale
        wk, bk = self.kv.weight[:C].reshape(H, D, C), self.kv.bias[:C].reshape(H, D)
        scores = torch.nn.functional.linear(x.reshape(N, C), torch.einsum('hdc,hd->hc', wk, q), (bk * q).sum(-1))  # (N, H)
        v = torch.nn.functional.linear(x.reshape(N, C), self.kv.weight[C:], self.kv.bias[C:]).reshape(1, N, H, D).transpose(1, 2)
        x = self.proj((scores.T.softmax(-1).reshape(1, H, 1, N) @ v).reshape(1, 1, C))
        return (x + self.mlp(self.norm(x)))[:, 0]

    x = pre(Image.new('RGB', (1024, 768), 'gray'))[None]
    test = torch.rand(x.shape) * 2 - 1
    with torch.no_grad(), ExitStack() as patches:
        want = m.visual(test)
        for cls, fn in [(torch.nn.Linear, linear), (timm.layers.LayerNorm, norm), (timm.layers.Attention, attention), (timm.layers.AttentionPoolLatent, pool)]:
            patches.enter_context(patch.object(cls, 'forward', fn))
        sim = torch.nn.functional.cosine_similarity(m.visual(test), want).item()
        assert sim > 0.9999, sim  # the rewrites must not change the result
        torch.onnx.export(m.visual, x, dst, input_names=['input'], output_names=['vector'], opset_version=20,
                          external_data=False)  # 20: Gelu, a native op in LiteRT
    print(f'{dst}: {dst.stat().st_size >> 20} MB')


def phone(path):
    """Similarity of each phone vector to the bench's PyTorch one: checks the app's resize and the phone runtime together."""
    bench = {Path(r).name: v for r, v in vectors('siglip2_b16').items()}
    got = json.loads(Path(path).read_text())
    sims = np.array([np.dot(v, bench[Path(r).name]) for r, v in got.items() if Path(r).name in bench])
    print(f'{len(sims)} of {len(got)} photos found in the bench; similarity min {sims.min():.4f} median {np.median(sims):.4f}, under 0.99: {(sims < 0.99).sum()}')


def tag_vectors(name, lang):
    """One vector per tag: the mean of its prompts. Cached by prompt text, so edited prompts are encoded again."""
    path = OUT / f'text_{name}.json'
    cache = json.loads(path.read_text()) if path.exists() else {}
    prompts = [p for en, ru in TAGS.values() for p in (en if lang == 'en' else [ru])]
    if any(p not in cache for p in prompts):
        m, _ = model(name)
        tok = open_clip.get_tokenizer(MODELS[name][0])
        with torch.no_grad():
            cache.update(zip(prompts, m.encode_text(tok(prompts), normalize=True).float().numpy().tolist()))
        path.write_text(json.dumps(cache))
    vecs = []
    for en, ru in TAGS.values():
        v = np.mean([cache[p] for p in (en if lang == 'en' else [ru])], axis=0)
        vecs.append(v / np.linalg.norm(v))
    return np.array(vecs)


def rankings(name, lang, rels):
    """For each tag, photo indexes best first."""
    v = vectors(name)
    scores = np.stack([v[r] for r in rels]) @ tag_vectors(name, lang).T
    return {tag: np.argsort(-scores[:, k]) for k, tag in enumerate(TAGS)}


def common(names):
    return sorted(set.intersection(*(set(vectors(n)) for n in names)))


def read_labels():
    path = OUT / 'labels.json'
    return json.loads(path.read_text()) if path.exists() else {'sample': {}, 'pool': {}}


def truth(labels, tag, rel):
    """True / False if someone looked at this photo for this tag, None if not."""
    if rel in labels['pool'].get(tag, {}):
        return labels['pool'][tag][rel]
    return tag in labels['sample'][rel] if rel in labels['sample'] else None


def thumb(photos_dir, rel, side):
    path = OUT / 'thumbs' / f'{hashlib.sha1(rel.encode()).hexdigest()[:12]}_{side}.jpg'
    if not path.exists():
        path.parent.mkdir(exist_ok=True)
        img = load(photos_dir / rel, side * 2)
        img.thumbnail((side, side))
        img.save(path, quality=85)
    return path


def sheet(photos_dir, name, rels, tile=280, cols=4):
    rows = (len(rels) + cols - 1) // cols
    img = Image.new('RGB', (cols * tile, rows * tile), '#888')
    d = ImageDraw.Draw(img)
    font = ImageFont.load_default(size=26)
    for i, r in enumerate(rels):
        x, y = i % cols * tile, i // cols * tile
        t = Image.open(thumb(photos_dir, r, tile - 6))
        d.rectangle([x + 2, y + 2, x + tile - 3, y + tile - 3], fill='white')
        img.paste(t, (x + (tile - t.width) // 2, y + (tile - t.height) // 2))
        d.rectangle([x + 2, y + 2, x + 40, y + 34], fill='yellow')
        d.text((x + 6, y + 4), str(i + 1), fill='black', font=font)
    (OUT / 'sheets').mkdir(exist_ok=True)
    img.save(OUT / 'sheets' / f'{name}.jpg', quality=85)


def sheets(photos_dir, sample_n, depth_en, depth_ru):
    labels = read_labels()
    todo = {}
    sample = [r for r in random.Random(1).sample(photos(photos_dir), sample_n) if r not in labels['sample']]
    for k in range(0, len(sample), 16):
        todo[f'sample_{k // 16:02}'] = {'rels': sample[k:k + 16]}
    if not sample:  # top hits only once the sample is labeled, so a sampled photo is never asked twice
        rels = common(MODELS)
        ranks = {(n, lang): rankings(n, lang, rels) for n in MODELS for lang in ('en', 'ru')}
        for tag in TAGS:
            hits = []
            for (n, lang), r in ranks.items():
                hits += [rels[i] for i in r[tag][:depth_en if lang == 'en' else depth_ru]]
            hits = [h for h in dict.fromkeys(hits) if truth(labels, tag, h) is None]
            for k in range(0, len(hits), 16):
                todo[f'{tag}_{k // 16:02}'] = {'tag': tag, 'rels': hits[k:k + 16]}
    for name, s in tqdm(todo.items(), desc='sheets'):
        sheet(photos_dir, name, s['rels'])
    (OUT / 'sheets.json').write_text(json.dumps(todo, ensure_ascii=False, indent=1))
    print(f'{len(todo)} sheets to label ({sum(len(s["rels"]) for s in todo.values())} photos) in {OUT / "sheets"}')


def label(pairs):
    todo = json.loads((OUT / 'sheets.json').read_text())
    labels = read_labels()
    for name, answer in zip(pairs[::2], pairs[1::2]):
        s = todo[name]
        if 'tag' in s:
            yes = {int(x) for x in answer.split() if x != '-'}
            assert yes <= set(range(1, len(s['rels']) + 1)), (name, yes)
            labels['pool'].setdefault(s['tag'], {}).update({r: i + 1 in yes for i, r in enumerate(s['rels'])})
        else:
            tags = {int(k): v.split(',') for k, v in (p.split(':') for p in answer.split() if p != '-')}
            assert set(tags) <= set(range(1, len(s['rels']) + 1)) and all(t in TAGS for v in tags.values() for t in v), (name, tags)
            labels['sample'].update({r: tags.get(i + 1, []) for i, r in enumerate(s['rels'])})
    (OUT / 'labels.json').write_text(json.dumps(labels, ensure_ascii=False, indent=1))


def score(photos_dir, depth):
    labels = read_labels()
    rels = common(MODELS)
    sizes = json.loads((OUT / 'info.json').read_text()) if (OUT / 'info.json').exists() else {}
    res = {}  # (model, lang) -> tag -> (P@depth, R-precision, unjudged in top, top rels)
    for n in MODELS:
        for lang in ('en', 'ru'):
            for tag, order in rankings(n, lang, rels).items():
                top = [rels[i] for i in order[:depth]]
                t = [truth(labels, tag, r) for r in top]
                pos = {r for r in rels if truth(labels, tag, r)}
                found = sum(1 for i in order[:len(pos)] if rels[i] in pos)
                res.setdefault((n, lang), {})[tag] = (sum(x is True for x in t) / depth, found / max(len(pos), 1), sum(x is None for x in t), top)
    positives = {tag: sum(1 for r in rels if truth(labels, tag, r)) for tag in TAGS}
    print(f'{len(rels)} photos, {len(labels["sample"])} in the random sample; P@{depth} / R-precision per tag')
    print(f'{"tag":11}{"pos":>5}' + ''.join(f'{n:>17}' for n in MODELS))
    for tag in TAGS:
        print(f'{tag:11}{positives[tag]:5}' + ''.join(f'{res[n, "en"][tag][0]:9.2f} / {res[n, "en"][tag][1]:.2f}' for n in MODELS))
    for lang in ('en', 'ru'):
        mean = lambda n, k: np.mean([v[k] for v in res[n, lang].values()])
        print(f'{"mean " + lang:16}' + ''.join(f'{mean(n, 0):9.2f} / {mean(n, 1):.2f}' for n in MODELS))
    unjudged = sum(v[2] for (n, lang), r in res.items() if lang == 'en' for v in r.values())
    if unjudged:
        print(f'{unjudged} photos in the English top {depth} not labeled yet: run sheets')

    # HTML: per tag, each model's top hits, green = has the tag, red = not, grey = not labeled
    css = """<style>body{font:14px sans-serif;margin:16px}table{border-collapse:collapse}td,th{padding:2px 8px;text-align:right}
    .hits img{width:96px;height:96px;object-fit:cover;margin:1px;border:3px solid #bbb}.y{border-color:#2a2!important}.n{border-color:#d22!important}</style>"""
    out = [css, f'<h1>Tagger bench</h1><p>{len(rels)} photos. P@{depth} / R-precision, English prompts.</p><table><tr><th>tag</th><th>labeled yes</th>']
    out += [f'<th>{n}<br><small>{sizes.get(n, {}).get("vision_m", "?")} M, {sizes.get(n, {}).get("cpu_ms", "?")} ms CPU</small></th>' for n in MODELS]
    for tag in TAGS:
        out.append(f'<tr><td>{tag}</td><td>{positives[tag]}</td>' + ''.join(f'<td>{res[n, "en"][tag][0]:.2f} / {res[n, "en"][tag][1]:.2f}</td>' for n in MODELS))
    for lang in ('en', 'ru'):
        out.append(f'<tr><th>mean {lang}</th><td></td>' + ''.join(f'<th>{np.mean([v[0] for v in res[n, lang].values()]):.2f} / {np.mean([v[1] for v in res[n, lang].values()]):.2f}</th>' for n in MODELS))
    out.append('</table>')
    for tag in TAGS:
        out.append(f'<h2>{tag}</h2>')
        for n in MODELS:
            imgs = []
            for r in res[n, 'en'][tag][3]:
                t = truth(labels, tag, r)
                src = os.path.relpath(thumb(photos_dir, r, 192), ROOT / 'testdata/out')
                imgs.append(f'<img class="{ {True: "y", False: "n"}.get(t, "")}" src="{html.escape(src)}" title="{html.escape(r)}">')
            out.append(f'<div class="hits"><b>{n}</b> {res[n, "en"][tag][0]:.2f}<br>{"".join(imgs)}</div>')
    (ROOT / 'testdata/out/tags.html').write_text('\n'.join(out))
    print(f'wrote {ROOT / "testdata/out/tags.html"}')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('cmd', choices=['embed', 'info', 'sheets', 'label', 'score', 'onnx', 'phone'])
    ap.add_argument('args', nargs='*', help='label: SHEET ANSWER pairs; phone: the JSON')
    ap.add_argument('--photos', type=Path, default=ROOT / 'testdata/takeout')
    ap.add_argument('--models', nargs='+', default=list(MODELS), choices=list(MODELS))
    ap.add_argument('--sample', type=int, default=320, help='random photos labeled for every tag')
    ap.add_argument('--depth', type=int, default=20, help='top hits per model and tag that get labeled and scored')
    ap.add_argument('--depth-ru', type=int, default=10)
    a = ap.parse_args()
    if a.cmd == 'embed':
        embed(a.photos, a.models)
    elif a.cmd == 'info':
        info(a.models)
    elif a.cmd == 'sheets':
        sheets(a.photos, a.sample, a.depth, a.depth_ru)
    elif a.cmd == 'label':
        label(a.args)
    elif a.cmd == 'onnx':
        export('siglip2_b16')
    elif a.cmd == 'phone':
        phone(a.args[0])
    else:
        score(a.photos, a.depth)


if __name__ == '__main__':
    main()
