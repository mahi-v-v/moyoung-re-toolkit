# Ghidra headless post-script (Jython) — find the c0 encoder bitrate default
# and how --bitrate / c0_bitrate / AWVideoInput_SetBitrate are wired in ai_glass_livestream.
from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor

TARGETS = ["*demo param: c0", "*demo param: c1", "--bitrate", "c0_bitrate",
           "c1_bitrate", "rc_mode", "AWVideoInput_SetBitrate", "SET_BITRATE",
           "bitRate", "AWVideoInput_Init"]

prog = currentProgram
listing = prog.getListing()
mon = ConsoleTaskMonitor()
decomp = DecompInterface()
decomp.openProgram(prog)

def find_string_hits():
    res = []
    it = listing.getDefinedData(True)
    while it.hasNext():
        d = it.next()
        try:
            if d.hasStringValue():
                s = str(d.getValue())
                for t in TARGETS:
                    if t in s:
                        res.append((t, s, d.getAddress()))
                        break
        except:
            pass
    return res

hits = find_string_hits()
print("\n================ STRING HITS ================")
funcs = set()
for t, s, addr in hits:
    print("STR %-24s @ %s : %r" % (t, addr, s[:70]))
    for r in getReferencesTo(addr):
        fa = r.getFromAddress()
        fn = getFunctionContaining(fa)
        nm = fn.getName() if fn else "(none)"
        print("    xref <- %s  in %s" % (fa, nm))
        if fn:
            funcs.add(fn)

print("\n================ DECOMPILED FUNCTIONS (%d) ================" % len(funcs))
for fn in funcs:
    print("\n----- FUNC %s @ %s -----" % (fn.getName(), fn.getEntryPoint()))
    try:
        r = decomp.decompileFunction(fn, 120, mon)
        if r and r.decompileCompleted():
            print(r.getDecompiledFunction().getC())
        else:
            print("  (decompile failed: %s)" % (r.getErrorMessage() if r else "no result"))
    except Exception as e:
        print("  (exception: %s)" % e)

print("\n================ DONE ================")
