#!/usr/bin/env python3
"""A pretend IPTV provider for the emulator test.

Speaks enough of the Xtream Codes API for WiseTV: login, live channels, movies, series,
an XMLTV guide generated around the current time, a throttled MPEG-TS live stream, an HLS
live stream, and MP4 files with range support. Like a real one-connection provider it
refuses a second simultaneous live stream.
"""
import json
import os
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MEDIA = sys.argv[1] if len(sys.argv) > 1 else "media"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
T0 = time.time()
HLS_SEG = 4
LIVE_TS = os.path.join(MEDIA, "live.ts")
LIVE_SECONDS = float(os.environ.get("LIVE_SECONDS", "420"))

live_lock = threading.Lock()
live_active = 0
state = {"pin_polls": 0, "update_on": False}

# ---------------------------------------------------------------- pretend Plex
PLEX_TOKEN = "plex-account-token"
SRV_TOKEN = "srv-token"
SRV2_TOKEN = "srv2-token"  # a friend's server shared with this account


def plex_movie(rk, title, container, vcodec, acodec, offset=0, subs=False):
    streams = [{"streamType": 1, "codec": vcodec}, {"streamType": 2, "codec": acodec}]
    if subs:
        streams.append({"streamType": 3, "codec": "srt", "key": f"/library/streams/{rk}9", "languageCode": "eng", "displayTitle": "English (SRT External)"})
    return {"ratingKey": str(rk), "key": f"/library/metadata/{rk}", "type": "movie", "title": title, "year": 2021,
            "summary": f"{title}: a test film served by the pretend Plex server.", "thumb": f"/library/metadata/{rk}/thumb/1",
            "art": f"/library/metadata/{rk}/art/1", "audienceRating": 8.1, "addedAt": int(T0) - rk, "duration": 120000,
            "viewOffset": offset, "contentRating": "PG", "Genre": [{"tag": "Test"}], "Director": [{"tag": "F. Mpeg"}],
            "Role": [{"tag": "A. Bars"}, {"tag": "B. Tone"}],
            "Media": [{"container": container, "videoCodec": vcodec, "audioCodec": acodec, "bitrate": 3000, "height": 720,
                       "Part": [{"key": f"/library/parts/{rk}/file.{container}", "container": container, "Stream": streams}]}]}


PLEX_MOVIES = [plex_movie(1001, "A Plex Movie", "mp4", "h264", "aac", subs=True),
               plex_movie(1002, "B Plex Movie with DTS", "mkv", "h264", "dca")]


def plex_episode(rk, season, ep):
    return {"ratingKey": str(rk), "key": f"/library/metadata/{rk}", "type": "episode", "title": f"Pilot {season}.{ep}",
            "index": ep, "parentIndex": season, "grandparentTitle": "A Plex Show", "grandparentRatingKey": "2001",
            "grandparentThumb": "/library/metadata/2001/thumb/1", "summary": "Something happens.", "thumb": f"/library/metadata/{rk}/thumb/1",
            "duration": 120000, "viewOffset": 0, "viewCount": 1 if rk == 2111 else 0, "addedAt": int(T0),
            "Media": [{"container": "mp4", "videoCodec": "h264", "audioCodec": "aac", "bitrate": 3000,
                       "Part": [{"key": f"/library/parts/{rk}/file.mp4", "container": "mp4"}]}]}


PLEX_EPISODES = [plex_episode(2100 + s * 10 + e, s, e) for s in (1, 2) for e in (1, 2)]
PLEX_SHOW = {"ratingKey": "2001", "key": "/library/metadata/2001/children", "type": "show", "title": "A Plex Show", "year": 2020,
             "summary": "A test show on the pretend Plex server.", "thumb": "/library/metadata/2001/thumb/1", "leafCount": 4,
             "viewedLeafCount": 1, "addedAt": int(T0), "Genre": [{"tag": "Drama"}]}


def mc(**kw):
    return {"MediaContainer": kw}


def plex_server(path, q):
    """Returns (status, json-able body) for a request under /plexsrv."""
    if path == "/identity":
        return 200, mc(machineIdentifier="srv1")
    if path == "/library/sections":
        return 200, mc(Directory=[{"key": "1", "type": "movie", "title": "Movies"}, {"key": "2", "type": "show", "title": "TV Shows"},
                                  {"key": "3", "type": "artist", "title": "Music"}])
    if path in ("/library/sections/1/all", "/library/sections/1/recentlyAdded"):
        return 200, mc(Metadata=PLEX_MOVIES)
    if path == "/library/sections/2/all":
        return 200, mc(Metadata=[PLEX_SHOW])
    if path == "/library/sections/2/recentlyAdded":
        # Like real Plex: new seasons and episodes, not shows.
        season = {"ratingKey": "2002", "type": "season", "title": "Season 2", "index": 2, "parentRatingKey": "2001",
                  "parentTitle": "A Plex Show", "parentThumb": "/library/metadata/2001/thumb/1", "thumb": "/library/metadata/2002/thumb/1",
                  "addedAt": int(T0)}
        return 200, mc(Metadata=[PLEX_EPISODES[3], season])
    if path == "/library/onDeck":
        return 200, mc(Metadata=[PLEX_EPISODES[1]])
    if path == "/library/metadata/2001/allLeaves":
        return 200, mc(Metadata=PLEX_EPISODES)
    if path.startswith("/library/metadata/"):
        rk = path.split("/")[3]
        for m in PLEX_MOVIES + PLEX_EPISODES + [PLEX_SHOW]:
            if m["ratingKey"] == rk:
                return 200, mc(Metadata=[m])
        return 404, mc()
    if path == "/hubs/search":
        return 200, mc(Hub=[{"type": "movie", "Metadata": PLEX_MOVIES}, {"type": "show", "Metadata": [PLEX_SHOW]}])
    if path in ("/:/timeline", "/:/scrobble", "/video/:/transcode/universal/stop"):
        log(f"plex {path} " + " ".join(f"{k}={v[0]}" for k, v in sorted(q.items()) if k in ("state", "time", "ratingKey", "key", "session")))
        return 200, mc()
    return None

CATS = [("1", "News"), ("2", "Sports"), ("3", "Entertainment")]
CHANNELS = [
    (1, "News One HD", "1"), (2, "World Report (HLS)", "1"), (3, "Daily Stories", "1"), (4, "Capital News", "1"),
    (5, "Sports Arena", "2"), (6, "Goal TV", "2"), (7, "Racing Live", "2"),
    (8, "Comedy Box", "3"), (9, "Drama Hour", "3"), (10, "Nature World", "3"), (11, "Kids Corner", "3"), (12, "No Guide Channel", "3"),
]
SHOWS = ["Morning Briefing", "The Daily Show", "City Desk", "Match of the Day", "Wild Planet", "Late Edition", "Cooking Hour", "Tech Today"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def xmltv():
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="mock">']
    for num, name, _ in CHANNELS:
        if num == 12:
            continue
        out.append(f'<channel id="ch{num}"><display-name>{name}</display-name></channel>')
    for num, name, _ in CHANNELS:
        if num == 12:
            continue
        t = now - timedelta(hours=3)
        i = num
        while t < now + timedelta(hours=40):
            length = 30 if (i % 3) else 60
            title = SHOWS[i % len(SHOWS)]
            if num == 3:
                # A series with a new episode in every slot, for series-rule tests. Three-hour slots
                # from the top of the hour keep the next airing well clear of the test run itself.
                title, length = "The Daily Show", 180
            stop = t + timedelta(minutes=length)
            fmt = "%Y%m%d%H%M%S +0000"
            out.append(
                f'<programme start="{t.strftime(fmt)}" stop="{stop.strftime(fmt)}" channel="ch{num}">'
                f'<title>{title}</title><sub-title>Episode {i}</sub-title>'
                f'<desc>{title} on {name}. {"Cartoons & more: sloppy XML on purpose." if num == 11 else "Rock &amp; roll, weather and more."}</desc>'
                f'<category>General</category><episode-num system="onscreen">S1 E{i}</episode-num></programme>'
            )
            t = stop
            i += 1
    out.append("</tv>")
    return "\n".join(out).encode()


def api(q, host):
    action = q.get("action", [None])[0]
    base = f"http://{host}"
    if action is None:
        return {
            "user_info": {"username": "u", "auth": 1, "status": "Active", "exp_date": str(int(time.time()) + 86400 * 200),
                          "max_connections": "1", "active_cons": str(live_active)},
            "server_info": {"timezone": "UTC"},
        }
    if action == "get_live_categories":
        return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in CATS]
    if action == "get_live_streams":
        return [{"num": n, "name": name, "stream_type": "live", "stream_id": n, "stream_icon": f"{base}/img/logo{n % 4}.png",
                 "epg_channel_id": None if n == 12 else f"ch{n}", "category_id": cat, "tv_archive": 0} for n, name, cat in CHANNELS]
    if action == "get_vod_categories":
        return [{"category_id": "10", "category_name": "Action"}, {"category_id": "11", "category_name": "Comedy"}]
    if action == "get_vod_streams":
        return [{"num": i, "name": f"Test Movie {i} ({2000 + i})", "stream_type": "movie", "stream_id": 100 + i,
                 "stream_icon": f"{base}/img/poster{i % 4}.png", "rating": str(5 + i % 5), "added": str(int(T0) - i * 3600),
                 "category_id": "10" if i % 2 else "11", "container_extension": "mp4"} for i in range(1, 25)]
    if action == "get_vod_info":
        return {"info": {"plot": "A test movie about colour bars and a steady tone.", "genre": "Test", "cast": "A. Bars, B. Tone",
                         "director": "F. Mpeg", "releasedate": "2020-01-01", "duration": "00:02:00", "rating": "7.5"},
                "movie_data": {"stream_id": int(q.get("vod_id", ["101"])[0]), "container_extension": "mp4"}}
    if action == "get_series_categories":
        return [{"category_id": "20", "category_name": "Drama"}]
    if action == "get_series":
        return [{"num": i, "name": f"Test Series {i}", "series_id": 200 + i, "cover": f"{base}/img/poster{(i + 1) % 4}.png",
                 "plot": "A short test series.", "genre": "Drama", "rating": "8", "last_modified": str(int(T0) - i * 100),
                 "category_id": "20"} for i in range(1, 9)]
    if action == "get_series_info":
        eps = {}
        n = 0
        for season in (1, 2):
            eps[str(season)] = []
            for e in (1, 2, 3):
                n += 1
                eps[str(season)].append({"id": str(3000 + n), "episode_num": e, "title": f"Chapter {e}", "container_extension": "mp4",
                                         "season": season, "info": {"plot": f"Things happen in chapter {e}.", "duration_secs": 120}})
        return {"info": {"name": "Test Series", "plot": "A short test series.", "genre": "Drama", "rating": "8"}, "episodes": eps}
    return []


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send_bytes(self, body, ctype="application/json", code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/v2/pins":
            state["pin_polls"] = 0
            log("plex.tv pin requested")
            return self.send_bytes(json.dumps({"id": 42, "code": "WTV4"}).encode(), code=201)
        if u.path == "/control/update-on":
            state["update_on"] = True
            log("control: update published")
            return self.send_bytes(b"ok", "text/plain")
        self.send_bytes(b"not found", "text/plain", 404)

    def plex_tv(self, path):
        if path == "/api/v2/pins/42":
            state["pin_polls"] += 1
            token = PLEX_TOKEN if state["pin_polls"] >= 2 else None  # "entered" after a couple of polls
            return self.send_bytes(json.dumps({"id": 42, "code": "WTV4", "authToken": token}).encode())
        if self.headers.get("X-Plex-Token") != PLEX_TOKEN:
            return self.send_bytes(b"{}", code=401)
        if path == "/api/v2/user":
            return self.send_bytes(json.dumps({"username": "jonathan", "title": "jonathan"}).encode())
        if path == "/api/v2/resources":
            host = self.headers.get("Host", f"10.0.2.2:{PORT}").split(":")[0]
            res = [{"name": "Home Plex", "product": "Plex Media Server", "provides": "server", "clientIdentifier": "srv1",
                    "accessToken": SRV_TOKEN, "owned": True,
                    "connections": [
                        # A dead address first, then one that only works with the right path, like real life's surprises.
                        {"protocol": "https", "address": "192.0.2.10", "port": 32400, "uri": "https://192-0-2-10.abc.plex.direct:32400", "local": True, "relay": False},
                        {"protocol": "http", "address": host, "port": PORT, "uri": f"http://{host}:{PORT}/plexsrv", "local": True, "relay": False}]},
                   {"name": "Friend's Plex", "product": "Plex Media Server", "provides": "server", "clientIdentifier": "srv2",
                    "accessToken": SRV2_TOKEN, "owned": False,
                    "connections": [{"protocol": "http", "address": host, "port": PORT, "uri": f"http://{host}:{PORT}/plexsrv2", "local": False, "relay": False}]},
                   {"name": "Phone", "product": "Plex for Android", "provides": "player", "clientIdentifier": "p1", "connections": []}]
            return self.send_bytes(json.dumps(res).encode())
        return self.send_bytes(b"{}", code=404)

    def do_GET(self):
        global live_active
        u = urlparse(self.path)
        q = parse_qs(u.query)
        path = u.path
        host = self.headers.get("Host", f"10.0.2.2:{PORT}")
        try:
            if path.startswith("/api/v2/"):
                return self.plex_tv(path)
            if path.startswith("/plexsrv/"):
                return self.plex(path[len("/plexsrv"):], q)
            if path.startswith("/plexsrv2/"):
                return self.plex2(path[len("/plexsrv2"):], q)
            if path == "/update/latest.json":
                if not state["update_on"]:
                    return self.send_bytes(b"not yet", "text/plain", 404)
                apk = os.path.join(MEDIA, "update.apk")
                import hashlib
                sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
                info = {"versionCode": 999999, "versionName": "9.9.9", "apk": "WiseTV-9.9.9.apk", "sha256": sha,
                        "size": os.path.getsize(apk), "notes": "Test update from the pretend server"}
                return self.send_bytes(json.dumps(info).encode())
            if path == "/update/WiseTV-9.9.9.apk":
                log("update apk downloaded")
                return self.send_file(os.path.join(MEDIA, "update.apk"), "application/vnd.android.package-archive")
            if path == "/player_api.php":
                return self.send_bytes(json.dumps(api(q, host)).encode())
            if path == "/xmltv.php":
                return self.send_bytes(xmltv(), "application/xml")
            if path.startswith("/img/"):
                return self.send_file(os.path.join(MEDIA, os.path.basename(path)), "image/png")
            if path.startswith("/live/"):
                sid = os.path.basename(path).split(".")[0]
                if sid == "2":
                    # Like some real providers: a ".ts" address that turns out to be HLS.
                    self.send_response(302)
                    self.send_header("Location", f"http://{host}/hls/live.m3u8")
                    self.send_header("Content-Length", "0")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    return
                return self.live_ts(sid)
            if path == "/hls/live.m3u8":
                return self.send_bytes(self.hls_playlist(), "application/vnd.apple.mpegurl")
            if path.startswith("/hls/"):
                return self.send_file(os.path.join(MEDIA, "hls", os.path.basename(path)), "video/mp2t")
            if path.startswith("/movie/") or path.startswith("/series/"):
                return self.send_file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
            self.send_bytes(b"not found", "text/plain", 404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def plex(self, path, q):
        token = self.headers.get("X-Plex-Token") or q.get("X-Plex-Token", [None])[0]
        if token != SRV_TOKEN:
            return self.send_bytes(b"{}", code=401)
        if path.startswith("/photo/:/transcode"):
            return self.send_file(os.path.join(MEDIA, "poster1.png"), "image/png")
        if path.startswith("/library/parts/"):
            log(f"plex direct play {path}")
            return self.send_file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
        if path.startswith("/library/streams/"):
            srt = "1\n00:00:01,000 --> 00:00:30,000\nSubtitle from Plex\n"
            return self.send_bytes(srt.encode(), "application/x-subrip")
        if path == "/video/:/transcode/universal/start.m3u8":
            log(f"plex transcode start path={q.get('path', ['?'])[0]} bitrate={q.get('maxVideoBitrate', ['original'])[0]}")
            files = sorted(f for f in os.listdir(os.path.join(MEDIA, "hls")) if f.endswith(".ts"))[:30]
            lines = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{HLS_SEG}", "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-MEDIA-SEQUENCE:0"]
            for f in files:
                lines += [f"#EXTINF:{HLS_SEG}.0,", f]
            lines.append("#EXT-X-ENDLIST")
            return self.send_bytes(("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl")
        if path.startswith("/video/:/transcode/universal/") and path.endswith(".ts"):
            return self.send_file(os.path.join(MEDIA, "hls", os.path.basename(path)), "video/mp2t")
        res = plex_server(path, q)
        if res is None:
            log(f"plex 404 {path}")
            return self.send_bytes(b"{}", code=404)
        code, body = res
        return self.send_bytes(json.dumps(body).encode(), code=code)

    def plex2(self, path, q):
        """The shared server: one movie library with one film."""
        token = self.headers.get("X-Plex-Token") or q.get("X-Plex-Token", [None])[0]
        if token != SRV2_TOKEN:
            return self.send_bytes(b"{}", code=401)
        movie = plex_movie(5001, "Friend's Movie", "mp4", "h264", "aac")
        if path.startswith("/photo/:/transcode"):
            return self.send_file(os.path.join(MEDIA, "poster2.png"), "image/png")
        if path.startswith("/library/parts/"):
            log(f"plex2 direct play {path}")
            return self.send_file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
        if path == "/identity":
            body = mc(machineIdentifier="srv2")
        elif path == "/library/sections":
            body = mc(Directory=[{"key": "7", "type": "movie", "title": "Friend Movies"}])
        elif path in ("/library/sections/7/all", "/library/sections/7/recentlyAdded", "/hubs/search"):
            body = mc(Metadata=[movie]) if path != "/hubs/search" else mc(Hub=[{"type": "movie", "Metadata": [movie]}])
        elif path == "/library/metadata/5001":
            body = mc(Metadata=[movie])
        elif path == "/library/onDeck":
            body = mc(Metadata=[])
        elif path in ("/:/timeline", "/:/scrobble"):
            log(f"plex2 {path} " + " ".join(f"{k}={v[0]}" for k, v in sorted(q.items()) if k in ("state", "ratingKey", "key")))
            body = mc()
        else:
            log(f"plex2 404 {path}")
            return self.send_bytes(b"{}", code=404)
        return self.send_bytes(json.dumps(body).encode())

    def hls_playlist(self):
        files = sorted(f for f in os.listdir(os.path.join(MEDIA, "hls")) if f.endswith(".ts"))
        seq = int((time.time() - T0) / HLS_SEG) + 5
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{HLS_SEG}", f"#EXT-X-MEDIA-SEQUENCE:{seq - 4}"]
        for s in range(seq - 4, seq + 1):
            if s % len(files) == 0:
                lines.append("#EXT-X-DISCONTINUITY")
            lines.append(f"#EXTINF:{HLS_SEG}.0,")
            lines.append(files[s % len(files)])
        return ("\n".join(lines) + "\n").encode()

    def send_file(self, fpath, ctype):
        if not os.path.exists(fpath):
            return self.send_bytes(b"missing", "text/plain", 404)
        size = os.path.getsize(fpath)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        code = 200
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a) if a else 0
            end = int(b) if b else size - 1
            code = 206
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Connection", "close")
        self.end_headers()
        with open(fpath, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(256 * 1024, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def live_ts(self, sid):
        global live_active
        # One connection at a time, with a short grace period for the previous one to hang up.
        deadline = time.time() + 1.0
        while True:
            with live_lock:
                if live_active < 1:
                    live_active += 1
                    break
            if time.time() > deadline:
                log(f"live {sid}: REFUSED (connection limit)")
                return self.send_bytes(b"max connections", "text/plain", 403)
            time.sleep(0.1)
        log(f"live {sid}: start")
        sent = 0
        try:
            size = os.path.getsize(LIVE_TS)
            rate = size / LIVE_SECONDS * 1.08  # just above real time, like a live source
            offset = int(((time.time() - T0) % LIVE_SECONDS) / LIVE_SECONDS * size) // 188 * 188
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Connection", "close")
            self.end_headers()
            burst = int(rate * 4)  # a few seconds up front so playback starts quickly
            began = time.time()
            with open(LIVE_TS, "rb") as f:
                f.seek(offset)
                while True:
                    allowed = burst + (time.time() - began) * rate
                    if sent >= allowed:
                        time.sleep(0.05)
                        continue
                    chunk = f.read(188 * 64)
                    if not chunk:
                        break  # end of the clip: the app is expected to reconnect
                    self.wfile.write(chunk)
                    sent += len(chunk)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with live_lock:
                live_active -= 1
            log(f"live {sid}: end after {sent // 1024} KB")


if __name__ == "__main__":
    log(f"mock IPTV server on :{PORT}, media from {MEDIA}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
