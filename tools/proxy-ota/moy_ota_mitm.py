"""
Push a custom Vision (.swu) or Core (.ufw) image to the MoYoung glasses using the OFFICIAL
"Da Echo" app — by intercepting its firmware check. No brick-prone custom BLE stack involved.

Grounded in the decompiled app (docs/13_OTA_via_Official_App_MITM.md):
  POST {baseUrl}/api/v1/firmware/check-upgrade  ->  {"status":"ok","data":{...}}
  data: firmware_ver, firmware_file(URL), firmware_md5, firmware_num(1=Jieli,2=Allwinner),
        type(otaType), has_upgrade
  The app downloads firmware_file, verifies firmware_md5, then:
        firmware_num==1 -> startOta(file)          (Core, BLE DFU)
        firmware_num==2 -> startAllWinnerOta(file)  (Vision, Wi-Fi)
  Because we forge the response we control BOTH the URL and the md5, so the integrity check
  passes on our file. No cert pinning in the app; the check host is MITM-able.

Run:
  MOY_SWU="./firmware/vision-v821/_patched/vision_noop.swu" \
  mitmdump -s moy_ota_mitm.py            # (or: mitmweb / mitmproxy -s ...)
Then set the phone's Wi-Fi proxy to this machine:8080 and install the mitmproxy CA on the phone.
Open the firmware-update screen in Da Echo -> it offers "our" update -> confirm -> it flashes.

Start with vision_noop.swu (zero functional change) to prove the pipeline, THEN vision_6mbps_adb.swu.
"""
import hashlib
import os

from mitmproxy import http

# ── config (override via env) ──────────────────────────────────────────────
IMG_PATH = os.environ.get(
    "MOY_SWU",
    r"./firmware/vision-v821/_patched/vision_noop.swu",
)
FW_NUM = int(os.environ.get("MOY_FW_NUM", "2"))        # 2 = Allwinner/Vision, 1 = Jieli/Core
FW_VER = os.environ.get("MOY_FW_VER", "9.9.0.99.3.2699999999")   # any string; shown in the UI only
OTA_TYPE = int(os.environ.get("MOY_OTA_TYPE", "3"))    # 3 = progress (forces the flash UI)
# The URL we advertise; the request for it is short-circuited below and served from IMG_PATH.
SERVE_URL = os.environ.get(
    "MOY_URL", "https://altair.moyoung.com/static/firmware/_tinker/custom_image"
)

CHECK_PATH = "/api/v1/firmware/check-upgrade"


def _md5(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


IMG_MD5 = _md5(IMG_PATH)
IMG_BYTES = open(IMG_PATH, "rb").read()
print(f"[moy-ota] serving {IMG_PATH}  ({len(IMG_BYTES)} bytes, md5={IMG_MD5}, fw_num={FW_NUM})")
print(f"[moy-ota] advertising as {SERVE_URL}")


def request(flow: http.HTTPFlow) -> None:
    # Serve our image directly for the advertised URL — the real server is never contacted.
    if flow.request.pretty_url == SERVE_URL:
        flow.response = http.Response.make(
            200, IMG_BYTES, {"Content-Type": "application/octet-stream"}
        )
        print(f"[moy-ota] served image to {flow.client_conn.address}")


def response(flow: http.HTTPFlow) -> None:
    # Forge the firmware-check reply to advertise our image + its md5.
    if CHECK_PATH in flow.request.path:
        body = (
            '{"status":"ok","data":{'
            f'"firmware_ver":"{FW_VER}",'
            f'"firmware_file":"{SERVE_URL}",'
            f'"firmware_md5":"{IMG_MD5}",'
            f'"firmware_num":{FW_NUM},'
            f'"type":{OTA_TYPE},'
            '"has_upgrade":true}}'
        )
        flow.response.status_code = 200
        flow.response.headers["Content-Type"] = "application/json"
        flow.response.text = body
        print(f"[moy-ota] forged check-upgrade -> fw_num={FW_NUM}, md5={IMG_MD5}")
