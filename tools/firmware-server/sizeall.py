#!/usr/bin/env python3
import ssl, urllib.request, concurrent.futures, collections, os, re
BASE="https://altair.moyoung.com/static/firmware/"
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
files=[l.strip() for l in open("fwlist.txt") if l.strip()]
def size(fn):
    req=urllib.request.Request(BASE+fn, headers={"Range":"bytes=0-0","User-Agent":"curl/8"})
    try:
        r=urllib.request.urlopen(req,context=ctx,timeout=20)
        cr=r.headers.get("Content-Range")  # "bytes 0-0/12345"
        if cr and "/" in cr:
            return fn, int(cr.rsplit("/",1)[1])
        cl=r.headers.get("Content-Length")
        return fn, int(cl) if cl else -1
    except Exception as e:
        return fn, -2
tot=0; per=collections.Counter(); cnt=collections.Counter(); errs=0; done=0
sizes={}
with concurrent.futures.ThreadPoolExecutor(max_workers=24) as ex:
    for fn,sz in ex.map(size, files):
        done+=1
        if sz<0: errs+=1; continue
        ext=os.path.splitext(fn)[1].lower() or "(none)"
        tot+=sz; per[ext]+=sz; cnt[ext]+=1; sizes[fn]=sz
        if done%500==0: print(f"  ...{done}/{len(files)} scanned, running total {tot/1e6:.0f} MB")
print("\n=== per-extension ===")
for ext,b in per.most_common():
    print(f"  {ext:6s}  {cnt[ext]:5d} files   {b/1e6:9.1f} MB")
print(f"\nTOTAL: {tot/1e6:.1f} MB  ({tot/1e9:.2f} GB)  over {sum(cnt.values())} files; errors={errs}")
# dedup-by-content estimate: many files share identical content (same md5 diff names). Group by size as a rough proxy.
from collections import defaultdict
bysz=defaultdict(list)
for fn,s in sizes.items(): bysz[s].append(fn)
uniq_bytes=sum(sz for sz in bysz)  # one copy per distinct size (very rough lower bound)
print(f"distinct sizes: {len(bysz)} (rough) ; sum of one-per-distinct-size = {uniq_bytes/1e6:.1f} MB")
# save sizes
with open("fwsizes.txt","w") as f:
    for fn,s in sorted(sizes.items()): f.write(f"{s}\t{fn}\n")
print("wrote fwsizes.txt")
