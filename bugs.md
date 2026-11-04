# Bugs

Style: one-line description, steps to reproduce, fix ideas. Code paths only when they help. Open bugs in full; a fixed bug becomes one line under Fixed at the bottom (the full write-up stays in git history).

## Grid thumbnails are the camera's EXIF preview

MediaStore's thumbnail is the photo's embedded EXIF preview, which causes two problems:
- **Soft grid.** Most camera photos come back as a 240x320 preview, stretched 1.5x into a ~360 px tile (logged on the test phone, 2026-10-27).
- **Viewer jumps.** The preview is not always the final photo (a wider view on zoomed shots, or an earlier frame). The tile shows it, then the photo shifts and grows when the sharp decode fades in. Found 2026-10-22: about 15 of 40 sampled photos from one phone camera, every zoomed one (EXIF DigitalZoomRatio > 1) and some 1x ones. That 510 x 384 preview has the photo's aspect, so the `small` check in `Thumb` misses it.

Steps:
1. Open a zoomed camera photo from the grid.
2. Watch the fade from thumbnail to photo: the picture moves.

Fix ideas:
- `Thumb` decodes the photo itself for every tile (halving, like the small-preview fix), plus a disk cache in the app's cache folder so only the first scroll pays. Check blank tiles with `make perf` before and after.
- Only when EXIF DigitalZoomRatio > 1: cheap, but misses the 1x shots and the soft grid.

Why (AOSP `ThumbnailUtils.createImageThumbnail`, checked 2026-11-04): a JPEG's EXIF preview is used at any size, with no size check, and turned only for EXIF 3, 6, 8. HEIC goes through `MediaMetadataRetriever`.
- The `small` check in `Thumb` is 200 px, but a tile is ~360 px, so a 240x320 preview passes it and stays soft.
- A mirrored EXIF (2, 4) keeps the preview's shape, so `sameShape` does not catch it.

Measured 2026-11-04 on the second test phone (MediaTek, 1216 px wide, 1050 HEIC + 881 JPEG), 60 random photos of each type, tile ~405 px:
- System thumbs are soft: HEIC 60 of 60 (all 240x320), JPEG 55 of 60 (mostly 144x192). The 200 px check in `Thumb` fixes only the JPEG ones, never HEIC.
- Shape differs from the photo: HEIC 1 of 60, JPEG 7 of 60.
- With today's checks (decode the file when the thumb is under 200 px or the wrong shape), same phone: HEIC 59 of 60 stay soft (240x320, 1.7x stretch). JPEG 49 of 60 are decoded from the file, the other 11 are fine or 1.1-1.2x. PNG (29 screenshots) comes back 292x633, 1.4x soft, no shape problem. So here only HEIC is clearly bad. On the Snapdragon 8 Elite phone the 240x320 previews (format not checked) pass the 200 px check, so JPEG there can be soft too.
- Own decode with halving (`ImageDecoder`, 12 MP, one thread, not a clean cold read): JPEG median 35 ms (p90 52), HEIC median 238 ms (p90 264). 4 threads on a HEIC + JPEG mix gave only 14 photos/s, so HEIC does not scale with threads.
- Scale to tile + WebP q80: +35 ms; ~32 KB (JPEG) / ~41 KB (HEIC) per thumb, so ~350 MB for 10k photos. Reading one back: ~4 ms.
- Not measured: `loadThumbnail` time (`ThumbnailUtils` needs an app context), the Snapdragon 8 Elite phone, a clean cold read. The program was a throwaway `app_process` dex, not kept.

Other apps:
- Immich shows its server's thumbnails: 250 px, made from the full photo, all 8 EXIF turns. For photos only on the phone it calls `loadThumbnail` like we do (`LocalImagesImpl.kt`), so those can be soft too.
- Glide galleries decode the file with halving, turn by all 8 EXIF values, and keep a disk cache.
- The phone's own gallery shows no jump, so it likely does the same (not checked).

## Library opens slowly

Reported 2026-10-29 by the user: the Library tab feels slow to show.

Measured 2026-10-31 on the phone's 0.4.0 release (simpleperf, first Library open after a cold start, ms from the tap). Compiled with the profile: data at ~280, covers at ~340. Uncompiled (as after an install or update, until Android compiles it in idle time): data at ~355, covers at ~445. One serial load, compiled run:
- taggedFaces 25, albums (MediaStore scan) 30, trash 8, placed 8.
- Categories ~170, 60% of the wait: `tags.json` parse 15, all photo vectors from SQL 85, scoring 67. Redone on every open, and the rows above wait for it.
- Covers start only after the data: ~40 more.

Done 2026-10-31: each photo's tags are stored (`photo_tag`, DB v15), so an open scores only new vectors. Debug build on the phone: categories ~100 ms (`tags.json` 40, missing-rows scan 11, `photo_tag` read 12, `categories()` 27); the first open after the migration scores all photos once (~390). Release not measured yet.

Measured 2026-11-05 on the 0.7.1 release (`make perf`, screencaps): all covers are drawn 151 ms after the tap on the first open after a cold start, 155 ms on a later one. That is the lowest the method reads, so it may be fixed. Check by feel before closing.

Steps:
1. Start the app, tap Library. Compare the first open after launch with a later one.

Suspects (from the code, `LibraryScreen` in `Library.kt`):
- The cache lives in memory only, so the first open after launch shows grey tiles until the whole load ends.
- One load for everything, step by step: people and albums wait for categories, which reads every photo vector (~13 MB, 100-280 ms on debug).
- Every open scans all of MediaStore (`albums`) and runs a second query for trash, even with cached data on screen.
- ~21 covers (12 faces, 9 tiles) start loading only after the data arrives.
- During a scan, each people save reloads the whole tab.

Fix ideas:
- First, log each step's time and when the covers are drawn, on the first open and a later one; release numbers after `make release`.
- Then: show people and albums before categories, start the load at app start (preload), or keep the cache on disk.

## Mirrored HEIC selfies show upside down in the viewer, sideways in the grid

Seen 2026-11-02 on another phone (MediaTek), release app: front-camera HEIC photos show upside down in the viewer and turned 90° in the grid; the phone's own gallery shows them right. Sample of that phone's HEICs (first 64 KB read, nothing kept):

| MediaStore | HEIC `irot` (ccw) | HEIC `imir` axis | EXIF | count |
|---|---|---|---|---|
| 270 | 90 | 1 (top-bottom) | 7 | 8 |
| 90 | 90 | 0 (left-right) | 5 | 1 |
| 180 | 0 | 0 | 4 | 21 |
| 0 | 0 | 1 | 2 | 3 |

Cause: the files are fine, the decoder is not. With `imir` read as libheif and the current HEIF text do (0 = top and bottom swap, 1 = left and right swap) and `irot` applied first, all four rows match their EXIF tag; an earlier note read `imir` the other way and put every row 180° off. Android's HEIF reader passes only a rotation to Skia (`SkHeifCodec.cpp`, checked 2026-11-04), so `ImageDecoder` (viewer, `Thumb` re-decode, `loadPhoto` in the index) applies `irot` and drops `imir`: row 3 (EXIF 4) shows upside down, rows 1, 2, 4 mirrored. If that phone shows row 1 upside down too, its decoder reads `imir` the old way. Either way faces in these photos are likely missed. The grid preview comes from `loadThumbnail`, and Android's thumbnailer turns only for EXIF 3, 6, 8, not the mirrored 2, 4, 5, 7: rows 1 and 2 stay sideways, row 3 upside down.

Checked 2026-11-04 on one open photo (an iPhone selfie HEIC, `irot` 90 + `imir` 0, MediaStore orientation 90): macOS shows it upright, the viewer shows it flipped top to bottom, its system thumb is a sideways 320x240 (the photo is portrait), and the People cover circles of its faces are upside down. So `ImageDecoder` applies `irot` and drops `imir`. Same phone, all 1050 HEICs (first 64 KB each): 236 (22%) have an `imir` box: (`irot` 90, `imir` 0) 109, (0, 0) 103, (0, 1) 16, (90, 1) 8. The other 814 have `irot` only or nothing. Only the one photo was compared by eye; the other combos follow the same rule.

Smaller fix idea: after the `ImageDecoder` decode, flip the bitmap by the file's `imir` axis (box type `imir`, size 9, in the first 64 KB), in the one place all three decodes share.

That fixes the grid only half way. Checked 2026-11-04 on a landscape HEIC with `irot` 0 + `imir` 0: the system thumb is the stored picture upside down, the same shape as the photo, so `Thumb` keeps it and the tile stays upside down while the fixed viewer opens upright. The `imir` + rotate-90 HEICs (117 of the 236) have a sideways thumb, so `Thumb` already re-decodes them and the decode fix covers them. The `imir`-only ones (`irot` 0, 119 of the 236) need `Thumb` to re-decode them too: the shape test cannot see them, so check the `imir` box, or decode every HEIC (the own-thumb plan above).

Fix idea: for a HEIC with a mirrored EXIF tag (2, 4, 5, 7), follow EXIF like the phone's gallery: decode the stored pixels with no turn (maybe `BitmapFactory`, check it skips `irot`) and apply the EXIF matrix, in one place all three decodes share; re-decode such tiles in `Thumb` like the wrong-shape ones. First check on the emulator with a made-up HEIC per row (arrow picture, `irot` + `imir`) what `ImageDecoder` and `BitmapFactory` apply, then on that phone.

Tests needed (so no phone has to be checked by eye; ROADMAP Phase 9):
- Files: a 4-colour picture (red, green, blue, yellow corners), 8 HEICs for EXIF 1-8 and the same 8 JPEGs with the EXIF orientation. Apple's ImageIO writes the HEICs with the phone's own box pairs (checked 2026-11-04: 2 = `irot` 0 + `imir` 1, 4 = 0 + 0, 5 = 90 + 0, 7 = 90 + 1, 3 / 6 / 8 `irot` only). Also AVIF 1-8 (Pillow; other box pairs, 7 = `irot` 270 + `imir` 0), PNG, WebP, junk tags 0 and 9, a big JPEG (halving), and JPEGs with a camera preview inside (also a 3:4 cut of a 4:3 photo). `bench/fixtures.py` makes them into `app/src/androidTest/assets/fixtures/` (43 files, 1.9 MB with the DNGs) and checks each against the EXIF table with macOS ImageIO, Pillow and LibRaw. DNG: four hand-written ones (turn tags 1, 3, 6, 8, 360 KB each) and one real Canon 60D frame from raw.pixls.us in `real/` (CC0, 0.47 MB, serial removed, source in `bench/fixtures.tsv`). Everything is in git, so no test depends on a download.
- Test (androidTest, emulator): put each file in MediaStore, run the real paths (grid tile `Thumb`, viewer decode, index `loadPhoto`), compare the four corner colours with a hand-written table for EXIF 1-8 (from the spec, not from what Android returns).
- The emulator is NOT enough for HEIC (found 2026-11-04, API 37 emulator): every HEIC case is green there, orientation 1-8 on grid, viewer and index, and the grid thumb is sharp. So Android 17 applies `imir` and gives a full-size HEIC thumb; the bug on the MediaTek phone needs an older emulator image (which Android version does that phone run?) or the phone. Once a fix flips by `imir` itself, the API 37 run must stay green (no double flip).
- Red on the API 37 emulator before the fixes (2026-11-04), all 176 cases green after: JPEG with a camera preview (grid for tags 2 and 4, sharpness for 1, 2, 3, 4, 6, 8) and DNG tags 3, 6, 8 on grid, viewer and index (Android does not turn a DNG by its TIFF tag). Checked on the phone with a made-up tag 6 DNG: grid and viewer show it right, so no double turn there. The phone's only real DNG is landscape, so no real portrait DNG was checked. Open, not tested: a mirrored (2, 4) JPEG whose preview is at least a tile / 1.2 wide still shows as stored.
- Decided 2026-11-04, then dropped (the user: HEIC is a strange one, keep it here for history): the `imir` check in `Thumb` and the known-failing HEIC tile. Not done.
- Sharpness: the grid tile's bitmap short side must be at least tile px / 1.2. Done for JPEG and the rest: `Thumb` re-decodes when the thumb is smaller (not HEIC) and asks MediaStore for a tile-size thumb. Scroll speed and blank tiles measured 2026-11-04 on release (`make perf`): 0% janky, 0% blank, so the re-decodes cost nothing visible. HEIC passes on the emulator; the phone's soft HEIC thumbs stay open above.

## No pets on another phone (Snapdragon 8 Gen 3)

Seen 2026-10-31 on another phone, release 0.4.0, fresh install, scan done: the Pets category shows the owner's cat, but People & pets has no pet. The same owner's photos (sent over a messenger) on the test phone's debug app give the owner's cat (64 photos, box scores 0.73-0.99, mean 0.91) and a stray (8 photos), so the 0.7 box cut and the 6-photo rule are not the cause. No code path differs by phone.

The models are downloaded. A model falls back to the next chip only when it fails to load (`open` in `Faces.kt`); errors while it runs and wrong numbers get no fallback. Likely causes (that chip: HTP V75 NPU, Adreno 750 GPU; only the test phone's 8 Elite was checked):
- `pets.tflite` loads but throws on each run: in the main loop the whole photo becomes an error, so it loses its pets, its boxes and its faces. Only photos whose best cat box is 0.5-0.7 (no pet run) keep boxes, which fits a Pets category with no pets. Logcat shows `index <id>` errors.
- A model runs with no error but gives wrong numbers: YOLO26-M cat scores under 0.7 but over 0.5, or broken pet vectors that never group. Logcat shows nothing.

Steps to find out (the phone in hand):
1. USB, take a new photo of the cat so a scan runs, then `adb logcat -d -s Memoria`: "objects.tflite on NPU/GPU/CPU", "pets.tflite on ...", "object models not loaded", "box <id>" errors.
2. If the models load: install the debug build next to the release (own data, the release app is untouched), push a few cat photos to its `test/`, Dev tools Run on NPU, GPU and CPU, compare each `objects_<mode>.json` with `uv run bench/objects.py phone`.

Fix ideas: wait for the phone (the user's call, no self-check at load). If one chip is wrong, skip it for that model, as `PetVectors` skips the NPU. If the pet model throws, keep the photo's faces and boxes and skip only the pet.

## Index is slow on a Dimensity 9500 phone with HEIC photos

Seen 2026-11-04, debug 0.7.0, fresh install: ~0.7-0.8 s per photo, so ~25 min for 2000 photos.

Not the models. All six run on the GPU (this chip has no Qualcomm NPU, so `Net` in `Faces.kt` skips it) and nothing failed. The index does one step at a time, so the CPU decodes while the GPU waits and the other way round; ~75% of the CPU is idle.

Per photo, ms, averaged over 80 photos: small decode 170, full decode 186 (only photos with faces), EXIF read in `kindOf` 31, tagger 88, objects 99, face detect 50, embed 68-290 (depends on the faces), database and people grouping ~40. Decoding is 35-50% of the total.

HEIC is the slow part: the 640 px decode takes 100-166 ms, a JPEG 19-37 ms. Reading the file takes ~10 ms, and bytes, file path and content URI give the same time, so it is the codec, not file access. HEIC decodes whole, so a smaller target size does not help and the full decode costs about the same again (120-340 ms).

Decode bench 2026-11-04 (12 HEIC, 6 JPEG, median ms per photo, index finished so nothing else ran):
- HEIC: `ImageDecoder` 640 px 129, full 257. `BitmapFactory` full 130, sample 4 136: the size changes nothing, the floor is ~130 ms. `MediaMetadataRetriever` primary image 274, scaled frame returns nothing. `loadThumbnail` 9, but only 320x240.
- JPEG: `ImageDecoder` 640 px 52, full 69. `BitmapFactory` sample 4 28, full 37.
- avif-coder 3.0.1 (software HEVC): HEIC full 596, sampled to 640 px 430, fails on JPEG. 3-4x slower than the system decoder, so not an option.
- Threads, `ImageDecoder` 640 px on HEIC: 143 ms per photo on 1 thread, 106 on 2, 100 on 4. The hardware decoder is shared, so one decode-ahead thread is enough.

Steps: run the scan on a phone with many HEIC photos and time each step. Logcat shows no app lines on this phone (`log.ratelimit`), so debug builds also write `memoria.log` in the app's files folder (`AppLog`): the chip each model runs on, errors, and the time per step.

Done 2026-11-04: the next photo's `kindOf` and 640 px decode run on a helper thread while the models work on this one (`prepare` in `Index.kt`). Same 196 photos indexed again: 128 s and 134 s without it, 101 s with it (~0.67 to ~0.52 s a photo). The loop waits 5-10 ms for a decode instead of ~170. The decode itself is unchanged, only the thread it runs on.

Still open:
- The full decode for photos with faces (~120-145 ms a photo on average) still runs in the loop, after the faces are found. The helper thread could do it early, at the price of decoding photos that turn out to have no faces.
- A 2048 px full decode is cheaper but changes the face crops. Face quality comes first, so only with the user's OK.

# Known issues and bad patterns

Found while reading the code, not seen on the phone yet. No steps, just the risk.

- **Stopped index leaves new faces out.** `IndexWorker` skips the last `savePeople()` when stopped, so faces added since the last regroup keep `personId = null` and are hidden in People until the next run.
- **Fixing a person's main face moves faces answered against it.** Answers point at the person's main face. If the user moves or removes that main face, after a regroup the faces with a yes for that person follow it. Fix: answer against a few starting faces, or move the answers to the new main face.
- **Small faces drop out.** Faces under 40 px never join a person and are also missing from the People screen (`People.kt` `groupFaces`). Intended, but they are silently gone, not "unsure".
- **Global mutable state.** `lastTimeline` and `thumbs` are top-level (`Photos.kt`); the Photos tab and the viewer both depend on them silently.
- **Bitmap churn.** `detect` makes new full-size bitmaps per photo and never recycles (`Faces.kt`). Adds to load cost and heat.
- **`groupFaces` holds an n×n float matrix in memory** (`People.kt`). Time is O(n²) since the NN-chain (2026-10-17), but memory is 17 MB at today's 2052 starting faces and ~400 MB at 10k. Store half of it (or float16) before libraries get that big.
- **Flaky `NavigationTest.search_categoryWordHit_backReturnsToSearchWithTheText`.** Failed once on the emulator (2026-10-31) with Compose's "Detected multithreaded access to SnapshotStateObserver", no app frames in the trace; the class passed 13/13 on a rerun. If it shows again, look for a draw started off the main thread.
- **Dead `photo.path` column.** `PhotoRow.path` (`Index.kt`) is written on insert but no query reads it. Remove it when the schema is next touched (needs a migration).
- **`FaceImage` loads one face per query.** Every face on screen runs its own `thumb(id)` query and WebP decode (`People.kt`). People draws ~50 covers (one per person, not per face), so ~50 queries where one `WHERE id IN (...)` would do. Not a wall (people grow slowly), but the wrong shape: batch the ids a screen is about to show, or coalesce the requests in the cache so no screen has to remember to prefetch.
- **AutoIndex scans the whole library on every app open.** `changedPhotos` (`AutoIndex.kt`) builds `libraryPhotos` (~4.4k `MediaPhoto`s) plus `photoIds` / `taggedIds` / `boxedIds` sets and `unplaced().size` each time the app comes to the front (3 s after the change burst), even when nothing is new. ~50-100 ms of background work per open, linear in the library. Compare a cheap count first.
- **Each screen scans MediaStore again.** `timeline`, `albums` and `trash` are three separate scans of the same table; Library runs two per open, and Search, Categories, Category, Place, Videos and Folders to skip each call `albums(context)` for the same rows (`Photos.kt`, `Albums.kt`). One shared, in-memory scan is in `ROADMAP.md` Phase 11 (from the 2026-11-02 perf review).
- **The face and thumb caches are never trimmed.** `thumbs` (`maxMemory / 2`) and `faceBitmaps` (`maxMemory / 8`) have no `onTrimMemory` (`Photos.kt`, `People.kt`), so 5/8 of the heap is held across a background/foreground switch.

## Perf numbers (`make perf`)

Release build, baseline profile compiled, full library (~4400 photos), median of 3. Frame times, plus how long Library takes to show its covers. Other screens' load times are not measured yet. Compare runs taken in the same state (a warm phone right after indexing reads 2-4x more janky frames). Earlier runs are in git history.

| 2026-11-05, v0.7.1 | frames | janky % | p90 ms | p99 ms |
|---|---|---|---|---|
| Cold start: first frame 135 ms, timeline 180 ms | | | | |
| Photos, first scroll | 431 | 0.5 | 6 | 8 |
| Photos, scroll | 418 | 0 | 5 | 7 |
| Library, open + scroll | 190 | 0.5 | 6 | 16 |
| Albums, open + scroll | 174 | 0.6 | 9 | 11 |
| Places, open + scroll | 187 | 0.5 | 8 | 12 |
| Categories, open + scroll | 55 | 0 | 7 | 11 |
| People, open + scroll | 188 | 0.5 | 6 | 11 |
| Viewer, open + 3 swipes | 266 | 1.1 | 6 | 17 |
| Details, open | 110 | 0 | 5 | 6 |

Library shows all its covers 151 ms after the tap on the first open (cold start) and 155 ms on a second open. That is the lowest the screencap method reads (one cap), so the real time is at or under it.

Native heap after 8 flings 52 MB (87 MB on 2026-11-04, not explained), no blank tiles on a cold fling. Cold start is up from v0.3.0's 116/163 ms (same phone, more library and DB work now).

# Fixed

- 2026-10-15 Viewer: screen-sized photos did not fill the screen. The photo fills it, bars float on top.
- 2026-10-15 Viewer: zoomed photo drawn over the next one. Pager pages don't clip: `clipToBounds`, pan clamped to the fitted photo.
- 2026-10-15 Viewer: panning while zoomed crawled. `pointerInput` must come before `graphicsLayer`, or deltas arrive divided by the zoom.
- 2026-10-16 Favorites: back from the viewer jumped to the top. `SaveableStateProvider("favorites")`.
- 2026-10-16 People: back from the viewer lost the open person. `SaveableStateProvider("people")`.
- 2026-10-16 People: back from a person lost the list scroll. Grid state lifted above the list/person branch.
- 2026-10-16 Library: People row twitched while scrolling. A `LazyRow` is as tall as its visible items; unnamed people keep an empty name line.
- 2026-10-16 Layout jumps on load and on state change (audit). Placeholders hold the space; the rule is in `AGENTS.md`.
- 2026-10-17 Face pictures stuck on the old face. `FaceImage` resets when the face changes.
- 2026-10-17 Person: merged photos showed only after a tab reload. The grid reloads when the photo list changes.
- 2026-10-17 Help recognize card always said "3 faces to check". It counted `take(3)`.
- 2026-10-17 Unsure faces card note wrapped for unnamed people. Shorter text ("8 to check").
- 2026-10-17 A removed sharp face was sure in its new person. A face with any No is sure only after a Yes.
- 2026-10-17 "1 photos", "1 faces to check". Singular forms.
- 2026-10-17 Details said "GMT-" for zones west of GMT. Kotlin's `if` took the rest of the line; `DetailsZoneTest`.
- 2026-10-17 Destructive migrations. Only v1-3 DBs are wiped; a missing migration fails `MigrationTest`.
- 2026-10-17 Month slider knob smaller than the mockup. 24 dp knob, 18 dp dot.
- 2026-10-17 Timeline month header not sticky. `stickyHeader`.
- 2026-10-17 Photos app bar had no search icon.
- 2026-10-18 Grid: blurry tiles on old camera photos (160x112 EXIF preview). `Thumb` decodes the photo itself when the thumbnail is under 200 px.
- 2026-10-18 People: blurry face pictures. Saved at 288 px, old ones cut again once.
- 2026-10-18 Person: back from the viewer landed at the top. `cachedProduceState`: an empty first frame clamps the restored scroll.
- 2026-10-18 Library tab felt laggy. Library, People and Details read faces without vectors (`taggedFaces`), counts use `libraryPhotoCount`.
- 2026-10-18 Empty album stayed open after its last photo left. `CloseWhenEmpty` on Album, Place, Videos and Person.
- 2026-10-18 Old cover flashed on Library after Change cover. `updateCached`.
- 2026-10-18 Crash when a tile left the grid while the viewer opened ("layouts are not part of the same hierarchy"). `motion()` guards `localBoundingBoxOf` and falls back to a fade.
- 2026-10-19 Photos tab-tap scroll to top stuttered. Measuring the distance with `scrollBy(-1e9f)` composed every row (1 s freeze, `gfxinfo` showed ~1% janky); now it jumps to three screens below the top first.
- 2026-10-19 Double-tap zoom always zoomed to the center. `zoomToFocusPan`.
- 2026-10-19 Month slider: taller than the mockup, knob stopped above the rail's end, only on the timeline. Tick marks removed (the user's call).
- 2026-10-20 Grid and viewer felt slow though frame times were fine. Grid loads a screen ahead with no fade and a 128 MB cache (no blank tiles); viewer decodes by halving into a hardware bitmap (tap to sharp ~330 -> ~175 ms).
- 2026-10-20 Viewer opened blurry, then sharpened. The sharp decode is at screen size now, so the fade is short.
- 2026-10-21 Back from an opened screen stuttered (Library). The tab stays composed under the opened screen.
- 2026-10-21 Back from a person showed the People list while sliding out.
- 2026-10-22 Photos tab stuttered on first load. Debug build only; judge smoothness on release.
- 2026-10-22 Flaky `library_pauseResume_progressBarKeepsLength`. The test went with the Library progress bar.
- 2026-10-22 Screenshot baselines depended on the machine's time zone. The preview passes UTC.
- 2026-10-22 Help recognize card text shifted left with fewer than 3 faces. The stack is always 3 faces wide.
- 2026-10-22 Photos tab reloaded the timeline while the app was in the background. One reload on return.
- 2026-10-22 Viewer: system bars stayed visible in the tapped state. Gone after a later viewer change.
- 2026-10-22 Memoria Debug never auto-indexed new photos: the models copied by hand were `shell`-owned and unreadable, so `modelsReady()` stayed false. `make push-models` sets the rights.
- 2026-10-23 Emulator: its software OpenGL GPU gave one face's result for every face after the 51st. `FacePipeline` runs the CPU on the emulator now.
- 2026-10-27 Grid tiles pixelated on some DSLR photos. An exact 480 px decode skipped pixels; now halving only.
- 2026-10-27 Opening a photo: the grow animation skipped or broke. It is a spring from rest now, and the grid stays put. A more mature open is Phase 7 in `ROADMAP.md`.
- 2026-11-03 An album named with 5 spaces showed as "(invalid)". Android makes "." and ".." into "(invalid)", and a keyboard likely turned the spaces into a dot. Next and Move stay off for names of only dots (`NewAlbum.kt`, `Move.kt`); not reproduced with the keyboard.
- 2026-10-29 A cat and a fish meme formed a person. A face outside every person box is dropped (`inPerson`, `Index.kt`), shipped in 0.4.0. The fish photo is not checked on that phone.
- 2026-10-30 Pet groups mixed different cats. Noodle pet vectors, boxes 0.7+, cut 0.68, split pets join under 0.76: none mixed; quality is below people's, limited by the models (`docs/objects.md`).
- 2026-10-31 Person or Pet screen kept a face removed in the viewer (Remove tag on a one-picture New pet left "1 photo"). A fix in the viewer now bumps `peopleSaved`, so the screen reloads and closes when its last face left.
- 2026-10-31 Index notification stayed after the run when a tap on it had dropped the foreground. The worker cancels it in `finally`.
- 2026-10-31 Back to Photos from Library jumped to the top after any earlier tab tap: the effect replayed the old tap. Only a newer tap scrolls now, and the bar that scrolled away stays away (`rememberSaveable`).
- 2026-10-31 The Photos grid under an opened screen followed the viewer too, walking up to 50 screens per swipe. Only the tab on top follows (`onTop`).
- 2026-10-31 Merge with re-sorted a moment after it opened. The grid stays empty until the face vectors load.
- 2026-10-31 Help recognize: a Yes on a face under 0.3 quality settled look-alikes only until the next regroup. `twinsOf` skips such faces, like the regroup.
- 2026-10-31 Help recognize: Someone else showed main faces, not covers, out of People order, and a New person could take a pet's name. Its No is one transaction now (`removeTag`).
- 2026-10-31 Move to album read the folder path as the file name for photos with no date taken. It reads `DISPLAY_NAME`.
- 2026-10-31 Month slider landed 1-2 items early after a reload with the same month count. The drag reads the latest month starts.
- 2026-10-31 Viewer froze for a moment on the first photos (debug). Not seen again after the hidden-grid fix.
- 2026-10-31 Release ran uncompiled after an update. Release APKs ship `assets/art-profile/baseline.prof`, which Android 15+ reads at install.
- 2026-11-01 Viewer: a blurry, zoomed copy stayed above and below a photo whose camera preview had another shape (a 3:4 middle cut of a 4:3 photo). `Thumb` decodes the photo itself when the preview's shape differs (`Tile.ratio`).
- 2026-11-02 `Person.photos` was a `get`, so People, Library, Search, Details and Help recognize recounted each person's photo count on every read, inside their sorts too. Counted once when the snapshot is built, like `faces` (`People.kt`).
- 2026-11-02 Only take the NPU on a Qualcomm chip: LiteRT's NPU fails quietly and runs on the CPU elsewhere, so `Net` drops it unless `Build.SOC_MANUFACTURER` is Qualcomm (`Faces.kt`).
- 2026-10-31 Viewer stuttered on a fast swipe to a neighbour. Decodes run two at a time and drop pages swiped past (`viewerLoads`), and only the tab on top follows the viewer. Checked smooth on release 2026-11-03.
- 2026-11-03 Library cover rows showed another item's photo while a scan reordered them. `Thumb` keys its state by `tile.id` (`ThumbTest`).
- 2026-11-03 Opening one Category or Place built every category or town from a full MediaStore scan. They read only their own photos now (`loadCategory`, `photosIn` + `photosByIds`).
- 2026-11-03 The Details panel read every tagged face on each swipe-up. It reads the person names now; the full list loads only when a face is tapped, for Who is this?.
- 2026-11-04 Grid tiles soft for JPEGs with a small camera preview, and mirrored previews (tags 2, 4) shown as stored. `Thumb` decodes the file when MediaStore's picture is under a tile / 1.2 (not HEIC) and asks for a tile-size thumb (`PhotoFileTest`). Release scroll checked 2026-11-04 (`make perf`): 0% janky, 0% blank tiles.
- 2026-11-04 DNG photos with a turn tag showed sideways: Android decodes a DNG as stored. Grid, viewer, index and Help recognize turn by the TIFF tag (`dngTurn`, `turnedBy` in `Faces.kt`). Checked on the phone with a made-up tag 6 DNG.
