# Bugs

Style: one-line description, steps to reproduce, fix ideas. Code paths only when they help.

## Photos tab stutters on first load

First time the photos appear the tab is rough; smooth after that. Cause: the
first build runs the timeline query, grid measure and one thumbnail decode per
visible tile all at once (`Photos.kt`).

Measured 2026-10-16 (cold start, `dumpsys gfxinfo`): 5 frames in 4 s, all janky, median 109 ms, slow UI thread (not GPU, not bitmap upload). After `cmd package compile -m speed`: median 57-73 ms. So about half is the debug build running cold code.

Steps:
1. Open the app, go to Photos.
2. Scroll right after the grid shows.

Fix ideas:
- Don't measure the grid before the first page of thumbnails is ready.
- Bounded thumbnail pool (small semaphore), not one job per visible tile.

2026-10-16: `Thumb` now loads at most 4 thumbnails at once and cancels a load when its tile leaves the screen. Fling test (8 flings, `dumpsys gfxinfo`), janky frames / 90th percentile: debug cold 29% / 61 ms, debug AOT cold 16% / 28 ms, debug AOT warm 1% / 10 ms, release AOT cold 0% / 6 ms. The rest is the debug build; judge smoothness on release only.
- Warm visible thumbnails right after `timeline()`.

## Photos: tapping the tab to scroll back to top stutters

Tap the Photos tab while already on it and the grid animates back to the top. The
animation is janky when you are scrolled well down.

Steps:
1. Photos, scroll well down.
2. Tap the Photos tab again.

Root cause (2026-10-18, read in the Compose source, then measured): the tab ran
`photosGrid.animateScrollToItem(0)`. That is already a real scroll (it teleports over far
items, `numOfItemsToTeleport = 100 * columns`, so it does not compose every tile). The
cost is per frame: it rebuilds the on-screen tiles and `Thumb` starts a decode for each
tile flying by; and its duration grows with the distance (spring chunks, not capped). On
a full library that is a long, load-heavy ride.

A/B on the emulator (software GPU, so judge the *difference*; 8 swipes down, then tap,
`dumpsys gfxinfo`, 3 runs):
- `animateScrollToItem(0)`: 75-82 frames, 25-41% janky, p50 38-48 ms.
- Now: 47-54 frames, 26-48% janky on the emulator, but 4.5-9% janky, p90 11-12 ms on the
  phone's release build (baseline fling 1.1%). The emulator's software GPU, not the
  animation, is what makes its numbers high.

2026-10-18: built Immich-style (one capped `animateScrollBy` tween, distance measured with
a `scrollBy` there and back in one frame, `Thumb` decoding paused while it runs — see
`decisions.md`). `jumpToTop` and `ScrollToTopTest` are gone. `make check` and all
androidTests green; release checked on the emulator (real scroll at 10x) and the phone.

2026-10-18: NOT fixed. The user still found it sloppy: from the middle of the real library
it stuttered, then snapped the rest of the way up.

Fixed 2026-10-19. Real cause, measured on the phone's release build from the deepest point
(~6400 items, 730 000 px): the "distance to the top" measure (`scrollBy(-1e9f)`) composes every
row it passes, so it froze the app for 1070 ms (+139 ms to scroll back), and the 350 ms tween
then ran as 3 frames of 500-800 ms (2.4 s in all). `gfxinfo` showed only ~1% janky, so it hid
this. Now, if the grid is more than three screens down, it jumps to three screens below the top
first (`scrollToItem`), then measures and animates only that part: measure 8 ms, 31 frames of
~11 ms, done in 355 ms.

Fix ideas:
- Done: one continuous scroll, capped at `tabScrollToTopMs`, no decode for tiles that only
  fly by, and a jump to three screens below the top first when far down.

## Library tab feels laggy

User report, release build. Each Library and People open read all 3555 face pictures and vectors (~30 MB, 15 `CursorWindow: Failed NO_MEMORY` per open). Fixed 2026-10-16: `FaceImage` reads its own picture by face id; 5 overflows per open are left (face vectors).

Measured after the fix (release): 1 dropped frame per tab switch, both tabs (building the new tab's first frame); Library scroll 1-2% janky. Not sure yet what the user feels.

Steps:
1. Switch Photos -> Library and scroll at once.

2026-10-18: the count no longer scans MediaStore (`libraryPhotoCount`), and Details no longer loads every face vector (`photoFaces` + vectorless `taggedFaces`). Library and People still load the full faces: the Merge with sheet ranks by vector, so the vectors are still needed there.

Fix ideas:
- Load faces without vectors for Library and People (only grouping needs them).

2026-10-18: fixed. Library and People read `taggedFaces()` (`LightFace`, no 512-float vector) and map with `toVec()`; the vector was never read on screen. Merge with is the only consumer (it ranks by the mean face), so `MergeSheet` loads `faceVecs()` once when its sheet opens and passes the means to `MergeContent` (empty in tests/previews, so they keep the old path). The People summary count now comes from a new `IndexDao.faceTotal()` (the old `faces.size` was the whole `faceVecs()` list). Emulator, debug, ~1.8k faces: People load ~100 -> ~50 ms and the ~3.6 MB of vectors per open are gone; Library's own number barely moved because albums/places dominate it. `Search.kt` still loads `faceVecs()` for its named-people list (same fix, not done). `make check`, `make layout` green.

## Viewer opens blurry, then sharpens

The photo is blurry for a moment, then becomes full resolution. Cause:
`ViewerPage` shows the small grid thumbnail first, then swaps in the big decode
(`Viewer.kt`).

Steps:
1. Open any photo in the viewer.

Fix ideas:
- Decode a screen-size image first (fast), then upgrade; the swap is less visible.
- Pre-decode the tapped photo and its neighbours before opening.
- Decode the real display size, not a fixed 2560.
- Short fade from thumbnail to full photo; now it swaps in one frame.
- The two neighbour pages decode at the same time as the open one; decode the open one first.

2026-10-18: the swap now fades in 150 ms (`Crossfade`). 2026-10-18: worse on old camera photos (see "Grid: blurry tiles"). The viewer stretches the 160 px preview to full width (~7x), and it has another aspect (160 x 112 vs 3:2), so the photo jumps in size when the real one lands.

## Grid: blurry tiles on old camera photos

Some tiles are blurry. MediaStore's cached thumbnail (`Pictures/.thumbnails/<id>.jpg`) is the camera's embedded EXIF preview, only 160 x 112. `loadThumbnail` returns it as is and the tile scales it up ~2.4x. On the test phone: 590 of 3930 thumbnails are 200 px or less, almost all from `Pictures/Takeout/`. Normal ones are ~600 px.

Steps:
1. Scroll Photos to an old camera photo from Takeout (2010s DSLR).
2. Compare the tile with the photo in the viewer.

Fix ideas:
- In `Thumb`, when the thumbnail is smaller than the tile, decode the photo itself at tile size (sampled JPEG decode) and cache that.

2026-10-18: fixed. `Thumb` decodes the photo at 480 px when the thumbnail's short side is under 200 px (memory cache only). Checked on the phone: the tile is sharp, and the viewer opens at the right size.

## People: blurry face pictures

Face pictures were saved at 160 px (with margin) while indexing, but People shows them at up to 96 dp = 288 px on the test phone (3x), so they were stretched ~1.8x.

Steps:
1. Library > People. Look at a Named face up close.

2026-10-18: fixed. `faceThumb` saves 288 px; the index run cuts the old pictures again once (`recutThumbs`, 1660 photos in 221 s, face ids kept). Checked on the phone: hair and eyes are sharp. Thumbs 12.7 -> 24.2 MB, DB 22 -> 35 MB.

## Viewer: screen-sized photos do not fill the screen

Fixed 2026-10-15 (photo now fills the screen, bars float on top).

Steps:
1. Open a screenshot in the viewer.

Fix ideas:
- Done: photo fills the screen, bars float on top.

## Viewer: zoomed photo drawn over the next photo

Fixed 2026-10-15 (Compose pager pages do not clip, so the scaled photo spilled sideways; added `clipToBounds`. Pan also clamped to the screen size instead of the photo's real size, so a 3:4 photo could be dragged off-center).

Steps:
1. Open a photo in the viewer, pinch to zoom.

Fix ideas:
- Done: clip the page, and clamp pan to the fitted photo size.

## Viewer: panning while zoomed crawls

Fixed 2026-10-15 (modifier order in `Viewer.kt`: `pointerInput` sat *inside* `graphicsLayer`, so Compose handed out touch deltas already divided by the zoom scale. At 6x a 300 px finger move arrived as 50 px.)

Measured on the phone with `adb` swipes plus screenshot cross-correlation: 600 px finger move -> 100 px image at 6x before, ~590 px after (1:1). Frame times were never the problem: 1.7% janky at 6x, same as the 1x baseline.

Steps:
1. Open a photo, zoom in, drag with one finger.
2. The more you zoom, the slower the photo moves.

Fix ideas:
- Done: `pointerInput` must come **before** `graphicsLayer` in the chain.
- Keep state reads of zoom/pan inside the `graphicsLayer {}` block, or every touch event recomposes the page.

## Favorites: back from the viewer jumps to the top

Fixed 2026-10-16 (the viewer replaces the whole screen tree, so the Favorites grid lost its scroll. Photos tab kept it because it sits in `tabs.SaveableStateProvider`; Favorites now does too, and its state is dropped on back so a new visit starts at the top).

Steps:
1. Library > Favorites, scroll down, open a photo, go back.

Fix ideas:
- Done: `SaveableStateProvider("favorites")` in `App`, `removeState` on back.

## People: back from the viewer lost the open person

Fixed 2026-10-16 (the viewer replaces the whole screen tree. The People screen was not in a `tabs.SaveableStateProvider`, so its saved `openId` was dropped and closing the viewer landed on the People list instead of the person).

Steps:
1. Library > People > open a person > tap a photo > go back.

Fix ideas:
- Done: `SaveableStateProvider("people")` in `App`, `removeState` on back (so a new visit from Library starts at the list).

## People: back from a person lost the list scroll

Fixed 2026-10-16 (the list grid and the person screen share one composable, and the branch swap disposed the grid's inner `rememberLazyGridState`. Hoisted the state to the `PeopleContent` level, and the `SaveableStateProvider` above it keeps it across the viewer too).

Steps:
1. Library > People, scroll to "Needs a name", open a person, go back.

Fix ideas:
- Done: hold `rememberLazyGridState()` above the list/person branch.

## Library: People row twitches while scrolling

Fixed 2026-10-16 (a `LazyRow` is as tall as its visible items; unnamed people had no name line, so the row shrank 72 px when only they were on screen and everything below jumped. Unnamed people now keep an empty name line).

Steps:
1. Library, scroll the People row right, then left.

Fix ideas:
- Done: `Text(p.name.orEmpty(), minLines = 1)` in `PeopleRow`.

## Face pictures stuck on the old face

Fixed 2026-10-17 (`FaceImage` loaded only when its state was empty, and `produceState` keeps the old value when the face changes. Same slot, new face: the old picture stayed. Seen in Help recognize; the review card stack and the person's Faces row had it too).

Steps:
1. Library > Help recognize > Review, tap Skip. The big face circle keeps the first face while the box moves.

Fix ideas:
- Done: `FaceImage` resets to the cache for the new face first.

## Person: merged photos show only after a tab reload

Fixed 2026-10-17 (the photo grid loaded once per person id; a merge keeps the id and only adds faces, so the grid kept the old photos. Now it reloads when the person's photo list changes).

Steps:
1. Library > People > a person > Merge with > pick someone > Merge.
2. The subtitle count grows, but the merged person's photos are missing until the tab is opened again.

## Help recognize card always said "3 faces to check"

Fixed 2026-10-17 (callers passed `unsure.take(3)` and the card counted that list). The card now gets the whole list and takes 3 faces itself.

## Unsure faces card: note wraps for unnamed people

Fixed 2026-10-17 (unnamed people now say "8 to check"). "8 may be this person" wraps to 2 lines next to a 3-face stack, so that person's card is taller than the mockup's (no jump, just uneven). Named people fit ("37 may be Alex").

Steps:
1. Library > People > an unnamed person with 3+ unsure faces.

Fix ideas:
- Done: shorter text for unnamed people ("8 to check").

## A removed sharp face was sure in its new person

Fixed 2026-10-17 (sure was quality 0.4+ or a yes, so a sharp face moved off a person by Remove tag landed in the next closest person as sure and was never asked). Now a face with any no is sure only after a yes.

Steps:
1. Viewer > swipe up > tap a sharp face > Remove tag. Dev tools > Index all photos.
2. The face is in another person, but no Help recognize card counts it.

## "1 photos", "1 faces to check"

Fixed 2026-10-17 (one-face people from New person made it common). Person subtitle, both sheets and the review card.

## Layout jumps on load and on state change

Fixed 2026-10-16 (found by an audit after the People row twitch, rule now in `AGENTS.md`).
- Library, cold start: the People row showed "No people yet" while loading, then faces; the Help recognize card popped in. Now grey placeholder circles and an invisible card hold the space.
- Index progress: the text could wrap to 2 lines mid-run (now one line), and Pause / Resume had different widths (now 88 dp min).
- Viewer details sheet: opened with the date only, then grew. Now opens once the details are loaded.

## Details sheet says "GMT-" for zones west of GMT

Fixed 2026-10-17 (only the sign was unparenthesized, so `if` swallowed the hours and minutes;
`DetailsZoneTest` caught it, the first test in `app/src/test/`). The fix is the line below.

`zoneLabel` (`Details.kt`) had read `"GMT" + if (seconds < 0) "-" else "+" + hours ...`.
Kotlin's `if` takes the whole rest of the line, so the hours and minutes only landed in
the `else` branch. A negative offset printed `"GMT-"` alone (`-18000 -> "GMT-"`, `18000 -> "GMT+5"`).
Not seen on the test device (which is east of GMT).

Fix:
- `"GMT" + (if (seconds < 0) "-" else "+") + abs(seconds / 3600) + ...`.

## Viewer closes when the phone rotates

Fixed 2026-10-17 (portrait lock in the manifest). Still open on large screens, where Android 16 ignores the lock (second fix idea below). Also still open for every other Activity restart: dark mode, wallpaper colours, font size, language, process death (found in the 2026-10-21 review, not checked on the phone).

`App` keeps the open photo id in `rememberSaveable` but the tile list in plain
`remember` (`MainActivity.kt`), and the manifest locks no orientation. On rotate the
Activity is recreated: `viewTiles` is empty, `viewing` is restored, and `Viewer` sees
an empty list and closes (`Viewer.kt`).

Steps:
1. Open any photo in the viewer, rotate the phone.

Fix ideas:
- Lock the app to portrait (`android:screenOrientation="portrait"`): no rotate, no
  recreate. The user's call (2026-10-17): the view stays static. Note Android 16
  ignores the lock on large screens (>600 dp), so the saveable fix below is still
  needed there.
- Or make `viewTiles` saveable, or reload the tiles from the timeline by start id.

## Slider knob is smaller than the mockup

Fixed 2026-10-17 (knob is now a 24 dp white circle with an 18 dp accent dot inside and the mockup's drop shadow, per the fix idea. `Ui.kt` `MonthSlider`, `MonthSliderPreview` baseline, `monthSlider_appearing_keepsContentPut`).

The timeline month slider's knob shows a 12 dp orange dot inside an 18 dp white circle. The mockup's knob is an 18px orange dot with a 3px white ring *outside* it (24px total), so the orange dot should be 18 dp and the whole knob 24 dp (`Ui.kt` `MonthSlider`).

Steps:
1. Photos tab, scroll. Compare the knob with `docs/design.html` > "Timeline month slider".

Fix ideas:
- `padding(end = 1.dp).size(24.dp).background(White).padding(3.dp).background(accent)`, offset `-12.dp`. Also missing: the mockup's knob drop shadow.

## Timeline month header is not sticky

Fixed 2026-10-17 (the timeline grid now emits month headings with `stickyHeader`, keyed by the same `m<month>` key and with the surface background so photos scroll under it, like Videos. Day headings stay normal items. `Photos.kt`).

The mockup ("Sticky month header") and the Photos caption say the month name stays pinned while its photos pass under it. `stickyHeader` is used only in `Albums.kt` (Videos); `Photos.kt` draws the month as a normal grid item, so it scrolls away.

Steps:
1. Photos tab, scroll into the middle of a month. No month name is pinned at the top.

Fix ideas:
- Use `stickyHeader` in the timeline grid like `Albums.kt`, keeping the same text and background.

## Photos app bar has no search icon

Fixed 2026-10-17 (the wordmark row now has the mockup's magnifier on the right, like the Library gear; the button does nothing until search is built; mockup "Search" since 2026-10-17. `Photos.kt`).

The mockup's Photos app bar is the wordmark plus a magnifier top-right (`docs/design.html`, Photos figure). The app bar is only "Memoria Photo".

Steps:
1. Open the Photos tab. No search button on the right.

Fix ideas:
- Add the search `IconButton` to the app bar like the Library gear (search itself not designed yet).

## Viewer: system bars stay visible in the tapped state

Steps:
1. Open a photo, tap the photo to hide the bars.

The two app bars hide, but the Android status and nav bars stay. The caller
calls `insetsController.hide(WindowInsets.Type.systemBars())` (`Viewer.kt`),
which the OnePlus 13 (ColorOS) seems to ignore. So the mockup's "Viewer, tapped"
(nothing but the photo) is not reached on the phone.

Fix ideas:
- Immersive mode: `WindowCompat.setDecorFitsSystemWindows(window, false)` plus
  `WindowInsetsControllerCompat.hide(systemBars())` with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.
- Check on a stock-Pixel emulator to tell whether it is the OEM.

## Person: back from the viewer lands at the top of the photos

Fixed 2026-10-18 (the person's photos loaded with plain `produceState`, so after the viewer tore the tree down they restarted empty; the grid's restored scroll clamped onto the short frame and never came back. Now `cachedProduceState("person${id}", photoIds)` — `People.kt` `PersonDetail`. Same class as the Library-from-Trash fix above. Test `personDetail_backFromViewer_keepsGridScroll`).

Steps:
1. Library > People > open a person > scroll their photo grid down > tap a photo > go back.
2. The grid is at the top, not where it was.

Fix ideas:
- Done: cache the last photo list per person so the first frame back is full.

## Double-tap zoom always zooms to the center

Double-tapping a photo in the viewer zooms to 2.5x around the center of the
screen, not around the point the user tapped. On a photo with detail near an
edge (a face in a corner), the tapped area drifts away and the user must pan to
it.

Steps:
1. Open a photo in the viewer.
2. Double-tap near a corner. The zoom centers the middle of the photo.

2026-10-19: fixed. The double-tap offset was ignored (`animateZoomTo(target)`
only changed the zoom and clamped the old pan). Now `onDoubleTap` passes the tap
`offset`, and `animateZoomTo(target, focus)` slides the pan to
`zoomToFocusPan(fit, box, target, focus)` (a new pure function) so the tapped
point stays under the finger, clamped to the photo's pan room. Zooming back out
(target 1x) returns the pan to center. Zoom and pan now animate together on one
`tween` progress, so the focus stays put through the whole motion. Checked with
5 unit tests in `ViewerFitTest` (center, right, clamped near an edge, zoom-out,
and that the tapped point lands back under the finger).

## Viewer: stutter when swiping to neighbours fast

Swiping to the next/previous photo in the viewer stutters, but only on a fast
swipe. A slow swipe feels fine.

Steps:
1. Open a photo in the viewer.
2. Swipe to a neighbour quickly, a few times in a row.

Fix ideas:
- The two neighbour pages decode at the same time as the open one; on a fast
  swipe the next page may not be ready and starts its decode during the
  animation. Decode ahead / keep a small window decoded.
- Check whether the page gets built during the fling (`Viewer.kt` `HorizontalPager`).

# Known issues and bad patterns

Found while reading the code, not seen on the phone yet. No steps, just the risk.

- **Stopped index loses new faces.** `IndexWorker` skips `savePeople()` when stopped, so faces added in that run keep `personId = null` and are hidden in People until a full run. Fix: regoup what is there on stop, or note it in the UI.
- **Person ids are not stable.** `savePeople` numbers people `1..N` by size each run; ids change when sizes change. Naming and per-person tuning will need a stable id.
- **Fixing a person's main face moves faces answered against it.** Answers point at the person's main face. If the user moves or removes that main face, after a regroup the faces with a yes for that person follow it. Fix: answer against a few starting faces, or move the answers to the new main face.
- **Small faces drop out.** Faces under 40 px never join a person and are also missing from the People screen (`People.kt` `groupFaces`). Intended, but they are silently gone, not "unsure".
- **Destructive migrations.** Fixed 2026-10-17: only v1-3 databases are wiped; a missing migration crashes on open, and `MigrationTest` fails first.
- **Global mutable state.** `lastTimeline` and `thumbs` are top-level (`Photos.kt`); the Photos tab and the viewer both depend on them silently.
- **Bitmap churn.** `detect` makes new full-size bitmaps per photo and never recycles (`Faces.kt`). Adds to load cost and heat.
- **Details loads every face vector.** Fixed 2026-10-18. `Details.kt` called `dao.faceVecs()` for one photo, then filtered by `photoId`. Now `IndexDao.photoFaces(id)` reads that photo's faces (a handful of rows) and `IndexDao.taggedFaces()` reads the saved people's faces **without** their 512-float vectors (`LightFace`, `People.kt`) for the Who is this? sheet. Details never matches by distance, so no vector is needed. Same "faces without vectors" idea as Library (2026-10-18: Library and People read `taggedFaces()` too; only Merge with loads `faceVecs()`, once, when its sheet opens — see the "Library tab feels laggy" entry).
- **`libraryPhotos(context).size` scans all of MediaStore.** Fixed 2026-10-18. `Library.kt`, `People.kt` and `MainActivity.kt` built a `MediaPhoto` per row just to count. Now `libraryPhotoCount(context)` (`Index.kt`) reads the row count only, with the skipped-album filter in the query. The full `libraryPhotos` list is still built for an index run only.
- **`groupFaces` holds an n×n float matrix in memory** (`People.kt`). Time is O(n²) since the NN-chain (2026-10-17), but memory is 17 MB at today's 2052 starting faces and ~400 MB at 10k. Store half of it (or float16) before libraries get that big.
- **Dead `photo.path` column.** `PhotoRow.path` (`Index.kt`) is written on insert but no query reads it. Remove it when the schema is next touched (needs a migration).

## Perf numbers (`make perf`)

Release build, baseline profile compiled, full library after a re-index (~4400 photos), median of 3. The phone runs at 120 Hz.

| 2026-10-18 | frames | janky % | p90 ms | p99 ms |
|---|---|---|---|---|
| Cold start: first frame 115 ms, timeline 154 ms | | | | |
| Photos, first scroll | 324 | 0.6 | 7 | 10 |
| Photos, scroll | 313 | 0.6 | 9 | 13 |
| Library, open + scroll | 150 | 1.3 | 7 | 24 |
| People, open + scroll | 105 | 1.0 | 5 | 13 |
| Viewer, open + 3 swipes | 165 | 1.8 | 5 | 24 |
| Details, open | 72 | 2.8 | 5 | 23 |

The first run, right after indexing (warm phone), read 2-4x more janky frames. Compare runs taken in the same state. These are frame times only; how long data takes to show on a screen is not measured yet.

| 2026-10-20 (3 columns) | frames | janky % | p90 ms | p99 ms |
|---|---|---|---|---|
| Cold start: first frame 113 ms, timeline 157 ms | | | | |
| Photos, first scroll | 338 | 0.6 | 6 | 11 |
| Photos, scroll | 319 | 0 | 6 | 8 |
| Library, open + scroll | 131 | 1.5 | 6 | 18 |
| People, open + scroll | 118 | 1.7 | 6 | 13 |
| Viewer, open + 3 swipes | 398 | 0.8 | 6 | 14 |
| Details, open | 83 | 1.2 | 5 | 13 |

| 2026-10-20, after the grid fix (load ahead, no fade, 128 MB cache) | frames | janky % | p90 ms | p99 ms |
|---|---|---|---|---|
| Cold start: first frame 109 ms, timeline 146 ms | | | | |
| Photos, first scroll | 339 | 0.6 | 5 | 9 |
| Photos, scroll | 314 | 0 | 5 | 7 |
| Library, open + scroll | 120 | 1.7 | 5 | 13 |
| People, open + scroll | 120 | 0 | 6 | 12 |
| Viewer, open + 3 swipes | 408 | 0.5 | 6 | 11 |
| Details, open | 83 | 1.2 | 5 | 13 |

Correction 2026-10-20: the tables above never had the baseline profile. `perf.py` sent `INSTALL_BASELINE_PROFILE`, but the receiver's action is `INSTALL_PROFILE` (result 0, nothing installed), so `compile -m speed-profile` used only what the app had recorded while running. Fixed in `perf.py` and `make release` (result 1, `status=speed-profile`). Pairs measured in the same session still compare fairly.

| 2026-10-20, grid + viewer fix, baseline profile really installed | frames | janky % | p90 ms | p99 ms |
|---|---|---|---|---|
| Cold start: first frame 123 ms, timeline 171 ms | | | | |
| Photos, first scroll | 337 | 0.6 | 5 | 16 |
| Photos, scroll | 311 | 0 | 6 | 7 |
| Library, open + scroll | 112 | 2.7 | 6 | 18 |
| People, open + scroll | 117 | 0.9 | 7 | 14 |
| Viewer, open + 3 swipes | 398 | 0.5 | 6 | 11 |
| Details, open | 83 | 1.2 | 5 | 15 |

Cold start is 14-25 ms slower than with the profile the app recorded during an hour of use, so the shipped baseline profile may be out of date (regenerate on the emulator, `:app:generateReleaseBaselineProfile`). The phone was hot after an hour of tests, so read small gaps as noise.

## Grid and viewer feel slow, though frame times are fine

Frame times are clean (table above), so the slowness is loading, not jank. Measured 2026-10-20 on the release build with perfetto and screencaps.

Steps:
1. Cold start, fling Photos fast: the rows coming in at the bottom are blank, up to 29% of the grid, for about 1 s.
2. Open a photo: it shows blurry, sharp only ~330 ms after the tap.

Causes found:
- **Viewer decode**: `ViewerPage` decodes 2560 px with `loadPhoto` (software bitmap, exact target size): 200-250 ms per photo. The render thread then uploads it (16 ms, a dropped frame when it turns sharp), and the two neighbour pages' uploads (6 ms each) drop 2-3 frames of the open animation.
- **Grid loads late**: a tile starts its thumbnail only once composed (about one row ahead), then fades in for 180 ms, so incoming rows are blank or faded.
- **Thumb cache is small**: `maxMemory / 8` = 32 MB, about 50-100 thumbs (3-5 screens). Bitmaps live outside the Java heap, so /8 is not the real limit. Scrolling back decodes and fades again.
- **Old-photo fallback**: the full-photo decode at 480 px (`Thumb`, `small`) takes 15-43 ms; ~11% of tiles, about half of all grid decode time, every launch.
- **No R8 in release**: no `buildTypes` block, 24.6 MB of dex, no mapping file. Compose expects R8.
- Not ours: the test phone has a screen-mirroring accessibility service on, so Compose builds accessibility data every frame (~1.3 ms per frame).
- Side note: `loadThumbnail(300x300)` returns 240x320 for 4:3 photos, stretched 1.5x into a 360 px tile.

Fix ideas: viewer decode with a power-of-two sample size near screen size and a hardware bitmap; grid `LazyLayoutCacheWindow` so tiles load ahead, shorter or no fade; a bigger thumb cache; sample-size decode for the fallback; R8 with keep rules for ONNX Runtime.

2026-10-20, grid part done: the Photos grid builds tiles one screen ahead (`LazyLayoutCacheWindow(aheadFraction = 1f)`, `MainActivity.kt`), the thumb fade is gone, the thumb cache is `maxMemory / 2` (128 MB). Same cold-start fling test, 3 runs: no blank tiles in any screencap (before: up to 29% of the grid for ~1 s). `make perf` the same or a bit better: Photos scroll p90 5 ms / p99 7 ms, cold start 109 / 146 ms. Memory, native heap after 15 / 45 flings and 5 s idle: old cache 96 / 314 / 52 MB, new 328 / 465 / 155 MB. Most of the peak is old bitmaps waiting for GC, in both builds. The fallback decode was not changed: `ImageDecoder` already uses the fast scaled JPEG decode for an exact target size; the time is reading the whole file. Viewer and R8 still open.

2026-10-20, viewer part done: `viewerPhoto` (`Viewer.kt`) halves the photo while its short side stays at least the screen's (`viewerSample`; 12 MP -> 2048x1536 instead of 2560x1920) and keeps the default allocator, so the bitmap is a hardware one, uploaded on Android's upload thread instead of the render thread. Closing the viewer reloads the screen under it only after Edit in… (favorite, trash and move bump `viewerEdits` themselves); every close used to reload the timeline and rebuild the grid (a 25-30 ms frame). Perfetto, release compiled with the profile, 3 runs: tap to sharp photo ~330 -> ~175 ms (decode 251 -> 135 ms), missed frames on open + 3 swipes + close 4 -> 1, worst swipe frame 13 -> 9 ms. Measure only after `cmd package compile -m speed-profile` (`make perf` does it): a fresh install runs uncompiled and every frame is ~2x slower. Not checked: zoom sharpness past ~1.5x (a bit lower than the old 2560 px). R8 still open.

## Empty album stays open after its last photo leaves

Moving or deleting the only photo of an album (album select, or the viewer opened from the album) leaves the album page open: no photos, title blank.

Steps:
1. Open an album with one photo.
2. Long press it, Move (or Delete), confirm.

Fix ideas: `albums(context, id)` returns nothing for an empty bucket, so `AlbumScreen` shows the loading state (title " "). Close the page (`onBack`) when a reload finds the album gone. Check Place and Person for the same.

2026-10-18: fixed, and the same check done on every set that can run empty. New `CloseWhenEmpty` (`Ui.kt`): a screen calls `onBack` once a load has finished and found nothing (null means still loading, so nothing closes on the first frame). Used by `AlbumScreen` (album gone), `PlaceScreen` (town gone), `VideosScreen` (no videos), and `PeopleScreen` (the open person left the list; only after it was seen once, so a stale cached list missing a just-created person does not close the screen on open). `cachedProduceState` no longer remembers an empty result — it is as short as no cache, and a stale empty value must not close a page that is only loading. Favorites already shows its "No favorites yet" hint, Trash its empty state, and the viewer already closes when its last photo leaves (trash or move). Behavior test `closeWhenEmpty_firesOnlyAfterALoadFindsNothing`; `make check` and `make layout` green.

## Old cover flashes on Library after Change cover

After picking a new cover for a person, Library's People row first shows the old face, then flips to the new one.

Steps:
1. Library > a person > Change cover, pick another face.
2. Go back to Library.

Fix ideas: Library starts from `cachedProduceState`'s last data (old cover), then reloads. Update the cached Library and People data when the cover changes, or preload before going back; a crossfade at least hides the flip.

2026-10-18: fixed. New `updateCached` (`Ui.kt`) replaces one cached value in place; `LibraryData` and `PeopleData` became data classes with a `withCover(personId, faceId)` helper, and Change cover calls it before `reload++` (`People.kt`). The Library tab is off composition while a person is open, so its cache now holds the new cover for its first frame back. Unit tests `CoverCacheTest` (cover mapping + the cache helper) and `make check` / `make layout` green.

## App crashes (and restarts) when a tile leaves the grid while the viewer opens

The app closes and reopens on its own. Cause (logcat, `AndroidRuntime`): `java.lang.IllegalArgumentException: layouts are not part of the same hierarchy` at `ViewerKt.ViewerPage$motion` (`Viewer.kt`), from the graphicsLayer that flies the photo out of its tile.

`motion()` calls `here.localBoundingBoxOf(spot, …)`: `here` is the viewer page, `spot` is the tile's saved coordinates (`tileSpots`). When the tile leaves the composition while the viewer opens (the grid follows the photo, an item is disposed), the two nodes no longer share a hierarchy and Compose throws.

Steps:
1. Open a photo, swipe through a few. Crash is a race, so not every time.

2026-10-18: fixed. `motion()` now guards the two `localBoundingBoxOf` calls (`Viewer.kt`, `bounds()` helper) and falls back to the no-tile fade path when the tile's node is gone: no fly-from-tile, no crash.

## Flaky layout test: library_pauseResume_progressBarKeepsLength

Failed once in a full `make layout` run (2026-10-18, no message), passed when run alone. Another session was driving the same emulator at the time, which may be the cause.

Steps: `make layout`; if it fails, rerun the one test with `-Pandroid.testInstrumentationRunnerArguments.class=app.memoria.LayoutJumpTest#library_pauseResume_progressBarKeepsLength`.

Fix ideas: run device tests only when no other session uses the emulator; if it fails again alone, wait for idle before reading the bar.

## Timeline month slider is taller than the mockup

Open again 2026-10-19. The 24 dp rail inset was built (`sliderPad`, `Ui.kt`), but the user did not
like the mockup change and it was reverted. The rail also ran through the pinned month heading.
Mockup now: `.slider` top 104 -> 150 px (under the heading), `z-index: 3` (a heading that scrolls
past no longer cuts the rail), bottom unchanged. Approved by the user 2026-10-19. Fixed in code the
same day (`Ui.kt`): `sliderPad` 10 dp, box ends 33 dp above the grid's bottom, starts 3 dp under the
pinned heading (`monthHeadingHeight`). Test: `monthSlider_rail_startsUnderPinnedHeading`. Checked on
the phone (Photos). Place and Album use it too (see `decisions.md`).

## Month slider knob stops above the rail's end at the bottom of a list

Fixed 2026-10-19. The knob follows the first month on screen. At the end of a list the last months
can be shorter than the screen, so the knob stayed a few ticks above the rail's bottom end (seen on
the phone, album "Restored"). Now it goes to the last month once the grid can't scroll forward
(`MonthSliderBox`, `Ui.kt`). Test: `monthSlider_atTheEnd_knobReachesTheRailBottom` (red before, green
after).

## Month slider on a date-grouped list other than the timeline

Fixed 2026-10-19 for Place: `MonthSliderBox` (`Ui.kt`) now holds the shared fade-in/out and drag
state, so a second screen gets the slider with no copy. Album and Person are built (2026-10-19): both
get month headings and the slider. Videos and Favorites are designed in the mockup, code not built; see `ROADMAP.md`.

## Month slider tick marks

Removed 2026-10-19 (the user did not like them): the slider is the rail and the knob only, in the mockup and in `Ui.kt`.

## Screenshot baselines depend on the recording machine's time zone

The `DetailsView` baseline shows the date and zone of the machine that recorded it (`Details.kt` uses `ZoneId.systemDefault()`). `make shot-check` fails on a machine in another zone.

Steps:
1. Run `TZ=UTC make shot-check`.
2. `DetailsView` differs from its baseline.

Fix ideas:
- Pin the zone in the preview (UTC), then `make screenshots`.
- Look for other previews that show a date or time.

## Back from an opened screen stutters (Library)

Going back from Albums, People, Places and the other screens opened from Library slides out with 2-5 slow frames.

Steps:
1. Release build, Library tab, tap a "See all".
2. Press Back. Opening is smooth, back is not.

Measured 2026-10-19 (release, `dumpsys gfxinfo`, 3 runs): open median 6 ms; back 90th percentile 32-42 ms, 2-5 janky frames of 18.

Cause: opening a screen drops the tab from composition, so Back builds Library again and reloads people, albums and places while the slide runs (`MainActivity.kt` `App`, `LibraryScreen`).

Fix ideas:
- Keep the tab composed under the opened screen: the tab is always the base, the opened screen is an overlay (`AnimatedContent` on the opened screen only). Block touches on the overlay and hide the base from TalkBack.
- Or skip the reload on return when the cached data is fresh.

Fixed 2026-10-21: the tab stays composed under an opened screen (`MainActivity.kt` `App`), so back only slides the screen away. Library reloads once you are back (`returns`). Release, 3 runs: See all back 0-1 janky of 16-17 frames, p90 8-20 ms; Settings back 1-2 janky of 16, p90 14-18 ms. The frame left is probably Library's reload landing during the slide; if it still shows, reload after the slide ends.

After the back stack (2026-10-21, release, 3 runs): See all back 0-1 janky of 17-18 frames, p90 13-15 ms; Settings back 0 janky of 17-18, p90 11-13 ms.

## Back from a person showed the People list while sliding out

Fixed 2026-10-21 (user report). Close cleared the open person at once, so the leaving screen turned into the People list (Help recognize card) for the slide. The person is now set or cleared when People opens, not on close. Checked on the emulator with animations slowed 10x.

## Help recognize card: text shifts left with fewer than 3 faces

With 1 face to check the stack holds 1 face, so the title and count move left (seen on Library, 7 → 1 faces). The mockup only shows 3 faces.

Steps:
1. Answer Help recognize faces until 1 or 2 are left.
2. Back to Library: the card's text starts further left than before.

Fix ideas:
- Keep the stack's 3-face width and leave the empty slots blank.

2026-10-22: fixed. The stack is always 96 dp wide (3 faces), empty slots stay blank (`ReviewCard`, `Library.kt`). Test: `library_fewerUnsureFaces_reviewTextStaysPut`; preview `LibraryOneUnsure`.

## Photos tab reloads the whole timeline while the app is in the background

The Photos tab's MediaStore observer (`PhotosScreen`, `Photos.kt`) stays registered after Home, and Compose keeps recomposing a stopped Activity (only animation frames pause). So each new photo or edit made in another app (camera, messenger) re-runs `timeline()` over the whole library 1 s later, with nobody looking. Found by reading the code and Compose's `Recomposer`, not measured on the phone.

Steps:
1. Open Memoria on the Photos tab, press Home.
2. Take a few photos with the camera: each one starts a full timeline query in Memoria.

Fix ideas:
- While the app is stopped, only note that something changed; reload once on return (`LifecycleStartEffect`), like the auto-index watcher does now.

2026-10-22: fixed. A change that settles while the Activity is stopped only sets `missed`; `LifecycleStartEffect` reloads once on return. Checked on the emulator with a temporary log: a photo added right after Home loads nothing in the background, one load on return. Also seen: Android already holds back observer calls while the app is cached and delivers them after return, so the background reload only happened while the process stayed alive (an index run's foreground service, or the first seconds after Home).

## Memoria Debug never auto-indexed new photos

Fixed 2026-10-22 on the phone (setup, not code). After the debug/release split, the face models and the NPU builds were copied into `Android/data/app.memoria.debug/files/` with `cp` on the phone, so they belonged to `shell` and were `rw-rw----`. The app could see `models/` but not read it, so `modelsReady()` was false and the watcher never started a run. Release was fine: its model files are `rw-rw-rw-`.

Steps:
1. Copy the models into a new package's `files/models/` with `cp` in `adb shell`.
2. Take a photo, open the app: no run, and Settings shows Face models as not downloaded.

Fix (done on the phone):
- `chmod 666` the model files, `chmod 2777` `models/` and `npu/` (so the app can also write NPU builds), `chmod 666` the files in `npu/`.
- Keep the model files' dates (`cp -p` or `touch -r`): an NPU build's name holds the model's size and date, so a new date means a rebuild, and a rebuild into a folder the app cannot write fails.
- A fresh install also re-cuts every face picture once (`recutThumbs`, no `recutTo` pref yet) before it indexes anything, so its first run takes minutes.
