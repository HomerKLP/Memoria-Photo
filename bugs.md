# Bugs

Style: one-line description, steps to reproduce, fix ideas. Code paths only when they help. Open bugs in full; a fixed bug becomes one line under Fixed at the bottom (the full write-up stays in git history).

## Emulator GPU gives every face after face 51 the same result, so people never form

Found 2026-10-23 on the `Memoria_Demo` emulator (no NPU, so the models ran on LiteRT's GPU over OpenGL, a software GPU there). The first 49 faces are fine. All 43 faces after them have the exact same quality (0.396484375, just under the 0.4 start cut) and the exact same vector, so only 4 of 9 people formed. It is face 51's real result: the Mac scores that photo 0.387, and the vector best matches another photo of the same person. So both GPU models ran right once, then gave that answer for every new face. Not fp16 overflow: the quality model's largest inner value is 15.5 in fp32. Had face 51 scored over 0.4, all 43 faces would have merged into one person.

Steps:
1. `Memoria_Demo` emulator, debug app, models pushed; 93 demo photos (9 people) in `DCIM/Camera` (`testdata/demo/photos/`).
2. Let the index run, pull the DB: `select quality, count(*) from face group by quality order by 2 desc`.
3. One value repeats for dozens of faces, all at the end (`order by id`), all with one vector.

Not on the phone: two Dev tools GPU runs in one process on the same 93 demo photos gave no repeats and matched the CPU (`docs/face-pipeline.md`, 2026-10-23). So it is the emulator's OpenGL GPU only.

Fix ideas:
- Find the trigger: rerun on the emulator with logcat. Maybe a second `FacePipeline` in the same process (a new worker run, or Dev tools): `docs/face-pipeline.md` already says the emulator's GPU builds only on the first try in a process.
- Done for now: `FacePipeline` uses the CPU on the emulator (`Build.HARDWARE == "ranchu"`). Dev tools Run on GPU still forces the GPU there, to test a fix.
- Guard: if faces from two different photos get the exact same vector, the GPU is broken; reopen the pipeline on the CPU and redo that photo. Keeps any other broken GPU from a mass merge.

## Viewer opens blurry, then sharpens

The viewer shows the grid thumbnail first, then fades (150 ms) into the real decode. Since 2026-10-20 it decodes at about screen size (tap to sharp ~330 -> ~175 ms), so only a short blur is left.

Steps:
1. Open any photo in the viewer.

Fix ideas:
- Pre-decode the tapped photo and its neighbours before opening.
- Decode the open page first: the two neighbour pages decode at the same time now.

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

Other apps: Immich calls `loadThumbnail` for anything up to 768 px (its `LocalImagesImpl.kt`), so it should get the same preview. The phone's own gallery shows no jump, so it likely makes its own thumbnails and keeps them on disk.

## Viewer freezes for a moment on the first photos (debug build)

Seen once, 2026-10-22, in Memoria Debug right after a `make install`: the first photo of a place lagged, and the swipes to the 2nd and 3rd photo froze for about half a second (365, 375, 236 ms frames). Not seen again on debug or release. Cause unknown.

Steps:
1. `make install`, open a place, tap its first photo, swipe to the next two.

Next time: record a Perfetto trace while it happens. A likely cause was fixed 2026-10-31: the hidden Photos grid followed the viewer of a screen opened from Search (see Fixed); check again if it shows up.

## Opening a photo: the grow animation sometimes skips or breaks

Found 2026-10-24 by the user. Fixed 2026-10-27 (debug, not measured on release): half the motion was one frame (the curve), the grid scrolled under the opening photo, and the bars composed at the end. Now the open is a spring from rest (damping 1, stiffness 500, ~340 ms), the grid stays put, the bars stay composed.

Left (Phase 7): the photo still opens as a crop window that widens step by step (`photoMotion`, `Viewer.kt`), which looks cheap; the user wants it to feel like a mature gallery app.

Fix ideas:
- The whole photo scales from the tile to full screen, no crop reveal; the grid behind dims. Check on a release build.
- Parallax when opening an album or a place: the page under it slides out slower than the new page comes in.
- From the code only: a tile whose thumbnail is not cached yet opens with a fade, and the sharp photo pops in mid-motion. A video once opened at near full size, half see-through (2026-10-27, not reproduced).
- The phone blocks `screenrecord`; measure frame by frame with `gfxinfo framestats`.

## Album named with only spaces shows as "(invalid)"

Seen 2026-10-28: a new album named with 5 spaces was made, and its Library tile reads "(invalid)".

Steps:
1. Library > Albums > New album (or Move to album > New album in the viewer).
2. Type 5 spaces as the name, pick a photo, move it.
3. Library's Albums row shows "(invalid)" with 1 photo.

Odd, since Next and Move should stay off for a blank name (`isBlank` in `NewAlbum.kt` and `Move.kt`). Android names a folder "(invalid)" when nothing is left of its name after it removes characters a file name can't have.

Fix ideas:
- Find what got past `isBlank` (keyboard space character, or a name with only dots and spaces).
- Turn Next / Move off when the name is empty after Android's file name cleanup, not only after `trim()`.

## Library opens slowly

Reported 2026-10-29 by the user: the Library tab feels slow to show.

Measured 2026-10-31 on the phone's 0.4.0 release (simpleperf, first Library open after a cold start, ms from the tap). Compiled with the profile: data at ~280, covers at ~340. Uncompiled (as after an install or update, until Android compiles it in idle time): data at ~355, covers at ~445. One serial load, compiled run:
- taggedFaces 25, albums (MediaStore scan) 30, trash 8, placed 8.
- Categories ~170, 60% of the wait: `tags.json` parse 15, all photo vectors from SQL 85, scoring 67. Redone on every open, and the rows above wait for it.
- Covers start only after the data: ~40 more.

Done 2026-10-31: each photo's tags are stored (`photo_tag`, DB v15), so an open scores only new vectors. Debug build on the phone: categories ~100 ms (`tags.json` 40, missing-rows scan 11, `photo_tag` read 12, `categories()` 27); the first open after the migration scores all photos once (~390). Release not measured yet.

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

## A cat and a fish meme formed a person

Seen 2026-10-28 on another phone's release build: a "No name" person with 16 photos of a cat, plus a meme of a fish with a human-like face.

Cause: a person starts from 6+ camera photos with face quality 0.4+, and EDifFIQA-T rates sharp, front-facing cat faces as good faces (it rates how clear a face is, not whether it is human). Then every other face joins the closest person under the cut, and AdaFace puts non-human faces near each other, so the fish joined the cat.

Fix ideas (`docs/objects.md`):
- Drop a face that is not inside any person box of the object detector (YOLO26, planned for pets too). With YOLO26-M at person score 0.05 it drops 32 faces of quality 0.4+, only 1 of them a real face; the rest are a painting, statues, cartoons, a cat.
- Softer: such a face can't start a person but can still join one, like quality under 0.4.

## Non-Qualcomm phone runs every model on the CPU, logs "on NPU"

Seen 2026-11-02 on a MediaTek phone (MT6993), release 0.6.0, first scan. Logcat says `tagger`, `objects`, `detector_raw`, `recognizer`, `quality` "on NPU", but LiteRT logs `Failed to initialize QNN backend` for each and runs them on XNNPACK (CPU). Only `pets.tflite` (no NPU in its list) went to the GPU.

Cause: `env.getAvailableAccelerators()` lists the NPU on every phone (the Qualcomm plugin is bundled), and `CompiledModel.create` with the NPU does not fail when the plugin fails, it quietly runs on the CPU. So `open` in `Faces.kt` never tries the GPU. LiteRT 2.3.0's Kotlin API has no "fully accelerated" check.

Fix idea: in `Net`, drop the NPU unless `Build.SOC_MANUFACTURER` is Qualcomm. A Qualcomm phone whose NPU fails to init would still fall to the CPU silently (maybe the 8 Gen 3 bug below).

Tried (debug, not committed): Dev tools Run on NPU, 3 photos, 2 faces, while both apps indexed: detect 58 ms, embed 112 ms per face, YOLO26 100 ms, tagger 118 ms. That is GPU speed (test phone GPU: 52, 45, 40; CPU: 380, 201, 488). With the indexes paused, Run on NPU (3 photos) and Run on GPU (23 photos, 9 faces) match: detect 80 / 70, embed 58 / 81, tagger 86 / 85, YOLO26 92 / 93. So that GPU is ~1.5-2x slower than the test phone's on these models. The app sets no `gpuOptions`, so precision is LiteRT's default; FP16 may be faster there, not tried. That phone's logd rate limit (`persist.log.ratelimit`) drops the app's log lines, so the chip lines can't be read there.

## Mirrored HEIC selfies show upside down in the viewer, sideways in the grid

Seen 2026-11-02 on another phone (MediaTek), release app: front-camera HEIC photos show upside down in the viewer and turned 90° in the grid; the phone's own gallery shows them right. Sample of that phone's HEICs (first 64 KB read, nothing kept):

| MediaStore | HEIC `irot` (ccw) | HEIC `imir` axis | EXIF | count |
|---|---|---|---|---|
| 270 | 90 | 1 (top-bottom) | 7 | 8 |
| 90 | 90 | 0 (left-right) | 5 | 1 |
| 180 | 0 | 0 | 4 | 21 |
| 0 | 0 | 1 | 2 | 3 |

Cause: the files are fine, the decoder is not. With `imir` read as libheif and the current HEIF text do (0 = top and bottom swap, 1 = left and right swap) and `irot` applied first, all four rows match their EXIF tag; an earlier note read `imir` the other way and put every row 180° off. Android's HEIF reader likely passes only a rotation to Skia (not checked), so `ImageDecoder` (viewer, `Thumb` re-decode, `loadPhoto` in the index) applies `irot` and drops `imir`: row 3 (EXIF 4) shows upside down, rows 1, 2, 4 mirrored. If that phone shows row 1 upside down too, its decoder reads `imir` the old way. Either way faces in these photos are likely missed. The grid preview comes from `loadThumbnail`, and Android's thumbnailer turns only for EXIF 3, 6, 8, not the mirrored 2, 4, 5, 7: rows 1 and 2 stay sideways, row 3 upside down.

Fix idea: for a HEIC with a mirrored EXIF tag (2, 4, 5, 7), follow EXIF like the phone's gallery: decode the stored pixels with no turn (maybe `BitmapFactory`, check it skips `irot`) and apply the EXIF matrix, in one place all three decodes share; re-decode such tiles in `Thumb` like the wrong-shape ones. First check on the emulator with a made-up HEIC per row (arrow picture, `irot` + `imir`) what `ImageDecoder` and `BitmapFactory` apply, then on that phone.

## No pets on another phone (Snapdragon 8 Gen 3)

Seen 2026-10-31 on another phone, release 0.4.0, fresh install, scan done: the Pets category shows the owner's cat, but People & pets has no pet. The same owner's photos (sent over a messenger) on the test phone's debug app give the owner's cat (64 photos, box scores 0.73-0.99, mean 0.91) and a stray (8 photos), so the 0.7 box cut and the 6-photo rule are not the cause. No code path differs by phone.

The models are downloaded. A model falls back to the next chip only when it fails to load (`open` in `Faces.kt`); errors while it runs and wrong numbers get no fallback. Likely causes (that chip: HTP V75 NPU, Adreno 750 GPU; only the test phone's 8 Elite was checked):
- `pets.tflite` loads but throws on each run: in the main loop the whole photo becomes an error, so it loses its pets, its boxes and its faces. Only photos whose best cat box is 0.5-0.7 (no pet run) keep boxes, which fits a Pets category with no pets. Logcat shows `index <id>` errors.
- A model runs with no error but gives wrong numbers: YOLO26-M cat scores under 0.7 but over 0.5, or broken pet vectors that never group. Logcat shows nothing.

Steps to find out (the phone in hand):
1. USB, take a new photo of the cat so a scan runs, then `adb logcat -d -s Memoria`: "objects.tflite on NPU/GPU/CPU", "pets.tflite on ...", "object models not loaded", "box <id>" errors.
2. If the models load: install the debug build next to the release (own data, the release app is untouched), push a few cat photos to its `test/`, Dev tools Run on NPU, GPU and CPU, compare each `objects_<mode>.json` with `uv run bench/objects.py phone`.

Fix ideas: wait for the phone (the user's call, no self-check at load). If one chip is wrong, skip it for that model, as `PetVectors` skips the NPU. If the pet model throws, keep the photo's faces and boxes and skip only the pet.

## Memoria Debug never auto-indexed new photos

Fixed 2026-10-22 on the phone (setup, not code), kept as a trap for pushing models by hand. After the debug/release split, the face models and the NPU builds were copied into `Android/data/app.memoria.debug/files/` with `cp` on the phone, so they belonged to `shell` and were `rw-rw----`. The app could see `models/` but not read it, so `modelsReady()` was false and the watcher never started a run.

Fix (done on the phone):
- `chmod 666` the model files, `chmod 2777` `models/` and `npu/` (so the app can also write NPU builds), `chmod 666` the files in `npu/`.
- Keep the model files' dates (`cp -p` or `touch -r`): an NPU build's name holds the model's size and date, so a new date means a rebuild, and a rebuild into a folder the app cannot write fails.

## Release runs uncompiled after an update

Found 2026-10-31: after the OTA update to 0.4.0 the phone's release app was `status=verify, reason=install` a day later, ~25% slower (Library data ~355 vs ~280 ms compiled). Outside Play, Android compiles the baseline profile only in its idle job, which needs charging and idle at once (Google lists it as a known issue).

How other apps handle it:
- Signal, Mihon: ship a baseline profile and accept the wait. Signal's own updater installs through a session, APK only. NewPipe, Element X: no profile at all. Immich is Flutter (Dart compiled ahead), so this barely matters to it.
- NuvioTV: its updater installs `base.apk` + `base.dm` in one session; the `.dm` files ship inside the APK's assets.
- NewTube (a SmartTube fork): a Gradle task writes `assets/art-profile/baseline.prof` with `profgen` (ART's own format, keyed to the final dex). Android 15+ reads it at install with no `.dm`, so OTA, browser and adb installs all compile at once. They measured cold start 926 -> 582 ms.

Done 2026-10-31: release builds also ship `assets/art-profile/baseline.prof`, AGP's profile converted to ART's format with the `profgen` inside AGP (`ArtProfileTask` in `app/build.gradle.kts`). Emulator (API 37), normal install and update over an old one: `verify` without the file, `speed-profile` with it, app starts. Check on the phone after the next OTA. Android 13-14 still wait for the idle job; a `.dm` in a session install would cover them.

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
- **Opening one Category or Place builds the whole library.** `CategoryScreen` runs `loadCategories(context)` (an `albums()` scan + every `photo_tag` row + `petPhotos` group) and then takes `.firstOrNull { it.id == id }`; `PlaceScreen` the same through `places(placed(), albums())` (`Categories.kt`, `Places.kt`). Work is linear in the library to show one grid. Query by id. Checked 2026-11-03 in the code: still true, and each open reruns it (`cachedProduceState` shows the old grid first).
- **The Details panel reads every tagged face.** `DetailsPanel` loads `taggedFaces()` (~2k rows on the test phone) and rebuilds a `Person` for every person on each swipe-up, to map one photo's few faces to their people (`Details.kt`). Grows with the library. Read only the person ids of that photo's faces. Checked 2026-11-03: still true, 2096 faces on the test phone's debug DB, the query alone 12 ms on the Mac. The full list also feeds Who is this? (`WhoIsThisSheet`), so read it only when that sheet opens.
- **AutoIndex scans the whole library on every app open.** `changedPhotos` (`AutoIndex.kt`) builds `libraryPhotos` (~4.4k `MediaPhoto`s) plus `photoIds` / `taggedIds` / `boxedIds` sets and `unplaced().size` each time the app comes to the front (3 s after the change burst), even when nothing is new. ~50-100 ms of background work per open, linear in the library. Compare a cheap count first.
- **Each screen scans MediaStore again.** `timeline`, `albums` and `trash` are three separate scans of the same table; Library runs two per open, and Search, Categories, Category, Place, Videos and Folders to skip each call `albums(context)` for the same rows (`Photos.kt`, `Albums.kt`). One shared, in-memory scan is in `ROADMAP.md` Phase 11 (from the 2026-11-02 perf review).
- **The face and thumb caches are never trimmed.** `thumbs` (`maxMemory / 2`) and `faceBitmaps` (`maxMemory / 8`) have no `onTrimMemory` (`Photos.kt`, `People.kt`), so 5/8 of the heap is held across a background/foreground switch.

## Perf numbers (`make perf`)

Release build, baseline profile compiled, full library (~4400 photos), median of 3. Frame times only: how long data takes to show is not measured yet. Compare runs taken in the same state (a warm phone right after indexing reads 2-4x more janky frames). Earlier runs are in git history.

| 2026-10-27, v0.3.0 (viewer: median of 2 runs) | frames | janky % | p90 ms | p99 ms |
|---|---|---|---|---|
| Cold start: first frame 116 ms, timeline 163 ms | | | | |
| Photos, first scroll | 340 | 0.3 | 5 | 8 |
| Photos, scroll | 315 | 0 | 6 | 8 |
| Library, open + scroll | 148 | 0.7 | 7 | 19 |
| People, open + scroll | 140 | 0.7 | 7 | 12 |
| Viewer, open + 3 swipes | 216 | 1.6 | 6 | 14.5 |
| Details, open | 82 | 1.2 | 5.5 | 13.5 |

Native heap after 8 flings 141 MB, no blank tiles on a cold fling. Taken before the 120 Hz request (the display ran at 90 Hz in the app).

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
- 2026-10-21 Back from an opened screen stuttered (Library). The tab stays composed under the opened screen.
- 2026-10-21 Back from a person showed the People list while sliding out.
- 2026-10-22 Photos tab stuttered on first load. Debug build only; judge smoothness on release.
- 2026-10-22 Flaky `library_pauseResume_progressBarKeepsLength`. The test went with the Library progress bar.
- 2026-10-22 Screenshot baselines depended on the machine's time zone. The preview passes UTC.
- 2026-10-22 Help recognize card text shifted left with fewer than 3 faces. The stack is always 3 faces wide.
- 2026-10-22 Photos tab reloaded the timeline while the app was in the background. One reload on return.
- 2026-10-22 Viewer: system bars stayed visible in the tapped state. Gone after a later viewer change.
- 2026-10-27 Grid tiles pixelated on some DSLR photos. An exact 480 px decode skipped pixels; now halving only.
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
- 2026-11-01 Viewer: a blurry, zoomed copy stayed above and below a photo whose camera preview had another shape (a 3:4 middle cut of a 4:3 photo). `Thumb` decodes the photo itself when the preview's shape differs (`Tile.ratio`).
- 2026-11-02 `Person.photos` was a `get`, so People, Library, Search, Details and Help recognize recounted each person's photo count on every read, inside their sorts too. Counted once when the snapshot is built, like `faces` (`People.kt`).
- 2026-10-31 Viewer stuttered on a fast swipe to a neighbour. Decodes run two at a time and drop pages swiped past (`viewerLoads`), and only the tab on top follows the viewer. Checked smooth on release 2026-11-03.
- 2026-11-03 Library cover rows showed another item's photo while a scan reordered them. `Thumb` keys its state by `tile.id` (`ThumbTest`).
