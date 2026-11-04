# /// script
# requires-python = "==3.12.*"
# dependencies = ["pillow", "piexif", "pyobjc-framework-Quartz", "rawpy"]
# ///
"""Photo-file test fixtures (bugs.md "Mirrored HEIC", ROADMAP Phase 9).

`uv run bench/fixtures.py` makes the files in app/src/androidTest/assets/fixtures/ (committed, up to ~400 KB each).
Real camera DNGs from others (CC0, small, no GPS, serial numbers removed) sit in fixtures/real/; bench/fixtures.tsv says where each came from.

Every made file is the same picture: red, green, blue, yellow corners (top-left, top-right, bottom-left, bottom-right).
After making them, the script checks each file against the table below with outside readers (macOS ImageIO, Pillow, LibRaw for DNG).
Mac only: ImageIO writes the HEICs, like a phone does.
"""
import io
import struct
from pathlib import Path

import piexif
import Quartz as Q
import rawpy
from Foundation import NSMutableData, NSURL
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/androidTest/assets/fixtures"
W, H = 1600, 1200  # bigger than a grid tile, so a 320x240 preview is soft
COL = {"R": (230, 30, 30), "G": (30, 200, 30), "B": (30, 30, 230), "Y": (240, 220, 30)}
PURE = {"R": (255, 0, 0), "G": (0, 255, 0), "B": (0, 0, 255), "Y": (255, 255, 0)}  # the DNGs: Android renders them through its own colour pipeline

# What the viewer must show for each EXIF orientation: corners as TL TR BL BR, from the EXIF spec.
UPRIGHT = {1: "RGBY", 2: "GRYB", 3: "YBGR", 4: "BYRG", 5: "RBGY", 6: "BRYG", 7: "YGBR", 8: "GYRB"}


def base(w=W, h=H, colors=COL):
    img = Image.new("RGB", (w, h))
    for i, c in enumerate("RGBY"):
        img.paste(colors[c], ((i % 2) * w // 2, (i // 2) * h // 2, (i % 2 + 1) * w // 2, (i // 2 + 1) * h // 2))
    return img


def corners(img):
    w, h = img.size
    out = ""
    for x, y in ((w // 4, h // 4), (3 * w // 4, h // 4), (w // 4, 3 * h // 4), (3 * w // 4, 3 * h // 4)):
        px = img.convert("RGB").getpixel((x, y))
        out += min(COL, key=lambda k: sum((a - b) ** 2 for a, b in zip(COL[k], px)))
    return out


def jpeg_bytes(img, **kw):
    buf = io.BytesIO()
    img.save(buf, "JPEG", **kw)
    return buf.getvalue()


def save_jpeg(name, img, orientation=None, preview=None):
    exif = {"0th": {}, "1st": {}}
    if orientation is not None:
        exif["0th"][piexif.ImageIFD.Orientation] = orientation
    if preview is not None:  # like a camera: the preview sits in the file as a small JPEG
        exif["1st"][piexif.ImageIFD.Compression] = 6
        exif["thumbnail"] = jpeg_bytes(preview, quality=80)
    img.save(OUT / name, "JPEG", quality=90, optimize=True, exif=piexif.dump(exif))


def save_heic(name, img, orientation):
    data = io.BytesIO()
    img.save(data, "PNG")
    src = Q.CGImageSourceCreateWithData(data.getvalue(), None)
    out = NSMutableData.data()
    dst = Q.CGImageDestinationCreateWithData(out, "public.heic", 1, None)
    Q.CGImageDestinationAddImage(dst, Q.CGImageSourceCreateImageAtIndex(src, 0, None),
                                 {Q.kCGImagePropertyOrientation: orientation, Q.kCGImageDestinationLossyCompressionQuality: 0.9})
    assert Q.CGImageDestinationFinalize(dst), name
    (OUT / name).write_bytes(bytes(out))


def macos_upright(path):
    """The picture as macOS ImageIO shows it: it turns by EXIF, `irot` and `imir`."""
    src = Q.CGImageSourceCreateWithURL(NSURL.fileURLWithPath_(str(path)), None)
    up = Q.CGImageSourceCreateThumbnailAtIndex(src, 0, {
        Q.kCGImageSourceCreateThumbnailWithTransform: True, Q.kCGImageSourceCreateThumbnailFromImageAlways: True,
        Q.kCGImageSourceThumbnailMaxPixelSize: 4000})
    out = NSMutableData.data()
    dst = Q.CGImageDestinationCreateWithData(out, "public.png", 1, None)
    Q.CGImageDestinationAddImage(dst, up, {})
    Q.CGImageDestinationFinalize(dst)
    return Image.open(io.BytesIO(bytes(out)))


def libraw_upright(path):
    """The DNG as LibRaw shows it (macOS cannot open a hand-written DNG)."""
    with rawpy.imread(str(path)) as raw:
        return Image.fromarray(raw.postprocess(use_camera_wb=True, no_auto_bright=True, output_bps=8))


def boxes(path):
    """`irot` (degrees, counter-clockwise) and `imir` (axis) of a HEIC or AVIF, None when the box is missing."""
    d = path.read_bytes()
    i, m = d.find(b"irot"), d.find(b"imir")
    return (d[i + 4] & 3) * 90 if i >= 0 else None, d[m + 4] & 1 if m >= 0 else None


def save_dng(name, img, orientation):
    """A small DNG: 8-bit linear RGB, no compression, with the TIFF orientation tag. Hand-written, no library makes DNGs."""
    w, h = img.size
    pix = img.convert("RGB").tobytes()
    xyz_to_srgb = (3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427)  # D50
    tags = [  # (tag, TIFF type, count, bytes); the strip offset is filled in below
        (254, 4, 1, struct.pack("<I", 0)),
        (256, 4, 1, struct.pack("<I", w)),
        (257, 4, 1, struct.pack("<I", h)),
        (258, 3, 3, struct.pack("<3H", 8, 8, 8)),
        (259, 3, 1, struct.pack("<H", 1)),
        (262, 3, 1, struct.pack("<H", 34892)),  # LinearRaw
        (273, 4, 1, None),
        (274, 3, 1, struct.pack("<H", orientation)),
        (277, 3, 1, struct.pack("<H", 3)),
        (278, 4, 1, struct.pack("<I", h)),
        (279, 4, 1, struct.pack("<I", len(pix))),
        (284, 3, 1, struct.pack("<H", 1)),
        (50706, 1, 4, bytes([1, 4, 0, 0])),  # DNGVersion
        (50707, 1, 4, bytes([1, 1, 0, 0])),  # DNGBackwardVersion
        (50708, 2, 13, b"Memoria test\0"),  # UniqueCameraModel
        (50721, 10, 9, b"".join(struct.pack("<ii", round(v * 10000), 10000) for v in xyz_to_srgb)),  # ColorMatrix1
        (50728, 5, 3, struct.pack("<6I", 1, 1, 1, 1, 1, 1)),  # AsShotNeutral
        (50778, 3, 1, struct.pack("<H", 23)),  # CalibrationIlluminant1: D50
    ]
    ext_at = 8 + 2 + 12 * len(tags) + 4
    pix_at = ext_at + sum(len(b) + len(b) % 2 for *_, b in tags if b and len(b) > 4)
    entries, ext = b"", b""
    for tag, typ, cnt, b in tags:
        b = b if b is not None else struct.pack("<I", pix_at)
        if len(b) > 4:
            entries += struct.pack("<HHII", tag, typ, cnt, ext_at + len(ext))
            ext += b + b"\0" * (len(b) % 2)
        else:
            entries += struct.pack("<HHI", tag, typ, cnt) + b.ljust(4, b"\0")
    (OUT / name).write_bytes(b"II*\0" + struct.pack("<IH", 8, len(tags)) + entries + struct.pack("<I", 0) + ext + pix)


def make():
    OUT.mkdir(parents=True, exist_ok=True)
    pic = base()
    small = pic.resize((320, 240))
    # a zoomed shot: the camera's preview is a 3:4 middle cut of the 4:3 photo
    cut = pic.crop((350, 0, 1250, 1200)).resize((240, 320))

    for o in range(1, 9):
        save_jpeg(f"jpg_o{o}.jpg", pic, o)
        save_jpeg(f"jpg_o{o}_prev.jpg", pic, o, small)
        save_heic(f"heic_o{o}.heic", pic, o)
        pic.save(OUT / f"avif_o{o}.avif", quality=90, exif=piexif.dump({"0th": {piexif.ImageIFD.Orientation: o}}))
    for o in (1, 3, 6, 8):
        save_dng(f"dng_o{o}.dng", base(400, 300, PURE), o)
    save_jpeg("jpg_none.jpg", pic)  # no EXIF at all
    save_jpeg("jpg_o0.jpg", pic, 0)  # junk values: show as normal
    save_jpeg("jpg_o9.jpg", pic, 9)
    save_jpeg("jpg_o1_prevcut.jpg", pic, 1, cut)
    save_jpeg("jpg_o6_big.jpg", base(4000, 3000), 6)  # big enough that the viewer decodes by halving
    pic.save(OUT / "png_o1.png", optimize=True)
    pic.save(OUT / "webp_o1.webp", quality=90)

    for p in sorted(f for f in OUT.iterdir() if f.is_file()):
        tag = p.stem.split("_")[1][1:]  # "o6" -> "6", "o6_big" -> "6", "none" -> ""
        want = UPRIGHT.get(int(tag), "RGBY") if tag.isdigit() else "RGBY"
        shown = corners((libraw_upright if p.suffix == ".dng" else macos_upright)(p))
        assert shown == want, f"{p.name}: reader shows {shown}, table says {want}"
        if p.suffix == ".jpg":
            assert corners(ImageOps.exif_transpose(Image.open(p))) == want, f"{p.name}: Pillow disagrees"
    for ext in ("heic", "avif"):
        for o in range(1, 9):
            print(f"{ext}_o{o}: irot, imir = {boxes(OUT / f'{ext}_o{o}.{ext}')}")
    print(f"{len(list(OUT.glob('*.*')))} made files in {OUT}")


if __name__ == "__main__":
    make()
