# /// script
# requires-python = "==3.12.*"
# dependencies = ["av"]
# ///
"""Video fixtures for VideoFileTest (ROADMAP Phase 9).

`uv run bench/videofix.py` writes the clips in app/src/androidTest/assets/videos/ (committed, a few
KB each) and reads them back to check them: the shape the player must report and the codec.
Three plain colours, so a frame's colour can be checked later if a case needs it.
A broken file is there too: the viewer's "cannot play" card needs one.
"""
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


CLIPS = [
    Clip("video_land.mp4", 320, 180, "libx264", "mp4"),  # a landscape phone video
    Clip("video_port.mp4", 180, 320, "libx264", "mp4"),  # a portrait one: the clip that stretched in the viewer
    Clip("video_hevc.mp4", 320, 180, "libx265", "mp4"),  # HEVC, what a phone's camera records
    Clip("video_vp9.webm", 320, 180, "libvpx-vp9", "webm"),  # a container the phone may not have
]


def make(c: Clip) -> Path:
    path = OUT / c.name
    with av.open(str(path), "w", format=c.container) as out:
        stream = out.add_stream(c.codec, rate=c.rate)
        stream.width, stream.height = c.w, c.h
        stream.pix_fmt = "yuv420p"
        stream.bit_rate = 150_000
        for i in range(c.frames):
            rgb = SHAPES["RGB"[i % 3]]
            frame = av.VideoFrame.from_ndarray(np.full((c.h, c.w, 3), rgb, dtype=np.uint8), format="rgb24")
            for packet in stream.encode(frame):
                out.mux(packet)
        for packet in stream.encode():
            out.mux(packet)
    return path


def check(c: Clip) -> str:
    with av.open(str(OUT / c.name)) as f:
        s = f.streams.video[0]
        frames = sum(1 for _ in f.decode(video=0))
        return f"{c.name}: {s.codec_context.name} {s.codec_context.width}x{s.codec_context.height}, {frames} frames, {(OUT / c.name).stat().st_size} bytes"


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for clip in CLIPS:
        make(clip)
    broken = OUT / "video_broken.mp4"
    broken.write_bytes(b"this is not a video")
    for clip in CLIPS:
        print(check(clip))
    print(f"{broken.name}: {broken.stat().st_size} bytes, on purpose")
