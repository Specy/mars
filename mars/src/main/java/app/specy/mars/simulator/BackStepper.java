package app.specy.mars.simulator;

import app.specy.mars.*;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.mips.instructions.*;
import app.specy.mars.mips.instructions.syscalls.RandomStreams;

import java.util.ArrayList;
import java.util.List;

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Used to "step backward" through execution, undoing each instruction.
 *
 * @author Pete Sanderson
 * @version February 2006
 */

public class BackStepper {
    // The types of "undo" actions. Under 1.5, these would be enumerated type.
    // These fit better in the BackStep class below but inner classes cannot have
    // static members.
    private static final int MEMORY_RESTORE_RAW_WORD = 0;
    private static final int MEMORY_RESTORE_WORD = 1;
    private static final int MEMORY_RESTORE_HALF = 2;
    private static final int MEMORY_RESTORE_BYTE = 3;
    private static final int REGISTER_RESTORE = 4;
    private static final int PC_RESTORE = 5;
    private static final int COPROC0_REGISTER_RESTORE = 6;
    private static final int COPROC1_REGISTER_RESTORE = 7;
    private static final int COPROC1_CONDITION_CLEAR = 8;
    private static final int COPROC1_CONDITION_SET = 9;
    private static final int HEAP_RESTORE = 100;
    private static final int STACK_TOP_RESTORE = 101;
    private static final int DO_NOTHING = 10; // instruction does not write anything.
    /**
     * One whole poke, however many values it wrote: the entry carries its restores itself rather
     * than taking a stack slot per value, so that a poke costs one slot of the history capacity,
     * exactly as the contract says, and can never be half evicted.
     */
    public static final int POKE = 11;
    /**
     * An exit service: puts back whether the program had exited (param1, 0 or 1) and the exit code
     * it had (param2), param3 being the code the exit set. Undoing it leaves the program running
     * on the syscall again.
     */
    private static final int EXIT_RESTORE = 12;
    /**
     * A random service: puts generator param1 back in the state it had before the service advanced
     * or reseeded it, its high half in param2 and its low half in param3, or forgets the generator
     * when param2 is RandomStreams.ABSENT. The state is the simulator's own, so the getters report
     * neither half.
     */
    private static final int RANDOM_STREAM_RESTORE = 13;

    // Flag to mark BackStep object as prepresenting specific situation: user
    // manipulates
    // memory/register value via GUI after assembling program but before running it.
    private static final int NOT_PC_VALUE = -1;

    // Module-lifetime identities: neither Undo, initialize nor reassembly can reuse one.
    // Only the bounded BackStep slots retain identities; no execution journal grows with a run.
    private static long nextSerial = 1;
    private long instructionsExecuted;
    public long getInstructionsExecuted() { return instructionsExecuted; }
    public void resetInstructionsExecuted() { instructionsExecuted = 0; }
    private long instructionSerial;
    private long pokeSerial;
    private int instructionPc;
    private boolean instructionDiscarded;

    private static long allocateSerial() {
        if (nextSerial == Long.MAX_VALUE) {
            throw new IllegalStateException("Instruction serial space exhausted");
        }
        return nextSerial++;
    }

    /** Begins one dynamic instruction, including one that exits or fails. */
    public void beginInstruction(int pc, boolean recording) {
        if (instructionSerial != 0 || pokeOpen()) {
            throw new IllegalStateException("An instruction or poke is already executing");
        }
        instructionSerial = allocateSerial();
        instructionPc = pc;
        instructionDiscarded = !recording || !engaged || backSteps.capacity == 0;
        // Undo must never cross a gap where execution had no recorded restores.
        if (instructionDiscarded) clearHistory();
    }

    /** Finishes even a failed instruction, giving a write-free instruction its own entry. */
    public void endInstruction() {
        instructionsExecuted++;
        try {
            if (!instructionDiscarded) addDoNothing(instructionPc);
        } finally {
            instructionSerial = 0;
        }
    }

    /** Zero outside execution; never rewound or reused. Available across an awaited handler. */
    public long getCurrentInstructionSerial() {
        return instructionSerial;
    }

    /** A reset forgets retained restores without rewinding the serial allocator. */
    public void clearHistory() {
        backSteps.size = 0;
        backSteps.top = -1;
    }

    private boolean engaged;
    private BackstepStack backSteps;

    /*
     * A poke - a register or memory value written by the host between two instructions - is one
     * entry of this same history, undone by one backStep() like an instruction, and one slot of its
     * capacity however many values it wrote. While a transaction is open the restores the setters
     * record are collected in pokeWrites instead of being pushed, and endPoke() pushes them as a
     * single POKE back step; backStep() then applies them in reverse. Pushing one slot per value
     * would make a long memory poke evict the instructions before it and, worse, let the poke be
     * half evicted: undoable only in part while its entry still claimed to hold every write.
     *
     * The entry carries a group key of its own, negative and distinct per transaction, so that it
     * is never an instruction address (a poke entry also keeps pc == NOT_PC_VALUE, so backStep()
     * does not restore a PC for it) and so that the finished writes a caller remembers can be
     * matched to the entry that still holds them.
     *
     * The counter is static so that a key is never reused within a session: reassembling builds a
     * fresh BackStepper, and a per-instance counter would hand the new stack keys that a caller may
     * still be holding poke records under.
     */
    private static final int NO_POKE_GROUP = 0;
    private static int nextPokeGroup = -2;
    private int pokeGroup = NO_POKE_GROUP;
    /** The open transaction's restores, oldest first, each an {action, param1, param2} triple. */
    private List<int[]> pokeWrites;

    // One can argue using java.util.Stack, given its clumsy implementation.
    // A homegrown linked implementation will be more streamlined, but
    // I anticipate that backstepping will only be used during timed
    // (currently max 30 instructions/second) or stepped execution, where
    // performance is not an issue. Its Vector implementation may result
    // in quicker garbage collection than a pure linked list implementation.

    /**
     * Create a fresh BackStepper. It is enabled, which means all
     * subsequent instruction executions will have their "undo" action
     * recorded here.
     */
    public BackStepper() {
        engaged = true;
        backSteps = new BackstepStack(Globals.maximumBacksteps);
    }

    public BackstepStack getBackStepsStack() {
        return backSteps;
    }

    /**
     * Determine whether execution "undo" steps are currently being recorded.
     *
     * @return true if undo steps being recorded, false if not.
     */
    public boolean enabled() {
        return engaged;
    }

    /**
     * Set enable status.
     *
     * @param state If true, will begin (or continue) recoding "undo" steps. If
     *              false, will stop.
     */
    public void setEnabled(boolean state) {
        engaged = state;
    }

    /**
     * Test whether there are steps that can be undone.
     *
     * @return true if there are no steps to be undone, false otherwise.
     */
    public boolean empty() {
        return backSteps.empty();
    }

    /**
     * Determine whether the next back-step action occurred as the result of
     * an instruction that executed in the "delay slot" of a delayed branch.
     *
     * @return true if next backstep is instruction that executed in delay slot,
     * false otherwise.
     */
    // Added 25 June 2007
    public boolean inDelaySlot() {
        return !empty() && backSteps.peek().inDelaySlot;
    }

    /**
     * Carry out a "back step", which will undo the latest execution step.
     * Does nothing if backstepping not enabled or if there are no steps to undo.
     */

    // Note that there may be more than one "step" in an instruction execution; for
    // instance the multiply, divide, and double-precision floating point operations
    // all store their result in register pairs which results in two store
    // operations.
    // Both must be undone transparently, so we need to detect that multiple steps
    // happen
    // together and carry out all of them here.
    // Use a do-while loop based on the dynamic instruction identity.
    public void backStep() {
        if (engaged && !backSteps.empty()) {
            BackStep first = (BackStep) backSteps.peek();
            engaged = false; // GOTTA DO THIS SO METHOD CALL IN SWITCH WILL NOT RESULT IN NEW ACTION ON
            // STACK!
            do {
                BackStep step = (BackStep) backSteps.pop();

                if (step.pc != NOT_PC_VALUE) {
                    RegisterFile.setProgramCounter(step.pc);
                }
                try {
                    if (step.action == POKE) {
                        // Newest write first, so that a value the poke wrote twice comes back to
                        // what it held before the first of those writes.
                        for (int i = step.pokeWrites.length - 1; i >= 0; i--) {
                            int[] write = step.pokeWrites[i];
                            applyRestore(write[0], write[1], write[2], 0);
                        }
                    } else {
                        applyRestore(step.action, step.param1, step.param2, step.param3);
                    }
                } catch (Exception e) {
                    String message = "Internal MARS error: address exception while back-stepping.";
                    // if the original action did not cause an exception this will not either.
                    System.out.println(message);
                    throw new RuntimeException(message);
                }
            } while (!backSteps.empty() && sameGroup(first, (BackStep) backSteps.peek()));
            if (first.pc != NOT_PC_VALUE) instructionsExecuted--;
            app.specy.mars.assembler.MemoryLayoutFacts.previousSp = RegisterFile.getValue(29);
            engaged = true; // RESET IT (was disabled at top of loop -- see comment)
        }
    }

    /** Carries out one recorded restore. One back step holds one; a poke entry holds its writes. */
    private static void applyRestore(int action, int param1, int param2, int param3) throws AddressErrorException {
        switch (action) {
            case HEAP_RESTORE:
                app.specy.mars.mips.hardware.Memory.heapAddress = param1; break;
            case STACK_TOP_RESTORE:
                app.specy.mars.assembler.MemoryLayoutFacts.stackTop = param1; break;
            case MEMORY_RESTORE_RAW_WORD:
                Globals.memory.setRawWord(param1, param2);
                break;
            case MEMORY_RESTORE_WORD:
                Globals.memory.setWord(param1, param2);
                break;
            case MEMORY_RESTORE_HALF:
                Globals.memory.setHalf(param1, param2);
                break;
            case MEMORY_RESTORE_BYTE:
                Globals.memory.setByte(param1, param2);
                break;
            case REGISTER_RESTORE:
                RegisterFile.updateRegister(param1, param2);
                break;
            case PC_RESTORE:
                Stack.popUntilIncluding(param1);
                RegisterFile.setProgramCounter(param1);
                break;
            case COPROC0_REGISTER_RESTORE:
                Coprocessor0.updateRegister(param1, param2);
                break;
            case COPROC1_REGISTER_RESTORE:
                Coprocessor1.updateRegister(param1, param2);
                break;
            case COPROC1_CONDITION_CLEAR:
                Coprocessor1.clearConditionFlag(param1);
                break;
            case COPROC1_CONDITION_SET:
                Coprocessor1.setConditionFlag(param1);
                break;
            case DO_NOTHING:
                break;
            case EXIT_RESTORE:
                ProgramExit.restore(param1 != 0, param2);
                break;
            case RANDOM_STREAM_RESTORE:
                RandomStreams.restore(param1, param2, param3);
                break;
        }
    }

    /** Two restores belong together exactly when they share a dynamic identity. */
    public static boolean sameGroup(BackStep one, BackStep other) {
        return one.serial == other.serial;
    }

    /**
     * Open a poke transaction: every restore recorded until {@link #endPoke()} is collected rather
     * than pushed, and endPoke() pushes the lot as one back step, which one backStep() undoes as a
     * unit and which costs one slot of the history. No instruction may execute while it is open.
     *
     * @return the group key given to this transaction.
     * @throws IllegalStateException if a poke is already open.
     */
    public int beginPoke() {
        if (pokeGroup != NO_POKE_GROUP) {
            throw new IllegalStateException("A poke is already open");
        }
        if (nextPokeGroup >= NOT_PC_VALUE) { // wrapped past the negative side; start over
            nextPokeGroup = -2;
        }
        pokeGroup = nextPokeGroup--;
        pokeSerial = allocateSerial();
        if (!engaged) clearHistory();
        pokeWrites = new ArrayList<int[]>();
        return pokeGroup;
    }

    /**
     * Close the open poke transaction.
     *
     * @return true if it pushed the one back step the poke became; false if it had nothing to
     * record, either because nothing was written or because recording is disabled, in which case
     * the writes stand but cannot be undone.
     * @throws IllegalStateException if no poke is open.
     */
    public boolean endPoke() {
        if (pokeGroup == NO_POKE_GROUP) {
            throw new IllegalStateException("No poke is open");
        }
        int group = pokeGroup;
        List<int[]> writes = pokeWrites;
        // Cleared before the push, so that the push itself goes on the stack rather than back into
        // the transaction it is closing.
        pokeGroup = NO_POKE_GROUP;
        pokeWrites = null;
        if (writes.isEmpty() || backSteps.capacity == 0) {
            return false;
        }
        backSteps.pushPoke(group, writes.toArray(new int[writes.size()][]));
        return true;
    }

    /**
     * Whether a poke transaction is open, so that the setters journal into it rather than writing
     * straight through.
     */
    public boolean pokeOpen() {
        return pokeGroup != NO_POKE_GROUP;
    }

    /*
     * Convenience method called below to get program counter value. If it needs to
     * be
     * be modified (e.g. to subtract 4) that can be done here in one place.
     */

    private int pc() {
        // PC incremented prior to instruction simulation, so need to adjust for that.
        return RegisterFile.getProgramCounter() - Instruction.INSTRUCTION_LENGTH;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a raw memory word value (setRawWord).
     *
     * @param address  The affected memory address.
     * @param value    The "restore" value to be stored there.
     * @param newValue The whole word the write left at that address.
     * @return the argument value
     */
    public int addMemoryRestoreRawWord(int address, int value, int newValue) {
        backSteps.push(MEMORY_RESTORE_RAW_WORD, pc(), address, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a memory word value.
     *
     * @param address  The affected memory address.
     * @param value    The "restore" value to be stored there.
     * @param newValue The whole word the write left at that address.
     * @return the argument value
     */
    public int addMemoryRestoreWord(int address, int value, int newValue) {
        backSteps.push(MEMORY_RESTORE_WORD, pc(), address, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a memory half-word value.
     *
     * @param address  The affected memory address.
     * @param value    The "restore" value to be stored there, in low order half.
     * @param newValue The half the write left at that address, in the low order half, so that both
     *                 values are read at the width of the write.
     * @return the argument value
     */
    public int addMemoryRestoreHalf(int address, int value, int newValue) {
        backSteps.push(MEMORY_RESTORE_HALF, pc(), address, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a memory byte value.
     *
     * @param address  The affected memory address.
     * @param value    The "restore" value to be stored there, in low order byte.
     * @param newValue The byte the write left at that address, in the low order byte, so that both
     *                 values are read at the width of the write.
     * @return the argument value
     */
    public int addMemoryRestoreByte(int address, int value, int newValue) {
        backSteps.push(MEMORY_RESTORE_BYTE, pc(), address, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a register file register value.
     *
     * @param register The affected register number.
     * @param value    The "restore" value to be stored there.
     * @param newValue The whole value the write left in that register.
     * @return the argument value
     */
    public int addRegisterFileRestore(int register, int value, int newValue) {
        backSteps.push(REGISTER_RESTORE, pc(), register, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore the program counter.
     *
     * @param value    The "restore" value to be stored there.
     * @param newValue The address the instruction set the program counter to, as it set it: unlike
     *                 the restore value it is not adjusted back onto the instruction that ran,
     *                 because it is the target, not a place to resume from.
     * @return the argument value
     */
    public int addPCRestore(int value, int newValue) {
        // adjust for value reflecting incremented PC.
        value -= Instruction.INSTRUCTION_LENGTH;
        // Use "value" insead of "pc()" for second arg because
        // RegisterFile.getProgramCounter()
        // returns branch target address at this point.
        backSteps.push(PC_RESTORE, value, value, 0, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a coprocessor 0 register value.
     *
     * @param register The affected register number.
     * @param value    The "restore" value to be stored there.
     * @param newValue The whole value the write left in that register.
     * @return the argument value
     */
    public int addCoprocessor0Restore(int register, int value, int newValue) {
        backSteps.push(COPROC0_REGISTER_RESTORE, pc(), register, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a coprocessor 1 register value.
     *
     * @param register The affected register number.
     * @param value    The "restore" value to be stored there.
     * @param newValue The whole value the write left in that register.
     * @return the argument value
     */
    public int addCoprocessor1Restore(int register, int value, int newValue) {
        backSteps.push(COPROC1_REGISTER_RESTORE, pc(), register, value, newValue);
        return value;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to set the given coprocessor 1 condition flag (to 1).
     *
     * @param flag The condition flag number.
     * @return the argument value
     */
    public int addConditionFlagSet(int flag) {
        backSteps.push(COPROC1_CONDITION_SET, pc(), flag);
        return flag;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to clear the given coprocessor 1 condition flag (to 0).
     *
     * @param flag The condition flag number.
     * @return the argument value
     */
    public int addConditionFlagClear(int flag) {
        backSteps.push(COPROC1_CONDITION_CLEAR, pc(), flag);
        return flag;
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to do nothing! This is just a place holder so when user is backstepping
     * through the program no instructions will be skipped. Cosmetic. If the top of
     * the
     * stack has the same dynamic serial, the do-nothing action will not be added.
     *
     * @return 0
     */
    /**
     * Add a new "back step" (the undo action) to the stack. The action here is to undo an exit:
     * the program had exited ({@code wasExited}) or not, and had exit code {@code previousCode},
     * before the exit service set {@code code}.
     */
    public void addExitRestore(boolean wasExited, int previousCode, int code) {
        backSteps.push(EXIT_RESTORE, pc(), wasExited ? 1 : 0, previousCode, code);
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here is to put random
     * generator {@code index} back in the state a service is about to change: {@code high} and
     * {@code low} are its two halves, or {@code high} is RandomStreams.ABSENT when the generator
     * does not exist yet.
     */
    public void addHeapRestore(int old) { backSteps.push(HEAP_RESTORE, pc(), old); }
    public void addStackTopRestore(int old) { backSteps.push(STACK_TOP_RESTORE, pc(), old); }

    public void addRandomStreamRestore(int index, int high, int low) {
        backSteps.push(RANDOM_STREAM_RESTORE, pc(), index, high, low);
    }

    public int addDoNothing(int pc) {
        if (backSteps.empty() || backSteps.peek().serial != instructionSerial) {
            backSteps.push(DO_NOTHING, pc);
        }
        return 0;
    }

    // Represents a "back step" (undo action) on the stack.
    public class BackStep {
        private long serial;
        private int action; // what do do MEMORY_RESTORE_WORD, etc
        private int pc; // program counter value when original step occurred
        private int param1; // first parameter required by that action
        private int param2; // optional second parameter required by that action
        // The value the write put there, beside the param2 it replaced: the whole register for a
        // register write, the bytes left at the address for a memory write at the width it was
        // made, the address the instruction set for a PC restore, the code an exit set. Captured
        // by the setter that made the write, never reconstructed afterwards. Zero for the actions
        // that restore no value: the condition flag set and clear, DO_NOTHING, and a poke entry,
        // whose own writes carry both sides already. A random generator's restore keeps the low
        // half of its state here, which getParam3() does not report.
        private int param3;
        private boolean inDelaySlot; // true if instruction executed in "delay slot" (delayed branching enabled)
        private int pokeGroup; // the poke transaction this step is, or 0 for an instruction's step
        // A poke's restores, oldest first, each an {action, param1, param2} triple; null otherwise.
        // They live in the entry rather than in a slot each, so that a poke of a hundred bytes is
        // still one slot of the history and is never evicted in part.
        private int[][] pokeWrites;


        /**
         * Whether this step is a whole poke - every value the host wrote in one transaction -
         * rather than one effect of an instruction.
         */
        public boolean isPoke() {
            return pokeGroup != NO_POKE_GROUP;
        }

        /**
         * The key of the poke this step is, or 0 for an instruction's step. Two consecutive pokes
         * have different keys, and no key is ever an instruction address.
         */
        public int getPokeGroup() {
            return pokeGroup;
        }

        public long getSerial() {
            return serial;
        }

        public int getAction() {
            return action;
        }

        public int getPc() {
            return pc;
        }

        public int getParam1() {
            return param1;
        }

        public int getParam2() {
            // A generator's state is the simulator's own, not a value the program can see.
            return action == RANDOM_STREAM_RESTORE ? 0 : param2;
        }

        /**
         * The value this write left behind, as the simulator saw it at the moment of the write:
         * the whole register for a register restore, the bytes at the address for a memory
         * restore, at the width the write was made, and the address the instruction set for a PC
         * restore. 0 for an action that restores no value.
         */
        public int getParam3() {
            return action == RANDOM_STREAM_RESTORE ? 0 : param3;
        }

        // Recycled entries take the current dynamic identity and the PC captured at its start.
        private void assign(int act, int programCounter, int parm1, int parm2, int parm3) {
            action = act;
            serial = instructionSerial != 0 ? instructionSerial : allocateSerial();
            programCounter = instructionSerial != 0 ? instructionPc : programCounter;
            pc = programCounter;
            // Stack entries are recycled, so never inherit the last poke that used this slot.
            pokeGroup = NO_POKE_GROUP;
            pokeWrites = null;
            param1 = parm1;
            param2 = parm2;
            param3 = parm3;
            inDelaySlot = Simulator.inDelaySlot(); // ADDED 25 June 2007

        }

        // A poke belongs to no instruction: it keeps NOT_PC_VALUE so that undoing it leaves the
        // program counter alone, and carries its transaction key instead, which tells it apart
        // from the poke before it. Its writes travel with it, which is what makes the whole
        // transaction one slot of the stack.
        private void assignPoke(int group, int[][] writes) {
            action = POKE;
            pc = NOT_PC_VALUE;
            serial = pokeSerial;
            pokeGroup = group;
            pokeWrites = writes;
            param1 = 0;
            param2 = 0;
            param3 = 0;
            inDelaySlot = false;
        }
    }

    // *****************************************************************************
    // special purpose stack class for backstepping. You've heard of circular queues
    // implemented with an array, right? This is a circular stack! When full, the
    // newly-pushed item overwrites the oldest item, with circular top! All
    // operations
    // are constant time. Upstream synchronized it too, to be safe (it was used by both the
    // simulation thread and the GUI thread for the back-step button).
    // Upon construction, it is filled with newly-created empty BackStep objects
    // which
    // will exist for the life of the stack. Push does not create a BackStep object
    // but instead overwrites the contents of the existing one. Thus during MIPS
    // program (simulated) execution, BackStep objects are never created or junked
    // regardless of how many steps are executed. This will speed things up a bit
    // and make life easier for the garbage collector.

    public class BackstepStack {
        private int capacity;
        private int size;
        private int top;
        private BackStep[] stack;

        // Stack is created upon successful assembly or reset. The one-time overhead of
        // creating all the BackStep objects will not be noticed by the user, and
        // enhances
        // runtime performance by not having to create or recycle them during MIPS
        // program execution.
        // The slots start empty: a slot's BackStep is created the first time the stack reaches it,
        // so the capacity can be large enough for a library call without a short program paying for
        // it, and once the stack has wrapped execution never creates or junks one.
        private BackstepStack(int capacity) {
            this.capacity = capacity;
            this.size = 0;
            this.top = -1;
            this.stack = new BackStep[capacity];
        }

        // The object in the slot the top has just moved onto, created the first time it is used.
        private BackStep slot() {
            BackStep step = stack[top];
            if (step == null) stack[top] = step = new BackStep();
            return step;
        }

        /** How many entries the stack holds. */
        public int size() {
            return size;
        }

        /** The entry {@code index} places below the top, without copying the stack; 0 is the top. */
        public BackStep fromTop(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException("No back step " + index);
            int slot = top - index;
            return stack[slot < 0 ? slot + capacity : slot];
        }

        public BackStep[] getStack() {
            //get only the used part of the stack
            BackStep[] usedStack = new BackStep[size];
            for (int i = 0; i < size; i++) {
                usedStack[i] = stack[(top - i + capacity) % capacity];
            }
            return usedStack;
        }

        private boolean empty() {
            return size == 0;
        }

        // Make room by evicting the oldest complete group, never half an instruction.
        // If this instruction alone exceeded capacity, forget it and the rest of its writes.
        private boolean advance() {
            if (capacity == 0 || instructionDiscarded && instructionSerial != 0) return false;
            if (size == capacity) {
                long oldest = fromTop(size - 1).serial;
                do { size--; } while (size > 0 && fromTop(size - 1).serial == oldest);
                if (oldest == instructionSerial && instructionSerial != 0) {
                    instructionDiscarded = true;
                    return false;
                }
            }
            int next = top + 1;
            top = next == capacity ? 0 : next;
            size++;
            return true;
        }

        private void push(int act, int programCounter, int parm1, int parm2, int parm3) {
            // While a poke is open no instruction is running, so every recorded restore is one of
            // its writes: it is collected rather than pushed, and the whole transaction is pushed
            // as one entry by endPoke(), whichever setter each write came through. A poke's entry
            // reports what it wrote through its own writes, read at endPoke(), so the value the
            // write left is not carried here.
            if (pokeGroup != NO_POKE_GROUP) {
                pokeWrites.add(new int[] { act, parm1, parm2 });
                return;
            }
            if (!advance()) return;
            // We'll re-use existing objects rather than create/discard each time.
            // Must use assign() method rather than series of assignment statements!
            slot().assign(act, programCounter, parm1, parm2, parm3);
        }

        private void push(int act, int programCounter, int parm1, int parm2) {
            push(act, programCounter, parm1, parm2, 0);
        }

        // The one entry a finished poke becomes. It is pushed by endPoke(), after the transaction
        // has been closed, so it takes the ordinary slot an instruction's step would.
        private void pushPoke(int group, int[][] writes) {
            if (!advance()) return;
            slot().assignPoke(group, writes);
        }

        private void push(int act, int programCounter, int parm1) {
            push(act, programCounter, parm1, 0);
        }

        private void push(int act, int programCounter) {
            push(act, programCounter, 0, 0);
        }

        // NO PROTECTION. This class is used only within this file so there is no excuse
        // for trying to pop from empty stack.
        private BackStep pop() {
            BackStep bs;
            bs = stack[top];
            if (size == 1) {
                top = -1;
            } else {
                top = (top + capacity - 1) % capacity;
            }
            size--;
            return bs;
        }

        // NO PROTECTION. This class is used only within this file so there is no excuse
        // for trying to peek from empty stack.
        private BackStep peek() {
            return stack[top];
        }

    }

}