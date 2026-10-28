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

## Viewer closes when the phone rotates

Fixed for every restart that keeps the process: portrait lock (2026-10-17), and the viewer's photo list is top-level state (2026-10-22, `NavigationTest.restore_keepsTheOpenPhoto`). Still open after process death: the list is gone and the viewer closes.

Steps:
1. Open a photo, press Home, `adb shell am kill app.memoria.debug`.
2. Reopen from recents: the viewer is closed.

Fix ideas:
- Reload the tiles from the timeline by the open photo's id.

## Viewer: stutter when swiping to neighbours fast

Swiping to the next/previous photo in the viewer stutters, but only on a fast swipe. A slow swipe feels fine.

Steps:
1. Open a photo in the viewer.
2. Swipe to a neighbour quickly, a few times in a row.

Fix ideas:
- The two neighbour pages decode at the same time as the open one; on a fast swipe the next page may not be ready and starts its decode during the animation. Decode ahead / keep a small window decoded.
- Check whether the page gets built during the fling (`Viewer.kt` `HorizontalPager`).

## Viewer freezes for a moment on the first photos (debug build)

Seen once, 2026-10-22, in Memoria Debug right after a `make install`: the first photo of a place lagged, and the swipes to the 2nd and 3rd photo froze for about half a second (365, 375, 236 ms frames). Not seen again on debug or release. Cause unknown.

Steps:
1. `make install`, open a place, tap its first photo, swipe to the next two.

Next time: record a Perfetto trace while it happens.

## Opening a photo: the grow animation sometimes skips or breaks

Found 2026-10-24 by the user. Fixed 2026-10-27 (debug, not measured on release): half the motion was one frame (the curve), the grid scrolled under the opening photo, and the bars composed at the end. Now the open is a spring from rest (damping 1, stiffness 500, ~340 ms), the grid stays put, the bars stay composed.

Left (Phase 7): the photo still opens as a crop window that widens step by step (`photoMotion`, `Viewer.kt`), which looks cheap; the user wants it to feel like a mature gallery app.

Fix ideas:
- The whole photo scales from the tile to full screen, no crop reveal; the grid behind dims. Check on a release build.
- Parallax when opening an album or a place: the page under it slides out slower than the new page comes in.
- From the code only: a tile whose thumbnail is not cached yet opens with a fade, and the sharp photo pops in mid-motion. A video once opened at near full size, half see-through (2026-10-27, not reproduced).
- The phone blocks `screenrecord`; measure frame by frame with `gfxinfo framestats`.

## Play Protect warns on install

Seen 2026-10-27 on the first in-app update (0.3.0 to 0.3.1, test phone). The update went through and kept the data, but on the way: the updater opened the APK with an "Open with" chooser (`ResolverActivity`), then the phone maker's installer, then Google's developer verifier ran (`com.google.android.verifier`) and Play Protect showed a warning that the app may be harmful. The user went on and it installed.

Cause, most likely: the app is not registered with Google's developer verification (sideloaded apps must come from a registered developer; enforced in four countries since 2026-09-30, worldwide planned for 2027). Not a finding in our code.

Fix ideas:
- Register the developer and `app.memoria` with the release key in Google's console before the worldwide start. The user's call: it ties the app to a verified identity at Google.
- Say in the release notes and README that Play Protect may warn, and how to check the APK (signing certificate SHA-256).
- Install with a `PackageInstaller` session instead of an `ACTION_VIEW` intent, so no "Open with" chooser shows.

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

## Library cover rows show another row item's photo while indexing

Seen 2026-10-28 during a scan: the Categories row showed Pets on a meme, Memes on a document, Documents on a cat. Once the scan ends, a fresh open is right.

Steps:
1. Start a scan with many new photos, open Library.
2. As counts grow, the categories change places in the row (most photos first).
3. The names move, the photos stay in their old slots.

Cause: `Thumb` (`Photos.kt`) keeps its picture when its `tile` changes. `produceState` creates the state once; the `tile.id` key only restarts the loader, which sees the old picture in `value` and stops. The Library rows (`CategoryRow`, `PlaceRow`, the Albums row) draw their tiles by position with no key, so a slot that gets a new item keeps the old photo. Lazy grids with item keys are fine.

Fix ideas:
- In `Thumb`, `key(tile.id) { produceState(...) }`, so a new tile gets a new state. One line, fixes every caller.
- Test: put two bitmaps in `thumbs`, show a `CoverTile` with the first tile, switch to the second, check the pixels.

## A cat and a fish meme formed a person

Seen 2026-10-28 on another phone's release build: a "No name" person with 16 photos of a cat, plus a meme of a fish with a human-like face.

Cause: a person starts from 6+ camera photos with face quality 0.4+, and EDifFIQA-T rates sharp, front-facing cat faces as good faces (it rates how clear a face is, not whether it is human). Then every other face joins the closest person under the cut, and AdaFace puts non-human faces near each other, so the fish joined the cat.

Fix ideas (`docs/objects.md`):
- Drop a face that is not inside any person box of the object detector (YOLO26, planned for pets too). On the bench this drops every cat and loses about 0.5% of good real faces, mostly partly hidden second people.
- Softer: such a face can't start a person but can still join one, like quality under 0.4.

## Memoria Debug never auto-indexed new photos

Fixed 2026-10-22 on the phone (setup, not code), kept as a trap for pushing models by hand. After the debug/release split, the face models and the NPU builds were copied into `Android/data/app.memoria.debug/files/` with `cp` on the phone, so they belonged to `shell` and were `rw-rw----`. The app could see `models/` but not read it, so `modelsReady()` was false and the watcher never started a run.

Fix (done on the phone):
- `chmod 666` the model files, `chmod 2777` `models/` and `npu/` (so the app can also write NPU builds), `chmod 666` the files in `npu/`.
- Keep the model files' dates (`cp -p` or `touch -r`): an NPU build's name holds the model's size and date, so a new date means a rebuild, and a rebuild into a folder the app cannot write fails.
- A fresh install also re-cuts every face picture once (`recutThumbs`, no `recutTo` pref yet) before it indexes anything, so its first run takes minutes.

# Known issues and bad patterns

Found while reading the code, not seen on the phone yet. No steps, just the risk.

- **Stopped index leaves new faces out.** `IndexWorker` skips the last `savePeople()` when stopped, so faces added since the last regroup keep `personId = null` and are hidden in People until the next run.
- **Fixing a person's main face moves faces answered against it.** Answers point at the person's main face. If the user moves or removes that main face, after a regroup the faces with a yes for that person follow it. Fix: answer against a few starting faces, or move the answers to the new main face.
- **Small faces drop out.** Faces under 40 px never join a person and are also missing from the People screen (`People.kt` `groupFaces`). Intended, but they are silently gone, not "unsure".
- **Global mutable state.** `lastTimeline` and `thumbs` are top-level (`Photos.kt`); the Photos tab and the viewer both depend on them silently.
- **Bitmap churn.** `detect` makes new full-size bitmaps per photo and never recycles (`Faces.kt`). Adds to load cost and heat.
- **`groupFaces` holds an n×n float matrix in memory** (`People.kt`). Time is O(n²) since the NN-chain (2026-10-17), but memory is 17 MB at today's 2052 starting faces and ~400 MB at 10k. Store half of it (or float16) before libraries get that big.
- **Dead `photo.path` column.** `PhotoRow.path` (`Index.kt`) is written on insert but no query reads it. Remove it when the schema is next touched (needs a migration).

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
