"""Pull the Dev tools media check report and print what differs from media.tsv (and from an emulator run).

Run the check on the phone first: Dev tools > Check media files. Then:
    uv run bench/media_check.py                      # pulls the report, compares with the table
    uv run bench/media_check.py --no-pull            # the copy already in testdata/out/
    uv run bench/media_check.py --emulator testdata/out/media_check_emulator.json

The report says pass / fail / skipped per file with the step and the reason, plus the phone's decoders.
The emulator file comes from the same row on an emulator build: it shows what only the phone gets wrong.
"""

import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLE = ROOT / 'app/src/androidTest/assets/media.tsv'
OUT = ROOT / 'testdata/out'
REMOTE = '/sdcard/Android/data/app.memoria.debug/files/media_check.json'
ADB = Path.home() / 'Library/Android/sdk/platform-tools/adb'


def pull(target):
    OUT.mkdir(parents=True, exist_ok=True)
    dest = OUT / 'media_check.json'
    adb = str(ADB) if ADB.exists() else 'adb'
    dest.write_bytes(subprocess.run([adb, *target, 'exec-out', 'cat', REMOTE], check=True, capture_output=True).stdout)
    print(f'pulled {REMOTE} -> {dest.relative_to(ROOT)}')
    return dest


def table():
    lines = [line for line in TABLE.read_text().splitlines() if line.strip()]
    head = lines[0].split('\t')
    return {row['file']: row for row in (dict(zip(head, line.split('\t'))) for line in lines[1:])}


def describe(entry):
    if entry['result'] == 'pass':
        return 'pass'
    where = f" at {entry['step']}" if entry.get('step') else ''
    return f"{entry['result']}{where}: {entry.get('reason', '')}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', nargs='?', help='a pulled media_check.json (default: testdata/out/media_check.json)')
    parser.add_argument('--no-pull', action='store_true')
    parser.add_argument('--serial', help='an adb serial (default: the one device on USB)')
    parser.add_argument('--emulator', help='a report from an emulator run, to show what the phone does differently')
    args = parser.parse_args()
    target = ['-s', args.serial] if args.serial else ['-d']

    path = Path(args.report) if args.report else (OUT / 'media_check.json' if args.no_pull else pull(target))
    report = json.loads(path.read_text())
    rows = table()
    files = {f['file']: f for f in report['files']}

    counts = {k: sum(1 for f in report['files'] if f['result'] == k) for k in ('pass', 'fail', 'skipped')}
    print(f"phone: {len(report['files'])} files, {counts['pass']} fine, {counts['fail']} failed, {counts['skipped']} skipped, "
          f"{report['millis'] // 1000 // 60}:{report['millis'] // 1000 % 60:02d}  (soc {report['soc']}, API {report['sdk']})")
    video = sorted({m for m in report['decoders'] if m.startswith('video/')})
    print(f"decoders: {len(report['decoders'])} types · video {', '.join(v.removeprefix('video/') for v in video)}")

    missing = sorted(set(rows) - set(files))
    if missing:
        print(f"\nnot in the report ({len(missing)}): {' '.join(missing)}")
    extra = sorted(set(files) - set(rows))
    if extra:
        print(f"\nnot in the table ({len(extra)}): {' '.join(extra)}")

    bad = [files[name] for name in rows if name in files and files[name]['result'] == 'fail']
    print(f"\ndiffers from the table ({len(bad)}):")
    for entry in bad:
        print(f"  {entry['file']:<28} {describe(entry)}")
    if not bad:
        print('  none')

    skipped = [files[name] for name in rows if name in files and files[name]['result'] == 'skipped']
    if skipped:
        print(f"\nskipped ({len(skipped)}):")
        for entry in skipped:
            print(f"  {entry['file']:<28} {describe(entry)}")

    if args.emulator:
        other = {f['file']: f for f in json.loads(Path(args.emulator).read_text())['files']}
        diff = [name for name in rows if name in files and name in other and files[name]['result'] != other[name]['result']]
        print(f"\ndiffers from the emulator ({len(diff)}):")
        for name in diff:
            print(f"  {name:<28} phone: {describe(files[name])} · emulator: {describe(other[name])}")
        if not diff:
            print('  none')


if __name__ == '__main__':
    main()
