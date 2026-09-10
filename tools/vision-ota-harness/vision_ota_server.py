#!/usr/bin/env python3
"""
Vision OTA laptop harness — a stand-in for the phone's NanoHTTPD.

WHY THIS EXISTS
    The Vision (Allwinner V821) firmware is flashed over Wi-Fi: the glasses come up
    as an OPEN AP (192.168.31.1) + DHCP server and HTTP-GET the .swu from whatever
    station holds the expected IP (normally the phone at 192.168.31.2:8182). The
    phone's own NanoHTTPD truncates that transfer at ~40% every run; a genuine
    unmodified image truncates identically, so the file is not the cause. This
    harness moves the SERVER onto the laptop so we can (a) capture BOTH directions
    with Wireshark (the laptop is a real endpoint — no monitor mode needed) and
    (b) remove Android from the data path entirely. If the transfer now completes,
    the verdict is "Android was tearing it down"; if it still stops at ~40% with a
    clean server + full route control, the verdict is "device-side / by design",
    and Wireshark shows exactly who sends the FIN/RST.

    BLE control stays on the phone (the app's "External-server Vision OTA" mode):
      phone: Enable Vision Wi-Fi (BLE) ─┐
      laptop: join open AP, static .2, run THIS, start Wireshark
      phone: Continue ── BLE "start OTA" ──> glasses GET http://192.168.31.2:8182/…

RUN (on the laptop, AFTER it has joined the glasses AP and taken 192.168.31.2):
    python vision_ota_server.py                 # serves the default genuine -ab image
    python vision_ota_server.py <file.swu>      # or an explicit file
    python vision_ota_server.py <file> 0.0.0.0 8182

WIRESHARK: capture on the Wi-Fi interface, display filter  tcp.port == 8182
    glasses(.1)->laptop(.2) FIN first  = device closed its read      (device-side)
    glasses ... RST                    = device reset                 (device-side)
    silence, then laptop retransmits, no ACK, timeout = path dropped  (network)
"""
import http.server
import socket
import sys
import time
import os

# Default = the genuine, unmodified -ab image (must match the size the app declares
# over BLE, so serve this exact file when the app's build "0 · Vision GENUINE -ab"
# is selected). Adjust if the repo moves.
DEFAULT_SWU = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "firmware", "vision-v821", "aiglass-ab",
    "20260729144724_openwrt_v821_aiglass-ab.swu",
)

def ts():
    return time.strftime("%H:%M:%S", time.localtime()) + f".{int((time.time()%1)*1000):03d}"

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"          # keep-alive + real Content-Length, like NanoHTTPD

    def log_message(self, fmt, *args):
        pass                                # we do our own logging

    def do_GET(self):
        fpath = self.server.swu_path
        size = os.path.getsize(fpath)
        peer = self.client_address[0]
        t0 = time.time()
        print(f"[{ts()}] >>> GET {self.path} from {peer}  (serving {os.path.basename(fpath)}, {size} bytes)")
        for h in ("Range", "User-Agent", "Host", "Connection"):
            v = self.headers.get(h)
            if v:
                print(f"[{ts()}]       {h}: {v}")
        rng = self.headers.get("Range")
        start = 0
        if rng and rng.startswith("bytes="):
            try:
                start = int(rng.split("=")[1].split("-")[0] or 0)
            except ValueError:
                start = 0
        try:
            if start:
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{size-1}/{size}")
                length = size - start
                print(f"[{ts()}]     (Range resume from byte {start})")
            else:
                self.send_response(200)
                length = size
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
        except (BrokenPipeError, ConnectionResetError) as e:
            print(f"[{ts()}] xxx client dropped during HEADERS: {type(e).__name__}")
            return

        sent = 0
        CHUNK = 64 * 1024
        try:
            with open(fpath, "rb") as f:
                f.seek(start)
                while True:
                    buf = f.read(CHUNK)
                    if not buf:
                        break
                    self.wfile.write(buf)
                    sent += len(buf)
                    if sent % (1024 * 1024) < CHUNK:           # ~1 MB heartbeat
                        pct = 100.0 * (start + sent) / size
                        print(f"[{ts()}]     sent {start+sent}/{size} ({pct:4.1f}%)")
            dt = time.time() - t0
            print(f"[{ts()}] === DONE sent={start+sent}/{size} in {dt:.1f}s "
                  f"avg={ (start+sent)/1024/max(dt,0.001):.0f} KB/s  reason=complete "
                  f"{'(FULL)' if start+sent == size else '(SHORT!)'}")
        except (BrokenPipeError, ConnectionResetError) as e:
            dt = time.time() - t0
            pct = 100.0 * (start + sent) / size
            print(f"[{ts()}] xxx TRUNCATED sent={start+sent}/{size} ({pct:.1f}%) after {dt:.1f}s "
                  f"-> {type(e).__name__}  (the peer closed or the path died mid-write — check Wireshark for FIN vs RST)")
        except Exception as e:
            print(f"[{ts()}] xxx ERROR sent={start+sent}/{size} -> {type(e).__name__}: {e}")

def main():
    swu = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SWU
    host = sys.argv[2] if len(sys.argv) > 2 else "0.0.0.0"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 8182
    swu = os.path.abspath(swu)
    if not os.path.isfile(swu):
        print(f"file not found: {swu}")
        print("pass the .swu path explicitly: python vision_ota_server.py <file.swu>")
        sys.exit(2)
    httpd = http.server.HTTPServer((host, port), Handler)
    httpd.swu_path = swu
    httpd.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"[{ts()}] Vision OTA harness up on {host}:{port}")
    print(f"[{ts()}] serving: {swu} ({os.path.getsize(swu)} bytes)")
    print(f"[{ts()}] expect the glasses (source 192.168.31.1) to GET shortly after you tap Continue …")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print(f"\n[{ts()}] bye")

if __name__ == "__main__":
    main()
