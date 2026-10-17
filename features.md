# Memoria Photo: features

Open-source Android gallery. All AI runs on the phone, nothing leaves it.

Tags: **MVP** = first release, **Later** = planned, **Idea** = not decided.

## Photo sources and first start
- **MVP** Photos already on the phone (MediaStore): camera, screenshots, messengers, SD card. Nothing is copied.
- **MVP** First start: ask photo permission, show the gallery at once, scan faces in the background with progress. People appear as groups form.
- **Later** Google Takeout import: pick the zip or folder, copy photos not already on the phone, take dates from the JSON, skip duplicates.
- **Later** Names from Takeout: Google tags say who is in a photo, not which face. Match them to our groups: photos with one tag and one face, then majority vote per group (same as `bench.py eval`).
- Not possible: direct Google Photos sync. Google closed library read access for other apps in 2025, and we have no INTERNET permission.

## Gallery basics
- **MVP** Timeline grid of all photos and videos, grouped by day and month. Pinch to resize.
- **MVP** Albums from device folders.
- **MVP** Full-screen viewer with zoom and swipe. Video player.
- **MVP** Share, delete to trash, restore (system trash).
- **MVP** Favorites.
- **MVP** Photo info: date, size, camera, place.
- **Later** Move and copy between albums, rename.
- **Later** Hidden album behind PIN or fingerprint.
- **Later** Simple edit: crop, rotate. Other edits open in another app.
- **Idea** Map of photos by GPS.

## Faces
- **MVP** Find faces on the phone and group them into people.
- **MVP** Name a person, see all their photos.
- **MVP** Fix mistakes: "not this person", merge, split, move a face.
- **MVP** Draw a face box by hand when the detector misses it.
- **MVP** Only sure faces join a person. Unsure ones go to "Help recognize this person": the app shows a face and asks "Is this X?", the user taps yes, no, someone else (pick or new person) or not a face.
- **MVP** Photo details list the people in it: tap a face to change the person or remove the tag.
- **MVP** Fixes make matching better for that person (see below).
- **Later** Hide a person or ignore a face (strangers in the back).
- **Later** Search by several people ("me + mom").
- **Idea** Pets.

### How tuning works
The model is not retrained on the phone. Each person keeps a set of face vectors. Confirmed faces join the set, rejected faces become "not this person" examples, and each person can have their own match threshold. This is fast and easy to undo.

## Photo type filter
Goal: keep screenshots, memes and documents out of face search.
- **MVP** Step 1, free: metadata.
  - Camera photos (camera EXIF): full face scan, can start a new person.
  - Downloads and messenger photos (no camera EXIF): faces can join existing people, never start one. Meme celebrities stay out. A person seen only in Telegram is named by hand.
  - Skip: screenshots, GIFs, tiny images.
- **MVP** Step 2: the face detector itself. It takes a few ms per photo, so it is the cheapest "has faces" check.
- **Later** Step 3: small classifier: selfie, group, screenshot, meme, document, other. Main gain is clean people groups (no celebrity faces from memes), not speed.
- **MVP** Save labels so the user can browse "Selfies", "Group photos", "Screenshots".

## Video compression
Re-encoding always loses some quality. Goal is no visible loss, not lossless.
- **MVP** Re-encode to HEVC (H.265) with the hardware encoder. Keep resolution and frame rate, lower the bitrate within a safe range.
- **MVP** Keep date, GPS and rotation.
- **MVP** Keep the original until the user approves the result (preview, size saved).
- **MVP** Skip videos that are already small.
- **Later** Auto mode: compress new videos at night while charging (opt-in).
- **Later** AV1 where a hardware encoder exists.

## Search and memories
- **Later** Search by date, place, person, photo type.
- **Later** AI labels on every photo: document, ID card, passport, receipt, screenshot, meme, food, pet and more. Type "documents" or "id", see all of them.
- **Later** "On this day" memories.
- **Later** Duplicates finder: exact copies, burst shots, very similar shots.
- **Later** Keep the best one: suggest the sharpest photo with open eyes and best faces. Deleting the rest needs the user's OK.
- **Later** Blurry and bad photo cleanup.
- **Later** Free text search ("beach at sunset", "passport") with an on-device CLIP-style model. Covers labels we did not list.
- **Idea** Search text inside photos (OCR).

## Privacy
- No INTERNET permission. Models ship inside the app.
- No accounts, ads, trackers or analytics.
- Face data lives in a local DB, with export and import for backup.
- Heavy work runs in the background, with a "only while charging" option.

## Not planned
- Cloud sync or backup server.
- iOS.
- Full photo editor.

## Open questions
- Library unification plan and decisions: `docs/library-unification.md`.
- Lowest Android version to support?
- Face models: best accuracy vs. license (see `docs/face-pipeline.md`).
- GitHub username for the package id.
