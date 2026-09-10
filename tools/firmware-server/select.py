#!/usr/bin/env python3
# Build the curated download selection from fwsizes.txt. Emits sel.tsv: subdir<TAB>size<TAB>filename
import re, sys, collections
rows=[]
for l in open("fwsizes.txt"):
    s,fn=l.rstrip("\n").split("\t",1); rows.append((int(s),fn))
def logical(fn): return re.sub(r'^\d{14}_','',fn)
def ts(fn):
    m=re.match(r'^(\d{14})_',fn); return m.group(1) if m else "0"

VISION_LINES={
 "openwrt_v821_aiglass-ai.swu":"vision-v821/aiglass-ai",
 "openwrt_v821_aiglass-ab.swu":"vision-v821/aiglass-ab",
 "openwrt_v821_aiglass_imx681-ab.swu":"vision-v821/aiglass-imx681-ab",
 "openwrt_v821_aiglass_imx681-ai.swu":"vision-v821/aiglass-imx681-ai",
}
sel={}  # key -> (subdir, size, filename)  ; keep newest timestamp per key
def consider(key, subdir, s, fn):
    if key not in sel or ts(fn) > ts(sel[key][2]):
        sel[key]=(subdir, s, fn)

for s,fn in rows:
    ln=logical(fn)
    if "A073" in fn:                       # our Core: keep one per distinct build (logical name = version+crc)
        consider(("core",ln), "core-jieli-a073", s, fn)
    elif ln in VISION_LINES:               # MoYoung V821 Vision: keep one per distinct size (≈distinct build)
        consider(("vis",ln,s), VISION_LINES[ln], s, fn)

# stats
by=collections.Counter(); byb=collections.Counter()
with open("sel.tsv","w") as f:
    for subdir,s,fn in sorted(sel.values()):
        f.write(f"{subdir}\t{s}\t{fn}\n")
        top=subdir.split("/")[0]; by[subdir]+=1; byb[subdir]+=s
print("=== curated selection ===")
for sd in sorted(by):
    print(f"  {sd:34s} {by[sd]:4d} files  {byb[sd]/1e6:9.1f} MB")
print(f"  {'TOTAL':34s} {sum(by.values()):4d} files  {sum(byb.values())/1e6:9.1f} MB ({sum(byb.values())/1e9:.2f} GB)")
