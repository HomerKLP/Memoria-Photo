# Roadmap

Hardest part first, UI last.

**Next step:** check live people while indexing on the phone, then pick from the list below (Change cover, faster Help recognize, Settings leftovers: Folders to skip, Export / Import, Licenses). Phase 1 leftovers (tuning limits, more Takeout parts) wait until the user asks.

## Phase 1: face pipeline quality (done, leftovers later)
Pick the best models on real photos before writing app code. Details: `docs/face-pipeline.md`.
- [x] Python bench: `bench/bench.py` (detect, group).
- [x] Test photos in `testdata/photos/` (Google Takeout part 1).
- [x] Compare detectors: YOLOv5m-face 640 wins.
- [x] Compare recognizers: AdaFace IR101 wins.
- [x] Labeled set: Google Photos tags, `bench.py eval`.
- [ ] Try LVFace if AdaFace/ArcFace are not good enough (not in UniFace).
- [ ] Rerun eval with more Takeout parts (more people, more labels).
- [ ] Tune quality cut for starting groups.
- [ ] Check AdaFace weights license (YOLOv5-face is GPL-3.0).

## Phase 2: face pipeline on Android (current)
- [x] Install Android Studio and SDK on the Mac, turn on USB debugging on the test phone.
- [x] Half-size (fp16) YOLOv5m-face and AdaFace .onnx files, match fp32.
- [x] Gradle project, Kotlin, Compose (`app/`).
- [x] Test screen: CPU results match the Mac (see `docs/face-pipeline.md`).
- [x] NPU run: both models fully on NPU, 6-10x faster than CPU, same accuracy.
- [x] Small APK (83 MB): models pushed to the phone, only 8 Elite NPU libs.
- [x] Small decode (640) for detection, full decode only if faces. Decode-ahead threads dropped: too much code, less control.
- [ ] Recheck small decode: speed gain unclear (~6%), results drift from the Mac (7 lost, 4 new faces of 362). Measure on a cool phone with step timers, give the bench the same smooth resize.
- [x] Face pictures (160 px WebP) saved in Room while indexing, People screen no longer decodes photos.
- [ ] Some face pictures look blurry on the People screen. Fix later (bigger picture, or upscale small faces).
- [x] Remove verbose ONNX Runtime logging from the test screen.
- [ ] Decide how to ship the models in a release (APK size vs download).
- [x] Background indexing with WorkManager, test folder `Pictures/MemoriaTest` only. Matches the Mac.
- [x] Photo type from metadata: camera, other (downloads, messengers), skip (screenshots, GIFs, tiny).
- [x] Store photos, faces and face vectors in Room.
- [x] Foreground index worker with a progress notification. Plain job was stopped 7 times mid-run.
- [ ] The OEM's background manager freezes the app ~30 s after screen off, even with the foreground service. Settings > Background work now asks for the battery exemption (the OEM shows the dialog); check if Allow stops the freeze.
- [x] Whole library: drop the folder filter, index new photos. `libraryPhotos()` queries all MediaStore images (videos out for now); scope = all minus an `excludedFolders` list, Immich-style. Progress bar + time left on Library and in the notification.
- [x] Grouping on the phone (same as the bench, camera faces start people) and a plain People screen.
- [x] Save people in Room: grouped again from scratch at the end of each index run. Keep people stable once naming comes.
- [x] Auto-index when new photos arrive: watch MediaStore (ContentObserver on images), start an index run for the new photos once the change burst settles (3 s). App open only (no background start, so no battery exemption needed): skips when a run is going or queued, when the user paused, when the models are missing, or when nothing is new (e.g. a favorite toggle). Decision is pure, `AutoIndexTest` (11 cases) covers it with no phone.
- [x] People appear in Library / People while indexing. The run regroups (`savePeople`) at most every 30 s and never closer than 10x the regroup time; Library and People reload on each save (`peopleSaved`). Person ids stay put across regroups. Not checked on the phone yet.
- [x] Naming: "Name this person" sheet (Add name / Rename), name survives re-index (`savePeople` carries it over on shared faces).
- [x] People and Person screens built to the mockup: People = top bar + search, Help recognize card, index progress, 4-column grid, one list: named people first, then unnamed (no label), each by photo count. Library row same order. Person = face chips, "Faces" row, "Unsure faces" card, 3-column photo grid; tap a photo opens the viewer. Merge with / Change cover / Hide person are placeholders (no screens yet).
- [x] Merge with (one person at a time, most alike first) and Hide person, both survive regroups (DB v7). Hidden people opens from Settings. Checked on the phone.
- [x] Settings from the Library gear: Only while charging, Background work, Find faces again, Hidden people. Folders to skip, Export / Import face data and Licenses are shown but wait for design or decisions. Checked on the phone.
- [ ] Fixes, per-person tuning. Change cover not designed. A new person starts from a face only ("Who is this?" > New person), never from an empty card. Full stable people: still group all faces without a person (old ones too), so 5 old photos plus 1 new one can form a person.
- [x] Start gate: only camera faces with quality 0.4+ (EDifFIQA-T on the phone) start a person. Junk groups gone.
- [x] A person needs starting faces from 6+ photos.
- [x] Sure-only matching: a face in a person is sure only with quality 0.4+. Unsure faces still join, but are faded and not counted.
- [x] "Help recognize": one unsure face at a time, Yes / No / Not a face / Skip. Opens from Library and People (all faces) and a person's Unsure faces card (that person only). Answers survive re-index (`answer` table, see `docs/face-pipeline.md`).
- [x] Help recognize: Someone else opens the Who is this? sheet: pick a named person or start a New person from the face. Checked on the phone.
- [x] Viewer details People row: tap a face to fix it (Who is this?, Remove tag) or name an unnamed person. Checked on the phone.
- [ ] Yes faces do not change how others match yet (the person's face vector set is still its starting faces).
- [ ] Main face: pick by quality score once a quality model runs on the phone (now: closest to the person's average face).

## Phase 3: photo type filter
- [ ] Metadata filter (screenshots, messengers, no camera EXIF).
- [ ] Classifier for selfie, group, meme, screenshot, document.

## Phase 4: gallery basics (paused, basics done)
- [x] Honey theme (`Theme.kt`, Bricolage Grotesque font in the APK) and two bottom tabs. Test screen moved to Library > Dev tools.
- [x] Photos tab: timeline from MediaStore, month and day headers, 4-column grid, video length. Thumbnails from MediaStore `loadThumbnail`.
- [ ] Photos tab: refresh when new photos arrive (now only when the tab opens). Place names in day headers (needs offline geocoding).
- [ ] Test photos with no EXIF date and no date in the name land on the push day. Set their file time from the sidecar `photoTakenTime` before pushing, if it gets in the way. (Full plan and decisions: `docs/library-unification.md`.)
- [x] Viewer: swipe between photos, date on top, share. Videos show a sharp frame and open in the phone's own player.
- [x] Viewer: zoom (pinch 1-6x, double tap), pan while zoomed, favorite. Swipe down and pager are off while zoomed.
- [x] Viewer details sheet (swipe up or the Info button): date and timezone, place, camera EXIF, file details.
- [x] Favorites album: MediaStore query with `QUERY_ARG_MATCH_FAVORITE`, heart on grid tiles.
- [x] Library tab layout from the mockup: search bar, People row, index progress, "Help recognize" card, Albums/Places placeholders, shortcuts (Favorites, Videos, Locked folder, Trash), Dev tools. Albums, Places, Locked folder, Trash and Help recognize wait for their screens.
- [ ] Viewer details sheet: People section (waits for naming), place name (waits for offline geocoding).
- [x] Viewer: the photo fills the whole screen; the top / bottom bars float on it, transparent (a soft gradient under the icons, not the old 50% black strip). Tapping the photo hides the bars and the system status / nav bars, so nothing covers the photo; tapping again brings them back. The bars are `ViewerChrome` (pure UI, so tests and previews render it without MediaStore). Test: `viewerBars_hiding_keepsPhotoPut`; previews: `ViewerBars` / `ViewerTapped`. Checked on the phone: the two bars hide and show on a tap (fade cut to 120 ms, see `decisions.md`), but the system status / nav bars do not hide (see `bugs.md`). Mockup: `docs/design.html` "Viewer" and "Viewer, tapped".
- [x] Viewer: delete. Trash button (4th in the bar) sends the photo to the phone's trash through Android's own confirm; on OK the next photo slides in.
- [x] Trash: the phone's own trash (MediaStore `QUERY_ARG_MATCH_TRASHED`), 3-column grid with days left, long press selects, Restore / Delete / Empty go through Android's own requests. Library card shows the real count.
- [x] Albums: device folders (MediaStore buckets), 6 on Library, Albums screen from See all, album grid opens the viewer.
- [x] Photos tab: select many (mockup "Photos, selecting"). Long press starts, drag adds, a day's circle picks the whole day. Share, Favorite, Delete (one Android trash confirm for all). Later: the same mode on albums and a person's photos, Move to album.
- [ ] Albums: New album card, More menu, long press select (no design for the actions yet).
- [x] Videos screen from the Library card: all videos by month (month pinned), 3-column grid, count and total size on top.
- [ ] Media3 video player.

## Phase 5: video compression
- [ ] HEVC re-encode with Media3 Transformer, keep metadata, keep original until approved.

## Phase 6: design and UI polish
- [x] Mockup, Honey palette: Photos, Library, viewer, Help recognize (link in `decisions.md`).
- [ ] Redesign the People screen: the user doesn't like how it looks (2026-10-17), even now that the app matches the mockup. Mockup first, then code.

## Phase 7: release
- [x] GPL-3.0 LICENSE file.
- [ ] GitHub releases.

## Phase 8: tests (regression)
- [x] Unit tests (`app/src/test/`, `./gradlew :app:testDebugUnitTest`): pure logic. `fallbackDate`, `groupFaces` (6-photo gate, quality cut, camera-only, small faces), `etaMillis` / `formatEta`, `zoneLabel`, `monthAt` / `trackY`, NN-chain grouping = the old closest-pair-first loop. 23 tests. First win: `DetailsZoneTest` caught the "GMT-" bug (now fixed).
- [x] Migration test (`MigrationTest`, androidTest): a v7 DB with a named, hidden person, an answer and a merge opens with the app's migrations and keeps them. Schemas are saved in `app/schemas/`; a missing migration fails the test and crashes the app instead of wiping it.
- [ ] Room tests (in-memory DB): DAO queries.
- [x] Compose layout tests (`app/src/androidTest/`, `./gradlew :app:connectedDebugAndroidTest`): `LayoutJumpTest` states the "nothing else may move" rule as an assertion. Library loading->loaded, Pause/Resume bar length, unnamed People row height, Trash selecting, TopBar empty subtitle. Runs on a 360 dp emulator (`wm density 480`), not the phone. Mutation-checked: removing the unnamed name line fails the row-height test.
- [ ] Compose flow tests: tabs open, grid shows, tap a tile opens the viewer, People list loads.
- [ ] `LayoutJumpTest` case for the People screen (loading -> loaded, rows of 4). It has none yet.
- [x] Screenshot tests (the built-in `com.android.compose.screenshot`, JVM, no phone): `./gradlew :app:updateDebugScreenshotTest` records, `:app:validateDebugScreenshotTest` compares. One preview per state (loading / indexing / paused / empty), paired with `LayoutJumpTest`.
- [ ] Keep phone checks for the rest: stutter, blurry viewer, NPU, foreground worker. Tests can't cover these.
