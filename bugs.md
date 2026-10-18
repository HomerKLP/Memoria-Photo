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

## Library tab feels laggy

User report, release build. Each Library and People open read all 3555 face pictures and vectors (~30 MB, 15 `CursorWindow: Failed NO_MEMORY` per open). Fixed 2026-10-16: `FaceImage` reads its own picture by face id; 5 overflows per open are left (face vectors).

Measured after the fix (release): 1 dropped frame per tab switch, both tabs (building the new tab's first frame); Library scroll 1-2% janky. Not sure yet what the user feels.

Steps:
1. Switch Photos -> Library and scroll at once.

Fix ideas:
- Load faces without vectors for Library and People (only grouping needs them).

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

Fixed 2026-10-17 (portrait lock in the manifest). Still open on large screens, where Android 16 ignores the lock (second fix idea below).

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

# Known issues and bad patterns

Found while reading the code, not seen on the phone yet. No steps, just the risk.

- **Stopped index loses new faces.** `IndexWorker` skips `savePeople()` when stopped, so faces added in that run keep `personId = null` and are hidden in People until a full run. Fix: regoup what is there on stop, or note it in the UI.
- **Person ids are not stable.** `savePeople` numbers people `1..N` by size each run; ids change when sizes change. Naming and per-person tuning will need a stable id.
- **Fixing a person's main face moves faces answered against it.** Answers point at the person's main face. If the user moves or removes that main face, after a regroup the faces with a yes for that person follow it. Fix: answer against a few starting faces, or move the answers to the new main face.
- **Small faces drop out.** Faces under 40 px never join a person and are also missing from the People screen (`People.kt` `groupFaces`). Intended, but they are silently gone, not "unsure".
- **Destructive migrations.** Fixed 2026-10-17: only v1-3 databases are wiped; a missing migration crashes on open, and `MigrationTest` fails first.
- **Global mutable state.** `lastTimeline` and `thumbs` are top-level (`Photos.kt`); the Photos tab and the viewer both depend on them silently.
- **Bitmap churn.** `detect` makes new full-size bitmaps per photo and never recycles (`Faces.kt`). Adds to load cost and heat.
- **Details loads every face vector.** `Details.kt` calls `dao.faceVecs()` for one photo, then filters by `photoId`. A per-photo query (plus a light people query) would be much lighter. Same "faces without vectors" idea as Library.
- **`libraryPhotos(context).size` scans all of MediaStore.** `Library.kt` and `People.kt` build a `MediaPhoto` per row just to count. A `SELECT COUNT(*)` query does the same.
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
