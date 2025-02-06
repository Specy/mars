package app.specy.mars;

import java.util.List;
import app.specy.mars.mips.fs.MIPSFileSystem;
import app.specy.mars.mips.fs.MemoryFileSystem;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.mips.instructions.SyscallLoader;
import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.simulator.Simulator;
import app.specy.mars.util.SystemIO;

public class MIPS {

    private List<MIPSprogram> programs;
    private MIPSprogram main;
    private static MIPSIO io;



    public void setIo(MIPSIO io) {
        MIPS.io = io;
        Globals.instructionSet.setSyscallLoader(new SyscallLoader(io));
        SystemIO.setMIPSIO(io);
    }

    public MIPS(MIPSprogram main, List<MIPSprogram> programs) {
        this.programs = programs;
        this.main = main;
    }

    public static MIPS fromFs(String main, MIPSFileSystem files) throws ProcessingException {
        MIPSprogram mainProgram = new MIPSprogram();
        List<MIPSprogram> programs = mainProgram.prepareFilesForAssembly(main, files, null);
        return new MIPS(mainProgram, programs);
    }

    public static MIPS fromSource(String source) throws ProcessingException {
        MIPSFileSystem files = new MemoryFileSystem();
        files.write("main", source);
        return MIPS.fromFs("main", files);
    }

    public static void initializeMIPS() {
        Globals.initialize();
    }

    public ErrorList assemble() throws ProcessingException {
        return this.main.assemble(this.programs, true);
    }

    public void initialize(boolean startAtMain) {
        RegisterFile.initializeProgramCounter(startAtMain);
    }

    public boolean simulate(int[] breakpoints) throws ProcessingException {
        return this.main.simulate(breakpoints);
    }
    public boolean simulate(int limit) throws ProcessingException {
        return this.main.simulate(limit);
    }
    public boolean simulate(int[] breakpoints, int limit) throws ProcessingException {
        return this.main.simulateFromPC(breakpoints, limit);
    }

    public boolean step() throws ProcessingException {
        return this.main.simulateStepAtPC();
    }

    public MIPSprogram getProgram() {
        return this.main;
    }

    public Simulator getSimulator() {
        return Simulator.getInstance();
    }

}
