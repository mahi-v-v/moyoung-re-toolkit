#!/bin/bash
# Build the trimmed, rootfs-ONLY .swu files (no-op + patched) for the Vision OTA.
# Writes ONLY /dev/mmcblk0p9 (rootfs_sdnand). Never touches boot0/uboot/kernel/riscv/user.
set -e
A=$HOME/otawork/a2
B=$HOME/otawork/build
OUT=$HOME/otawork/out; rm -rf "$OUT"; mkdir -p "$OUT"

# ---- the trimmed sw-description (sdnand group, rootfs-only) ----
read -r -d '' SWDESC <<'EOF' || true
software =
{
    version = "0.1.0";
    description = "Firmware update for Tina Project";

    stable = {

        /* rootfs-only in-place update of the running SD-NAND system.
           Deliberately omits boot0/uboot/kernel/riscv/user to minimise brick risk. */
        sdnand = {
            images: (
                {
                    filename = "rootfs_sdnand";
                    device = "/dev/mmcblk0p9";
                    installed-directly = true;
                }
            );

            scripts: (
                {
                    filename = "preinstall_sdnand.sh";
                    type = "preinstall";
                }
            );

            bootenv: (
                {
                    name = "swu_mode";
                    value = "";
                }
            );
        };
    };

    bootenv: (
        { name = "swu_param";    value = ""; },
        { name = "swu_software"; value = ""; },
        { name = "swu_mode";     value = ""; },
        { name = "swu_version";  value = ""; }
    );
}
EOF

build_one () {
  local name="$1"; local rootfs_src="$2"
  local S="$OUT/stage_$name"; rm -rf "$S"; mkdir -p "$S"; cd "$S"
  printf '%s\n' "$SWDESC" > sw-description
  cp "$A/preinstall_sdnand.sh" preinstall_sdnand.sh
  cp "$rootfs_src" rootfs_sdnand
  # cpio_item_md5: sw-description, image, script (mirror vendor: no self-entry)
  { md5sum sw-description; md5sum rootfs_sdnand; md5sum preinstall_sdnand.sh; } \
     | sed 's#\./##' > cpio_item_md5
  # pack: sw-description FIRST, then image, script, md5 LAST; newc-CRC (070702)
  printf '%s\n' sw-description rootfs_sdnand preinstall_sdnand.sh cpio_item_md5 \
     | cpio -o -H crc --quiet > "$OUT/${name}.swu"
  echo "== ${name}.swu =="
  echo "  magic: $(head -c6 "$OUT/${name}.swu")"
  echo "  size : $(stat -c%s "$OUT/${name}.swu") bytes"
  echo "  members:"; cpio -itv < "$OUT/${name}.swu" 2>/dev/null | awk '{printf "    %8s  %s\n",$5,$NF}'
}

build_one "vision_noop"        "$A/rootfs_sdnand"
build_one "vision_6mbps_adb"   "$B/rootfs_sdnand.patched"

echo; echo "=== md5 of the two payload rootfs (should differ) ==="
md5sum "$A/rootfs_sdnand" "$B/rootfs_sdnand.patched"
echo; echo "=== outputs ==="; ls -l "$OUT"/*.swu | awk '{print $5, $9}'
