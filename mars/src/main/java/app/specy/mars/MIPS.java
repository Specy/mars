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

    /**
     * The program of the unit whose File is {@code path} among those the last assembly read, the
     * entry's or one of its other units', for that unit's lines and tokens; null for any other path.
     */
    public MIPSprogram getUnitProgram(String path) {
        requireTokenized();
        for (MIPSprogram program : main.getUnitPrograms()) if (program.getFilename().equals(path)) return program;
        return null;
    }

    /**
     * Every File the last assembly read for the program's own units: the entry's File and what it
     * includes, then those of each other unit the link took. A unit it did not take is never read.
     */
    public List<String> getUnitFiles() {
        requireTokenized();
        Set<String> paths = new java.util.LinkedHashSet<>();
        for (MIPSprogram program : main.getUnitPrograms())
            for (SourceLine line : program.getSourceLineList()) paths.add(line.getSourcePath());
        return new java.util.ArrayList<>(paths);
    }

    /**
     * The program's own units the last assembly linked, by File: the entry first, then the other
     * units the program needed. Empty before an assembly; only the entry in the MARS dialect.
     */
    public List<String> getLinkedUnits() {
        if (main == null) return List.of();
        return assemblerProfile == AssemblerProfile.GNU_COMPILER_V1 ? main.getLinkedUnits() : List.of(entryFile);
    }

    /**
     * The units, the program's own and its library's, whose text the last assembly parsed rather
     * than taking the parse an earlier assembly kept of the same text.
     */
    public List<String> getAssembledUnits() {
        return main == null ? List.of() : main.getAssembledUnits();
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
    private List<String> units = List.of();

    /**
     * Links {@code library}'s members for the globals the program uses and does not define, and
     * starts execution at {@code entrySymbol} instead of the global {@code main}. The entry symbol
     * must be defined by the link and pulls its own member. Either may be null.
     */
    public void setLinkInputs(RuntimeLibrary library, String entrySymbol) {
        this.runtimeLibrary = library;
        this.entrySymbol = entrySymbol == null || entrySymbol.isEmpty() ? null : entrySymbol;
    }

    /**
     * The program's other units: Files of the source set, GNU compiler assembly like the entry, that
     * the link takes as an archive's members, each only when it defines a global the program still
     * needs, searched before the library. A unit it takes is the program's own code, with its own
     * local labels. Which globals a unit defines is read from its declarations first, and only a
     * unit the link takes is parsed, so one it leaves out is never checked: it can be in another
     * dialect, or have mistakes, without failing the assembly. Only for the gnu-compiler-v1
     * profile.
     */
    public void setUnits(String[] paths) {
        if (paths == null) throw new IllegalArgumentException("Units must be an array of paths");
        Set<String> seen = new HashSet<>();
        for (String path : paths) {
            SourcePath.requireCanonical(path);
            if (path.equals(entryFile)) throw new IllegalArgumentException("The entry file is no other unit: " + path);
            if (!files.exists(path)) throw new IllegalArgumentException("Unit is not present in the source set: " + path);
            if (!seen.add(path)) throw new IllegalArgumentException("Duplicate unit: " + path);
        }
        if (paths.length > 0 && assemblerProfile != AssemblerProfile.GNU_COMPILER_V1)
            throw new IllegalArgumentException("Other units are linked only with the gnu-compiler-v1 profile");
        this.units = List.of(paths);
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
        lastRunInstructionCount = 0;
        Globals.program = null;
        Globals.symbolTable.clear();
        Globals.memory.clear();
        main = new MIPSprogram();
        main.prepareForAssembly(entryFile, files, assemblerProfile);
        main.setLinkInputs(runtimeLibrary, entrySymbol);
        main.setArchive(units, files);
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
        // nothing pending from the last run: no device line raised and the timer off until Compare
        // is written, the instruction count starting over with it
        Coprocessor0.resetInterrupts();
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
        lastRunInstructionCount = 0;
    }

    public StackFrame[] getCallStack(){
        requireAssembled();
        return Stack.getCallStack().toArray(new StackFrame[0]);
    }

    /** The newest {@code max} frames of the call stack, oldest first; all of them when it is negative. */
    public StackFrame[] getCallStack(int max){
        requireAssembled();
        return Stack.getCallStack(max).toArray(new StackFrame[0]);
    }

    /** How many frames deep the call stack is, those past its limit included; in constant time. */
    public int getCallDepth() {
        return Stack.getCallDepth();
    }

    /**
     * The label at an address: the entry unit's own or a global, else one of another linked unit's
     * own labels, such as a static function's, which only that unit's table holds.
     */
    public String getLabelAtAddress(int address){
        requireAssembled();
        Symbol symbol = this.main.getLocalSymbolTable().getSymbolGivenIntAddressLocalOrGlobal(address);
        if (symbol != null) return symbol.getName();
        for (MIPSprogram program : main.getUnitPrograms()) {
            if (program == main || program.getLocalSymbolTable() == null) continue;
            symbol = program.getLocalSymbolTable().getSymbolGivenIntAddress(address);
            if (symbol != null) return symbol.getName();
        }
        return null;
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

    /**
     * Runs from the program counter until the program stops, a breakpoint, a pause, {@code limit}
     * instructions (none when 0 or less), or the call stack is {@code untilCallDepth} frames deep or
     * less (none when negative). A breakpoint at the program counter stops the call before anything
     * runs unless {@code skipBreakpointAtPc}. See
     * {@link Simulator#simulate(MIPSprogram, int, int, int[], int, boolean)}.
     */
    public Simulator.Reason run(int[] breakpoints, int limit, int untilCallDepth, boolean skipBreakpointAtPc)
            throws ProcessingException {
        return run(() -> this.main.simulateFromPC(breakpoints, limit, untilCallDepth, !skipBreakpointAtPc));
    }

    private interface Run {
        Simulator.Reason run() throws ProcessingException;
    }

    private Simulator.Reason run(Run body) throws ProcessingException {
        requireAssembled();
        lastRunInstructionCount = 0;
        if (ProgramExit.hasExited()) {
            // An exit ends the program: what follows the syscall is not run, as MARS does not
            // resume a program that has finished. Undo or initialize starts it again.
            stopReason = Simulator.Reason.NORMAL_TERMINATION;
            return stopReason;
        }
        double before = Simulator.getInstance().getInstructionsRun();
        // whatever the host changed since the last call, a line or a register, is looked at first
        Coprocessor0.beginRun();
        try {
            stopReason = body.run();
        } catch (ProcessingException failure) {
            stopReason = Simulator.Reason.EXCEPTION;
            throw failure;
        } finally {
            lastRunInstructionCount = Simulator.getInstance().getInstructionsRun() - before;
        }
        return stopReason;
    }

    /** How many instructions the last run call executed, see {@link Simulator#getInstructionsRun()}. */
    private double lastRunInstructionCount = 0;

    public double getLastRunInstructionCount() {
        return lastRunInstructionCount;
    }

    /**
     * Every instruction a run call has executed since the module loaded, see
     * {@link Simulator#getInstructionsRun()}: never rewound by undo nor reset by initialize.
     */
    public double getInstructionsExecuted() {
        return Simulator.getInstance().getInstructionsRun();
    }

    /** Ends the run call in flight after its current instruction, see {@link Simulator#pauseExecution()}. */
    public void pause() {
        Simulator.getInstance().pauseExecution();
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
