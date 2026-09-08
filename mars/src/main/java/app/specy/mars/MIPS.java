package app.specy.mars;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.specy.mars.assembler.SourceLine;
import app.specy.mars.assembler.SymbolTable;
import app.specy.mars.assembler.TokenList;
import app.specy.mars.mips.fs.MIPSFileSystem;
import app.specy.mars.mips.fs.MemoryFileSystem;
import app.specy.mars.mips.fs.SourcePath;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.mips.instructions.Instruction;
import app.specy.mars.mips.instructions.InstructionSet;
import app.specy.mars.mips.instructions.SyscallLoader;
import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.simulator.Simulator;
import app.specy.mars.util.SystemIO;

public class MIPS {

    private MIPSprogram main;
    private final String entryFile;
    private final MemoryFileSystem files;
    private boolean assemblyAttempted;
    private boolean assembled;
    private static MIPSIO io;
    private boolean terminated = false;


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

    private MIPS(String entryFile, MemoryFileSystem files) {
        this.entryFile = entryFile;
        this.files = files;
    }

    public static MIPS fromFs(String entryFile, MIPSFileSystem sourceFiles) {
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
        return new MIPS(entryFile, snapshot);
    }

    public static void initializeMIPS() {
        Globals.initialize();
    }

    public ErrorList assemble() throws ProcessingException {
        assemblyAttempted = true;
        assembled = false;
        terminated = false;
        Globals.program = null;
        Globals.symbolTable.clear();
        Globals.memory.clear();
        main = new MIPSprogram();
        main.prepareForAssembly(entryFile, files);
        ErrorList result = main.assemble(List.of(main), true);
        Globals.program = main;
        assembled = true;
        return result;
    }

    public void initialize(boolean startAtMain) {
        requireAssembled();
        RegisterFile.resetRegisters();
        Coprocessor0.resetRegisters();
        Coprocessor1.resetRegisters();
        RegisterFile.initializeProgramCounter(startAtMain);
        Stack.clearCallStack();
        terminated = false;
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
        return this.main.getLocalSymbolTable().getSymbolGivenIntAddress(address).getName();
    }

    public boolean simulate(int[] breakpoints) throws ProcessingException {
        requireAssembled();
        terminated = this.main.simulate(breakpoints);
        return terminated;
    }
    public boolean simulate(int limit) throws ProcessingException {
        requireAssembled();
        terminated = this.main.simulate(limit);
        return terminated;
    }
    public boolean simulate(int[] breakpoints, int limit) throws ProcessingException {
        requireAssembled();
        terminated = this.main.simulateFromPC(breakpoints, limit);
        return terminated;
    }

    public boolean step() throws ProcessingException {
        requireAssembled();
        terminated = this.main.simulateStepAtPC();
        return terminated;
    }

    public MIPSprogram getProgram() {
        requireAssembled();
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
