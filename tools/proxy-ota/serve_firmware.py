#!/usr/bin/env python3
"""
Local firmware server for the MoYoung "OTA via the official Da Echo app" flow (docs/13).

It just serves the patched .swu files over HTTP and LOUDLY logs every request, so you can watch
the phone download the image (or see nothing = the app blocked the download / wrong URL).

Run it in its own terminal:
    python serve_firmware.py
On startup it prints, for each .swu: the exact URL, its md5, and its size — copy those into the
Reqable breakpoint/rewrite of the check-upgrade response.

Env overrides (optional):
    MOY_DIR   folder with the .swu files   (default: firmware/vision-v821/_patched)
    MOY_PORT  listen port                  (default: 8000)
"""
import datetime
import hashlib
import http.server
import os
import socket
import socketserver

DIR = os.environ.get(
    "MOY_DIR", r"./firmware/vision-v821/_patched"
)
PORT = int(os.environ.get("MOY_PORT", "8000"))


def _md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def _ts():
    return datetime.datetime.now().strftime("%H:%M:%S")


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=DIR, **k)

    # quiet the default noise; we print our own richer lines
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        rng = self.headers.get("Range", "")
        ua = self.headers.get("User-Agent", "?")
        print(f"[{_ts()}] >>> GET {self.path}  from {self.client_address[0]}"
              f"{('  Range='+rng) if rng else ''}\n            UA: {ua}", flush=True)
        try:
            super().do_GET()
            print(f"[{_ts()}] <<< done {self.path}", flush=True)
        except (BrokenPipeError, ConnectionResetError):
            print(f"[{_ts()}] !!! client dropped {self.path}", flush=True)

    def do_HEAD(self):
        print(f"[{_ts()}] >>> HEAD {self.path}  from {self.client_address[0]}", flush=True)
        super().do_HEAD()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    ip = _lan_ip()
    print("=" * 72)
    print("  MoYoung firmware server")
    print(f"  serving : {DIR}")
    print(f"  listen  : 0.0.0.0:{PORT}   (phone -> http://{ip}:{PORT}/<file>)")
    print("-" * 72)
    if os.path.isdir(DIR):
        for f in sorted(os.listdir(DIR)):
            if f.endswith(".swu"):
                p = os.path.join(DIR, f)
                print(f"  {f}")
                print(f"      url  = http://{ip}:{PORT}/{f}")
                print(f"      md5  = {_md5(p)}")
                print(f"      size = {os.path.getsize(p)}")
    else:
        print(f"  !! folder not found: {DIR}  (set MOY_DIR)")
    print("=" * 72)
    print("waiting for the phone to download...  (Ctrl+C to stop)\n", flush=True)
    with Server(("0.0.0.0", PORT), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")
