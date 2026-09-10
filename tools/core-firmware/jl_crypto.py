#!/usr/bin/env python3
import glob, os, collections, math, hashlib
D=r"./firmware/core-jieli-a073"
def rd(p): return open(p,"rb").read()
def ent(b):
    if not b: return 0
    c=collections.Counter(b); n=len(b)
    return -sum(v/n*math.log2(v/n) for v in c.values())
def ident(fn):  # short label from filename
    import re; m=re.search(r'MOY-A073-([0-9.]+)-(?:BIN-)?([0-9A-Fa-f]{8})',fn);
    return f"{m.group(1)}/{m.group(2)}" if m else os.path.basename(fn)[:20]

files=sorted(glob.glob(D+"/*.ufw"))
print(f"{len(files)} ufw files\n")

# 1) Are same-version+CRC builds byte-identical? (deterministic vs random-IV)
print("=== (1) determinism: md5 of the three 0.0.8-C363B807 copies ===")
c363=[f for f in files if "C363B807" in f]
for f in c363:
    print(f"  {hashlib.md5(rd(f)).hexdigest()}  {os.path.basename(f)[:60]}")
c0cd=[f for f in files if "C0CD42A8" in f]
print("  --- 0.0.7-C0CD42A8 copies ---")
for f in c0cd:
    print(f"  {hashlib.md5(rd(f)).hexdigest()}  {os.path.basename(f)[:60]}")

# 2) header/tail/entropy of one file
f0=[f for f in files if "C363B807" in f][0]; b0=rd(f0)
print(f"\n=== (2) structure of {ident(f0)} ({len(b0)} bytes, entropy {ent(b0):.3f}) ===")
print("  head 64:", b0[:64].hex())
print("  tail 64:", b0[-64:].hex())
# windowed entropy 4KB -> find plaintext (low-entropy) regions
W=4096; lows=[]
for i in range(0,len(b0),W):
    e=ent(b0[i:i+W])
    if e<6.5: lows.append((i,round(e,2)))
print(f"  low-entropy (<6.5) 4KB windows: {len(lows)} of {len(b0)//W}",
      ("-> e.g. "+str(lows[:6]) if lows else "(none -> whole file encrypted)"))

# 3) cross-version XOR test for reused keystream
def xorstats(a,b):
    m=min(len(a),len(b)); x=bytes(a[i]^b[i] for i in range(m))
    zeros=x.count(0); run=cur=0
    for v in x:
        if v==0: cur+=1; run=max(run,cur)
        else: cur=0
    return m,zeros,run,ent(x)
print("\n=== (3) cross-build XOR (reused-keystream test) ===")
def grp(sz): return [f for f in files if os.path.getsize(f)==sz]
pairs=[]
# different-content builds of the SAME size
for sz in sorted({os.path.getsize(f) for f in files}):
    g=grp(sz)
    # unique by CRC
    seen={};
    for f in g:
        k=ident(f); seen.setdefault(k,f)
    u=list(seen.values())
    if len(u)>=2:
        pairs.append((u[0],u[1]))
for a,c in pairs[:6]:
    m,z,run,e=xorstats(rd(a),rd(c))
    print(f"  {ident(a):14s} ^ {ident(c):14s} sz={m} zeros={100*z/m:.2f}% longest0={run} xorEnt={e:.3f}")
print("  [random-keystream => zeros~0.39%, xorEnt~8.0 ; reused-keystream+shared code => zeros high, long runs]")
