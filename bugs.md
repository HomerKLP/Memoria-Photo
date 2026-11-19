# Bugs

Style: one-line description, steps to reproduce, fix ideas. Code paths only when they help. Open bugs in full; a fixed bug becomes one line under Fixed at the bottom (the full write-up stays in git history).

## VideoPlayTest flakes on the 10-bit HEVC clip

`make layout` on the API 37 emulator once failed `plays_asItsRowSays[videos/video_hevc10.mp4]` with `ERROR_CODE_FAILED_RUNTIME_CHECK` (1004); the rerun of `VideoPlayTest` was 11/11 green. The row (`plays or card`) allows only 4000-4999 as "the decoder refused it", and the emulator's 10-bit HEVC path sometimes reports 1004 instead.

Fix idea: for `plays or card`, accept any error whose code is not a file/container error, or treat this row as `card` on an emulator.

## Regroup reads every face vector on each save

Each save (`regroup`, `Regroup.kt`) reads `faceVecs()`, every face with its vector, in one transaction, so a rename waits for it. Before the row-write fix (2026-11-18) a save with nothing new took 613-824 ms on the phone (debug): grouping 265-372, the row rewrite ~350-450. After it: 313-539 ms, grouping 201-395, so the read and the person rows are now 112-144 ms.

Fix idea: store each person's vector sum so the linkage does not need every vector; the stored sums were dropped on 2026-11-17.

## Categories shows a blank page with no categories

Seen on an emulator with no tagger model: the bar says "0 categories" and the page is empty, where Places shows its "No places yet" state. Fix idea: an empty state like Places (design first).

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
- A mirrored EXIF (2, 4) keeps the preview's shape, so `sameShape` does not catch it, and a preview at least tile / 1.2 wide still shows as stored (open, 2026-11-04).

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

## To check on the test phone: grid speed after the HEIC fix

The grid now decodes every turned or mirrored HEIC itself (about 70% of iPhone photos) instead of using MediaStore's thumb. On the test phone, release build: `make release` (the user runs it), pause the index, `make perf`. JPEG must not get slower than the 2026-11-04 numbers (0% janky, 0% blank tiles on a cold fling); HEIC may. The JPEG path did not change (`photoTurn` gives 1 with no file read), so a drop there is a new bug. Not done yet.

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

## HDR video lights up slower and looks dimmer than in the phone's player
- Seen 2026-11-15, same HLG clip: both reach the same panel peak (654 nits, SurfaceFlinger `displayBrightnessNits`), but the phone's player gets there in ~0.5 s, ours in ~4.5 s, and the user sees the phone's player brighter.
- Why: its video goes to the screen as its own HLG layer (`SurfaceView`), so the phone's HDR / Dolby Vision path maps it. Ours is drawn by the app into its window (8-bit, at most 5x), and the phone ramps that kind of HDR slowly.
- Fix idea: the SurfaceView swap (HDR step 2 in `decisions.md`), which also fixes the judder below.

## Video judders a little when the screen runs at 120 Hz
- Seen 2026-11-15 on the test phone: after a touch (and during a 2x hold) the screen runs at 120 Hz, and a 30 fps clip shows frames 24/33/41 ms apart instead of 33 (2x: 8/16/24 instead of 16). At 60 Hz it is even.
- Why: a `TextureView` shows a frame as soon as ExoPlayer hands it over (up to 50 ms early, on its 10 ms work loop) and ignores the frame's time; a `SurfaceView` waits for it.
- Fix ideas: the SurfaceView swap (HDR step 2 in `decisions.md`); or Media3's `setEarlySchedulingThresholdUs` with dynamic scheduling (experimental).
- Check: `adb shell dumpsys gfxinfo app.memoria.debug framestats` while a clip plays, gaps between IntendedVsync.

## 2x hold takes about half a second to reach full speed
- Seen 2026-11-17, the price of the pause/play fix below: `speed 2x at 23329 ms` → `speed 1x at 25842 ms`, so 2513 ms of picture in 1594 ms of finger (1.6x): about 0.7 s of the hold ran at 1x. On the 11 s clip: 1564 ms in 1111 ms.
- Why: without `setEnableAudioTrackPlaybackParams` ExoPlayer drains the buffered sound through Sonic before the new speed applies. The flag skipped the drain but froze the clock after a pause (Fixed, 2026-11-17).
- Fix idea: wait for the SurfaceView swap (HDR step 2) and see if the drain still matters; or set the speed on the AudioTrack only while a finger is down, if media3 ever offers it per speed.
- Tried 2026-11-17: `1.12.0-beta01` (the newest, one step past the stable 1.11.1) still freezes with `setEnableAudioTrackPlaybackParams(true)` (+63, +29, +27 ms per 0.5 s of play after the second pause). So a media3 bump is not the way; the comment in `Viewer.kt` says so.

# Known issues and bad patterns

Found while reading the code, not seen on the phone yet. No steps, just the risk.

- **Stopped index leaves new faces out.** `IndexWorker` skips the last `savePeople()` when stopped, so faces added since the last regroup keep `personId = null` and are hidden in People until the next run.
- **Fixing a person's main face moves faces answered against it.** Answers point at the person's main face. If the user moves or removes that main face, after a regroup the faces with a yes for that person follow it. Fix: answer against a few starting faces, or move the answers to the new main face.
- **Small faces drop out.** Faces under 40 px never join a person and are also missing from the People screen (`People.kt` `groupFaces`). Intended, but they are silently gone, not "unsure".
- **Global mutable state.** `lastTimeline` and `thumbs` are top-level (`Photos.kt`); the Photos tab and the viewer both depend on them silently.
- **Index bitmaps wait for GC.** The full decode (`loadPhoto`, twice when turned) and `Letterbox` are never recycled. Measured 2026-11-19 (Dev tools > Run on NPU, 132 full decodes, debug): native heap 120-250 MB, one peak 354, back to 77 after; the GC keeps up and speed is the same. If a low-RAM phone runs out: `image.recycle()` after the faces loop in `IndexWorker` and recycle the source in `turnedBy`.
- **`averageLinkage` holds an n×n float matrix in memory** (`Groups.kt`). Since 2026-11-17 a save links people plus loose faces (39 + 471 on the DB copy, ~1 MB), but a full regroup (a `GROUPING_VERSION` bump) still links every starting face: 17 MB at 2052 faces, ~400 MB at 10k. Store half of it (or float16) before libraries get that big.
- **Flaky `NavigationTest.search_categoryWordHit_backReturnsToSearchWithTheText`.** Failed once on the emulator (2026-10-31) with Compose's "Detected multithreaded access to SnapshotStateObserver", no app frames in the trace; the class passed 13/13 on a rerun. If it shows again, look for a draw started off the main thread.
- **Dead `photo.path` column.** `PhotoRow.path` (`Index.kt`) is written on insert but no query reads it. Remove it when the schema is next touched (needs a migration).
- **The face and thumb caches are never trimmed.** `thumbs` and `faceBitmaps` (`Thumbs.kt`, `FaceViews.kt`) have no `onTrimMemory`. Measured 2026-11-19 (debug, 40 flings, then Home): the app keeps ~85 MB native + ~140 MB graphics, and Android sent no trim while it was the previous app. No harm seen on the 12 GB test phone; trimming would cost blank tiles on return.

# Fixed
- 2026-11-18 Regroup rewrote every face row on each save (clear every `personId` and `nearSure`, then write every person's faces): ~350-450 ms of a save with nothing new. It writes only the faces whose person or flag changed (`faceWrites`, `RegroupTest`), and `nearSure` is part of the diff since the full clear was its only reset. On the phone (debug, 2026-11-18): three saves with nothing new 518, 539 and 313 ms (grouping 374, 395, 201), so the rest fell from ~350-450 ms to 112-144; 47 of 47 people kept their ids, and the pulled DB had no face linked to a missing person and every main face with its person.
- 2026-11-15 Viewer: opened from a screen over the Photos tab (Search > Videos), a close after a swipe flew to a thin strip at the left edge or faded. Both grids wrote the same id in the global `tileSpots`, and the hidden tab's tile could win or delete the visible one. The tab's tiles now write only while the tab is on top, and a tile removes only its own entry.
- 2026-11-15 Player: a 2x hold started about 1 s late and froze the picture 4 times (60-75 ms): ExoPlayer changed the sound's speed only after its 0.5 s of buffered sound played out. The system now changes the speed (`setEnableAudioTrackPlaybackParams`), so 2x starts at once.
- 2026-11-15 Player: HDR clips (HLG, PQ, Dolby Vision) looked dull: a `TextureView` shows HDR only in an HDR window. The viewer sets `COLOR_MODE_HDR` while the player holds an HDR clip, so two HDR clips in a row never dim in between. The phone fades the brightness by itself (3 to 5 s), so the app does not.
- 2026-11-15 iPhone HEIC selfies showed upside down in the viewer and sideways in the grid: `ImageDecoder` applies `irot` and drops `imir`. Every decode (grid, viewer, index) reads both boxes, hides them and turns once (`plainSource`); faces found before the fix keep their row (`matchFaces`).
- 2026-11-13 Search built its own copy of everything on every open (a MediaStore scan, every tagged face, tag scoring twice). It reads Library's snapshot now, and `libraryData` scores the tags once.
- 2026-11-13 Viewer: a tap hid the bars at once, but the status bar stayed for over a second. The app left the system bars on Android's default behavior, which fades them out over 1.5 s; the immersive behavior (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) slides them out in 340 ms.
- 2026-11-13 Player: a drag on the seek bar moved only the knob; the video jumped on the lift. It seeks as the finger moves now, in Media3's scrubbing mode.
- 2026-11-13 Player: a swipe dragged past the middle and back left the video as a still with its sound running (the `ready` flag stayed off). The video now shows on the page whose file the player holds (`prepared`), and moves with it.
- 2026-11-13 Player: every video page got the playing video's shape, so a portrait clip next to a landscape one slid in small, then jumped. A neighbour keeps its own poster's shape.
- 2026-11-13 Player: closing a video, the picture swapped to another frame at the start and again on landing (the tile shows MediaStore's middle frame). The live frame shrinks now (paused), and the tile's picture fades in over the second half of the way.
- 2026-11-13 Player: the poster was "any" frame (`-1`), not the first, so the picture changed when playback started. It is frame 0 now.
- 2026-11-13 Player: the controls never faded while playing; the mockup says 2 s after the last touch. They fade now, and stay while paused, with More or the details open.
- 2026-11-13 Player: unplugging headphones kept the sound going on the speaker. `setHandleAudioBecomingNoisy`.
- 2026-11-13 Player: the start of every swipe on a video counted as a 2x hold for one frame (pill flash, speed blip): a cancelled wait looked like a timeout.
- 2026-11-13 Player: the clock read the player 4 times a second: the knob jumped back after a seek, and short clips moved in big steps. It reads every ~3 px of the line now, and a seek moves the knob at once; the bottom line is drawn from the clock without recomposing the viewer.
- 2026-11-13 Player: the progress line showed while the page moved (open, close, pull). Only with the bars hidden and the page still now.
- 2026-11-13 Player: mid-swipe the bar showed the last clip's time and length. The clock follows the player only while it holds the page's video.
- 2026-11-13 Player: the "cannot play" card dimmed the top bar, missed "MP4 · 48 MB" and used Material's button. The bars sit over the scrim, the facts line is there, the button is `PillButton`.
- 2026-11-13 Player: the seek line was 1.5 dp (mockup: 4) and the knob had no shadow.
- 2026-11-12 Player: the video stayed full screen over the grid while the viewer opened or closed, and over the details panel. The fix assumed a `TextureView` ignores the page's motion; it does not (Compose draws it inside its own layers), and the 2026-11-13 review put the video back on the moving page (`videoPage_pictureMovesWithThePage`).
- 2026-11-12 Player: the lift that ends a 2x hold also counted as a tap, so the bars slid open after every hold.
- 2026-11-12 Player: the page was sized by the tile's MediaStore ratio (0 for some files) until the player reported, so the box jumped as the video started. The poster's own shape is the fallback now.
- 2026-11-12 Player: the screen could sleep under a playing video. `keepScreenOn` while it plays.
- 2026-11-12 Viewer: the bars took their padding from the visible system bars, so hiding them on a tap moved the icons by the status bar height while they faded, and so did the grid under them. The height now comes from the bars even while hidden (`*IgnoringVisibility`).
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
- 2026-10-27 Opening a photo: the grow animation skipped or broke. It is a spring from rest now, and the grid stays put. The mature open landed 2026-11-03 (`decisions.md`).
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
- 2026-11-06 Library opened slowly on the first open after launch: the load ran inside `LibraryScreen`, so the tab waited for it and showed grey tiles. `libraryData` loads once now and `preloadLibrary` warms it at launch, so the first open starts from data.
- 2026-11-06 Video GPS read as "no GPS" for every video. Android hides the location behind the plain video URI; `MediaStore.setRequireOriginal` in `videoGps()`, as `Details.kt` already does.
- 2026-11-13 Photos lost the open and close animation, the drag-down and the details square: the page only counted as current when the video player held its file, and a photo never sets that flag. `pageIsCurrent` leaves the player out for photos (later dropped: the page in view takes the motion, the player only decides where the video shows). Checked on the phone: the photo grows out of its tile and shrinks back.
- 2026-11-13 A nudged video page restarted its video from 0 when the pager snapped back: the settle step re-prepared the same file. `prepared` remembers the tile the player holds, so the same video is never prepared twice.
- 2026-11-15 People & pets: the first open after launch was empty while its list loaded, then 28 covers came one by one (all shown 0.2-0.37 s after the tap). `preloadPeople` warms the list and the first 60 covers at app start, after `preloadLibrary`; the first open now loads nothing, like the second (3 cold starts on the phone, debug).
- 2026-11-17 Regroup linked every starting face again on each save: an n×n table (2013 faces, 16 MB, 2-3 s on debug) that would run out of memory around 4-8k faces. Saved people now keep their faces and the linkage goes on from them (`groupFaces`, `averageLinkage` with group sums); a new `GROUPING_VERSION` regroups everyone once. On the phone (debug): the one-time full save 2313 ms (grouping 1622), then 613-824 ms (grouping 265-372); all 46 people kept their ids, names and hidden flag, no old face moved. Reading and rewriting every face row (~350-450 ms) is now the bigger part.
- 2026-11-17 Player: two pause/play taps froze the clock (sound gone, picture at ~1/3 speed), and 10 s later the "can't play here" card. `setEnableAudioTrackPlaybackParams` made the AudioTrack stop consuming after a pause while media3 kept it as its clock, until `StuckPlayerDetector` threw `ERROR_CODE_TIMEOUT`. The flag is gone and ExoPlayer changes the sound's speed itself again. On the phone (debug): 5 pause/play cycles on a 1080p and a 4K HEVC clip advance 1 s per second, the 4K clip played to its end with 0 dropped frames and 0 audio underruns; seek, 2x hold, swipe to a photo and back, and close checked too. The 2x hold starts again about 0.5 s late (open bug above).
- 2026-11-18 Library's snapshot loaded twice at once: at start (the warm-up and the tab) and on every Back from Search, Places or Categories (the leaving screen and Library asked together). `sharedValue` now keeps one load per stamp in an app scope (`SharedCacheTest`); emulator: Back went from 2 `library load` lines to 1. Also from the 2026-11-18 review: Places stayed stale after a delete in a town while indexing was paused (select-mode edits now bump the global `viewerEdits`, which is in the stamp); Videos went back to its own `albums()` scan, since an edit there rebuilt the whole snapshot; Help recognize and Merge with read only people's faces (`personFaceVecs`); the Place screen reads only its town's tags (`tagsInTown`).
