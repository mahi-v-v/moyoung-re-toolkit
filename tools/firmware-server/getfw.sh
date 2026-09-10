#!/bin/bash
# Download the curated firmware selection + build MANIFEST.csv. Resumable.
SCR="<scratch>/071407ba-46e4-47d0-82cc-4726a5e4dd05/scratchpad"
DEST="./firmware"
BASE="https://altair.moyoung.com/static/firmware"
LOG="$DEST/download.log"
: > "$LOG"
# sel.tsv was written on Windows (CRLF) -> strip CR so URLs aren't malformed.
SEL=/tmp/sel_lf.tsv
tr -d '\r' < "$SCR/sel.tsv" > "$SEL"
decode(){ python3 -c "import urllib.parse,sys;print(urllib.parse.unquote(sys.argv[1]))" "$1"; }
tot=$(wc -l < "$SEL"); n=0; okc=0; failc=0
echo "$(date) start; $tot files" >> "$LOG"
while IFS=$'\t' read -r sub sz fn; do
  n=$((n+1))
  sub=${sub%$'\r'}; sz=${sz%$'\r'}; fn=${fn%$'\r'}
  local_fn=$(decode "$fn" | tr ' ' '_')
  out="$DEST/$sub/$local_fn"
  if [ -f "$out" ] && [ "$(stat -c%s "$out" 2>/dev/null)" = "$sz" ]; then
    echo "[$n/$tot] skip $local_fn" >> "$LOG"; okc=$((okc+1)); continue
  fi
  curl -sk --retry 3 --retry-delay 2 -o "$out" "$BASE/$fn"
  got=$(stat -c%s "$out" 2>/dev/null || echo 0)
  if [ "$got" = "$sz" ]; then
    echo "[$n/$tot] ok   $local_fn ($sz)" >> "$LOG"; okc=$((okc+1))
  else
    echo "[$n/$tot] FAIL $local_fn got=$got want=$sz" >> "$LOG"; failc=$((failc+1))
  fi
done < "$SEL"
echo "$(date) downloads done: ok=$okc fail=$failc" >> "$LOG"

echo "$(date) building manifest..." >> "$LOG"
MAN="$DEST/MANIFEST.csv"
echo "subdir,filename,size,md5,sha256,internal_version" > "$MAN"
extract_ver(){
  local f="$1" t; t=$(mktemp -d)
  ( cd "$t" && cpio -idm user < "$f" 2>/dev/null \
      && unsquashfs -q -f -d u user etc/ag_user_version.conf >/dev/null 2>&1 \
      && head -1 u/etc/ag_user_version.conf 2>/dev/null )
  rm -rf "$t"
}
find "$DEST" -type f \( -name '*.ufw' -o -name '*.swu' -o -name '*.fw' \) | LC_ALL=C sort | while read -r p; do
  rel=${p#"$DEST"/}; sub=$(dirname "$rel"); base=$(basename "$p")
  sz=$(stat -c%s "$p"); md5=$(md5sum "$p"|cut -d' ' -f1); sha=$(sha256sum "$p"|cut -d' ' -f1)
  ver=""; case "$base" in *.swu) ver=$(extract_ver "$p");; esac
  printf '%s,%s,%s,%s,%s,%s\n' "$sub" "$base" "$sz" "$md5" "$sha" "$ver" >> "$MAN"
done
echo "$(date) manifest done: $(( $(wc -l < "$MAN") - 1 )) entries" >> "$LOG"
echo "ALL DONE" >> "$LOG"
