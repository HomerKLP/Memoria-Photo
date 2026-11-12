# /// script
# requires-python = "==3.12.*"
# dependencies = ["av", "numpy"]
# ///
"""Video fixtures for VideoFileTest (ROADMAP Phase 9).

`uv run bench/videofix.py` writes the clips in app/src/androidTest/assets/videos/ (committed, a few
KB each) and reads them back to check them: the shape the player must report, the codec and the turn.
The clips cover what a library actually holds, so a new codec or container is one line here plus one
case in VideoFileTest: a camera's H.264 and HEVC, a portrait phone video (stored landscape, turned by
the track's display matrix), a QuickTime .mov from an iPhone, a .mkv download, VP9, AV1 and 10-bit
HEVC. Three plain colours, so a frame's colour can be checked later if a case needs it.
A broken file is there too: the viewer's "cannot play" card needs one.
"""
import math
import struct
from dataclasses import dataclass
from pathlib import Path

import av
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/androidTest/assets/videos"
SHAPES = {"R": (230, 30, 30), "G": (30, 200, 30), "B": (30, 30, 230)}


@dataclass
class Clip:
    name: str
    w: int
    h: int
    codec: str
    container: str
    frames: int = 8
    rate: int = 8
    pix_fmt: str = "yuv420p"
    rotate: int = 0  # degrees the player must turn it, as a phone writes a portrait video


CLIPS = [
    Clip("video_land.mp4", 320, 180, "libx264", "mp4"),  # a landscape phone video
    Clip("video_port.mp4", 180, 320, "libx264", "mp4"),  # a portrait one: the clip that stretched in the viewer
    Clip("video_hevc.mp4", 320, 180, "libx265", "mp4"),  # HEVC, what a phone's camera records
    Clip("video_vp9.webm", 320, 180, "libvpx-vp9", "webm"),  # a container the phone may not have
    Clip("video_rot90.mp4", 320, 180, "libx264", "mp4", rotate=90),  # a portrait camera video: 320x180 stored, shown 180x320
    Clip("video_mov.mov", 320, 180, "libx264", "mov"),  # an iPhone's QuickTime file
    Clip("video_mkv.mkv", 320, 180, "libx265", "matroska"),  # a download's container
    Clip("video_av1.mp4", 320, 180, "libsvtav1", "mp4"),  # AV1: plays where the phone has a decoder
    Clip("video_hevc10.mp4", 320, 180, "libx265", "mp4", pix_fmt="yuv420p10le"),  # 10-bit HEVC, like an HDR recording
]


def make(c: Clip) -> Path:
    path = OUT / c.name
    with av.open(str(path), "w", format=c.container) as out:
        stream = out.add_stream(c.codec, rate=c.rate)
        stream.width, stream.height = c.w, c.h
        stream.pix_fmt = c.pix_fmt
        stream.bit_rate = 150_000
        for i in range(c.frames):
            rgb = SHAPES["RGB"[i % 3]]
            frame = av.VideoFrame.from_ndarray(np.full((c.h, c.w, 3), rgb, dtype=np.uint8), format="rgb24")
            for packet in stream.encode(frame.reformat(format=c.pix_fmt)):
                out.mux(packet)
        for packet in stream.encode():
            out.mux(packet)
    if c.rotate:
        set_rotation(path, c.rotate)
    return path


def matrix_at(data: bytes) -> int:
    """Where the video track's 3x3 display matrix starts, 16.16 fixed point, inside the tkhd box."""
    i = data.index(b"tkhd")
    size = struct.unpack(">I", data[i - 4 : i])[0]
    return i + (56 if size == 104 else 44)  # version 1 / version 0


def set_rotation(path: Path, degrees: int) -> None:
    """PyAV writes an identity matrix only (the old `rotate` stream tag is ignored by FFmpeg's mov
    muxer), so patch the display matrix the way ffmpeg's `av_display_rotation_set` writes it. A phone
    does the same: a portrait video is stored landscape and the player turns it."""
    data = bytearray(path.read_bytes())
    at = matrix_at(bytes(data))
    identity = struct.pack(">9i", 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)
    assert data[at : at + 36] == identity, data[at : at + 36].hex()
    radians = math.radians(-degrees)
    c, s = round(math.cos(radians)), round(math.sin(radians))
    data[at : at + 36] = struct.pack(">9i", int(c * 65536), int(-s * 65536), 0, int(s * 65536), int(c * 65536), 0, 0, 0, 0x40000000)
    path.write_bytes(bytes(data))


def rotation(path: Path) -> int:
    data = path.read_bytes()
    a, b, *_ = struct.unpack(">9i", data[matrix_at(data) : matrix_at(data) + 36])
    return round(math.degrees(math.atan2(b, a))) % 360


def check(c: Clip) -> str:
    path = OUT / c.name
    with av.open(str(path)) as f:
        s = f.streams.video[0]
        frames = sum(1 for _ in f.decode(video=0))
        turn = rotation(path) if b"tkhd" in path.read_bytes() else 0
        shown = f"{s.codec_context.height}x{s.codec_context.width}" if turn % 180 else f"{s.codec_context.width}x{s.codec_context.height}"
        return f"{c.name}: {s.codec_context.name} {s.codec_context.width}x{s.codec_context.height}, shown {shown} (turn {turn}), {frames} frames, {path.stat().st_size} bytes"


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for clip in CLIPS:
        make(clip)
    broken = OUT / "video_broken.mp4"
    broken.write_bytes(b"this is not a video")
    for clip in CLIPS:
        print(check(clip))
    print(f"{broken.name}: {broken.stat().st_size} bytes, on purpose")
