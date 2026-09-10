#!/bin/bash
# Build patched rootfs_sdnand for the Vision (bitrate 1.5->6 Mbps + adbd-over-TCP), root-free.
set -e
A=$HOME/otawork/a2
B=$HOME/otawork/build
rm -rf "$B"; mkdir -p "$B"; cd "$B"

SRC="$A/rootfs_sdnand"     # untouched vendor squashfs (the device's / on SD-NAND)
echo "=== source squashfs ==="; unsquashfs -s "$SRC" | grep -Ei "superblock|Compression|Block size|size " | head

echo "=== extract original tree (non-root; dev/console mknod will warn, ignored) ==="
set +e
unsquashfs -f -d "$B/orig" "$SRC"  >/tmp/uq1.log 2>&1
set -e
echo "extracted files: $(find "$B/orig" -type f | wc -l), symlinks: $(find "$B/orig" -type l | wc -l)"
cp -a "$B/orig" "$B/patched"

echo "=== verify + apply the 2-byte bitrate patch (offset 0xBA66) ==="
python3 - "$B/patched/bin/ai_glass_livestream" <<'PY'
import sys
p=sys.argv[1]; d=bytearray(open(p,'rb').read())
off=0xBA66
orig=bytes([0x37,0xE7,0x16,0x00])   # lui x14,0x16E  (1500000 with the following addi +864)
new =bytes([0x37,0x97,0x5B,0x00])   # lui x14,0x5B9  (=> 6,001,504 bps  ~6.0 Mbps)
cur=bytes(d[off:off+4])
assert cur==orig, f"UNEXPECTED bytes at 0x{off:X}: {cur.hex()} (expected {orig.hex()})"
d[off:off+4]=new
open(p,'wb').write(d)
print(f"patched 0x{off:X}: {orig.hex()} -> {new.hex()}  (bitrate 1,500,000 -> 6,001,504)")
PY

echo "=== enable adbd over TCP (uncomment ADB_TRANSPORT_PORT=5555) ==="
sed -i 's/^#ADB_TRANSPORT_PORT=5555/ADB_TRANSPORT_PORT=5555/' "$B/patched/etc/init.d/adbd"
grep -n "ADB_TRANSPORT_PORT" "$B/patched/etc/init.d/adbd"

echo "=== rebuild squashfs (lzo, 32K, all-root, +/dev/console pseudo) ==="
mksquashfs "$B/patched" "$B/rootfs_sdnand.patched" \
  -comp lzo -b 32768 -noappend -all-root -no-progress -no-exports \
  -p 'dev/console c 600 0 0 5 1'  >/tmp/mksq.log 2>&1 || { echo MKSQUASHFS_FAIL; tail -20 /tmp/mksq.log; exit 1; }
ls -l "$B/rootfs_sdnand.patched" "$SRC" | awk '{print $5, $9}'

echo "=== VALIDATION: re-extract patched image and diff tree vs original ==="
set +e
unsquashfs -f -d "$B/verify" "$B/rootfs_sdnand.patched" >/tmp/uq2.log 2>&1
set -e
echo "-- files that differ between orig tree and rebuilt-patched tree (expect exactly 2: ai_glass_livestream, init.d/adbd):"
diff -rq "$B/orig" "$B/verify" 2>/dev/null | grep -vi "dev/console"
echo "-- confirm patched bytes survived the rebuild:"
python3 - "$B/verify/bin/ai_glass_livestream" <<'PY'
import sys; d=open(sys.argv[1],'rb').read(); off=0xBA66
print("bytes@0xBA66:", d[off:off+4].hex(), "OK" if d[off:off+4]==bytes([0x37,0x97,0x5B,0x00]) else "MISMATCH")
PY
echo "-- confirm adbd line survived:"; unsquashfs -cat "$B/rootfs_sdnand.patched" etc/init.d/adbd 2>/dev/null | grep -n "ADB_TRANSPORT_PORT" | head
echo "=== DONE ==="
