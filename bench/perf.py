"""Speed numbers from the phone: cold start and janky frames per screen, on the installed release build.

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


def open_viewer():
    """Tap the second tile under the first day header; True once the viewer's Details button shows."""
    m = re.search(r'text="\w{3}, \d+ \w{3}[^"]*"[^>]*?bounds="\[\d+,\d+\]\[\d+,(\d+)\]"', screen())
    if not m:
        return False
    sh(f"input tap {W * 3 // 8} {int(m[1]) + W // 8}")
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
    measure("Library, open + scroll", lambda: (tap_text("Library"), fling(2)))
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
    sh(f"am broadcast -a androidx.profileinstaller.action.INSTALL_BASELINE_PROFILE -n {PKG}/androidx.profileinstaller.ProfileInstallReceiver")
    print("compile:", sh(f"cmd package compile -f -m speed-profile {PKG}").strip())

    rows = {}
    for i in range(a.runs):
        print(f"run {i + 1}/{a.runs}")
        run(rows)

    print(f"\nmedian of {a.runs} runs")
    for name, runs in rows.items():
        vals = {k: [r[k] for r in runs if r[k] is not None] for k in runs[0]}
        print(f"{name:26}", "  ".join(f"{k} {statistics.median(v):g}" if v else f"{k} -" for k, v in vals.items()))


if __name__ == "__main__":
    main()
