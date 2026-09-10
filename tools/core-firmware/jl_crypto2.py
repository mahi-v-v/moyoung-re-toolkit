#!/usr/bin/env python3
import glob, os, collections, math
D=r"./firmware/core-jieli-a073"
def rd(p): return open(p,"rb").read()
def ent(b):
    if not b: return 0
    c=collections.Counter(b); n=len(b); return -sum(v/n*math.log2(v/n) for v in c.values())
g=lambda pat: sorted(glob.glob(D+"/*"+pat+"*.ufw"))
A=rd(g("0.0.7-BIN-3BA599AB")[0]); B=rd(g("0.0.7-BIN-DB9EAD80")[0])
m=min(len(A),len(B)); X=bytes(A[i]^B[i] for i in range(m))

# 1) locate the longest zero run in X (region where plaintexts identical => ciphertext==keystream+constPT)
best=(0,0); cur=0; st=0
for i in range(m):
    if X[i]==0:
        if cur==0: st=i
        cur+=1
        if cur>best[0]: best=(cur,st)
    else: cur=0
runlen,runstart=best
print(f"longest identical region: {runlen} bytes @ 0x{runstart:x}..0x{runstart+runlen:x}")

# 2) In that region, ciphertext A == B. Examine it: is it periodic (repeating key)?
seg=A[runstart:runstart+min(runlen,262144)]
print(f"segment entropy={ent(seg):.3f}  first 48 bytes: {seg[:48].hex()}")
# periodicity: for candidate periods, fraction of matching bytes seg[i]==seg[i+P]
print("period test (match% of seg[i]==seg[i+P]):")
for P in [16,32,64,128,256,512,1024,2048,4096,8192,16384,32768,65536]:
    if P<len(seg):
        n=len(seg)-P; mt=sum(1 for i in range(0,n,7) if seg[i]==seg[i+P]); tot=len(range(0,n,7))
        print(f"  P={P:6d}: {100*mt/tot:.1f}%")

# 3) Is the keystream GLOBAL (same across different versions)? XOR two DIFFERENT versions at start.
C=rd(g("0.0.8-BIN-C363B807")[0])
m2=min(len(A),len(C)); X2=bytes(A[i]^C[i] for i in range(m2))
z2=X2.count(0)
print(f"\ncross-version 0.0.7 ^ 0.0.8: zeros={100*z2/m2:.2f}%  (high => keystream still shared across versions)")

# 4) header across versions: how many leading bytes are IDENTICAL between builds (fixed encrypted header)?
def common_prefix(a,b):
    n=0
    for i in range(min(len(a),len(b))):
        if a[i]==b[i]: n+=1
        else: break
    return n
print(f"identical leading bytes 0.0.7a vs 0.0.7b: {common_prefix(A,B)}")
print(f"identical leading bytes 0.0.7 vs 0.0.8 : {common_prefix(A,C)}")
# tail: JLUFW footer region
print(f"tail 0.0.7a: {A[-48:].hex()}")
print(f"tail 0.0.8 : {C[-48:].hex()}")
