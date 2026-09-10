#!/bin/bash
R=~/fw/abx/r
echo "############ WHERE IS ai_glass_livestream LAUNCHED / CONFIGURED ############"
echo "=== init.d + procd + any script referencing the ai_glass apps ==="
grep -rIn 'ai_glass_livestream\|ai_glass_normal\|ai_glass_video' "$R/etc" 2>/dev/null | head -20
echo "=== bitrate/rc_mode keys as TEXT anywhere in rootfs (configs, scripts) ==="
grep -rIn 'c0_bitrate\|c1_bitrate\|rc_mode\|--bitrate' "$R" 2>/dev/null | grep -v 'Binary file' | head -20
echo "=== files that literally contain the word bitrate (non-binary) ==="
grep -rIl 'bitrate' "$R" 2>/dev/null | head
echo "=== init.d listing ==="
ls "$R/etc/init.d" 2>/dev/null
echo "=== inittab / rcS head ==="
sed -n '1,40p' "$R/etc/inittab" 2>/dev/null
echo "=== ai_glass_normal (orchestrator) strings: how it spawns livestream + defaults ==="
NB="$R/bin/ai_glass_normal"
[ -f "$NB" ] && strings -n5 "$NB" | grep -iE 'ai_glass_livestream|--bitrate|c0_bitrate|c1_bitrate|rc_mode|bitrate|/etc/.*\.conf|\.json|param' | head -30

echo
echo "############ NEWEST -ai VERSION (trajectory toward 2.4.0.22) ############"
BASE=https://altair.moyoung.com/static/firmware
cd ~/fw && f=20260721182507_openwrt_v821_aiglass-ai.swu
[ -f nai.swu ] || curl -sk -o nai.swu "$BASE/$f"
rm -rf naix && mkdir naix && cd naix && cpio -idm user < ~/fw/nai.swu 2>/dev/null
unsquashfs -q -f -d u user etc/ag_user_version.conf >/dev/null 2>&1
echo "newest -ai ($f) version => $(cat u/etc/ag_user_version.conf 2>/dev/null)"
