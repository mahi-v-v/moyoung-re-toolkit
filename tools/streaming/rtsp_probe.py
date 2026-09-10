#!/usr/bin/env python3
"""
Find the glasses' RTSP server on the LAN and report the stream's real resolution/bitrate.

Usage:
  python rtsp_probe.py                      # scan PC's /24 for RTSP, DESCRIBE + ffprobe hits
  python rtsp_probe.py rtsp://10.x.x.x/...  # probe an exact URL (from onLiveUrlChanged)
  python rtsp_probe.py 10.136.210.50        # probe one IP across common paths

Needs only stdlib + ffprobe on PATH. No nmap required.
"""
import socket, sys, subprocess, concurrent.futures, re, json, shutil, os

FFPROBE = shutil.which("ffprobe") or r"C:/ffmpeg/ffmpeg-master-latest-win64-gpl-shared/bin/ffprobe.exe"
PORTS = [554, 8554]
# Common RTSP paths for Allwinner / generic IP-cam / ELP-style stream servers.
PATHS = ["", "/", "/live", "/live/0", "/live/av0", "/live/ch0", "/live0", "/ch0", "/ch0_0",
         "/stream0", "/stream1", "/0", "/1", "/11", "/12", "/main", "/sub", "/video",
         "/video0", "/h264", "/media/1", "/av0", "/cam", "/cam/realmonitor?channel=1&subtype=0"]

def local_subnet():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.136.210.27", 80)); ip = s.getsockname()[0]
    except Exception:
        ip = "10.136.210.33"
    finally:
        s.close()
    return ip.rsplit(".", 1)[0], ip

def tcp_open(ip, port, t=0.35):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(t)
        return s.connect_ex((ip, port)) == 0

def rtsp_describe(ip, port, path):
    url = f"rtsp://{ip}:{port}{path}"
    req = (f"DESCRIBE {url} RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n"
           f"User-Agent: probe\r\n\r\n")
    try:
        with socket.socket() as s:
            s.settimeout(2.0); s.connect((ip, port))
            s.sendall(req.encode()); data = s.recv(8192).decode("latin1")
        status = data.splitlines()[0] if data else ""
        sdp = data.split("\r\n\r\n", 1)[1] if "\r\n\r\n" in data else ""
        return url, status, sdp
    except Exception as e:
        return url, f"ERR {e}", ""

def ffprobe(url):
    try:
        out = subprocess.run(
            [FFPROBE, "-v", "error", "-rtsp_transport", "tcp",
             "-show_streams", "-show_format", "-of", "json", url],
            capture_output=True, text=True, timeout=25)
        j = json.loads(out.stdout or "{}")
        rows = []
        for st in j.get("streams", []):
            if st.get("codec_type") == "video":
                rows.append(f"  VIDEO {st.get('codec_name')} {st.get('width')}x{st.get('height')} "
                            f"{st.get('avg_frame_rate')}fps profile={st.get('profile')} "
                            f"bitrate={st.get('bit_rate','?')} pix={st.get('pix_fmt')}")
            elif st.get("codec_type") == "audio":
                rows.append(f"  AUDIO {st.get('codec_name')} {st.get('sample_rate')}Hz "
                            f"ch={st.get('channels')} bitrate={st.get('bit_rate','?')}")
        fmt = j.get("format", {})
        if fmt: rows.append(f"  FORMAT bitrate={fmt.get('bit_rate','?')} {fmt.get('format_name','')}")
        return "\n".join(rows) or ("  (ffprobe returned no streams)\n  stderr: " + out.stderr[:300])
    except Exception as e:
        return f"  ffprobe failed: {e}"

def probe_url(url):
    print(f"\n### {url}")
    m = re.match(r"rtsp://([^:/]+)(?::(\d+))?(/.*)?", url)
    ip, port, path = m.group(1), int(m.group(2) or 554), m.group(3) or ""
    _, status, sdp = rtsp_describe(ip, port, path)
    print(f"  DESCRIBE -> {status}")
    if sdp: print("  SDP:\n" + "\n".join("    " + l for l in sdp.splitlines() if l.strip()))
    print(ffprobe(url))

def main():
    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if arg.startswith("rtsp://"):
            probe_url(arg); return
        ip = arg
        for port in PORTS:
            if tcp_open(ip, port):
                print(f"[+] {ip}:{port} open")
                for p in PATHS:
                    url, status, sdp = rtsp_describe(ip, port, p)
                    if "200" in status:
                        print(f"[HIT] {url} -> {status}"); probe_url(url)
        return

    net, ip = local_subnet()
    print(f"Scanning {net}.0/24 for RTSP (my ip {ip}) ...")
    hosts = [f"{net}.{i}" for i in range(1, 255)]
    found = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as ex:
        futs = {ex.submit(tcp_open, h, port): (h, port) for h in hosts for port in PORTS}
        for f in concurrent.futures.as_completed(futs):
            h, port = futs[f]
            if f.result():
                print(f"[+] RTSP port open: {h}:{port}"); found.append((h, port))
    if not found:
        print("No RTSP servers found on this subnet. Is the stream running and on THIS WiFi?")
        return
    for h, port in found:
        for p in PATHS:
            url, status, sdp = rtsp_describe(h, port, p)
            if "200" in status:
                print(f"[HIT] {url} -> {status}"); probe_url(url); break

if __name__ == "__main__":
    main()
