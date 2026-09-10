#!/usr/bin/env python3
# Ciphertext-only crib-drag on the A073 Core .ufw set (global reused keystream).
# Idea: C_f = K xor P_f (K global by offset). Pick an aligned same-size group.
# For a guessed crib at offset o in file0: K_cand = C0[o:o+L]^crib.
# Validate by decrypting the OTHER files there: P_g = C_g^K_cand = (C_g^C0)^crib.
# A real hit => P_g printable in ALL other files (chance ~0.37^(L*G) => negligible FP).
import numpy as np, glob, os, hashlib
D=r"./firmware/core-jieli-a073"
SZ=1691968  # largest aligned group (10 files, versions 0.0.5/0.0.6)
paths=[f for f in glob.glob(D+"/*.ufw") if os.path.getsize(f)==SZ]
uniq={}
for f in paths:
    d=open(f,"rb").read(); uniq[hashlib.md5(d).hexdigest()]=(f,np.frombuffer(d,np.uint8))
arrs=[v[1] for v in uniq.values()]; names=[os.path.basename(v[0])[:46] for v in uniq.values()]
print(f"aligned group size {SZ}: {len(arrs)} distinct builds")
for n in names: print("   ", n)
N=len(arrs[0]); C0=arrs[0]; others=arrs[1:]
Ds=[(g.astype(np.int16) ^ C0.astype(np.int16)).astype(np.uint8) for g in others]  # D_g = P_g ^ P0

def printable(a): return (a>=32)&(a<127)

CRIBS=[b"MOY-A073-0.0.", b"MOY-A073", b"JL_EQ_A07", b"JLQFNLVD", b"AC701", b"AC700N",
       b"jieli",b"Jieli",b"JIELI", b"version",b"Version",b"VERSION", b"config",b"Config",
       b"cpu/",b"sdk/",b" 2026",b"2026",b".bin",b".c",b"br28",b"br36",b"banff",
       b"\x00\x00\x00\x00\x00\x00\x00\x00", b"        ", b"ota",b"OTA",b"bt_",b"le_",
       b"MOYOUNG",b"Moyoung",b"Altair",b"altair",b"firmware",b"Firmware"]

def cribdrag(crib):
    L=len(crib); cr=np.frombuffer(crib,np.uint8)
    acc=np.ones(N-L+1,dtype=bool)
    for Dg in Ds:
        for k in range(L):
            pk=Dg[k:k+(N-L+1)] ^ cr[k]
            acc &= printable(pk)
        if not acc.any(): return np.array([],dtype=int)
    return np.nonzero(acc)[0]

def extend(o,L):  # greedy grow K window right/left while all-others-printable; assumes P0 unknown outside crib
    # K known only over [o,o+L] (=C0^crib). We can still SHOW P_g over crib window.
    return None

print("\n=== crib-drag hits ===")
found=[]
for crib in CRIBS:
    hits=cribdrag(crib)
    if len(hits):
        # keep only well-separated hits, cap
        for o in hits[:5]:
            L=len(crib); Kc=(C0[o:o+L].astype(np.int16)^np.frombuffer(crib,np.uint8).astype(np.int16)).astype(np.uint8)
            print(f"\nCRIB {crib!r} @0x{o:x}  (recovered K[{o:#x}:+{L}] = {bytes(Kc).hex()})")
            for nm,arr in zip(names,arrs):
                pg=bytes((arr[o:o+L].astype(np.int16)^Kc.astype(np.int16)).astype(np.uint8))
                # widen view using D relative (P_g^P0) to hint context
                print(f"    {nm[:30]:30s} P={pg!r}")
        found.append((crib,len(hits)))
print("\n=== summary ===")
for c,n in found: print(f"  {c!r}: {n} hit(s)")
if not found: print("  no crib hits (try different cribs / group)")
