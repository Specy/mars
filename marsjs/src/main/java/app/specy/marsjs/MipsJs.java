package app.specy.marsjs;

import app.specy.mars.Globals;
import app.specy.mars.MIPS;
import app.specy.mars.ProcessingException;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.mips.instructions.InstructionSet;
import app.specy.mars.mips.instructions.SyscallLoader;
import app.specy.mars.simulator.Simulator;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSFunction;

public class MipsJs {
    private MIPS main;
    private static JsMIPSIO ioHandler;

    private MipsJs(MIPS main) {
        this.main = main;
    }

    private static JsMIPSIO getIOHandler() {
        if (ioHandler == null) {
            ioHandler = new JsMIPSIO();
            MIPS.setIo(ioHandler);
        }
        return ioHandler;
    }

    @JSExport
    public static void initializeMIPS() {
        MIPS.initializeMIPS();
    }

    @JSExport
    public static MipsJs makeMipsfromSource(String source) throws ProcessingException {
        MipsJs.getIOHandler(); // Ensure that the IO handler is initialized
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

    @JSExport
    public int getRegisterValue(String register) {
        return RegisterFile.getUserRegister(register).getValue();
    }

    @JSExport
    public void registerHandler(String name, JSFunction handler) {
        getIOHandler().registerHandler(name, handler);
    }

    @JSExport
    public JsMIPSIO getIO() {
        return getIOHandler();
    }
}
