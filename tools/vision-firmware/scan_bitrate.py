#!/usr/bin/env python3
# Find lui+addi pairs (RV32/64) that materialize a target 32-bit constant, and locate literal words.
import sys, struct

path = sys.argv[1]
data = open(path, "rb").read()

def sext(v, bits):
    m = 1 << (bits - 1)
    return (v ^ m) - m

def decode_lui(instr):
    if (instr & 0x7f) != 0x37:
        return None
    rd = (instr >> 7) & 0x1f
    val = instr & 0xfffff000          # RV32 value; upper 20 bits << 12
    return rd, val

def decode_addi(instr):
    if (instr & 0x7f) != 0x13:        # OP-IMM
        return None
    if ((instr >> 12) & 7) != 0:      # funct3 == 0 (ADDI)
        return None
    rd = (instr >> 7) & 0x1f
    rs1 = (instr >> 15) & 0x1f
    imm = sext(instr >> 20, 12)
    return rd, rs1, imm

targets = {"1.5Mbps c0": 1500000, "12Mbps hevc": 0xC00000, "6Mbps?": 6000000, "8Mbps?": 8000000}

print("=== lui+addi pairs that equal a target constant ===")
found = {}
for off in range(0, len(data) - 8, 2):     # 2-byte step for RVC alignment
    i1 = struct.unpack_from("<I", data, off)[0]
    lui = decode_lui(i1)
    if not lui:
        continue
    rd, base = lui
    # look at the next 32-bit instr (and one after) for an addi to same reg
    for gap in (4,):
        i2 = struct.unpack_from("<I", data, off + gap)[0]
        addi = decode_addi(i2)
        if not addi:
            continue
        ard, ars1, imm = addi
        if ard == rd and ars1 == rd:
            val = (base + imm) & 0xffffffff
            for name, tv in targets.items():
                if val == tv:
                    found.setdefault(name, []).append((off, rd, base, imm, i1, i2))

for name, tv in targets.items():
    lst = found.get(name, [])
    print(f"\n[{name} = {tv} = 0x{tv:X}] : {len(lst)} site(s)")
    for (off, rd, base, imm, i1, i2) in lst:
        print(f"  file_off=0x{off:06X}  lui x{rd},0x{base>>12:X} (0x{i1:08X}) ; addi x{rd},x{rd},{imm} (0x{i2:08X})")
        # what a 6/8 Mbps patch of the LUI-immediate-only would look like
        for tgt, tname in ((6000000,"6M"), (8000000,"8M")):
            # keep addi imm, solve lui imm
            new_base = (tgt - imm) & 0xffffffff
            if new_base & 0xfff:  # not representable by lui alone with this addi
                # choose nearest lui multiple keeping addi
                new_lui_imm = round((tgt - imm) / 4096) & 0xfffff
                achieved = ((new_lui_imm << 12) + imm) & 0xffffffff
                new_i1 = (i1 & 0x00000fff) | (new_lui_imm << 12)
                print(f"      ->{tname}: set lui imm=0x{new_lui_imm:X} -> new_lui=0x{new_i1:08X}, achieved={achieved} ({achieved/1e6:.3f} Mbps)")
            else:
                new_lui_imm = (new_base >> 12) & 0xfffff
                new_i1 = (i1 & 0x00000fff) | (new_lui_imm << 12)
                print(f"      ->{tname}: set lui imm=0x{new_lui_imm:X} -> new_lui=0x{new_i1:08X}, achieved={tgt}")

print("\n=== literal 32-bit words present ===")
for name, tv in targets.items():
    needle = struct.pack("<I", tv)
    offs = []
    start = 0
    while True:
        k = data.find(needle, start)
        if k < 0: break
        offs.append(k); start = k + 1
    print(f"  0x{tv:08X} ({name}) at file offsets: {[hex(o) for o in offs][:12]}")
