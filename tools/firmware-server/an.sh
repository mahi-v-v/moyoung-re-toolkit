#!/bin/bash
# Self-contained: download newest -ab swu, extract, report version + livestream encoder config.
BASE=https://altair.moyoung.com/static/firmware
FWIDX=<scratch>/071407ba-46e4-47d0-82cc-4726a5e4dd05/scratchpad/fwindex.html
mkdir -p ~/fw && cd ~/fw
f=$(grep -oE '20[0-9]{12}_openwrt_v821_aiglass-ab\.swu' "$FWIDX" | sort -u | tail -1)
echo "newest -ab = $f"
[ -f ab.swu ] || curl -sk -o ab.swu "$BASE/$f"
echo "size=$(stat -c%s ab.swu)"
rm -rf abx && mkdir abx && cd abx
cpio -idm < ~/fw/ab.swu 2>/dev/null
echo "=== members ==="; ls
rm -rf r && unsquashfs -q -d r rootfs >/dev/null 2>&1
echo "rootfs files extracted: $(find r -type f 2>/dev/null | wc -l)"
echo "=== version files ==="
find r -iname '*version*' 2>/dev/null | while read -r v; do echo "$v => $(head -1 "$v" 2>/dev/null)"; done
echo "=== grep 2.4.0 / 2603302218 ==="
grep -rIn '2\.4\.0\|2603302218' r 2>/dev/null | head
echo "=== ai_glass binaries ==="
find r -name 'ai_glass*' 2>/dev/null
LS=$(find r -name 'ai_glass_livestream' 2>/dev/null | head -1)
echo "=== livestream=$LS ; encoder strings ==="
[ -n "$LS" ] && strings -n5 "$LS" | grep -iE 'demo param|bitrate|c0:|c1:| w&h|/etc/|\.conf|rc_mode|cbr|vbr|gop|kbps' | head -40
