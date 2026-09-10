#!/bin/bash
# Scan every archived Vision .swu for a real WebRTC/TURN stack. Grounded, offline.
BASE=./firmware/vision-v821
OUT=./firmware/webrtc_scan.txt
# Strict markers that only a real WebRTC impl carries (avoid substring FPs like "return"/"turning").
MARKERS='libwebrtc|PeerConnection|createOffer|ice_ufrag|dtls-srtp|a=candidate|rtcp-mux|srtp|stun\.l\.google|:19302|IceCandidate|RTCPeer'
: > "$OUT"
echo "scan start $(date -u)" >> "$OUT"
find "$BASE" -name '*.swu' | sort | while read -r swu; do
  d=$(mktemp -d)
  ( cd "$d" && cpio -idm rootfs rootfs_sdnand user < "$swu" 2>/dev/null )
  ver="(no user part)"
  if [ -f "$d/user" ]; then
    unsquashfs -q -f -d "$d/u" "$d/user" etc/ag_user_version.conf >/dev/null 2>&1
    [ -f "$d/u/etc/ag_user_version.conf" ] && ver=$(head -1 "$d/u/etc/ag_user_version.conf")
  fi
  rootsq="$d/rootfs_sdnand"; [ -f "$rootsq" ] || rootsq="$d/rootfs"
  hits=0; ls_hits=0
  if [ -f "$rootsq" ]; then
    unsquashfs -q -d "$d/r" "$rootsq" >/dev/null 2>&1
    hits=$(grep -rIaslE "$MARKERS" "$d/r" 2>/dev/null | grep -viE 'libgio|libglib' | wc -l)
    LS=$(find "$d/r" -name ai_glass_livestream 2>/dev/null | head -1)
    [ -n "$LS" ] && ls_hits=$(strings -n5 "$LS" 2>/dev/null | grep -icE 'webrtc|peerconnection|createoffer|dtls-srtp|ice_ufrag|rtcpeer')
  fi
  printf '%-52s | ver=%-26s | webrtc_files=%s | livestream_webrtc_strings=%s\n' \
     "$(basename "$swu")" "$ver" "$hits" "$ls_hits" | tee -a "$OUT"
  rm -rf "$d"
done
echo "DONE $(date -u)" >> "$OUT"
