# Roadmap

Open-source Android gallery. All photo processing and AI runs on the phone: photos and face data never leave it, the face models download once on first run, no accounts, ads, trackers or analytics. The internet is used only for optional comfort features — map tiles for the photo map, update checks, and that one-time model download — never to upload or analyze photos. Face data stays in a local DB, with export and import for backup.

Released (0.11.0). This file lists open work only, most important first. What is done and why each call was made: `decisions.md` and git.

**Index upgrades:** a new index step (search labels, OCR, video towns) fills in only what an existing index is missing (the catch-up pass in `IndexWorker`). Never re-index from scratch, so names, merges, hidden people, covers and answers stay.

## Open, most important first

1. **Locked folder** (built 2026-11-23, mockup "Locked folder"): a photo or video moves out of the gallery into encrypted app-private storage, opened by fingerprint or phone PIN. Done and checked on the emulator: the container, the store, the screen, Lock in the viewer and the select bar, video playback, Move out, delete, tests. Auto-lock checked on the emulator, the phone's sheet on the phone; the key survives the screen lock turned off (2026-11-23). Left: a release build; the no-screen-lock screen and dialog and the Move out question are designed (mockup, 2026-11-23), not built. Why each call: `decisions.md`, 2026-11-23.
2. **Photos grid: pinch to resize.** Levels: days (never fewer than 3 photos a row), pinch out to months, then to years (the top level), with an animation between levels. Month and year views are not in the mockup yet: design first.
3. **Video compression.** No visible loss, not lossless. HEVC re-encode with Media3 Transformer and the hardware encoder: keep resolution and frame rate, lower the bitrate within a safe range. Keep date, GPS and rotation. Keep the original until the user approves the result (a preview with the size saved). Skip videos that are already small. Later: auto mode, compress new videos at night while charging (opt-in), AV1 where a hardware encoder exists.
4. **Free text search** ("beach at sunset") with an on-device CLIP-style model (SigLIP2's text side; English and Russian).
5. **Media file matrix** (planned 2026-11-13, not started): one tiny made-up file for every kind of photo and video a phone library can hold, each run through the app's real code on the emulator, so a format bug shows up without the phone. Kinds, checks and tools: `docs/media-matrix.md`.
6. **R8 on release builds:** `isMinifyEnabled = true` in `buildTypes.release` (`app/build.gradle.kts`, beside the signing config). Release ships 24.6 MB of unshrunk dex, and Compose expects R8 (smaller APK, faster start). LiteRT calls Java from native code, so add keep rules and check an index run (CPU, GPU and NPU) on the phone. Keep each release's mapping file to read crash traces.
7. **Open a photo from another app:** `ACTION_VIEW` filter in the manifest, the viewer takes a single URI. The test phone's camera sends its last-photo review tap straight to the phone maker's gallery, so a filter will not catch that one; other apps' `ACTION_VIEW` would reach us. Not tried. Later: be a photo picker for other apps (`ACTION_PICK` / `GET_CONTENT`).
8. **Sound the phone cannot decode** (AC-3, E-AC-3, DTS, in some `.mkv` and camcorder files) plays silent with no word today. Tell the user; design first.
9. **Load time in `make perf`:** ms from opening a screen until its data shows (People, Person, Details, the viewer's sharp photo), like the cold start's timeline time. Library is done (2026-11-05).
10. **Strings in `res/values/strings.xml`,** with `pluralStringResource` for counts. All UI text is in Kotlin now and plurals are English only, so the app can't be translated. Cheaper before more screens are built.
11. **Regenerate the baseline profile** on the emulator (the file is from 2026-10-23 and the code changed a lot), then check cold start with `make perf`.
12. **Place name in the day header** (mockup "Photos": `Sun, 28 Sep` + the town, muted). Offline geocoding is done (`photo.town`), so this only reads it into the header. Photos only; videos have a town since 2026-11-07, so they could come later.
13. **Sheets, menus and dialogs: smooth in and out.** The `Menu` part (`Ui.kt`) has no motion yet.
14. **Search: two people at once, and a results grid** instead of one list of hits.
15. **Updater without the "Open with" chooser:** install through a `PackageInstaller` session instead of an `ACTION_VIEW` intent. Not decided yet.
16. **Play Protect warning:** say in the release notes and README that Play Protect may warn about the app (not registered with Google's developer verification) and how to check the APK with the signing certificate SHA-256. Not decided yet.
17. **A file that breaks on a real phone gets a made-up copy of the same kind in the test files first,** then the fix. The real file is personal, so it never goes in the repo.
18. **Place covers: small towns can still pick a portrait or a dull shot.** A selfie / group demotion is the next step if it bothers.

## Later

- Media management access (`MANAGE_MEDIA`): one switch in Android's Settings, so Android's own "Allow Memoria to delete this photo?" stops showing on Lock, and its dialogs for Delete, Restore and Move too. Memoria then asks itself where something is lost for good (Delete for good). Without the switch, Android's dialogs stay. Mockup first: how the app asks for the switch.
- Google Takeout import: copy photos not already on the phone, dates from the JSON, skip duplicates. Plan: `docs/library-unification.md`.
- Names from Takeout: match Google's people tags to our people (photos with one tag and one face, then a majority vote per person, like `bench.py eval`).
- Setting: photos needed to make a person (today 6, hardcoded in `groupFaces` and `groupPets`). Easy: a `minPhotos` parameter, a change runs one full regroup, no re-index. Open: does a named person under a raised number keep its name; one number for people and pets, or two. Mockup first.
- Setting: change the app's fonts (today the two bundled ones). Pick a pair in Settings; mockup first.
- First-run hint on Library about the models download.
- Dark theme: a dark Honey palette that follows the system setting. Not in the mockup yet: design first.
- Simple edit: crop, rotate. Other edits open in another app (More > Edit in…).
- Rename an album.
- "On this day" memories.
- Duplicates finder: exact copies, burst shots, very similar shots; keep the best one (sharpest, open eyes, best faces), deleting the rest needs the user's OK.
- Blurry and bad photo cleanup.
- Pets in Backup (Export / Import face data).
- Index: no extra decoding. Today a photo with faces is decoded twice (640 to detect, full size for the vectors); pets would add a crop per box. Goal (the user's call, 2026-10-29): one small decode for every detector, then cut faces and pets from the original with a region decode (`ImageDecoder.setCrop`), the same code for both. The first try saves 10-25% of a run but risks silent vector drift, so measure first.
- NPU on other Snapdragon chips: the APK ships the NPU libs for HTP V69 to V81 (8 Gen 1 to 8 Elite Gen 5) and picks the one that fits. Only V79 is checked (the test phone); others need a phone. V68 (888) is left out: no fp16 on its NPU.
- NPU on MediaTek and Samsung: add LiteRT's NPU packages (Dimensity 7300-9500, Exynos 2500 and 2600), check the APK size, test on a phone with each chip. Until then they run on the GPU (detect 52 ms per photo).
- Detector swap to YOLO11s-pose face (half the size, likely ~2x less compute, `docs/face-pipeline.md`). Bench it on the same photos; if it finds as many faces, replace YOLOv5m. Needs a new Kotlin decode (no anchors).
- Optional: OCR, so search finds the text inside documents, receipts and screenshots. Runs only on photos the tagger marks as one; an open model, no ML Kit.

## Ideas

- Map of photos by GPS (offline map tiles, or tiles fetched when online).

## Open questions

- Media matrix size: the 8K video and the 108 / 200 MP photos may be MBs even in solid colour. Commit them, make them on the emulator at test time, or keep them out of git and run them only on the Mac's emulator?
- A slow-motion clip (120 or 240 fps with the camera's slow-motion flag): play it slowed like the phone's gallery, or at real speed as today?
- Sound the phone cannot decode: where and how to say it (a line on the player, the card, a toast)?
- Spots inside places: what to call them, where offline names come from (GeoNames lakes, parks, peaks; OpenStreetMap; or the user names a cluster of photos), and which town a spot outside town limits joins. Bench (`bench/places.py`): the nearest town of 1000+ is right; the nearest town of 15000+ sends resort and lake towns 13-22 km away.
- Districts of very big cities: GeoNames lists some as their own towns (district seats), so a few photos get a district tile instead of the city's. Maybe a big city takes every place within a radius that grows with its population.
