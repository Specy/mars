package app.specy.marsjs;

import app.specy.mars.Globals;
import app.specy.mars.MIPS;
import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.assembler.SourceLine;
import app.specy.mars.assembler.TokenList;
import app.specy.mars.mips.fs.MemoryFileSystem;
import app.specy.mars.mips.hardware.AddressErrorException;
import app.specy.mars.mips.hardware.Coprocessor0;
import app.specy.mars.mips.hardware.Coprocessor1;
import app.specy.mars.mips.hardware.Register;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.simulator.BackStepper;
import org.teavm.jso.JSExceptions;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSBoolean;
import org.teavm.jso.core.JSFunction;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.function.JSConsumer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /*
     * How many step/simulate calls of THIS core are in flight. A task is counted from the moment it
     * is submitted until its body returns, which includes the time the coroutine spends parked on
     * an IO handler's promise: the simulator's state is half-written then, so a poke must be
     * refused. The count is per instance, not static: a host builds throwaway cores (the editor
     * assembles one to check the source), and a step on one of those must not refuse a poke on
     * another.
     */
    private int executingInstructions;

    private JSPromise<JSBoolean> run(Body body) {
        executingInstructions++;
        boolean submitted = false;
        try {
            JSPromise<JSBoolean> promise = JSPromise.create((resolve, reject) -> submit(() -> {
                boolean result;
                try {
                    result = body.run();
                } catch (Throwable t) {
                    reject.accept(JSExceptions.getJSException(t));
                    return;
                } finally {
                    // Exactly once, on every path out of the body, so that a failed step cannot
                    // leave the core refusing pokes for the rest of the session.
                    executingInstructions--;
                }
                resolve.accept(JSBoolean.valueOf(result));
            }));
            submitted = true;
            return promise;
        } finally {
            if (!submitted) {
                // The task never reached the queue, so its body will never run the decrement.
                executingInstructions--;
            }
        }
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

    /**
     * Sets one of the 8 FPU condition flags. Outside a poke the write is direct and records no undo
     * step: Coprocessor1.setConditionFlag and clearConditionFlag push a backstep entry, which a
     * host presetting the FPU must not do. Inside a poke it joins the open transaction.
     */
    @JSExport
    public void setConditionFlag(int flag, boolean value) {
        if (flag < 0 || flag >= 8) {
            throw new IllegalArgumentException("Condition flag must be between 0 and 7");
        }
        if (openPoke == null) {
            Coprocessor1.setConditionFlagDirectly(flag, value);
            return;
        }
        int old = Coprocessor1.getConditionFlag(flag);
        if (old == (value ? 1 : 0)) {
            return; // unchanged: nothing to journal and nothing to undo
        }
        journalRegister(conditionFlagName(flag), REGISTER_KIND_CONDITION_FLAG, flag, old);
        if (value) {
            Coprocessor1.setConditionFlag(flag);
        } else {
            Coprocessor1.clearConditionFlag(flag);
        }
    }

    /**
     * The raw 32 bit patterns of the FPU registers, element i being $fi. A double occupies an
     * even/odd pair, the low word in the even register and the high word in the odd one that
     * follows it, as MARS does.
     */
    @JSExport
    public int[] getCoprocessor1Values() {
        Register[] registers = Coprocessor1.getRegisters();
        int[] values = new int[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i].getValue();
        }
        return values;
    }

    /**
     * Writes one FPU register. Outside a poke the write is direct, bypassing the backstepper,
     * exactly as setRegisterValue writes a general register: a value the host presets is not a step
     * to undo. Inside a poke it joins the open transaction.
     */
    @JSExport
    public void setCoprocessor1Value(int index, int value) {
        Register[] registers = Coprocessor1.getRegisters();
        // TeaVM does not coerce the argument at the export boundary, so a fractional index would
        // slip past a plain range check and then blow up inside the array access with a raw
        // TypeError. `index | 0` truncates in the compiled JS, which makes the comparison reject
        // it with the documented exception, while staying a no-op in Java.
        if (index < 0 || index >= registers.length || index != (index | 0)) {
            throw new IllegalArgumentException("FPU register index must be a whole number between 0 and "
                    + (registers.length - 1));
        }
        Register register = registers[index];
        if (openPoke == null) {
            register.setValue(value);
            return;
        }
        int old = register.getValue();
        if (old == value) {
            return;
        }
        journalRegister(register.getName(), REGISTER_KIND_COPROCESSOR1, register.getNumber(), old);
        Coprocessor1.updateRegister(register.getNumber(), value);
    }

    /**
     * The values of the four implemented coprocessor 0 registers, in the order Coprocessor0
     * holds them: $8 (vaddr), $12 (status), $13 (cause), $14 (epc).
     */
    @JSExport
    public int[] getCoprocessor0Values() {
        Register[] registers = Coprocessor0.getRegisters();
        int[] values = new int[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i].getValue();
        }
        return values;
    }

    /**
     * Writes one coprocessor 0 register. Outside a poke the write is direct, bypassing the
     * backstepper; inside a poke it joins the open transaction. The register is named by its MIPS
     * number (8, 12, 13 or 14), not by its position.
     */
    @JSExport
    public void setCoprocessor0Value(int number, int value) {
        for (Register register : Coprocessor0.getRegisters()) {
            if (register.getNumber() == number) {
                if (openPoke == null) {
                    register.setValue(value);
                    return;
                }
                int old = register.getValue();
                if (old == value) {
                    return;
                }
                journalRegister(register.getName(), REGISTER_KIND_COPROCESSOR0, number, old);
                Coprocessor0.updateRegister(number, value);
                return;
            }
        }
        throw new IllegalArgumentException("Coprocessor 0 register number must be 8, 12, 13 or 14");
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

    /**
     * The raw back step stack, newest first: one element per recorded step. An instruction occupies
     * one to three of them, a poke exactly one, whatever it wrote - the element with `isPoke` set,
     * which is what tells a poke apart from a host write made before anything ran, since both carry
     * pc -1. Use getUndoGroups() to read the history the way undo() pops it.
     *
     * Every element reports both sides of the write it undoes: the value the write replaced, in the
     * param the action documents, and `newValue`, the value it left, taken by the setter at the
     * moment of the write.
     */
    @JSExport
    public JSArray<JSObject> getUndoStack() {
        BackStepper.BackStep[] stack = this.main.getProgram().getBackStepper().getBackStepsStack().getStack();
        JSArray<JSObject> steps = JSArray.create(stack.length);
        for (int i = 0; i < stack.length; i++) {
            steps.set(i, JsBackStep.of(stack[i]));
        }
        return steps;
    }

    /**
     * The same history as getUndoStack(), grouped the way undo() pops it: one entry per executed
     * instruction or per poke, newest first. An instruction that records several backsteps - a jal
     * restoring both $ra and the program counter, a multiply writing hi and lo - is one entry, and
     * so is a poke, which is one back step to begin with.
     */
    @JSExport
    public JSArray<JSObject> getUndoGroups() {
        BackStepper.BackStep[] stack = this.main.getProgram().getBackStepper().getBackStepsStack().getStack();
        List<JSObject> groups = new ArrayList<>();
        Set<Integer> livePokeGroups = new HashSet<>();
        int start = 0;
        while (start < stack.length) {
            int end = start + 1;
            while (end < stack.length && BackStepper.sameGroup(stack[end - 1], stack[end])) {
                end++;
            }
            JSArray<JSObject> steps = JSArray.create(end - start);
            for (int i = start; i < end; i++) {
                steps.set(i - start, JsBackStep.of(stack[i]));
            }
            if (stack[start].isPoke()) {
                int group = stack[start].getPokeGroup();
                livePokeGroups.add(group);
                groups.add(JsUndoGroup.poke(POKE_PC, steps, writesOfPoke(group)));
            } else {
                groups.add(JsUndoGroup.instruction(stack[start].getPc(), steps));
            }
            start = end;
        }
        // A poke whose entry has fallen off the circular stack can never be reported again.
        forgetPokeRecordsOtherThan(livePokeGroups);
        JSArray<JSObject> result = JSArray.create(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            result.set(i, groups.get(i));
        }
        return result;
    }

    /*
     * Pokes.
     *
     * A poke is a register or memory value the host changes between two instructions: one entry of
     * this same history, undone by one undo(). beginPoke() opens a transaction; until endPoke() the
     * setters above write through the simulator's own paths, so the back stepper records what they
     * changed under the transaction's own group key, and they journal here what the value was, so
     * that the entry can say what it changed. Outside a transaction every setter is direct and
     * records nothing, which is what presetting a testcase needs.
     */

    /** The pc a poke reports: it belongs to no instruction, so it has no address of its own. */
    private static final int POKE_PC = -1;

    private static final int REGISTER_KIND_GENERAL = 0;
    private static final int REGISTER_KIND_COPROCESSOR1 = 1;
    private static final int REGISTER_KIND_COPROCESSOR0 = 2;
    private static final int REGISTER_KIND_CONDITION_FLAG = 3;

    /*
     * Finished pokes are remembered here so that getUndoGroups() can report what they changed long
     * after the writes happened. Only a poke still on the back step stack can be reported, so the
     * list is pruned whenever the groups are read, and capped for the case where they never are.
     */
    private static final int MAX_POKE_RECORDS = 1024;

    private PokeJournal openPoke;
    private final List<PokeRecord> pokeRecords = new ArrayList<>();

    /**
     * Opens a poke transaction. Every write made by the setters until endPoke() becomes part of one
     * history entry, restored as a unit by one undo().
     *
     * @throws IllegalStateException if a poke is already open, or if an instruction is executing.
     */
    @JSExport
    public void beginPoke() {
        if (executingInstructions > 0) {
            throw new IllegalStateException("Cannot begin a poke while an instruction is executing");
        }
        if (openPoke != null) {
            throw new IllegalStateException("A poke is already open");
        }
        openPoke = new PokeJournal(this.main.getProgram().getBackStepper().beginPoke());
    }

    /**
     * Closes the open poke transaction.
     *
     * @return true if it recorded one history entry, false if it wrote nothing - or if undo is
     * disabled, in which case the writes stand but cannot be undone.
     * @throws IllegalStateException if no poke is open.
     */
    @JSExport
    public boolean endPoke() throws AddressErrorException {
        if (openPoke == null) {
            throw new IllegalStateException("No poke is open");
        }
        PokeJournal journal = openPoke;
        openPoke = null;
        if (!this.main.getProgram().getBackStepper().endPoke()) {
            return false;
        }
        pokeRecords.add(new PokeRecord(journal.group, buildWrites(journal)));
        while (pokeRecords.size() > MAX_POKE_RECORDS) {
            pokeRecords.remove(0);
        }
        return true;
    }

    /** Whether a poke transaction is open, so that the setters journal instead of writing through. */
    @JSExport
    public boolean pokeOpen() {
        return openPoke != null;
    }

    private BackStepper backStepper() {
        return this.main.isAssembled() ? this.main.getProgram().getBackStepper() : null;
    }

    private static String conditionFlagName(int flag) {
        return "flag " + flag;
    }

    private void journalRegister(String name, int kind, int index, int oldValue) {
        // The first write to a register in the transaction holds the value to restore; a later one
        // overwrites a value the poke itself put there.
        if (openPoke.registersByName.containsKey(name)) {
            return;
        }
        PokeRegisterWrite write = new PokeRegisterWrite(name, kind, index, oldValue);
        openPoke.registersByName.put(name, write);
        openPoke.registers.add(write);
    }

    private void journalMemory(int address, int oldByte) {
        // As for a register, the first write to an address holds the value to restore.
        Long key = address & 0xffffffffL;
        if (!openPoke.memoryOldBytes.containsKey(key)) {
            openPoke.memoryOldBytes.put(key, oldByte);
        }
    }

    private static int currentRegisterValue(int kind, int index) {
        switch (kind) {
            case REGISTER_KIND_COPROCESSOR1:
                return Coprocessor1.getValue(index);
            case REGISTER_KIND_COPROCESSOR0:
                return Coprocessor0.getValue(index);
            case REGISTER_KIND_CONDITION_FLAG:
                return Coprocessor1.getConditionFlag(index);
            default:
                return RegisterFile.getValue(index);
        }
    }

    /**
     * What the poke changed: every register it wrote, in the order it wrote them, then every run of
     * consecutive memory addresses it wrote, by ascending address. The new values are read now, at
     * the end of the transaction, so a value the poke wrote twice reports only its final state.
     */
    private static JSArray<JSObject> buildWrites(PokeJournal journal) throws AddressErrorException {
        List<JSObject> writes = new ArrayList<>();
        for (PokeRegisterWrite register : journal.registers) {
            writes.add(JsPokeWrite.register(register.name, register.oldValue,
                    currentRegisterValue(register.kind, register.index)));
        }
        List<Long> addresses = new ArrayList<>(journal.memoryOldBytes.keySet());
        Collections.sort(addresses); // unsigned order: the keys are addresses widened to long
        int runStart = 0;
        while (runStart < addresses.size()) {
            int runEnd = runStart + 1;
            while (runEnd < addresses.size()
                    && addresses.get(runEnd).longValue() == addresses.get(runEnd - 1).longValue() + 1) {
                runEnd++;
            }
            int[] oldBytes = new int[runEnd - runStart];
            int[] newBytes = new int[runEnd - runStart];
            for (int i = runStart; i < runEnd; i++) {
                int address = (int) (long) addresses.get(i);
                oldBytes[i - runStart] = journal.memoryOldBytes.get(addresses.get(i));
                newBytes[i - runStart] = Globals.memory.getByteNoNotify(address) & 0xff;
            }
            writes.add(JsPokeWrite.memory((int) (long) addresses.get(runStart), oldBytes, newBytes));
            runStart = runEnd;
        }
        JSArray<JSObject> result = JSArray.create(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            result.set(i, writes.get(i));
        }
        return result;
    }

    private JSArray<JSObject> writesOfPoke(int group) {
        for (PokeRecord record : pokeRecords) {
            if (record.group == group) {
                return record.writes;
            }
        }
        return JSArray.create(0);
    }

    private void forgetPokeRecordsOtherThan(Set<Integer> livePokeGroups) {
        for (int i = pokeRecords.size() - 1; i >= 0; i--) {
            if (!livePokeGroups.contains(pokeRecords.get(i).group)) {
                pokeRecords.remove(i);
            }
        }
    }

    /** One register the open poke has written, with the value to put back. */
    private static final class PokeRegisterWrite {
        final String name;
        final int kind;
        final int index;
        final int oldValue;

        PokeRegisterWrite(String name, int kind, int index, int oldValue) {
            this.name = name;
            this.kind = kind;
            this.index = index;
            this.oldValue = oldValue;
        }
    }

    /** What the open poke has written so far. */
    private static final class PokeJournal {
        final int group;
        final List<PokeRegisterWrite> registers = new ArrayList<>();
        final Map<String, PokeRegisterWrite> registersByName = new HashMap<>();
        /** Old byte per written address, the address widened to an unsigned long. */
        final Map<Long, Integer> memoryOldBytes = new HashMap<>();

        PokeJournal(int group) {
            this.group = group;
        }
    }

    /** A finished poke, kept for as long as its entry is on the back step stack. */
    private static final class PokeRecord {
        final int group;
        final JSArray<JSObject> writes;

        PokeRecord(int group, JSArray<JSObject> writes) {
            this.group = group;
            this.writes = writes;
        }
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
        if (openPoke != null) {
            for (int i = 0; i < bytes.length; i++) {
                int at = address + i;
                int old = Globals.memory.getByteNoNotify(at) & 0xff;
                if (old == (bytes[i] & 0xff)) {
                    continue; // unchanged: no write, no notification, nothing to undo
                }
                journalMemory(at, old);
                // The ordinary store, so observers hear it; the back stepper is open on a poke, so
                // the entry it records joins that poke's group rather than the last instruction's.
                Globals.memory.setByte(at, bytes[i]);
            }
            return;
        }
        // A host write outside a poke is not a step: it writes the way the program does, so that a
        // memory mapped display still repaints, but records nothing. Left recording, each byte
        // would push a backstep under the last executed instruction's address and the next undo
        // would revert the host's write together with that instruction.
        BackStepper backStepper = backStepper();
        boolean recording = backStepper != null && backStepper.enabled();
        if (recording) {
            backStepper.setEnabled(false);
        }
        try {
            for (int i = 0; i < bytes.length; i++) {
                Globals.memory.setByte(address + i, bytes[i]);
            }
        } finally {
            if (recording) {
                backStepper.setEnabled(true);
            }
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

    /**
     * Writes one general register. Outside a poke the write is direct, bypassing the backstepper;
     * inside a poke it joins the open transaction. `$zero` is not writable inside a poke: it holds
     * no value, so there would be nothing for undo to restore.
     */
    @JSExport
    public void setRegisterValue(String register, int value) {
        Register target = RegisterFile.getUserRegister(register);
        if (openPoke == null) {
            target.setValue(value);
            return;
        }
        if (target.getNumber() == 0) {
            return;
        }
        int old = target.getValue();
        if (old == value) {
            return;
        }
        journalRegister(target.getName(), REGISTER_KIND_GENERAL, target.getNumber(), old);
        RegisterFile.updateRegister(target.getNumber(), value);
    }

    @JSProperty
    @JSExport
    public boolean terminated() {
        return this.main.hasTerminated();
    }
}
