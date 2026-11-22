#!/usr/bin/env python3
"""Drives WiseTV mobile on a phone emulator the way a person would: by tapping and swiping.

Usage: e2e.py <results-dir> <apk>
Finds things on screen with uiautomator, taps them through adb, waits for the app's own log
lines (tag "WiseTV"), takes screenshots, and records pass/fail checks in <results-dir>/report.txt.
"""
import os
import re
import subprocess
import sys
import time
import urllib.request

OUT = sys.argv[1]
APK = sys.argv[2]
PKG = "com.wisetv.mobile"
SERVER = "http://10.0.2.2:8080"
BACK, HOME = 4, 3

os.makedirs(OUT, exist_ok=True)
checks = []
shots = 0


def sh(cmd, timeout=90):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout, errors="replace").stdout
    except subprocess.TimeoutExpired:
        return ""


def say(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def key(code, pause=0.8):
    sh(f"adb shell input keyevent {code}")
    time.sleep(pause)


def shot(name):
    global shots
    shots += 1
    path = os.path.join(OUT, f"{shots:02d}-{name}.png")
    subprocess.run(f"adb exec-out screencap -p > '{path}'", shell=True, timeout=60)
    say(f"screenshot {os.path.basename(path)}")


def app_log():
    return [l for l in sh("adb logcat -d -v time -s WiseTV:I").splitlines() if "WiseTV" in l]


def mark():
    return len(app_log())


def last_log(pattern, since=0):
    rx = re.compile(pattern)
    hits = [l for l in app_log()[since:] if rx.search(l)]
    return hits[-1] if hits else None


def wait_log(pattern, timeout=40, since=0):
    end = time.time() + timeout
    rx = re.compile(pattern)
    while time.time() < end:
        for line in app_log()[since:]:
            if rx.search(line):
                return line
        time.sleep(1)
    return None


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    say(f"{'PASS' if ok else 'FAIL'}: {name} {detail}")


def step(title):
    say(f"--- {title}")


def crashed():
    out = sh("adb logcat -d -b crash")
    return out if PKG in out else ""


# ---------------------------------------------------------------- finding things on screen

NODE = re.compile(r'<node [^>]*?>')
ATTR = re.compile(r'(\w[\w-]*)="([^"]*)"')


def nodes():
    """The current screen's accessibility nodes: dicts with text, desc and centre."""
    for _ in range(4):
        sh("adb shell rm -f /sdcard/ui.xml")
        sh("adb shell uiautomator dump --compressed /sdcard/ui.xml", timeout=40)
        xml = sh("adb shell cat /sdcard/ui.xml")
        if "<hierarchy" in xml:
            out = []
            for m in NODE.finditer(xml):
                a = dict(ATTR.findall(m.group(0)))
                b = re.findall(r"\d+", a.get("bounds", ""))
                if len(b) != 4:
                    continue
                x1, y1, x2, y2 = map(int, b)
                out.append({"text": a.get("text", ""), "desc": a.get("content-desc", ""), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                            "x1": x1, "y1": y1, "x2": x2, "y2": y2})
            return out
        time.sleep(1.5)
    return []


def find(text=None, desc=None, exact=True, timeout=15, where=None):
    end = time.time() + timeout
    while time.time() < end:
        for n in nodes():
            for want, got in ((text, n["text"]), (desc, n["desc"])):
                if want is None or not got:
                    continue
                if (got == want) if exact else (want.lower() in got.lower()):
                    if where is None or where(n):
                        return n
        time.sleep(1)
    return None


def tap(text=None, desc=None, exact=True, timeout=15, where=None, long=False):
    n = find(text, desc, exact, timeout, where)
    if not n:
        say(f"not found on screen: {text or desc}")
        return False
    if long:
        sh(f"adb shell input swipe {n['x']} {n['y']} {n['x']} {n['y']} 900")
    else:
        sh(f"adb shell input tap {n['x']} {n['y']}")
    time.sleep(1.2)
    return True


def type_into(label, value):
    if not tap(label, exact=True):
        return False
    sh(f"adb shell input text '{value}'")
    time.sleep(0.6)
    return True


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("adb shell wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


# ---------------------------------------------------------------- the test

def run():
    step("install and first launch")
    say(sh(f"adb install -r '{APK}'", timeout=180).strip())
    sh(f"adb shell setprop debug.wisetv.plex_tv {SERVER}")
    sh(f"adb shell setprop debug.wisetv.update_url {SERVER}/update/latest.json")
    sh(f"adb shell appops set {PKG} REQUEST_INSTALL_PACKAGES allow")
    sh(f"adb shell pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    sh("adb shell settings put system accelerometer_rotation 1")
    # Android's one-time "Viewing full screen" tip would swallow the next tap or Back press.
    sh("adb shell settings put secure immersive_mode_confirmations confirmed")
    sh("adb logcat -c")
    sh(f"adb shell am start -n {PKG}/.ui.MainActivity")
    check("app starts on the add-playlist screen", wait_log(r"screen AddPlaylist", 60))
    time.sleep(3)
    shot("welcome")

    step("free channel presets are offered")
    tap("Free channels")
    time.sleep(1)
    check("the free playlists from WiseTV for Windows are listed", find("Samsung TV Plus", timeout=5) and find("Roku Channel", timeout=5))
    shot("free-channels")

    step("add an Xtream playlist")
    tap("Xtream Codes")
    type_into("Playlist name (optional)", "Test TV")
    type_into("Server address", SERVER)
    type_into("Username", "u")
    type_into("Password", "p")
    key(BACK)  # hide the keyboard
    shot("xtream-form")
    m = mark()
    tap("Add playlist")
    check("channels, movies and series load", wait_log(r"sync done", 120, m))
    check("group picker opens", wait_log(r"screen ManageGroups", 30, m))
    time.sleep(2)
    shot("choose-groups")
    m = mark()
    tap("Done")
    check("Live TV opens", wait_log(r"screen Main\(section=LIVE\)", 30, m))
    line = wait_log(r"epg done", 90, m)
    n = int(re.search(r"programmes=(\d+)", line).group(1)) if line else 0
    check("TV guide loads", n > 300, f"({n} programmes)")
    time.sleep(4)
    shot("guide")

    step("tap a channel: it plays at the top")
    w, h = screen_size()
    m = mark()
    # The guide's channel column is on the left; channel 1 shows its number under the logo.
    tap("1", where=lambda nd: nd["x"] < w * 0.25)
    check("channel plays in the mini player", wait_log(r"tune channel=News One", 20, m) and wait_log(r"player state=READY", 45, m))
    time.sleep(4)
    shot("mini-player")

    step("full screen")
    m = mark()
    tap(desc="Full screen")
    check("full-screen player opens", wait_log(r"screen Player", 20, m))
    time.sleep(2)
    tap("Got it", timeout=2)
    time.sleep(1)
    shot("player-controls")
    time.sleep(5)
    shot("player-clean")
    w2, h2 = screen_size()
    landscape = "landscape" if "SurfaceOrientation: 1" in sh("adb shell dumpsys input | grep SurfaceOrientation") or \
        "mCurrentRotation=ROTATION_90" in sh("adb shell dumpsys window displays | grep mCurrentRotation") else "portrait"
    say(f"orientation: {landscape}")

    step("swipe up: next channel (HLS)")
    m = mark()
    dims = sh("adb shell dumpsys window displays | grep -m1 'cur='")
    say(dims.strip())
    mm = re.search(r"cur=(\d+)x(\d+)", dims)
    cw, ch = (int(mm.group(1)), int(mm.group(2))) if mm else (2400, 1080)
    sh(f"adb shell input swipe {cw // 2} {int(ch * 0.75)} {cw // 2} {int(ch * 0.25)} 250")
    check("swiping changes channel", wait_log(r"tune channel=World Report", 20, m))
    check("HLS channel plays", wait_log(r"player state=READY", 60, m))
    time.sleep(3)
    shot("player-hls-channel")

    step("controls: channel list and options")
    sh(f"adb shell input tap {cw // 2} {ch // 2}")
    time.sleep(1)
    if not find("Channels", timeout=3):
        sh(f"adb shell input tap {cw // 2} {ch // 2}")
    shot("player-controls-2")
    if tap("Channels", timeout=5):
        time.sleep(1.5)
        shot("channel-panel")
        key(BACK)
    sh(f"adb shell input tap {cw // 2} {ch // 2}")
    time.sleep(1)
    if tap(desc="Options", timeout=5):
        time.sleep(1.5)
        shot("player-options")
        key(BACK)
    check("video decoder is logged", any("video decoder" in l for l in app_log()))

    step("background audio")
    m = mark()
    key(HOME, pause=3)
    check("the picture stops in the background", wait_log(r"video off \(background audio\)", 10, m))
    time.sleep(4)
    check("the sound keeps playing", not last_log(r"player state=(IDLE|ENDED)", m))
    sessions = sh("adb shell dumpsys media_session")
    check("a media session is published for the notification", PKG in sessions, "")
    sh("adb shell cmd statusbar expand-notifications")
    time.sleep(2)
    shot("notification")
    sh("adb shell cmd statusbar collapse")
    m = mark()
    sh(f"adb shell am start -n {PKG}/.ui.MainActivity")
    check("the picture comes back", wait_log(r"video on", 15, m))
    time.sleep(2)
    m = mark()
    key(BACK)
    check("Back returns to Live TV", wait_log(r"screen Main\(section=LIVE\)", 20, m))
    time.sleep(2)
    shot("live-after-player")

    step("list view and favorites")
    tap(desc="Show as a list")
    time.sleep(2)
    shot("channel-list")
    m = mark()
    if tap("Sports Arena", exact=False, long=True, timeout=8) or tap("Goal TV", exact=False, long=True, timeout=5):
        time.sleep(1.5)
        shot("channel-sheet")
        tap("Add to favorites")
        check("a channel can be added to favorites", wait_log(r"favorite .* -> true", 10, m))
    else:
        check("a channel can be added to favorites", False, "(no channel row found)")
    tap(desc="Show the guide")
    time.sleep(1)

    step("movies")
    m = mark()
    tap("Movies", where=lambda nd: nd["y"] > h * 0.85)
    check("Movies opens", wait_log(r"screen Main\(section=MOVIES\)", 20, m))
    time.sleep(3)
    shot("movies")
    m = mark()
    tap("Test Movie 1 (2001)", timeout=10)
    check("movie page opens", wait_log(r"screen MovieDetail", 15, m))
    time.sleep(2)
    shot("movie-detail")
    m = mark()
    tap("Play")
    check("movie plays", wait_log(r"play kind=movie", 15, m) and wait_log(r"player state=READY live=false", 45, m))
    time.sleep(4)
    shot("movie-playing")
    m = mark()
    key(BACK)
    time.sleep(1)
    key(BACK)
    check("back to Movies with the now-playing bar", wait_log(r"screen Main\(section=MOVIES\)", 15, m))
    time.sleep(2)
    shot("now-playing-bar")

    step("series")
    m = mark()
    tap("Series", where=lambda nd: nd["y"] > h * 0.85)
    time.sleep(3)
    tap("Test Series 1", timeout=10)
    check("series page opens", wait_log(r"screen SeriesDetail", 15, m))
    time.sleep(3)
    shot("series-detail")
    key(BACK)

    step("search")
    m = mark()
    tap("Search", where=lambda nd: nd["y"] > h * 0.85)
    check("Search opens", wait_log(r"screen Main\(section=SEARCH\)", 15, m))
    tap("Channels, programmes, movies, series", exact=False)
    sh("adb shell input text 'test'")
    time.sleep(3)
    key(BACK)
    shot("search-results")
    check("search finds movies and series", find("Test Movie", exact=False, timeout=8) is not None)

    step("Plex")
    tap("Settings", where=lambda nd: nd["y"] > h * 0.85)
    time.sleep(2)
    shot("settings")
    m = mark()
    if tap("Link your Plex account", timeout=8):
        time.sleep(2)
        shot("plex-link")
        check("Plex links with a code", wait_log(r"plex linked", 30, m))
        time.sleep(6)
        shot("plex-linked")
    tap("Movies", where=lambda nd: nd["y"] > h * 0.85)
    time.sleep(3)
    tap("Recently added", exact=False, timeout=5)
    time.sleep(2)
    shot("movie-categories")
    check("Plex libraries appear among the movie categories", find("Plex", exact=False, timeout=5) is not None)
    key(BACK)

    step("update offered")
    m = mark()
    try:
        urllib.request.urlopen(urllib.request.Request(f"{SERVER.replace('10.0.2.2', '127.0.0.1')}/control/update-on", data=b""), timeout=5)
    except Exception as e:  # noqa: BLE001
        say(f"control error: {e}")
    tap("Settings", where=lambda nd: nd["y"] > h * 0.85)
    time.sleep(1)
    tap("WiseTV mobile", exact=False)
    check("a new version is found and downloaded", wait_log(r"update ready 9\.9\.9", 60, m))
    time.sleep(4)
    shot("update-offer")

    check("no crash", not crashed())


if __name__ == "__main__":
    try:
        run()
    except Exception as e:  # noqa: BLE001
        check("test script ran to the end", False, repr(e))
    finally:
        c = crashed()
        if c:
            open(os.path.join(OUT, "crash.txt"), "w").write(c)
        open(os.path.join(OUT, "app-log.txt"), "w").write("\n".join(app_log()))
        sh(f"adb logcat -d -v time > '{os.path.join(OUT, 'logcat.txt')}'")
        passed = sum(1 for _, ok, _ in checks if ok)
        with open(os.path.join(OUT, "report.txt"), "w") as f:
            f.write(f"{passed}/{len(checks)} checks passed\n\n")
            for name, ok, detail in checks:
                f.write(f"{'PASS' if ok else 'FAIL'}  {name} {detail}\n")
        print(open(os.path.join(OUT, "report.txt")).read())
        sys.exit(0 if checks and passed == len(checks) else 1)
