#!/usr/bin/env python3
import glob, os, collections
D=r"./firmware/core-jieli-a073"
def rd(p): return open(p,"rb").read()
g=lambda pat: sorted(glob.glob(D+"/*"+pat+"*.ufw"))
def xz(a,b):
    m=min(len(a),len(b)); z=sum(1 for i in range(m) if a[i]==b[i]); return m,100*z/m

print("=== same-SIZE, different-VERSION XOR (tests GLOBAL positional keystream) ===")
# 0.0.8 and 0.0.9 are both 1702560
p=[("0.0.8-BIN-635B71D3","0.0.9-BIN-58233E57"),
   ("0.0.5-BIN-C8B4BEC8","0.0.6-BIN-31C211B1"),   # both 1691968
   ("0.0.8-BIN-635B71D3","0.0.8-BIN-006B3E00")]    # same version baseline
for x,y in p:
    fx,fy=g(x),g(y)
    if fx and fy:
        m,zp=xz(rd(fx[0]),rd(fy[0]))
        print(f"  {x} ^ {y}: size={m} identical={zp:.2f}%")

print("\n=== per-offset dominant byte across ALL same-size (1691968) files ===")
grp=[f for f in g("") if os.path.getsize(f)==1691968]
print(f"  {len(grp)} files of size 1691968")
blobs=[rd(f) for f in grp]
if len(blobs)>=3:
    n=len(blobs[0]); dom=0; sample=range(0,n,101)
    strong=0
    for i in sample:
        c=collections.Counter(b[i] for b in blobs)
        mc=c.most_common(1)[0][1]
        if mc==len(blobs): dom+=1
        if mc>=len(blobs)-1: strong+=1
    tot=len(list(sample))
    print(f"  offsets where ALL {len(blobs)} files share the same ciphertext byte: {100*dom/tot:.1f}%")
    print(f"  offsets where >=all-but-1 share: {100*strong/tot:.1f}%")
    print("  (high => big shared plaintext region under one positional pad)")

print("\n=== footer parse (0.0.8) ===")
b=rd(g("0.0.8-BIN-C363B807")[0])
j=b.rfind(b"JLUFW")
print(f"  'JLUFW' at offset 0x{j:x} (file len 0x{len(b):x}); bytes after JLUFW: {b[j:].hex()}")
print(f"  64 bytes before JLUFW: {b[j-64:j].hex()}")
