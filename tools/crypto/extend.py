#!/usr/bin/env python3
# Grow the global keystream from the confirmed version-string anchor at offset 4.
# A correct K[i] must decrypt ALL builds to printable; version fields are near-identical
# across builds, so tie-break by max agreement. Recovers the ASCII header + strings.
import numpy as np, glob, os, hashlib
D=r"./firmware/core-jieli-a073"
SZ=1691968
paths=[f for f in glob.glob(D+"/*.ufw") if os.path.getsize(f)==SZ]
uniq={}
for f in paths:
    d=open(f,"rb").read(); uniq[hashlib.md5(d).hexdigest()]=(os.path.basename(f),np.frombuffer(d,np.uint8))
arrs=[v[1] for v in uniq.values()]; names=[v[0] for v in uniq.values()]
N=len(arrs[0])
G=len(arrs)
C=np.vstack(arrs).astype(np.uint8)  # G x N

anchor=4; seed=b"MOY-A073-0.0."
K={}
for j,ch in enumerate(seed):
    K[anchor+j]=C[0,anchor+j]^ch

def best_k(i):
    # try all k; keep those making all G printable; tie-break by max agreement (mode count)
    col=C[:,i].astype(np.int16)
    cands=[]
    for k in range(256):
        pv=col^k
        if np.all((pv>=32)&(pv<127)):
            vals=pv.astype(np.uint8)
            agree=np.max(np.bincount(vals,minlength=256))
            cands.append((agree,k))
    if not cands: return None
    cands.sort(reverse=True)
    return cands[0][1], len(cands)

# extend right
i=anchor+len(seed)
while i<N:
    r=best_k(i)
    if r is None: break
    K[i]=r[0]; i+=1
    if i-anchor>4096: break
right_end=i
# extend left
i=anchor-1
while i>=0:
    r=best_k(i)
    if r is None: break
    K[i]=r[0]; i-=1
left_end=i+1
print(f"recovered contiguous keystream: offsets 0x{left_end:x}..0x{right_end:x}  ({right_end-left_end} bytes)")

def dec(g,a,b): return bytes((C[g,a:b].astype(np.int16)^np.array([K[o] for o in range(a,b)],np.int16)).astype(np.uint8))
a,b=left_end,right_end
print(f"\n=== decrypted header [0x{a:x}:0x{b:x}] for all {G} builds ===")
for g in range(G):
    txt=dec(g,a,b).decode('latin1')
    txt=''.join(c if 32<=ord(c)<127 else '.' for c in txt)
    print(f"  {names[g][:44]:44s} | {txt}")
