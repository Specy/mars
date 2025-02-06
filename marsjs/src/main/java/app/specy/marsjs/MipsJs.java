package app.specy.marsjs;

import app.specy.mars.MIPS;
import app.specy.mars.ProcessingException;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

public class MipsJs {
    private MIPS main;

    private MipsJs(MIPS main) {
        this.main = main;
    }


    @JSExport
    public static MipsJs makeMipsfromSource(String source) throws ProcessingException {
        return new MipsJs(MIPS.fromSource(source));
    }

    @JSExport
    public void assemble() throws ProcessingException {
        this.main.assemble();
    }

    @JSExport
    public void initialize(boolean startAtMain) {
        this.main.initialize(startAtMain);
    }

    @JSExport
    public boolean step() throws ProcessingException {
        return this.main.step();
    }

    @JSExport
    public boolean simulateWithLimit(int limit) throws ProcessingException {
        return this.main.simulate(limit);
    }

    @JSExport
    public boolean simulateWithBreakpoints(int[] breakpoints) throws ProcessingException {
        return this.main.simulate(breakpoints);
    }

    @JSExport
    public boolean simulateWithBreakpointsAndLimit(int[] breakpoints, int limit) throws ProcessingException {
        return this.main.simulate(breakpoints, limit);
    }

}
