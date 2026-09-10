#!/usr/bin/env python3
"""
Two-time-pad feasibility + format analysis for the bundled MOY-TTT3 Jieli "Core" firmware images.

The question we're answering: is the high-entropy payload a *stream cipher with a reused keystream*
(=> two-time-pad crackable), a *block cipher / per-image-keyed* blob (=> not TTP), or just
*compression* (=> no crypto, different problem)?

Discriminators:
  1. Header dissection (shared magic, section table).
  2. XOR of two versions:  C1 ^ C2 == P1 ^ P2  IFF the keystream is identical at that offset.
     - Long runs of 0x00 in C1^C2  => identical plaintext AND same keystream (or both plaintext).
       This is the smoking gun for keystream reuse / plaintext structure survival.
     - Uniform high entropy everywhere => different keystreams OR compression avalanche.
  3. Compression magic scan (gzip/zlib/lzma/lz4/xz) at/after the header.
  4. Windowed Shannon entropy of a single image (encrypted/compressed ~8.0; plain code has dips).
"""
import sys, math, collections, struct

DIR = r"./vendor-sdk/ios/IOS-SDK-Glasses-1.2.0/swift-SdkDemo/TestSdk"
FILES = ["MOY-TTT3-2.0.3.bin", "MOY-TTT3-2.0.4.bin", "MOY-TTT3-2.0.5.bin", "MOY-TTT3-2.0.5-test.bin"]

def load(name):
    with open(f"{DIR}/{name}", "rb") as f:
        return f.read()

def entropy(b):
    if not b: return 0.0
    c = collections.Counter(b); n = len(b)
    return -sum((v/n) * math.log2(v/n) for v in c.values())

def hexdump_head(b, n=48):
    return " ".join(f"{x:02x}" for x in b[:n])

blobs = {n: load(n) for n in FILES}

print("="*78)
print("1) HEADER DISSECTION")
print("="*78)
for n in FILES:
    b = blobs[n]
    # header looks like: magic(4) then several LE uint32
    words = struct.unpack_from("<8I", b, 0)
    print(f"\n{n}  ({len(b):,} bytes, entropy={entropy(b):.3f})")
    print(f"  magic   : {b[:4].hex()}")
    print(f"  u32[1..7]: " + " ".join(f"0x{w:08x}({w})" for w in words[1:]))
    print(f"  head    : {hexdump_head(b)}")

print("\n" + "="*78)
print("2) XOR OF VERSION PAIRS  (C1 ^ C2)  — the two-time-pad test")
print("="*78)
pairs = [("MOY-TTT3-2.0.3.bin","MOY-TTT3-2.0.4.bin"),
         ("MOY-TTT3-2.0.5.bin","MOY-TTT3-2.0.5-test.bin"),
         ("MOY-TTT3-2.0.3.bin","MOY-TTT3-2.0.5.bin")]
for a, c in pairs:
    ba, bc = blobs[a], blobs[c]
    m = min(len(ba), len(bc))
    x = bytes(ba[i] ^ bc[i] for i in range(m))
    zeros = x.count(0)
    # longest run of 0x00
    longest = cur = 0
    for byte in x:
        if byte == 0:
            cur += 1; longest = max(longest, cur)
        else:
            cur = 0
    # zero density in first 64KB vs whole
    z64 = x[:65536].count(0)
    print(f"\n{a}  ^  {c}   (overlap {m:,} B)")
    print(f"  zero bytes      : {zeros:,} ({100*zeros/m:.2f}%)   [random would be ~{100/256:.2f}%]")
    print(f"  longest 0x00 run: {longest:,} bytes")
    print(f"  entropy of XOR  : {entropy(x):.3f} bits/byte")
    print(f"  zeros in 1st64KB: {z64:,} ({100*z64/65536:.2f}%)")

print("\n" + "="*78)
print("3) COMPRESSION-MAGIC SCAN (first 4 KB windows across each file)")
print("="*78)
MAGICS = {b"\x1f\x8b":"gzip", b"\x78\x01":"zlib/low", b"\x78\x9c":"zlib/def",
          b"\x78\xda":"zlib/best", b"\xfd7zXZ":"xz", b"\x5d\x00\x00":"lzma",
          b"\x04\x22\x4d\x18":"lz4", b"BZh":"bzip2", b"PK\x03\x04":"zip",
          b"\x27\x05\x19\x56":"uboot", b"ANDROID!":"bootimg"}
for n in FILES:
    b = blobs[n]; hits = []
    for magic, label in MAGICS.items():
        off = b.find(magic)
        if 0 <= off < len(b):
            hits.append(f"{label}@0x{off:x}")
    print(f"  {n}: {hits if hits else 'no common compression magic found'}")

print("\n" + "="*78)
print("4) WINDOWED ENTROPY of MOY-TTT3-2.0.5.bin  (16 KB windows)")
print("="*78)
b = blobs["MOY-TTT3-2.0.5.bin"]
W = 16384
lows = 0
line = []
for i in range(0, len(b), W):
    e = entropy(b[i:i+W])
    if e < 7.0: lows += 1
    line.append("#" if e > 7.9 else ("+" if e > 7.0 else "."))
print("  legend: '#'=>7.9  '+'=7.0-7.9  '.'=<7.0 (structured/plain)")
print("  " + "".join(line))
print(f"  windows below 7.0 bits (structured): {lows} / {len(line)}")
print("\nDONE.")
