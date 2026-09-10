#!/usr/bin/env python3
"""Decide: reused-keystream cipher vs. plaintext-container-with-compression."""
import collections, math, re, gzip, zlib

DIR = r"./vendor-sdk/ios/IOS-SDK-Glasses-1.2.0/swift-SdkDemo/TestSdk"
b = open(f"{DIR}/MOY-TTT3-2.0.5.bin","rb").read()

def entropy(x):
    if not x: return 0
    c=collections.Counter(x); n=len(x)
    return -sum(v/n*math.log2(v/n) for v in c.values())

# A) Find the lowest-entropy 16KB window and show its byte histogram top-8.
best=(9,0)
for i in range(0,len(b),16384):
    e=entropy(b[i:i+16384])
    if e<best[0]: best=(e,i)
e,off=best
win=b[off:off+16384]
hist=collections.Counter(win).most_common(8)
print(f"A) Lowest-entropy 16KB window @0x{off:x}  entropy={e:.3f}")
print(f"   top bytes: " + ", ".join(f"0x{v:02x}:{c}({100*c/len(win):.1f}%)" for v,c in hist))

# B) ASCII strings: count printable runs >=5 in the WHOLE file.
runs=re.findall(rb"[\x20-\x7e]{5,}", b)
print(f"\nB) printable strings (len>=5): {len(runs)} total")
# show a few from the middle of the file
mid=[s.decode('ascii','replace') for s in runs if len(s)>=8][:15]
for s in mid[:15]: print("   ", s[:70])

# C) Try to actually decompress at the consistent gzip magic and any zlib offsets.
print("\nC) decompression attempts")
for off in [b.find(b"\x1f\x8b")]:
    try:
        import io
        d=gzip.GzipFile(fileobj=io.BytesIO(b[off:])).read(4096)
        print(f"   gzip@0x{off:x}: OK, {len(d)} bytes out; head={d[:40]!r}")
    except Exception as ex:
        print(f"   gzip@0x{off:x}: FAIL ({ex})")
for magic in (b"\x78\x9c", b"\x78\xda", b"\x78\x01"):
    off=b.find(magic)
    if off<0: continue
    try:
        d=zlib.decompressobj().decompress(b[off:off+200000])
        print(f"   zlib{magic.hex()}@0x{off:x}: {len(d)} bytes out (may be partial)")
    except Exception as ex:
        print(f"   zlib{magic.hex()}@0x{off:x}: FAIL")

# D) Is the first 64KB identical across ALL four versions? (bootloader stub?)
names=["MOY-TTT3-2.0.3.bin","MOY-TTT3-2.0.4.bin","MOY-TTT3-2.0.5.bin","MOY-TTT3-2.0.5-test.bin"]
heads=[open(f"{DIR}/{n}","rb").read(65536) for n in names]
allsame=all(h==heads[0] for h in heads)
# where does 2.0.3 vs 2.0.5 first differ?
a=open(f"{DIR}/{names[0]}","rb").read(); c=open(f"{DIR}/{names[2]}","rb").read()
firstdiff=next((i for i in range(min(len(a),len(c))) if a[i]!=c[i]), -1)
print(f"\nD) first 64KB identical across all 4 versions: {allsame}")
print(f"   2.0.3 vs 2.0.5 first byte that differs: 0x{firstdiff:x} ({firstdiff})")
