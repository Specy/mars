package app.specy.mars;

import java.util.List;

import app.specy.mars.assembler.SymbolTable;
import app.specy.mars.assembler.TokenList;
import app.specy.mars.mips.fs.MIPSFileSystem;
import app.specy.mars.mips.fs.MemoryFileSystem;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.mips.instructions.Instruction;
import app.specy.mars.mips.instructions.InstructionSet;
import app.specy.mars.mips.instructions.SyscallLoader;
import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.simulator.Simulator;
import app.specy.mars.util.SystemIO;

public class MIPS {

    private List<MIPSprogram> programs;
    private MIPSprogram main;
    private static MIPSIO io;
    private boolean terminated = false;


    public static void setIo(MIPSIO io) {
        MIPS.io = io;
        Globals.instructionSet.setSyscallLoader(new SyscallLoader(io));
        SystemIO.setMIPSIO(io);
    }

    public ProgramStatement getAddressFromSourceLine(int line) {
        List<ProgramStatement> statements = this.main.getParsedList();
        for(ProgramStatement statement : statements) {
            if(statement.getSourceLine() == line) {
                return statement;
            }
        }
        return null;
    }

    public List<TokenList> getTokens(){
        return this.main.getTokenList();
    }

    public ProgramStatement getStatementAtAddress(int address) {
        return this.main.getMachineStatement(address);
    }

    public List<ProgramStatement> getParsedStatements() {
        return this.main.getParsedList();
    }

    public List<ProgramStatement> getStatements() {
        return this.main.getMachineList();
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
        ErrorList result = this.main.assemble(this.programs, true);
        Globals.program = this.main;
        return result;
    }

    public void initialize(boolean startAtMain) {
        RegisterFile.resetRegisters();
        Coprocessor0.resetRegisters();
        Coprocessor1.resetRegisters();
        RegisterFile.initializeProgramCounter(startAtMain);
        Stack.clearCallStack();
        terminated = false;
    }

    public StackFrame[] getCallStack(){
        StackFrame[] stack = new StackFrame[Stack.getCallStack().size()];
        for(int i = 0; i < stack.length; i++) {
            stack[i] = Stack.getCallStack().get(i);
        }
        return stack;
    }

    public String getLabelAtAddress(int address){
        return this.main.getLocalSymbolTable().getSymbolGivenIntAddress(address).getName();
    }

    public boolean simulate(int[] breakpoints) throws ProcessingException {
        terminated = this.main.simulate(breakpoints);
        return terminated;
    }
    public boolean simulate(int limit) throws ProcessingException {
        terminated = this.main.simulate(limit);
        return terminated;
    }
    public boolean simulate(int[] breakpoints, int limit) throws ProcessingException {
        terminated = this.main.simulateFromPC(breakpoints, limit);
        return terminated;
    }

    public boolean step() throws ProcessingException {
        terminated = this.main.simulateStepAtPC();
        return terminated;
    }

    public MIPSprogram getProgram() {
        return this.main;
    }

    public static InstructionSet getInstructionSet() {
        if(Globals.instructionSet == null) {
            initializeMIPS();
        }
        return Globals.getInstructionSet();
    }

    public boolean hasTerminated(){
        return terminated;
    }

    public Simulator getSimulator() {
        return Simulator.getInstance();
    }

}
