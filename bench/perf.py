"""Speed numbers from the phone: cold start, janky frames per screen, memory, blank grid tiles and Library's time to show covers, on the installed release build.

Only taps, swipes and dumpsys, nothing is installed or removed, so the app's data stays.
Install the build first (`make release`). Usage: uv run bench/perf.py [--runs 3] [--serial ID]
"""
import argparse
import os
import re
import statistics
import subprocess
import sys
import time

PKG = "app.memoria"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEV = ["-d"]  # the USB phone; --serial picks another device


def sh(cmd):
    return subprocess.run([ADB, *DEV, "shell", cmd], capture_output=True, text=True, check=True).stdout


def screen():
    return subprocess.run([ADB, *DEV, "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout


def tap_text(text):
    m = re.search(rf'text="{re.escape(text)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', screen())
    if not m:
        sys.exit(f'"{text}" is not on the screen')
    x1, y1, x2, y2 = map(int, m.groups())
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(1.5)


def fling(n=4, up=True):
    a, b = (H * 3 // 4, H // 4) if up else (H // 4, H * 3 // 4)
    for _ in range(n):
        sh(f"input swipe {W // 2} {a} {W // 2} {b} 120")
        time.sleep(0.5)


def frames():
    out = sh(f"dumpsys gfxinfo {PKG}")
    num = lambda pat: float(re.search(pat, out).group(1))
    return {
        "frames": num(r"Total frames rendered: (\d+)"),
        "janky %": num(r"Janky frames: \d+ \(([\d.]+)%\)"),
        "p50 ms": num(r"50th percentile: (\d+)ms"),
        "p90 ms": num(r"90th percentile: (\d+)ms"),
        "p99 ms": num(r"99th percentile: (\d+)ms"),
    }


def cold_start():
    """First frame (am start -W) and timeline shown (Photos calls ReportDrawnWhen), in ms."""
    sh(f"am force-stop {PKG}")
    sh("logcat -c")
    first = int(re.search(r"TotalTime: (\d+)", sh(f"am start -W -n {PKG}/.MainActivity")).group(1))
    for _ in range(50):
        m = re.search(rf"Fully drawn {PKG}/\.MainActivity: \+(?:(\d+)s)?(\d+)ms", sh("logcat -d -e 'Fully drawn'"))
        if m:
            return first, int(m[1] or 0) * 1000 + int(m[2])
        time.sleep(0.2)
    return first, None


def native_mb():
    """The app's native heap (PSS, MB): thumbnails live there, so a bigger cache shows up here."""
    m = re.search(r"Native Heap:\s+(\d+)", sh(f"dumpsys meminfo {PKG}"))
    return round(int(m[1]) / 1024) if m else None


def blank_grid(caps=16):
    """Cold start, two fast flings, screencaps meanwhile: the worst share of the grid still blank (placeholder colour), in %.
    The caps hold personal photos, so they stay in memory and are deleted from the phone."""
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -n {PKG}/.MainActivity")
    time.sleep(2.5)
    swipe = f"input swipe {W // 2} {H * 4 // 5} {W // 2} {H // 5} 90"
    sh(f"({swipe}; {swipe}) & for i in $(seq {caps}); do screencap /data/local/tmp/memoria_cap$i.raw; done; wait")
    worst = 0.0
    for i in range(1, caps + 1):
        raw = subprocess.run([ADB, *DEV, "exec-out", "cat", f"/data/local/tmp/memoria_cap{i}.raw"], capture_output=True).stdout
        top = len(raw) - W * H * 4  # header size differs between Android versions
        grid = raw[top + W * 4 * (H * 15 // 100):top + W * 4 * (H * 88 // 100)]  # between the top heading and the bottom bar
        worst = max(worst, grid.count(b"\xf3\xea\xda\xff") * 4 * 100 / len(grid))  # surfaceVariant, Theme.kt
    sh("rm -f /data/local/tmp/memoria_cap*.raw")
    return round(worst, 1)


def parse_load(log):
    """The first "library load: ..." line of the app's log as {step ms}, None if there is none. Check: python3 -m doctest bench/perf.py

    >>> parse_load("11-05 I Memoria: library load: people 25, albums 30, total 171")
    {'people ms': 25, 'albums ms': 30, 'total ms': 171}
    >>> parse_load("nothing here")
    """
    m = re.search(r"library load: (.+)", log)
    return {f"{k} ms": int(v) for k, v in re.findall(r"(\w+) (\d+)", m[1])} if m else None


def library_load(caps=20):
    """Cold start, tap Library while screencaps run. Per open: ms from the tap to the first screencap that shows Library with no blank cover tile.
    First open has no cached data, the second has. A cap takes ~150-300 ms, so ~150 is the lowest number it can show: "already drawn".
    The caps hold personal photos, so they stay in memory and are deleted from the phone.
    Also returns the app's own step times of the first Library load, from its log."""
    sh(f"am force-stop {PKG}")
    sh("logcat -c")
    sh(f"am start -W -n {PKG}/.MainActivity")
    time.sleep(2.5)
    result = []
    for n in range(2):
        m = re.search(r'text="Library"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', screen())
        if not m:
            sys.exit('"Library" is not on the screen')
        x1, y1, x2, y2 = map(int, m.groups())
        out = sh(f"(input tap {(x1 + x2) // 2} {(y1 + y2) // 2}; echo tap $(date +%s%N)) & for i in $(seq {caps}); do echo cap $i $(date +%s%N); screencap /data/local/tmp/memoria_cap$i.raw; done; wait")
        tap = int(re.search(r"tap (\d+)", out)[1])
        ms = {int(i): (int(ns) - tap) / 1e6 for i, ns in re.findall(r"cap (\d+) (\d+)", out)}
        blank = {}
        for i in ms:
            raw = subprocess.run([ADB, *DEV, "exec-out", "cat", f"/data/local/tmp/memoria_cap{i}.raw"], capture_output=True).stdout
            top = len(raw) - W * H * 4  # header size differs between Android versions
            area = raw[top + W * 4 * (H * 10 // 100):top + W * 4 * (H * 88 // 100)]  # under the status bar, above the tabs
            blank[i] = area.count(b"\xf3\xea\xda\xff") * 4 * 100 / len(area)  # surfaceVariant, Theme.kt
        sh("rm -f /data/local/tmp/memoria_cap*.raw")
        # the last cap is the loaded Library; its search bar is surfaceVariant too, so "blank" means more than that
        floor = blank[caps]
        late = max((i for i in ms if ms[i] >= 0 and blank[i] > floor + 1), default=0)
        # Photos, still on screen just after the tap, has less surfaceVariant than the loaded Library
        shown = [i for i in ms if i > late and ms[i] >= 0 and blank[i] >= floor - 1]
        result.append(round(ms[shown[0]]) if shown else None)
        if n == 0:
            steps = parse_load(sh("logcat -d -s Memoria"))
            tap_text("Photos")
    return result, steps


def see_all_xy(title):
    """Where the See all on a Library section's title line is: scroll down from the top until the title shows, above the tabs."""
    fling(3, up=False)
    for _ in range(5):
        s = screen()
        t = re.search(rf'text="{title}"[^>]*?bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"', s)
        if t and H * 12 // 100 < (int(t[1]) + int(t[2])) // 2 < H * 80 // 100:
            ty = (int(t[1]) + int(t[2])) // 2
            for m in re.finditer(r'text="See all"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s):
                x1, y1, x2, y2 = map(int, m.groups())
                if abs((y1 + y2) // 2 - ty) < 60:
                    return (x1 + x2) // 2, (y1 + y2) // 2
        fling(1)
    sys.exit(f'"See all" for {title} is not on the screen')


def open_viewer():
    """Tap the first tile under the first day header (a day can have one photo); True once the viewer's Details button shows."""
    m = re.search(r'text="\w{3}, \d+ \w{3}[^"]*"[^>]*?bounds="\[\d+,\d+\]\[\d+,(\d+)\]"', screen())
    if not m:
        return False
    sh(f"input tap {W // 6} {int(m[1]) + W // 8}")
    time.sleep(1.5)
    return 'content-desc="Details"' in screen()


def run(rows):
    start = cold_start()
    rows.setdefault("Cold start", []).append({"first frame ms": start[0], "timeline ms": start[1]})

    def measure(name, action, reset=True):
        if PKG not in sh("dumpsys window | grep mCurrentFocus"):
            sys.exit("The app is not on screen any more; stopped so no tap lands elsewhere.")
        if reset:
            sh(f"dumpsys gfxinfo {PKG} reset")
        if action() is False:
            return
        time.sleep(1)
        rows.setdefault(name, []).append(frames())

    measure("Photos, first scroll", fling, reset=False)  # frames since launch: the first-load stutter
    measure("Photos, scroll", fling)
    rows.setdefault("Memory after 8 flings", []).append({"native heap MB": native_mb()})
    measure("Library, open + scroll", lambda: (tap_text("Library"), fling(2)))
    for title in ("Albums", "Places", "Categories"):
        x, y = see_all_xy(title)  # found before the reset, so the scrolling to it is not counted
        measure(f"{title}, open + scroll", lambda: (sh(f"input tap {x} {y}"), time.sleep(1.5), fling(2)))
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
    fling(3, up=False)  # back to the top, where the first See all is People's
    measure("People, open + scroll", lambda: (tap_text("See all"), fling(2)))
    sh("input keyevent KEYCODE_BACK")
    tap_text("Photos")

    def viewer():
        if not open_viewer():
            print("viewer did not open, skipped")
            return False
        for _ in range(3):
            sh(f"input swipe {W * 4 // 5} {H // 2} {W // 5} {H // 2} 150")
            time.sleep(0.8)

    n = len(rows.get("Viewer, open + 3 swipes", []))
    measure("Viewer, open + 3 swipes", viewer)
    if len(rows.get("Viewer, open + 3 swipes", [])) > n:  # no Back after: the next run force-stops anyway
        measure("Details, open", lambda: sh(f"input swipe {W // 2} {H * 7 // 10} {W // 2} {H * 3 // 10} 250"))
    # frame times miss slow loading, so also count tiles still blank while the grid flies by
    rows.setdefault("Photos, blank on cold fling", []).append({"worst %": blank_grid()})
    (first, second), steps = library_load()
    rows.setdefault("Library, covers shown", []).append({"first open ms": first, "second open ms": second})
    rows.setdefault("Library, load steps", []).append(steps or {"total ms": None})


def main():
    global W, H
    p = argparse.ArgumentParser()
    p.add_argument("--runs", type=int, default=3)
    p.add_argument("--serial")
    a = p.parse_args()
    if a.serial:
        DEV[:] = ["-s", a.serial]

    if "DEBUGGABLE" in sh(f"dumpsys package {PKG}"):
        sys.exit("A debug build is installed; its numbers mean little. Run `make release` first.")
    if "SystemForegroundService" in sh(f"dumpsys activity services {PKG}"):
        sys.exit("An index run is going. Pause it (Library) or let it finish, then run again.")
    W, H = map(int, re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1])

    # compile with the baseline profile, as Android does a day after install, so every run starts the same
    sh(f"am start -W -n {PKG}/.MainActivity")
    sh(f"am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n {PKG}/androidx.profileinstaller.ProfileInstallReceiver")
    print("compile:", sh(f"cmd package compile -f -m speed-profile {PKG}").strip())

    rows = {}
    for i in range(a.runs):
        print(f"run {i + 1}/{a.runs}")
        run(rows)

    print(f"\nmedian of {a.runs} runs")
    for name, runs in rows.items():
        vals = {k: [r[k] for r in runs if r.get(k) is not None] for k in runs[0]}
        print(f"{name:26}", "  ".join(f"{k} {statistics.median(v):g}" if v else f"{k} -" for k, v in vals.items()))


if __name__ == "__main__":
    main()
