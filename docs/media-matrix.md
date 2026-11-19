# Media file matrix

One tiny made-up file for every kind of photo and video a phone library can hold, each run through the app's real code on the emulator, so a format bug shows up without the phone.

Grows `VideoFileTest` / `bench/videofix.py` and `PhotoFileTest` / `bench/fixtures.py`: one line in the script plus one case per new kind. Rows go in `app/src/androidTest/assets/media.tsv`, and `MediaTableTest` keeps files and rows in step.

Planned 2026-11-13, not started.

## Video kinds

- Containers: MP4, MOV, M4V, 3GP, MKV, WebM, TS / MTS (camcorders), MPG, AVI, FLV, WMV.
- Codecs: H.264 (baseline, main, high, 10-bit), HEVC (main, main 10), VP8, VP9 (8 and 10-bit), AV1 (8 and 10-bit), MPEG-4 Part 2 (old phones, DivX), H.263, MPEG-2, Motion JPEG (old cameras), ProRes, Dolby Vision.
- HDR: HLG, PQ (HDR10), HDR10+.
- Frame rates: 23.976, 24, 25, 30, 50, 60, 120, 240, variable (phone cameras), a slow-motion clip with its flag.
- Sizes: 240p up to 1080p, 1440p, 4K UHD, DCI 4K, 8K; portrait of each; odd ones (16x16, odd width, 21:9, 1:1, very tall).
- Turn: display matrix 0, 90, 180, 270, a pixel aspect (anamorphic), a crop (1080 stored as 1088).
- Sound: AAC, HE-AAC, MP3, Opus, Vorbis, FLAC, PCM, AMR (old 3GP), AC-3, E-AC-3, DTS, none, two tracks, 5.1.
- Broken: 0 bytes, cut in the middle, index at the end of the file, one frame, a wrong extension.

## Video checks, per file

- Plays or shows the expected card, never a crash or a hang (a time limit).
- The shape on screen (turn and pixel aspect applied).
- Length and clock.
- The poster upright, the right shape, the first frame.
- A seek to the middle shows that moment (each second of a clip a different colour, so a frame tells its time).
- The sound track picked, or the expected "no sound" word.
- Time to the first frame logged (a trend, not a pass mark on the emulator).

## Photo kinds

- Formats: JPEG (baseline, progressive, CMYK, grey), HEIC (one image, a phone's grid of tiles, 10-bit, with depth data, a burst), AVIF (still, animated), PNG (alpha, 16-bit), WebP (lossy, lossless, animated), GIF, DNG, BMP, Ultra HDR JPEG (gain map), a motion photo (JPEG with a video inside: the photo shows), TIFF and PSD (what do we show?).
- Sizes: 16x16, a long screenshot (1080x20000), a panorama (20000x2000), 12, 50, 108 and 200 MP.
- Turn: EXIF 1-8 (done for JPEG, HEIC, AVIF), HEIC `irot` / `imir` (mirrored selfies, open in `bugs.md`), EXIF and MediaStore disagreeing.
- Colour: sRGB, Display P3, Adobe RGB, no profile.
- Broken: no EXIF, broken EXIF, no date, a wrong extension, cut in the middle, 0 bytes.

## Photo checks, per file

- The grid tile upright, sharp, the right crop.
- The viewer upright, the right shape, within the 4096 px GPU limit, no out-of-memory on the 200 MP one.
- The index decode upright (faces found where they are).
- The details panel facts.
- The open and close motion fits the photo.

## Emulator limits

Software decoders only. Each case reads the emulator's `MediaCodecList` and skips with a reason where it has no decoder (as AV1 does today). 4K and 8K check that frames decode, not that they are smooth. The HDR look is not judged there.

## Phone sweep without instrumentation

A `connected*AndroidTest` on the phone uninstalls the app and wipes its data, so the phone sweep pushes the same files to the debug app (like `make push-models`), and a Dev tools button runs them through the same player and decoders and writes a results JSON with the phone's codec list; `bench/media_check.py` compares it on the Mac. Built 2026-11-14 (Dev tools > Check media files). The same sweep on another phone shows its gaps.

## Making them

FFmpeg through PyAV covers most kinds (H.264 8 and 10-bit, HEVC with HLG / PQ tags, VP8, VP9, AV1, MPEG-4, H.263, MPEG-2, Motion JPEG, WMV, ProRes, AC-3, E-AC-3, MP3, Opus, FLAC, PCM); macOS ImageIO and Pillow write the photos. Not makeable here: Dolby Vision, AMR, a phone's slow-motion flag, Ultra HDR, a phone's HEIC grid: small real CC0 samples, like the DNGs in `fixtures/real/`, or a phone-only case. Solid colours keep files small.
