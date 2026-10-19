# Roadmap

Open-source Android gallery. All photo processing and AI runs on the phone: photos and face data never leave it, models ship inside the app, no accounts, ads, trackers or analytics. The internet is used only for optional comfort features — map tiles for the photo map, and update checks — never to upload or analyze photos. Face data stays in a local DB, with export and import for backup.

Hardest part first, UI last. Phases are the order of work; Later and Ideas at the bottom have no phase yet.

**Index upgrades:** a new index step (search labels, OCR, video towns) fills in only what an existing index is missing, like `recutThumbs` does for face pictures. Never re-index from scratch, so names, merges, hidden people, covers and answers stay.

**Next step:** Phase 6 (design and UI polish) is current; Phase 2 (face pipeline) is paused and its index / grouping follow-ups wait until the UI is mostly finished. UI items to pick from: the UX pass list in Phase 6. Phase 1 leftovers (tuning limits, more Takeout parts) wait until the user asks.

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

## Phase 2: face pipeline on Android (paused, UI first)
Paused while the UI comes first (Phase 6). The index and grouping follow-ups in this phase (small-decode shakedown, blurry face pictures, group/matching tuning, main face) get rechecked once the UI is mostly finished. Working items: see `## Phase 6`.
- [x] Install Android Studio and SDK on the Mac, turn on USB debugging on the test phone.
- [x] Half-size (fp16) YOLOv5m-face and AdaFace .onnx files, match fp32.
- [x] Gradle project, Kotlin, Compose (`app/`).
- [x] Test screen: CPU results match the Mac (see `docs/face-pipeline.md`).
- [x] NPU run: both models fully on NPU, 6-10x faster than CPU, same accuracy.
- [x] Small APK (83 MB): models pushed to the phone, only 8 Elite NPU libs.
- [x] Small decode (640) for detection, full decode only if faces. Decode-ahead threads dropped: too much code, less control.
- [x] Small decode kept as the right way: detect on a 640 decode, and only photos with faces pay the big decode (4096 on the long side). The gain is small (~6%) and results drift a little from the Mac (7 lost, 4 new faces of 362, mostly small or borderline), but the approach is sound and other galleries avoid decoding the original for detection too. No recheck.
- [x] Face pictures (160 px WebP) saved in Room while indexing, People screen no longer decodes photos.
- [x] Some face pictures looked blurry on the People screen: saved at 160 px, shown at up to 288 px. Now saved at 288 px, old ones cut again once. No upscale: ~90% of faces in people are 288 px or bigger in the photo.
- [ ] Decode only the face regions instead of the whole photo. When faces are found, the app decodes the photo at 4096 on the long side (`loadPhoto`) just to embed the faces and cut their 288 px thumbs; only a few small tiles are needed. `ImageDecoder.setCrop` on each face box would decode a tiny bitmap per face, no 12 MP frame. Not on the bench path, so it may drift from the Mac; measure it. May also sharpen the blurry face pictures above.
- [x] Remove verbose ONNX Runtime logging from the test screen.
- [ ] Decide how to ship the models in a release (APK size vs download).
- [x] Background indexing with WorkManager, test folder `Pictures/MemoriaTest` only. Matches the Mac.
- [x] Photo type from metadata: camera, other (downloads, messengers), skip (screenshots, GIFs, tiny).
- [x] Store photos, faces and face vectors in Room.
- [x] Foreground index worker with a progress notification. Plain job was stopped 7 times mid-run.
- [x] The OEM's background manager froze the app ~30 s after screen off, even with the foreground service. Fixed by the battery exemption: Settings > Background work asks for it (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, the OEM shows its own dialog), and with Allow the freeze is gone. Checked on the phone.
- [x] Whole library: drop the folder filter, index new photos. `libraryPhotos()` queries all MediaStore images (videos out for now); scope = all minus an `excludedFolders` list, Immich-style. Progress bar + time left on Library and in the notification.
- [x] Grouping on the phone (same as the bench, camera faces start people) and a plain People screen.
- [x] Save people in Room: grouped again from scratch at the end of each index run. Keep people stable once naming comes.
- [x] Auto-index when new photos arrive: watch MediaStore (ContentObserver on images), start an index run for the new photos once the change burst settles (3 s). App open only (no background start, so no battery exemption needed): skips when a run is going or queued, when the user paused, when the models are missing, or when nothing is new (e.g. a favorite toggle). Decision is pure, `AutoIndexTest` (11 cases) covers it with no phone.
- [x] People appear in Library / People while indexing. The run regroups (`savePeople`) at most every 30 s and never closer than 10x the regroup time; Library and People reload on each save (`peopleSaved`). Person ids stay put across regroups. Not checked on the phone yet.
- [x] Naming: "Name this person" sheet (Add name / Rename), name survives re-index (`savePeople` carries it over on shared faces).
- [x] People and Person screens built to the mockup: People = top bar + search, Help recognize card, index progress, 4-column grid, one list: named people first, then unnamed (no label), each by photo count. Library row same order. Person = face chips, "Faces" row, "Unsure faces" card, 3-column photo grid; tap a photo opens the viewer. Merge with / Change cover / Hide person are placeholders (no screens yet).
- [x] Merge with (one person at a time, most alike first) and Hide person, both survive regroups (DB v7). Hidden people opens from Settings. Checked on the phone.
- [x] Settings from the Library gear: Only while charging, Background work, Find faces again, Hidden people. Folders to skip, Export / Import face data and Licenses are shown but wait for design or decisions. Checked on the phone.
- [ ] **Important, blocks the first release:** Licenses screen (Settings > About, mockup first). The APK already ships the GeoNames towns, so the credit is a must. Until the screen is built, a `THIRD_PARTY.md` in the repo holds the same list. Must credit GeoNames (CC BY 4.0: credit, the license, and what we changed: towns of 1000+ only, no districts), and list the fonts (OFL 1.1), ONNX Runtime (MIT), Qualcomm QNN libs, AndroidX / Compose / Kotlin / Material icons (Apache 2.0), and the face models once the app ships them. License texts live inside the app (no internet).
- [ ] Fixes, per-person tuning. Change cover built (mockup "Change cover"): the chip on the person screen opens a sheet with the person's sure faces, clearest first, current cover on top; picking one saves it and it survives re-index (DB v8). A new person starts from a face only ("Who is this?" > New person), never from an empty card. Full stable people: still group all faces without a person (old ones too), so 5 old photos plus 1 new one can form a person.
- [x] Start gate: only camera faces with quality 0.4+ (EDifFIQA-T on the phone) start a person. Junk groups gone.
- [x] A person needs starting faces from 6+ photos.
- [x] Sure-only matching: a face in a person is sure only with quality 0.4+. Unsure faces still join, but are faded and not counted.
- [x] "Help recognize": one unsure face at a time, Yes / No / Not a face / Skip. Opens from Library and People (all faces) and a person's Unsure faces card (that person only). Answers survive re-index (`answer` table, see `docs/face-pipeline.md`).
- [x] Help recognize: Someone else opens the Who is this? sheet: pick a named person or start a New person from the face. Checked on the phone.
- [x] Viewer details People row: tap a face to fix it (Who is this?, Remove tag) or name an unnamed person. Checked on the phone.
- [ ] Yes faces do not change how others match yet (the person's face vector set is still its starting faces).
- [ ] Main face: pick by quality score once a quality model runs on the phone (now: closest to the person's average face).

## Phase 3: photo type filter
Goal: keep screenshots, memes and documents out of face search.
- [x] Metadata filter (screenshots, messengers, no camera EXIF): done in Phase 2. Only camera faces start a person; messenger faces can join one.
- [ ] Classifier for selfie, group, meme, screenshot, document. Main gain is clean people (no celebrity faces from memes), not speed.
- [ ] Save the labels so the user can browse Selfies, Group photos, Screenshots.

## Phase 4: gallery basics (paused, basics done)
- [x] Honey theme (`Theme.kt`, Bricolage Grotesque font in the APK) and two bottom tabs. Test screen moved to Library > Dev tools.
- [x] Photos tab: timeline from MediaStore, month and day headers, 4-column grid, video length. Thumbnails from MediaStore `loadThumbnail`.
- [ ] Photos tab: pinch to resize the grid.
- [x] Photos tab: refresh when new photos arrive. A MediaStore observer reloads the timeline 1 s after the last change; tiles and day headers use `animateItem`, so new photos fade in and the rest slide. Scrolled down, nothing moves. Checked on the emulator; the phone's release build is not checked yet.
- [ ] Photos tab: place name in the day header (mockup "Photos": `Sun, 28 Sep` + the town, muted). Offline geocoding is already done (`photo.town`), so this only reads it into the header. Photos only, videos have no town yet.
- [ ] Test photos with no EXIF date and no date in the name land on the push day. Set their file time from the sidecar `photoTakenTime` before pushing, if it gets in the way. (Full plan and decisions: `docs/library-unification.md`.)
- [x] Viewer: swipe between photos, date on top, share. Videos show a sharp frame and open in the phone's own player.
- [x] Viewer: zoom (pinch 1-6x, double tap), pan while zoomed, favorite. Swipe down and pager are off while zoomed.
- [x] Viewer details panel (swipe up or the Info button): date and timezone, place, camera EXIF, file details. The black panel rides up with the finger while the photo moves into a full-width square at the top; the panel overlaps the square a little and its list scrolls (with a flick's coast) when longer (mockup "Viewer, swiped up").
- [x] Favorites album: MediaStore query with `QUERY_ARG_MATCH_FAVORITE`, heart on grid tiles.
- [x] Favorites: fewer dates. Month headers only, 3-column grid, no day headings, no hearts (`FavoritesContent`, mockup "Favorites").
- [x] Viewer More menu (mockup "Viewer, More"), from the shared `Menu` part (`Ui.kt`): Edit in… and Use as… (Android's chooser), Show in album (opens the photo's folder, hidden when the viewer came from that album). Tests: `viewer_more_opensMenuAndFiresItem`, `viewerMore_opening_keepsContentPut`; preview `ViewerMore`. Move to album built (mockup "Move to album"): `MoveToAlbumSheet` / `MoveToAlbumContent` (`Move.kt`), folders under DCIM / Pictures (`moveTargets`), New album, Android's confirm; tests `moveToAlbum_*`, preview `MoveToAlbumSheetPreview`. Album More and album select built (see Albums below).
- [x] Library tab layout from the mockup: search bar, People row, index progress, "Help recognize" card, Albums/Places placeholders, shortcuts (Favorites, Videos, Locked folder, Trash), Dev tools. Albums, Places, Locked folder, Trash and Help recognize wait for their screens.
- [x] Viewer details: People section, and Place as the nearest town (never coordinates; `DetailsPlaceTest`).
- [x] Viewer: the photo fills the whole screen; the top / bottom bars float on it, transparent (a soft gradient under the icons, not the old 50% black strip). Tapping the photo hides the bars and the system status / nav bars, so nothing covers the photo; tapping again brings them back. The bars are `ViewerChrome` (pure UI, so tests and previews render it without MediaStore). Test: `viewerBars_hiding_keepsPhotoPut`; previews: `ViewerBars` / `ViewerTapped`. Checked on the phone: the two bars hide and show on a tap (fade cut to 120 ms, see `decisions.md`), but the system status / nav bars do not hide (see `bugs.md`). Mockup: `docs/design.html` "Viewer" and "Viewer, tapped".
- [x] Viewer: delete. Trash button (4th in the bar) sends the photo to the phone's trash through Android's own confirm; on OK the next photo slides in.
- [x] Trash: the phone's own trash (MediaStore `QUERY_ARG_MATCH_TRASHED`), 3-column grid with days left, long press selects, Restore / Delete / Empty go through Android's own requests. Library card shows the real count.
- [x] Albums: device folders (MediaStore buckets), 6 on Library, Albums screen from See all, album grid opens the viewer.
- [x] Photos tab: select many (mockup "Photos, selecting"). Long press starts, drag adds, a day's circle picks the whole day. Share, Favorite, Delete (one Android trash confirm for all). Albums have it too (Albums below); a person's photos later. No Move here: the grid does not say which album a photo is in (Move is in the viewer and album select).
- [x] Album More and select (mockup "Album, More", "Album, selecting"). Long press or More > Select; Select all; Share, Favorite, Move (the Move to album sheet, moved photos leave), Delete (one Android confirm). Ignore faces in this album: asks first when turning on, saves the album's bucket id; the next index run drops its photos and their faces (`libraryPhotos`), Settings > Folders to skip shows the count. Tests: `album_*` in `BehaviorTest`, `album_selecting_keepsTopBarAndGridTop`; previews `AlbumSelect`, `AlbumMore`. Not checked on the phone.
- [x] Albums: New album (mockup "New album" + "New album, pick photos"). The tile on Albums (accent fill) opens a name sheet (Next off while empty or the name is taken), then the timeline in select mode; Move here moves the picked photos to `Pictures/<name>/` and opens the new album. Nothing is made until a move. From Move to album the same field's button reads Move. Tests: `newAlbum_next_needsANameAndAUniqueOne`, `newAlbumPick_moveHereOffUntilPicked`, `newAlbum_typingPlaceholder_keepsFieldAndButtonsPut`, `newAlbumPick_loadingToLoaded_topBarAndFooterStayPut`; previews `NewAlbumSheetPreview`, `NewAlbumPickPreview`.
- [x] Offline town names at index time: `assets/towns.tsv` (GeoNames, 1000+ people, no districts), each photo's GPS and nearest town saved on its photo row (DB v9). Photos only, not videos yet.
- [x] Places screen and Place screen (mockup "Places", "Place"): Library row (3 biggest towns) and See all grid from `photo.townId`, Place = photos by month like Videos, tap opens the viewer. Checked on the phone.
- [x] Select on Favorites, Place, Person and Videos (mockups "Favorites, selecting", "Place, selecting", "Person, selecting", "Videos, selecting"): long press, Select all, the Select bar without Move. On Favorites the middle action is Unfavorite. One `rememberPick` (`Photos.kt`) holds the picked ids and Share / Favorite / Delete for these screens and Album. Tests: `*_selecting_*` in `BehaviorTest` and `LayoutJumpTest`; previews `FavoritesSelect`, `VideosSelect`, `PlaceSelect`, `PersonSelect`. Checked on the phone (all four and Album): long press, tap, Select all, X, Back, Share; nothing above the grid moves. Favorite and Delete not tapped on the phone (real photos); tests cover their buttons.
- [ ] Places leftovers: videos (no town yet), an empty state when no photo has a place (designed, mockup "Places, empty"; grey tiles hold the row for now).
- [ ] Later: spots inside a place (a lake or park near a town shows inside that town's Place). Not designed; see Open questions.
- [x] Videos screen from the Library card: all videos by month (month pinned), 3-column grid, count and total size on top.
- [ ] Media3 video player.
- [ ] Open a photo from another app (`ACTION_VIEW` filter in the manifest, the viewer takes a single URI). Later: be a photo picker for other apps (`ACTION_PICK` / `GET_CONTENT`).
- [x] Search screen (mockup "Search", `Search.kt`): from the Photos icon and the Library bar. Empty field: named people, Videos / Favorites / Screenshots chips (Screenshots opens the phone's Screenshots album, hidden without one), Recent. Typing finds word starts in people, places, albums, years and months; each hit opens a screen we have, and back returns to Search. A year or month opens the Photos tab at that month. Checked on the phone. Later: two people at once, free text, a results grid.

## Phase 5: video compression
Goal: no visible loss, not lossless (re-encoding always loses some).
- [ ] HEVC re-encode with Media3 Transformer and the hardware encoder. Keep resolution and frame rate, lower the bitrate within a safe range.
- [ ] Keep date, GPS and rotation. Keep the original until the user approves the result (preview, size saved).
- [ ] Skip videos that are already small.
- [ ] Later: auto mode, compress new videos at night while charging (opt-in). AV1 where a hardware encoder exists.

## Phase 6: design and UI polish (current)
- [x] Mockup, Honey palette: Photos, Library, viewer, Help recognize (link in `decisions.md`).
- [x] Redesign the People screen: the user didn't like how it looked (2026-10-17), even when the app matched the mockup. Mockup first, then code. The user likes the result (2026-10-18).
  - [x] Mockup (2026-10-18): "Named" 96 dp faces, 3 a row, name 6 dp under the face, count right under; "No name yet" 56 dp faces, 5 a row, no text.
  - [x] Code: built in `PeopleContent` / `PersonCard` (`People.kt`). Merge with and Hidden people get the same tight card (6 dp, count right under). Layout change: `LayoutJumpTest` case, `make layout` and `make screenshots`.
- [x] Match the app to the mockup's "Shapes and sizes" part (designed 2026-10-18). Each is a layout change: `make layout` and `make screenshots`.
  - [x] Search bar on Library and People: 52 → 48 dp, 24 dp corners, 16 dp padding (`SearchBar`, `Library.kt`).
  - [x] People top bar: drop the search icon (`People.kt`, it does nothing today).
  - [x] Photo details: People row faces 56 → 72 dp, side padding 20 → 16 dp (`Details.kt`).
  - [x] Merge with: faces 60 → 68 dp (`MergeSheet`, `People.kt`).
  - [x] Merge with and Change cover: a picked face also gets the accent check (`PersonCard`, `ChangeCoverSheet`).
  - [x] Sheets: side padding 20 → 16 dp, subtitle 13 → 12 sp, 16 dp between a row's picture and its text. Who is this? (`Recognize.kt`), Merge with, Change cover, Name this person (`People.kt`), Move to album (`Move.kt`).
  - [x] Menu: 14 → 16 dp between icon and text (`Menu`, `Ui.kt`).
  - [x] Search built to the mockup: album hit with its cover as a square, month hit a square icon, faces 72 dp.
- [ ] UX pass: find and fix what feels broken in use, page by page. Known so far:
  - [x] Moving or deleting the last photo of an album leaves an empty album page with no title. An album that becomes empty should close and go back. Fixed 2026-10-18 with `CloseWhenEmpty` (`Ui.kt`); see `bugs.md`.
  - [x] Check every page that shows a set that can run empty, after a move, delete, unfavorite, hide or merge: Album, Place, Person, Favorites, Videos, Trash, and the viewer when its last photo goes. Each should close or show its empty state, never a blank page. Done 2026-10-18: Album, Place, Videos and the open Person close; Favorites and Trash show their empty states; the viewer already closed. See `bugs.md`.
  - [ ] After Change cover, going back to Library shows the old face for a moment, then flips to the new one. Library starts from its cached data and only then reloads. Fix by updating the cache when the cover changes (or preloading), or at least a crossfade.
  - [x] Month slider on more screens. `MonthSliderBox` (`Ui.kt`) holds the shared fade/drag state, lifted out of `PhotosContent`. Place has it: the "Place" mockup shows the slider and `PlaceContent` wraps its grid (one entry per sticky month heading). The rail position is open again: the mockup change was reverted and redone, approved and built 2026-10-19 for Photos, Place, Album and Person (Album and Person now have month headings, `bugs.md`). Videos and Favorites got it too (2026-10-19, `VideosContent`, `FavoritesContent`).
  - [ ] Main grid (Photos): the top bar hides as you scroll down, so the grid gets more room. It comes back as soon as you scroll up, and on the very top it is always visible. Mockup first ("Photos" figure), then code; a layout change, so `LayoutJumpTest` plus screenshots. Check how it reads against the pinned month header and the month slider.

## Phase 7: animations
Goal: motion that feels finished, like a mature gallery app, before the first APK release. No motion design in the mockup: Claude checks each motion on the emulator, the user on the phone.
- [x] Opening and closing a photo: it grows out of its tile and shrinks back into it (swipe down or Back). The grid behind follows the photo you swipe to, so it always lands in a clear tile.
- [x] Double tap in the viewer: the zoom animates in and out instead of jumping (tween, `Viewer.kt`).
- [ ] Page changes: open and back between screens, and the tab switch.
- [ ] Sheets, menus and dialogs: smooth in and out.
- [ ] Select mode: the top bar and the Select bar swap smoothly.
- [ ] Lists: photos and people that leave (delete, move, merge, hide) animate out instead of vanishing; new pictures fade in (thumbnails, covers).
- [ ] Rules: the no-layout-jump rule still holds; short motions; follow Android's "Remove animations" setting; check on a release build that nothing stutters.

## Phase 8: release
Items marked **Important** must be done before the first public release. The Licenses screen (Phase 2) is one of them.
- [x] GPL-3.0 LICENSE file.
- [ ] **Important:** turn telemetry off. ONNX Runtime's Android package adds the INTERNET permission and a provider that starts a Microsoft telemetry client at launch (seen in the built APK, 2026-10-19; we never call `setTelemetry(false)`). Remove both in the manifest (`tools:node="remove"`) and call `setTelemetry(false)`. Then `aapt2 dump permissions` on the release APK must show no INTERNET. The OTA item below brings the permission back, scoped to the update check; the provider stays removed.
- [ ] **Important:** release signing key. `make release` signs with Android's public debug key, so that APK must never be published. Make one release keystore (name `CN=Memoria Photo`, no real name), keep it and its passwords outside the repo, back it up in two places, and read both from environment variables in a `signingConfigs.release` block (`app/build.gradle.kts`). Write where it lives in `decisions.md` (not tracked). Never change the key later: users would have to uninstall and lose their face data. A phone with the debug-key build can't update to the release key for the same reason, so test the release APK on the emulator. Before every release, `apksigner verify --print-certs` must not say `Android Debug`.
- [ ] **Important:** `README.md`. What the app is, the privacy promise, how to install the APK, how to build it, the license. The APK has no face models and the app has no internet, so say how to make them (`bench/fp16.py`) and push them to the phone, and which phones work (NPU libs for the Snapdragon 8 Elite only). Check each model's license first (Phase 1) and keep the models out of the release until "ship the models" (Phase 2) is decided.
- [ ] **Important:** `android:allowBackup="false"` in the manifest. Without it Android's cloud backup may copy the face database to the user's Google account. Names and faces leave the phone only through our own Export.
- [ ] Dev tools move to Settings, shown only in debug builds and gone from release. Today Library shows a "Dev tools" button to everyone (`Library.kt`). Its page gets the same look as the other pages. Mockup first (`docs/design.html`), then code; a layout change, so `LayoutJumpTest` and screenshots.
- [ ] Disable the buttons that do nothing yet: Locked folder (Library), Folders to skip, Export / Import face data and Licenses (Settings), and any other tap with no action. Greyed and not clickable until each one is built. Add the disabled look to the mockup's shared parts first.
- [ ] GitHub releases: tag each build, attach the signed APK, keep a changelog.
- [ ] OTA updates from GitHub release APKs: the app notices a newer release, downloads the APK, and installs it through Android's own installer (PackageInstaller, `REQUEST_INSTALL_PACKAGES`). Needs its own screen (mockup first) and an INTERNET permission scoped to the update check (see Open questions).

## Phase 9: tests (regression)
- [x] Unit tests (`app/src/test/`, `./gradlew :app:testDebugUnitTest`): pure logic. `fallbackDate`, `groupFaces` (6-photo gate, quality cut, camera-only, small faces), `etaMillis` / `formatEta`, `zoneLabel`, `monthAt` / `trackY`, NN-chain grouping = the old closest-pair-first loop. 23 tests. First win: `DetailsZoneTest` caught the "GMT-" bug (now fixed).
- [x] Migration test (`MigrationTest`, androidTest): a v7 DB with a named, hidden person, an answer and a merge opens with the app's migrations and keeps them. Schemas are saved in `app/schemas/`; a missing migration fails the test and crashes the app instead of wiping it.
- [ ] Room tests (in-memory DB): DAO queries.
- [x] Compose layout tests (`app/src/androidTest/`, `./gradlew :app:connectedDebugAndroidTest`): `LayoutJumpTest` states the "nothing else may move" rule as an assertion. Library loading->loaded, Pause/Resume bar length, unnamed People row height, Trash selecting, TopBar empty subtitle. Runs on a 360 dp emulator (`wm density 480`), not the phone. Mutation-checked: removing the unnamed name line fails the row-height test.
- [ ] Compose flow tests: tabs open, grid shows, tap a tile opens the viewer, People list loads.
- [x] `LayoutJumpTest` cases for the People screen: loading -> loaded, cards in a row the same height.
- [x] Screenshot tests (the built-in `com.android.compose.screenshot`, JVM, no phone): `./gradlew :app:updateDebugScreenshotTest` records, `:app:validateDebugScreenshotTest` compares. One preview per state (loading / indexing / paused / empty), paired with `LayoutJumpTest`.
- [ ] Keep phone checks for the rest: stutter, blurry viewer, NPU, foreground worker. Tests can't cover these.

## Phase 10: smart search (later)
- [ ] AI labels on every photo (document, ID card, receipt, meme, food, pet...): type "documents" or "id", see all of them. Builds on the Phase 3 classifier.
- [ ] Free text search ("beach at sunset") with an on-device CLIP-style model.

## Phase 11: performance (feels slow; measure first)
Today perf is found only after someone feels it (`bugs.md` holds the numbers). No repeatable measurement, no gate. Goal: a number per screen, then fix the load costs, then keep them from coming back.
- [x] Baseline profiles: `androidx.profileinstaller` + `app/src/main/baseline-prof.txt` (generated, 1376 app classes merged into the APK). `<profileable android:shell="true"/>` so release can be traced. A release build no longer runs cold/JIT.
- [x] `:baselineprofile` module (Macrobenchmark): generates the profile. Its Gradle runs uninstall the app (data loss), and the emulator has no library, so it is not the perf gauge.
- [x] `make perf` (`bench/perf.py`): cold start (first frame, timeline shown) and janky frames for Photos, Library, People, viewer, Details, on the phone's release build. Taps and dumpsys only, data stays. First numbers in `bugs.md`: every screen under 3% janky frames.
- [ ] Fix the known load costs, by measured impact. They do not show as janky frames; if a screen still feels slow, measure how long its data takes to show first. Done 2026-10-18: Details reads only its photo's faces and the people's vectorless faces (`photoFaces` + `taggedFaces`); Library/People count with `libraryPhotoCount` instead of building a `MediaPhoto` per row. Left: thumbnails start only when a screen opens (warm the visible ones after `timeline()`); the viewer decodes full size then sharpens (`Viewer.kt`); `Search.kt` still loads full `faceVecs()` for its named-people list.
- [ ] Load time in `make perf`: ms from opening a screen until its data shows (Library, People, Person, Details, the viewer's sharp photo), like the cold start's timeline time.
- [x] Perf line in `AGENTS.md` (Commands): `make release`, pause indexing, `make perf`.

## Later
- Google Takeout import: copy photos not already on the phone, dates from the JSON, skip duplicates. Plan: `docs/library-unification.md`.
- Names from Takeout: match Google's people tags to our people (photos with one tag and one face, then a majority vote per person, like `bench.py eval`).
- Split a person. Ignore a face (strangers in the back).
- Locked folder behind PIN or fingerprint (Library card in the mockup, no screen yet).
- Simple edit: crop, rotate. Other edits open in another app (Viewer, More > Edit in…).
- Rename an album.
- "On this day" memories.
- Duplicates finder: exact copies, burst shots, very similar shots. Keep the best one (sharpest, open eyes, best faces); deleting the rest needs the user's OK.
- Blurry and bad photo cleanup.

## Ideas
- Map of photos by GPS (offline map tiles, or tiles fetched when online).
- Pets.
- Search text inside photos (OCR).

## Not planned
- Cloud sync or backup server.
- Direct Google Photos sync: Google closed library read access for other apps in 2025.
- iOS.
- Full photo editor.

## Open questions
- Spots inside places: what to call them, where offline names come from (GeoNames lakes, parks, peaks; OpenStreetMap; or the user names a cluster of photos), and which town a spot outside town limits joins. Bench (`bench/places.py`): the nearest town of 1000+ is right; the nearest town of 15000+ sends resort and lake towns 13–22 km away.
- OTA updates: check the GitHub releases API for a newer tag, download the APK, install it through Android's own installer. Needs `REQUEST_INSTALL_PACKAGES` and the release page URL / repo. Decide the check (on open, daily, or manual).
- Districts of very big cities: GeoNames lists some as their own towns (district seats), so a few photos get a district tile instead of the city's. Maybe a big city takes every place within a radius that grows with its population.
