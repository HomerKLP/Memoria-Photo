# Library unification (Takeout + phone)

Goal: one clean, deduplicated photo library on the phone. Takeout photos kept,
correct dates, no duplicates, nothing lost.

## Approach
- The merge runs **on the phone**, not on a computer. A local read-only backup
  and an extracted Takeout copy are only staging.
- The phone's own photos stay where they are (`DCIM/...`). Imported Takeout
  media goes into a single `Pictures/Takeout/` folder (per-year Takeout albums
  are flattened into it).
- **Conservative dedupe**: only **100% exact content duplicates** (SHA-256) are
  removed, and only after the user reviews the list. Near-duplicates
  (re-encodes, renamed copies) are kept and just listed. Videos: same rule.
- Every deletion must be a Takeout copy that has a byte-identical phone twin.
  Nothing under `DCIM/` is ever deleted.
- Keep the backup until the result is checked on the phone.

## Takeout specifics
- Each photo can have a JSON sidecar: `x.jpg.supplemental-metadata.json`, or a
  `(1)` variant for `x(1).jpg`. A big export is split into several zips and a
  sidecar may live in a different part than its photo.
- The extracted wrapper folder is stripped before pushing.
- Album folders are named by year/month and may carry localized (Cyrillic)
  names; those are left as they are.
- The phone itself has no sidecars: metadata is embedded in each file (EXIF).

## Edited variants
- Takeout keeps Google Photos edits next to the original with a suffix
  (`-<edited>` in the local language, `(1)`, `_1`, `-MIX`). Every edit has its
  original beside it.
- Decision: **keep them all**. Do not merge or delete outside the app.
  Duplicates are removed only through the in-app duplicates finder, with the
  user's approval (see `ROADMAP.md`, Later).
- If such a bulk delete is ever run by mistake, the Takeout copy is the backup.

## Gotchas (learned the hard way)
- **Motion photos**: Pixel `.MP` video clips are ignored by MediaStore (the
  extension is not a known media type), so no gallery shows them; the motion is
  already embedded in the matching `.MP.jpg`. Removing the `.MP` files is safe.
- **Stale tiles after a delete**: galleries can keep black "0 B" tiles for files
  that were just deleted until the media store rescans or the phone restarts.
  They are not corrupt files.
- **Re-verify before deleting**: re-hash every candidate and its keeper fresh
  right before removal; abort if any candidate has no live keeper.
- **No date taken**: Takeout files and Google-Photos restores have no
  `DateTimeOriginal`, so galleries fall back to the import day (see below).

## Timestamps (open)
Files without `DateTimeOriginal` show the import day. Fix options, not decided:
(A) write EXIF `DateTimeOriginal` + file time into the files, (B) fix the file
time only, (C) keep the date in our own Room DB (app-only), (D) write the
MediaStore `datetaken` column. Source order for any of them: EXIF date -> sidecar
`photoTakenTime` (UTC) -> local time -> filename.

## Tooling
- `bench/unify.py` (computer): `index` (sha256, size, date from EXIF else
  sidecar, sidecar path) and `report` (duplicate groups, reclaimable size,
  no-date count). Sidecar matching handles `(1)` and 46-char name truncation.
  Used for the first overlap check; the real dedupe ran on the phone with
  `sha256sum`, skipping MediaStore `.thumbnails` entries.
- Date fixing used a throwaway `exiftool` script (not committed): write
  `DateTimeOriginal`/`DateTimeDigitized`/`DateTime` (images) and
  `CreateDate`/`ModifyDate` (videos), plus file mtime.
