import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Reference;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class find_bitrate extends GhidraScript {
    public void run() throws Exception {
        String[] TARGETS = {"*demo param: c0", "*demo param: c1", "--bitrate",
            "c0_bitrate", "c1_bitrate", "rc_mode", "AWVideoInput_SetBitrate",
            "SET_BITRATE", "AWVideoInput_Init", "AWVideoInput_Configure"};
        Listing listing = currentProgram.getListing();
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();

        Set<Function> funcs = new LinkedHashSet<Function>();
        println("\n================ STRING HITS ================");
        DataIterator it = listing.getDefinedData(true);
        while (it.hasNext()) {
            Data d = it.next();
            if (d.hasStringValue()) {
                String s = d.getValue().toString();
                for (String t : TARGETS) {
                    if (s.contains(t)) {
                        println("STR " + t + " @ " + d.getAddress() + " : " + trunc(s, 72));
                        for (Reference r : getReferencesTo(d.getAddress())) {
                            Address fa = r.getFromAddress();
                            Function fn = getFunctionContaining(fa);
                            println("    xref <- " + fa + " in " + (fn != null ? fn.getName() : "(none)"));
                            if (fn != null) funcs.add(fn);
                        }
                        break;
                    }
                }
            }
        }
        println("\n================ DECOMPILED (" + funcs.size() + ") ================");
        for (Function fn : funcs) {
            println("\n----- FUNC " + fn.getName() + " @ " + fn.getEntryPoint() + " -----");
            DecompileResults res = decomp.decompileFunction(fn, 120, mon);
            if (res != null && res.decompileCompleted())
                println(res.getDecompiledFunction().getC());
            else
                println("  (decompile failed)");
        }
        println("\n================ DONE ================");
    }

    private String trunc(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
