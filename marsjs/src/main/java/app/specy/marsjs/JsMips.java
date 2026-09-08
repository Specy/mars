package app.specy.marsjs;

import app.specy.mars.Globals;
import app.specy.mars.MIPS;
import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.assembler.SourceLine;
import app.specy.mars.assembler.TokenList;
import app.specy.mars.mips.fs.MemoryFileSystem;
import app.specy.mars.mips.hardware.AddressErrorException;
import app.specy.mars.mips.hardware.Coprocessor1;
import app.specy.mars.mips.hardware.Register;
import app.specy.mars.mips.hardware.RegisterFile;
import org.teavm.jso.JSExceptions;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSBoolean;
import org.teavm.jso.core.JSFunction;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.function.JSConsumer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JsMips {
    private MIPS main;
    private static JsMIPSIO ioHandler;

    private JsMips(MIPS main) {
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
    public static JsMips makeMipsFromFiles(String[] sourcePaths, String[] sources, String entryFile) {
        JsMips.getIOHandler(); // Ensure that the IO handler is initialized
        if (sourcePaths == null || sources == null || sourcePaths.length != sources.length) {
            throw new IllegalArgumentException("Source paths and contents must have the same length");
        }
        MemoryFileSystem files = new MemoryFileSystem();
        for (int i = 0; i < sourcePaths.length; i++) {
            files.write(sourcePaths[i], sources[i]);
        }
        return new JsMips(MIPS.fromFs(entryFile, files));
    }

    @JSExport
    public JsCompilationResult assemble() throws ProcessingException {
        try{
            return new JsCompilationResult(this.main.assemble());
        }catch (ProcessingException e) {
            return new JsCompilationResult(e.errors());
        }
    }

    @JSExport
    public JsMipsTokenizedLine[] getTokenizedLines() {
        List<TokenList> tokenizedLines = this.main.getTokens();
        List<SourceLine> sourceLines = this.main.getSourceLines();
        JsMipsTokenizedLine[] result = new JsMipsTokenizedLine[tokenizedLines.size()];
        for (int lineIndex = 0; lineIndex < tokenizedLines.size(); lineIndex++) {
            TokenList tokenizedLine = tokenizedLines.get(lineIndex);
            SourceLine sourceLine = sourceLines.get(lineIndex);
            JsMipsToken[] tokens = new JsMipsToken[tokenizedLine.size()];
            for (int tokenIndex = 0; tokenIndex < tokenizedLine.size(); tokenIndex++) {
                tokens[tokenIndex] = new JsMipsToken(tokenizedLine.get(tokenIndex));
            }
            result[lineIndex] = new JsMipsTokenizedLine(sourceLine.getSourcePath(),
                    sourceLine.getLineNumber(), sourceLine.getOriginalSource(),
                    sourceLine.getProcessedSource(), tokens);
        }
        return result;
    }

    @JSExport
    public void initialize(boolean startAtMain) {
        this.main.initialize(startAtMain);
    }


    /*
     * Simulation runs inside a single long-lived TeaVM coroutine ("green thread"), so that a
     * JS IO handler returning a promise can suspend the Java stack and resume once it settles.
     *
     * The coroutine is started once and then parked on a JS promise between tasks. Waking it
     * costs a microtask, whereas starting a fresh coroutine per call (Thread.start, which is what
     * JSPromise.callAsync does) goes through setTimeout and costs a full macrotask - about 1ms
     * per step, which is far too slow for instruction-level stepping.
     */
    private static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private static JSConsumer<JSObject> unpark;
    private static boolean workerStarted;

    private interface Body {
        boolean run() throws ProcessingException;
    }

    private static void workerLoop() {
        while (true) {
            while (!tasks.isEmpty()) {
                Runnable task = tasks.poll();
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // run() already settles the promise for every outcome, so there is nowhere
                    // left to report this; swallow it rather than killing the worker.
                }
            }
            JSPromise<JSObject> parked = JSPromise.create((resolve, reject) -> unpark = resolve);
            parked.await();
            unpark = null;
        }
    }

    private static void submit(Runnable task) {
        tasks.add(task);
        if (!workerStarted) {
            workerStarted = true;
            JSPromise.runAsync(JsMips::workerLoop);
            return;
        }
        JSConsumer<JSObject> resume = unpark;
        if (resume != null) {
            unpark = null;
            resume.accept(null);
        }
    }

    private static JSPromise<JSBoolean> run(Body body) {
        return JSPromise.create((resolve, reject) -> submit(() -> {
            boolean result;
            try {
                result = body.run();
            } catch (Throwable t) {
                reject.accept(JSExceptions.getJSException(t));
                return;
            }
            resolve.accept(JSBoolean.valueOf(result));
        }));
    }

    @JSExport
    public JSPromise<JSBoolean> step() {
        return run(() -> this.main.step());
    }

    @JSExport
    public JSPromise<JSBoolean> simulateWithLimit(int limit) {
        return run(() -> this.main.simulate(limit));
    }

    @JSExport
    public JsStackFrame[] getCallStack(){
        List<JsStackFrame> stack = new ArrayList<>();
        for(int i = 0; i < this.main.getCallStack().length; i++) {
            stack.add(new JsStackFrame(this.main.getCallStack()[i]));
        }
        return stack.toArray(new JsStackFrame[0]);
    }

    @JSExport
    public String getLabelAtAddress(int address){
        return this.main.getLabelAtAddress(address);
    }

    @JSExport
    public int[] getConditionFlags() {
        int[] flags = new int[8];
        for (int i = 0; i < 8; i++) {
            flags[i] = Coprocessor1.getConditionFlag(i);
        }
        return flags;
    }

    @JSExport
    public JSPromise<JSBoolean> simulateWithBreakpoints(int[] breakpoints) {
        return run(() -> this.main.simulate(breakpoints));
    }

    @JSExport
    public JSPromise<JSBoolean> simulateWithBreakpointsAndLimit(int[] breakpoints, int limit) {
        return run(() -> this.main.simulate(breakpoints, limit));
    }

    @JSExport
    public int getRegisterValue(String register) {
        return RegisterFile.getUserRegister(register).getValue();
    }

    @JSExport
    public void registerHandler(String name, JSFunction handler) {
        getIOHandler().registerHandler(name, handler);
    }

    @JSProperty
    @JSExport
    public int getStackPointer() {
        return RegisterFile.getUserRegister("$sp").getValue();
    }

    @JSProperty
    @JSExport
    public int getProgramCounter() {
        return RegisterFile.getProgramCounter();
    }

    @JSExport
    public int[] getRegistersValues() {
        return Arrays.stream(RegisterFile.getRegisters()).mapToInt(Register::getValue).toArray();
    }


    @JSExport
    public int getHi(){
        return RegisterFile.getValue(33);
    }

    @JSExport
    public int getLo(){
        return RegisterFile.getValue(34);
    }

    @JSExport
    public JsBackStep[] getUndoStack() {
        return Arrays.stream(this.main.getProgram().getBackStepper().getBackStepsStack().getStack()).map(JsBackStep::new).toArray(JsBackStep[]::new);
    }

    @JSExport
    public int[] readMemoryBytes(int address, int length) throws AddressErrorException {
        int[] memory = new int[length];
        for (int i = 0; i < length; i++) {
            // No notification: the host inspecting memory is not the program reading it, and a
            // memory viewer must not make a memory-mapped register consume its pending input.
            memory[i] = Globals.memory.getByteNoNotify(address + i);
        }
        return memory;
    }

    @JSExport
    public void setMemoryBytes(int address, int[] bytes) throws AddressErrorException {
        for (int i = 0; i < bytes.length; i++) {
            Globals.memory.setByte(address + i, bytes[i]);
        }
    }

    @JSExport
    public void setPeripheralWord(double address, int value) throws AddressErrorException {
        Globals.memory.setRawWordNoNotify(toAddress(address), value);
    }

    /**
     * Addresses cross from JavaScript as plain numbers, and one above 2^31-1 - which every
     * memory-mapped register is - stays positive instead of wrapping into a negative int.
     * Normalizing here means 0xffff0000 and 0xffff0000 | 0 name the same word, rather than the
     * unsigned form quietly registering an observer that can never match an access.
     */
    private static int toAddress(double address) {
        return (int) (long) address;
    }

    /*
     * Memory observers live on the Memory singleton, which assemble() and initialize() only clear
     * the contents of, so a registration survives both exactly like a registered IO handler and,
     * like one, is shared by every JsMips instance. The registrations are mirrored here because
     * Memory.deleteObserver leaves an empty observable behind for every removal and every memory
     * access walks that collection; removal therefore rebuilds it from the survivors.
     */
    private static final List<JsMemoryObserver> memoryObservers = new ArrayList<>();
    private static int nextMemoryObserverHandle = 1;

    @JSExport
    public int addMemoryWriteObserver(double startAddress, double endAddress, JSFunction handler)
            throws AddressErrorException {
        return addMemoryObserver(JsMemoryObserver.overRange(nextMemoryObserverHandle,
                toAddress(startAddress), toAddress(endAddress), handler));
    }

    @JSExport
    public int addMemoryAccessObserver(double address, JSFunction onRead, JSFunction onWrite)
            throws AddressErrorException {
        return addMemoryObserver(
                JsMemoryObserver.atWord(nextMemoryObserverHandle, toAddress(address), onRead, onWrite));
    }

    @JSExport
    public void removeMemoryObserver(int handle) {
        for (int i = 0; i < memoryObservers.size(); i++) {
            if (memoryObservers.get(i).handle == handle) {
                memoryObservers.remove(i);
                rebuildMemoryObservers();
                return;
            }
        }
    }

    @JSExport
    public void removeMemoryObservers() {
        memoryObservers.clear();
        Globals.memory.deleteObservers();
    }

    @JSExport
    public int countMemoryObservers() {
        return memoryObservers.size();
    }

    private static int addMemoryObserver(JsMemoryObserver observer) throws AddressErrorException {
        // Registering first leaves the mirror untouched when the range is rejected.
        Globals.memory.addObserver(observer, observer.startAddress, observer.endAddress);
        memoryObservers.add(observer);
        nextMemoryObserverHandle++;
        return observer.handle;
    }

    private static void rebuildMemoryObservers() {
        Globals.memory.deleteObservers();
        for (JsMemoryObserver observer : memoryObservers) {
            try {
                Globals.memory.addObserver(observer, observer.startAddress, observer.endAddress);
            } catch (AddressErrorException alreadyValidated) {
                // Every surviving registration passed this same check when it was added.
            }
        }
    }


    @JSProperty
    @JSExport
    public boolean canUndo() {
        return !this.main.getProgram().getBackStepper().empty();
    }

    @JSExport
    public void setUndoSize(int size) {
        Globals.maximumBacksteps = size;
    }

    @JSExport
    void setUndoEnabled(boolean enabled) {
        this.main.getProgram().getBackStepper().setEnabled(enabled);
    }

    @JSExport
    public void undo() {
        this.main.getProgram().getBackStepper().backStep();
    }

    @JSExport
    public JsProgramStatement getNextStatement() {
        return new JsProgramStatement(this.main.getStatementAtAddress(this.getProgramCounter()));
    }

    @JSExport
    public JsProgramStatement getStatementAtAddress(int address) {
        return new JsProgramStatement(this.main.getStatementAtAddress(address));
    }

    @JSExport
    public JsProgramStatement[] getCompiledStatements() {
        List<ProgramStatement> statements = this.main.getStatements();
        JsProgramStatement[] jsStatements = new JsProgramStatement[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            jsStatements[i] = new JsProgramStatement(statements.get(i));
        }
        return jsStatements;
    }

    @JSExport
    public JsProgramStatement[] getParsedStatements() {
        List<ProgramStatement> statements = this.main.getParsedStatements();
        JsProgramStatement[] jsStatements = new JsProgramStatement[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            jsStatements[i] = new JsProgramStatement(statements.get(i));
        }
        return jsStatements;
    }

    @JSExport
    public JsProgramStatement[] getStatementsAtSourceLocation(String sourcePath, double sourceLine) {
        if (!Double.isFinite(sourceLine) || sourceLine < 1 || sourceLine != Math.floor(sourceLine)
                || sourceLine > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Source line must be a positive integer");
        }
        return this.main.getStatementsAtSourceLocation(sourcePath, (int) sourceLine).stream()
                .map(JsProgramStatement::new)
                .toArray(JsProgramStatement[]::new);
    }

    @JSExport
    public static JsInstruction[] getInstructionSet() {
        return MIPS.getInstructionSet().getInstructionList().stream().map(JsInstruction::new).toArray(JsInstruction[]::new);
    }

    @JSExport
    public void setRegisterValue(String register, int value) {
        RegisterFile.getUserRegister(register).setValue(value);
    }

    @JSProperty
    @JSExport
    public boolean terminated() {
        return this.main.hasTerminated();
    }
}
