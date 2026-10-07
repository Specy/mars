package app.specy.mars;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.specy.mars.assembler.AssemblerProfile;
import app.specy.mars.assembler.RuntimeLibrary;
import app.specy.mars.assembler.SourceLine;
import app.specy.mars.assembler.Symbol;
import app.specy.mars.assembler.SymbolTable;
import app.specy.mars.assembler.TokenList;
import app.specy.mars.mips.fs.MIPSFileSystem;
import app.specy.mars.mips.fs.MemoryFileSystem;
import app.specy.mars.mips.fs.SourcePath;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.mips.instructions.Instruction;
import app.specy.mars.mips.instructions.InstructionSet;
import app.specy.mars.mips.instructions.SyscallLoader;
import app.specy.mars.mips.instructions.syscalls.RandomStreams;
import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.simulator.ProgramExit;
import app.specy.mars.simulator.Simulator;
import app.specy.mars.util.SystemIO;

public class MIPS {

    private MIPSprogram main;
    private final String entryFile;
    private final MemoryFileSystem files;
    private boolean assemblyAttempted;
    private boolean assembled;
    private static MIPSIO io;
    /** Why the last run call stopped, or null when none has run since the program was initialized. */
    private Simulator.Reason stopReason = null;


    public static void setIo(MIPSIO io) {
        MIPS.io = io;
        Globals.instructionSet.setSyscallLoader(new SyscallLoader(io));
        SystemIO.setMIPSIO(io);
    }

    public List<TokenList> getTokens(){
        requireTokenized();
        return this.main.getTokenList();
    }

    public List<SourceLine> getSourceLines() {
        requireTokenized();
        return this.main.getSourceLineList();
    }

    public ProgramStatement getStatementAtAddress(int address) {
        requireAssembled();
        return this.main.getMachineStatement(address);
    }

    /** The address of a defined label or alias, local to the entry program or global, or -1. */
    public int getAddressOfLabel(String label) {
        requireAssembled();
        return main.getLocalSymbolTable().getAddressLocalOrGlobal(label);
    }

    public List<ProgramStatement> getParsedStatements() {
        requireAssembled();
        return this.main.getParsedList();
    }

    public List<ProgramStatement> getStatements() {
        requireAssembled();
        return this.main.getMachineList();
    }

    public List<ProgramStatement> getStatementsAtSourceLocation(String sourcePath, int sourceLine) {
        requireAssembled();
        SourcePath.requireCanonical(sourcePath);
        if (sourceLine < 1) {
            throw new IllegalArgumentException("Source line must be a positive integer");
        }
        return this.main.getMachineList().stream()
                .filter(statement -> statement.getSourcePath().equals(sourcePath)
                        && statement.getSourceLine() == sourceLine)
                .toList();
    }

    private final AssemblerProfile assemblerProfile;
    private RuntimeLibrary runtimeLibrary;
    private String entrySymbol;

    /**
     * Links {@code library}'s members for the globals the program uses and does not define, and
     * starts execution at {@code entrySymbol} instead of the global {@code main}. The entry symbol
     * must be defined by the link and pulls its own member. Either may be null.
     */
    public void setLinkInputs(RuntimeLibrary library, String entrySymbol) {
        this.runtimeLibrary = library;
        this.entrySymbol = entrySymbol == null || entrySymbol.isEmpty() ? null : entrySymbol;
    }

    private MIPS(String entryFile, MemoryFileSystem files, AssemblerProfile profile) {
        this.entryFile = entryFile;
        this.files = files;
        this.assemblerProfile = java.util.Objects.requireNonNull(profile);
    }

    public static MIPS fromFs(String entryFile, MIPSFileSystem sourceFiles) {
        return fromFs(entryFile, sourceFiles, AssemblerProfile.MARS);
    }

    public static MIPS fromFs(String entryFile, MIPSFileSystem sourceFiles, AssemblerProfile profile) {
        SourcePath.requireCanonical(entryFile);
        if (sourceFiles == null) {
            throw new IllegalArgumentException("Source set must be an object");
        }

        MemoryFileSystem snapshot = new MemoryFileSystem();
        Set<String> paths = new HashSet<>();
        for (MIPSFile file : sourceFiles.getFiles()) {
            if (file == null) {
                throw new IllegalArgumentException("Source set must not contain null files");
            }
            String path = SourcePath.requireCanonical(file.getName());
            if (!paths.add(path)) {
                throw new IllegalArgumentException("Duplicate source path: " + path);
            }
            if (file.getSource() == null) {
                throw new IllegalArgumentException("Source content must be a string: " + path);
            }
            snapshot.write(path, file.getSource());
        }
        if (!paths.contains(entryFile)) {
            throw new IllegalArgumentException("Entry file is not present in the source set: " + entryFile);
        }
        return new MIPS(entryFile, snapshot, profile);
    }

    public static void initializeMIPS() {
        Globals.initialize();
    }

    public ErrorList assemble() throws ProcessingException {
        assemblyAttempted = true;
        assembled = false;
        stopReason = null;
        Globals.program = null;
        Globals.symbolTable.clear();
        Globals.memory.clear();
        main = new MIPSprogram();
        main.prepareForAssembly(entryFile, files, assemblerProfile);
        main.setLinkInputs(runtimeLibrary, entrySymbol);
        ErrorList result = main.assemble(List.of(main), true);
        Globals.memory.resetHeap(main.getHeapStart());
        Globals.program = main;
        assembled = true;
        return result;
    }

    public void initialize(boolean startAtMain) {
        requireAssembled();
        main.getBackStepper().clearHistory();
        RegisterFile.resetRegisters();
        Coprocessor0.resetRegisters();
        Coprocessor1.resetRegisters();
        if (entrySymbol != null) RegisterFile.initializeProgramCounter(entrySymbol);
        else RegisterFile.initializeProgramCounter(startAtMain);
        Stack.clearCallStack();
        // A new run starts with an empty heap, where this program's heap starts.
        Globals.memory.resetHeap(main.getHeapStart());
        // The state of the run: a new one has not exited, and its random generators start afresh.
        // Assembling leaves them alone, because a host assembles throwaway programs while one runs.
        ProgramExit.reset();
        RandomStreams.reset();
        stopReason = null;
    }

    public StackFrame[] getCallStack(){
        requireAssembled();
        StackFrame[] stack = new StackFrame[Stack.getCallStack().size()];
        for(int i = 0; i < stack.length; i++) {
            stack[i] = Stack.getCallStack().get(i);
        }
        return stack;
    }

    public String getLabelAtAddress(int address){
        requireAssembled();
        Symbol symbol = this.main.getLocalSymbolTable().getSymbolGivenIntAddressLocalOrGlobal(address);
        return (symbol == null) ? null : symbol.getName();
    }

    public Simulator.Reason simulate(int[] breakpoints) throws ProcessingException {
        return run(() -> this.main.simulate(breakpoints));
    }

    /** Runs at most {@code limit} instructions, or until the program stops when it is 0 or less. */
    public Simulator.Reason simulate(int limit) throws ProcessingException {
        return run(() -> this.main.simulate(limit));
    }

    public Simulator.Reason simulate(int[] breakpoints, int limit) throws ProcessingException {
        return run(() -> this.main.simulateFromPC(breakpoints, limit));
    }

    public Simulator.Reason step() throws ProcessingException {
        return run(() -> this.main.simulateStepAtPC());
    }

    private interface Run {
        Simulator.Reason run() throws ProcessingException;
    }

    private Simulator.Reason run(Run body) throws ProcessingException {
        requireAssembled();
        if (ProgramExit.hasExited()) {
            // An exit ends the program: what follows the syscall is not run, as MARS does not
            // resume a program that has finished. Undo or initialize starts it again.
            stopReason = Simulator.Reason.NORMAL_TERMINATION;
            return stopReason;
        }
        try {
            stopReason = body.run();
        } catch (ProcessingException failure) {
            stopReason = Simulator.Reason.EXCEPTION;
            throw failure;
        }
        return stopReason;
    }

    /**
     * Where the program's heap, and so the first block sbrk hands out, starts: MARS's heap base, or
     * the first page after static data in a GNU-profile program whose static data reaches past it.
     */
    public int getHeapStart() {
        requireAssembled();
        return main.getHeapStart();
    }

    /** Why the last run call stopped, or null when none has run since the program was initialized. */
    public Simulator.Reason getStopReason() {
        return stopReason;
    }

    /**
     * The program's exit code: exit2's operand once it has run, 0 otherwise - after exit, after
     * running off the end, while the program runs. Initialize resets it; undo puts it back.
     */
    public int getExitCode() {
        return ProgramExit.code();
    }

    public MIPSprogram getProgram() {
        requireAssembled();
        return this.main;
    }

    /**
     * Whether a program has assembled successfully, so that {@link #getProgram()} answers rather
     * than throwing. A host setter that only wants the back stepper when there is one asks this
     * instead of catching the failure.
     */
    public boolean isAssembled() {
        return assembled;
    }

    public static InstructionSet getInstructionSet() {
        if(Globals.instructionSet == null) {
            initializeMIPS();
        }
        return Globals.getInstructionSet();
    }

    /**
     * Whether the program has ended, read from its state rather than from the last run call, so
     * that it is right after undo too: an exit service has run, or there is no statement at the
     * program counter because execution ran off the end of the program.
     */
    public boolean hasTerminated(){
        if (!assembled) return false;
        return ProgramExit.hasExited() || Simulator.noStatementAt(RegisterFile.getProgramCounter());
    }

    /**
     * The statement the program runs next, or null when it has ended: after an exit, the statement
     * that follows the syscall is not one the program will run.
     */
    public ProgramStatement getNextStatement() {
        requireAssembled();
        if (hasTerminated()) return null;
        return this.main.getMachineStatement(RegisterFile.getProgramCounter());
    }

    public Simulator getSimulator() {
        return Simulator.getInstance();
    }

    private void requireTokenized() {
        if (!assemblyAttempted) {
            throw new IllegalStateException("Program has not been assembled");
        }
        if (main == null || main.getTokenList() == null || main.getSourceLineList() == null) {
            throw new IllegalStateException("Program tokenization did not complete");
        }
    }

    private void requireAssembled() {
        if (!assembled) {
            throw new IllegalStateException("Program has not been assembled successfully");
        }
    }

}
