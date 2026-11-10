package app.specy.mars.mips.hardware;

import app.specy.mars.Globals;
import app.specy.mars.mips.instructions.Instruction;
import app.specy.mars.simulator.BackStepper;
import app.specy.mars.simulator.Simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The call stack a debugger shows, and what a run's {@code untilCallDepth} reads.
 * <p>
 * A frame is pushed by a call ({@code jal}; {@code jalr} unless it links into {@code $zero}; a
 * taken {@code bal}, {@code bgezal} or {@code bltzal}) and by an exception or interrupt that enters
 * the program's handler. A frame is gone when:
 * <ol>
 * <li>{@code jr $ra} runs: every frame down to the innermost call frame whose return address is
 * where it jumps to, or only the innermost call frame when none is. It never pops a handler frame
 * ({@link #returnThrough});</li>
 * <li>after any other instruction, the program counter is the innermost frame's return address with
 * {@code $sp}, unsigned, at or above where it was at the call: that ends a call returned by
 * {@code jr $t0} or a jump, and a {@code bal} to the next instruction at once ({@link #arrive});</li>
 * <li>{@code eret} runs: every frame down to the newest handler frame ({@link #returnFromHandler}).</li>
 * </ol>
 * <p>
 * The newest {@link #LIMIT} frames are kept, in a ring, and the depth goes on counting past them: a
 * {@code jal} used as a jump in a loop adds a frame on every pass and moves no stack, so nothing
 * else bounds it. A frame past the limit is gone for good; a return that would pop one only takes
 * the depth down.
 * <p>
 * Compiled C calls as often as every few instructions, so the frames are kept and reused, each
 * with its own copy of the registers, rather than made for each call: a new frame and its new
 * register array were the largest allocation the simulator made while a program ran. A host gets
 * copies, which later calls cannot change.
 * <p>
 * Every push and pop is part of the Undo history: undoing a call takes its frame off again, and puts
 * back the frame the limit pushed out for it, and undoing a return puts back the frame it popped,
 * which the entry keeps until then ({@link BackStepper#addReturnRestore}). The frame object the
 * call stack's slot takes instead is one the history no longer needs, so calls and returns still
 * allocate no frames once the history is full. The frames are tracked whether or not the history
 * records.
 */
public class Stack {

    // No static field has an initializer, so that the class has nothing to initialize and the run
    // loop, which reads two of them after every instruction, is not made to check that it has.

    /** The most frames held: the newest ones. A power of two, so that a slot is a mask away. */
    public static final int LIMIT = 131072;
    private static final int MASK = LIMIT - 1;
    /** {@link #returnWatch} with no frame held. Any value would do: {@link #arrive} checks again. */
    private static final int NO_WATCH = 0;
    // marks an empty slot of the return-address table: a return address is a word address, never 1
    private static final int NO_KEY = 1;

    /**
     * A ring: frame {@code i}, counted from the oldest of the whole depth, is in slot
     * {@code i & MASK}. Null until the first call.
     */
    private static StackFrame[] frames;
    /** Every frame, those past the limit included. */
    private static int depth;
    /** The newest frames, which {@link #frames} holds: at most {@link #LIMIT}. */
    private static int held;
    /**
     * The innermost frame's return address, or {@link #NO_WATCH} with none held: the simulator
     * compares the program counter with it after every instruction, and calls {@link #arrive} when
     * they are equal. NO_WATCH, the default, to begin with.
     */
    public static int returnWatch;
    /**
     * One more than the instruction ({@link Simulator#getInstructionsRun()} while it ran) whose
     * {@code jr $ra} or {@code eret} last popped frames, 0 before any did: rule 2 does not pop again
     * after one, which in a recursion that keeps {@code $sp} where it was would take the caller's
     * frame too.
     */
    private static double returnedAt;
    // How many handler frames are held, and how many held call frames return to each address: an
    // open-addressing table of int keys, which allocates nothing as calls come and go, made on the
    // first call. A key stays once used, at a count of 0; a return address is the address after a
    // call, so the table never holds more keys than the program has calls. With them a return that
    // no held frame matches pops the innermost frame without looking through the others, and an
    // `eret` with no handler frame held pops nothing without looking either.
    private static int handlersHeld;
    private static int[] returnKeys;
    private static int[] returnCounts;
    private static int returnKeysUsed;

    /** The number of frames, those past the limit included. */
    public static int getCallDepth() {
        return depth;
    }

    /**
     * Pushes a frame for a call to {@code toAddress} made now, returning to {@code returnAddress}:
     * the program counter, the stack and frame pointers and every register as the calling
     * instruction has left them so far.
     */
    public static void pushCall(int toAddress, int returnAddress) {
        // the program counter has already moved past the calling instruction
        int next = RegisterFile.getProgramCounter();
        push(StackFrame.KIND_CALL, next, next - Instruction.INSTRUCTION_LENGTH, toAddress, returnAddress);
    }

    /**
     * Pushes a frame for the program's handler at {@code handler}, entered now by an exception or an
     * interrupt ({@code kind}) at the instruction {@code callSite}, that returns to {@code epc}.
     */
    public static void pushHandler(int kind, int callSite, int handler, int epc) {
        push(kind, epc, callSite, handler, epc);
    }

    private static void push(int kind, int pc, int callSite, int toAddress, int returnAddress) {
        int slot = depth & MASK;
        boolean recording = Globals.getSettingsProperties().getBackSteppingEnabled();
        StackFrame frame;
        if (held == LIMIT) {
            // the oldest frame held gives its slot up; the history keeps it to put back
            StackFrame evicted = frames[slot];
            releases(evicted);
            frame = recording ? backStepper().addCallRestore(toAddress, evicted) : evicted;
            if (frame == null) frame = new StackFrame();
            frames[slot] = frame;
        } else {
            if (frames == null || slot >= frames.length) grow();
            frame = frames[slot];
            if (frame == null) frames[slot] = frame = new StackFrame();
            held++;
            if (recording) backStepper().addCallRestore(toAddress, null);
        }
        frame.capture(pc, callSite, toAddress, returnAddress, kind);
        holds(frame);
        depth++;
        returnWatch = returnAddress;
    }

    /**
     * {@code jr $ra} jumping to {@code target}: pops every frame down to the innermost call frame
     * returning there, or only the innermost call frame when none does, and never a handler frame.
     */
    public static void returnThrough(int target) {
        if (depth == 0) return;
        int pops = 1;
        if (held > 0) {
            StackFrame innermost = frames[(depth - 1) & MASK];
            if (innermost.getKind() != StackFrame.KIND_CALL) return;
            // nearly every return is the innermost frame's; an outer frame is looked for only when
            // one returns there, so a return that matches none costs no walk through all of them
            if (innermost.getReturnAddress() == target || callsReturningTo(target) == 0) {
                pop(1);
                return;
            }
            for (int i = depth - 2; i >= depth - held; i--) {
                StackFrame frame = frames[i & MASK];
                if (frame.getKind() != StackFrame.KIND_CALL) break;
                if (frame.getReturnAddress() == target) {
                    pops = depth - i;
                    break;
                }
            }
        }
        pop(pops);
    }

    /** {@code eret}: pops every frame down to the newest handler frame, or none when there is none. */
    public static void returnFromHandler() {
        if (handlersHeld == 0) return;
        for (int i = depth - 1; i >= depth - held; i--) {
            if (frames[i & MASK].getKind() != StackFrame.KIND_CALL) {
                pop(depth - i);
                return;
            }
        }
    }

    /**
     * After an instruction left the program counter at {@code pc}, equal to {@link #returnWatch}:
     * the innermost frame is gone when {@code sp}, unsigned, is at or above where it was at the
     * call, unless a {@code jr $ra} or {@code eret} of this same instruction popped already.
     */
    public static void arrive(int pc, int sp) {
        if (held == 0 || returnedAt == Simulator.getInstance().getInstructionsRun() + 1) return;
        StackFrame top = frames[(depth - 1) & MASK];
        if (top.getReturnAddress() != pc) return;
        if ((sp ^ Integer.MIN_VALUE) < (top.getSP() ^ Integer.MIN_VALUE)) return;
        popOne(Globals.getSettingsProperties().getBackSteppingEnabled());
        watchTop();
    }

    private static void pop(int count) {
        boolean recording = Globals.getSettingsProperties().getBackSteppingEnabled();
        for (int i = 0; i < count; i++) popOne(recording);
        returnedAt = Simulator.getInstance().getInstructionsRun() + 1;
        watchTop();
    }

    private static void popOne(boolean recording) {
        depth--;
        if (held == 0) {
            // a frame past the limit: there is nothing to keep, only the depth to put back
            if (recording) backStepper().addReturnRestore(null);
            return;
        }
        held--;
        releases(frames[depth & MASK]);
        if (recording) {
            int slot = depth & MASK;
            // the history keeps the popped frame to put back, and hands over one it no longer needs
            frames[slot] = backStepper().addReturnRestore(frames[slot]);
        }
    }

    /** Undoes a call that took a free slot: the frame comes off again, its object kept for the next call. */
    public static void undoCall() {
        if (depth > 0) depth--;
        if (held > 0) {
            held--;
            releases(frames[depth & MASK]);
        }
        watchTop();
    }

    /**
     * Undoes a call that pushed the oldest frame held out: {@code evicted} goes back in its slot.
     *
     * @return the frame object the call had filled, which nothing else holds, for the history to
     * hand out again
     */
    public static StackFrame undoEvictingCall(StackFrame evicted) {
        depth--;
        int slot = depth & MASK;
        StackFrame spare = frames[slot];
        releases(spare);
        frames[slot] = evicted;
        holds(evicted);
        watchTop();
        return spare;
    }

    /**
     * Undoes a return: puts back {@code frame}, the one the return popped, or only counts it again
     * when it was past the limit ({@code frame} null).
     *
     * @return the frame object the slot held meanwhile, which nothing else holds, for the history
     * to hand out again; or null
     */
    public static StackFrame undoReturn(StackFrame frame) {
        if (frame == null) {
            depth++;
            watchTop();
            return null;
        }
        int slot = depth & MASK;
        if (frames == null || slot >= frames.length) grow();
        StackFrame spare = frames[slot];
        frames[slot] = frame;
        holds(frame);
        depth++;
        held++;
        watchTop();
        return spare;
    }

    private static void watchTop() {
        returnWatch = held > 0 ? frames[(depth - 1) & MASK].getReturnAddress() : NO_WATCH;
    }

    private static BackStepper backStepper() {
        return Globals.program.getBackStepper();
    }

    /** {@code frame} is held now: it is counted. */
    private static void holds(StackFrame frame) {
        if (frame.getKind() == StackFrame.KIND_CALL) count(frame.getReturnAddress(), 1);
        else handlersHeld++;
    }

    /** {@code frame} is no longer held: it is no longer counted. */
    private static void releases(StackFrame frame) {
        if (frame.getKind() == StackFrame.KIND_CALL) count(frame.getReturnAddress(), -1);
        else handlersHeld--;
    }

    private static int returnSlot(int[] keys, int address) {
        int hash = address * 0x9E3779B9;
        return (hash ^ (hash >>> 16)) & (keys.length - 1);
    }

    /** How many held call frames return to {@code address}. */
    private static int callsReturningTo(int address) {
        if (returnKeys == null) return 0;
        int mask = returnKeys.length - 1;
        for (int at = returnSlot(returnKeys, address); ; at = (at + 1) & mask) {
            int key = returnKeys[at];
            if (key == address) return returnCounts[at];
            if (key == NO_KEY) return 0;
        }
    }

    /** Adds {@code delta} to the count of held call frames that return to {@code address}. */
    private static void count(int address, int delta) {
        if (returnKeys == null) {
            returnKeys = emptyKeys(64);
            returnCounts = new int[64];
        }
        int mask = returnKeys.length - 1;
        for (int at = returnSlot(returnKeys, address); ; at = (at + 1) & mask) {
            int key = returnKeys[at];
            if (key == address) {
                returnCounts[at] += delta;
                return;
            }
            if (key == NO_KEY) {
                if ((returnKeysUsed + 1) * 2 > returnKeys.length) {
                    growReturnCounts();
                    count(address, delta);
                    return;
                }
                returnKeys[at] = address;
                returnCounts[at] = delta;
                returnKeysUsed++;
                return;
            }
        }
    }

    private static int[] emptyKeys(int size) {
        int[] keys = new int[size];
        Arrays.fill(keys, NO_KEY);
        return keys;
    }

    private static void growReturnCounts() {
        int[] keys = returnKeys;
        int[] counts = returnCounts;
        returnKeys = emptyKeys(keys.length * 2);
        returnCounts = new int[keys.length * 2];
        int mask = returnKeys.length - 1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == NO_KEY) continue;
            int at = returnSlot(returnKeys, keys[i]);
            while (returnKeys[at] != NO_KEY) at = (at + 1) & mask;
            returnKeys[at] = keys[i];
            returnCounts[at] = counts[i];
        }
    }

    // Until the limit is first reached the depth is the frames held, all in slots below the array's
    // length, so growing keeps every slot where it was.
    private static void grow() {
        int length = frames == null ? 0 : frames.length;
        StackFrame[] grown = new StackFrame[Math.min(LIMIT, Math.max(16, length * 2))];
        if (length > 0) System.arraycopy(frames, 0, grown, 0, length);
        frames = grown;
    }

    public static void clearCallStack() {
        depth = 0;
        held = 0;
        returnedAt = 0;
        handlersHeld = 0;
        // a program that called from many places leaves nothing large behind it
        if (returnKeys != null && returnKeys.length > 1024) {
            returnKeys = null;
            returnCounts = null;
        } else if (returnKeys != null) {
            Arrays.fill(returnKeys, NO_KEY);
            Arrays.fill(returnCounts, 0);
        }
        returnKeysUsed = 0;
        watchTop();
    }

    /** Copies of the frames held, oldest first. */
    public static List<StackFrame> getCallStack() {
        return getCallStack(-1);
    }

    /** Copies of the newest {@code max} frames held, or all of them when it is negative, oldest first. */
    public static List<StackFrame> getCallStack(int max) {
        int count = max < 0 || max > held ? held : max;
        List<StackFrame> copies = new ArrayList<>(count);
        for (int i = depth - count; i < depth; i++) copies.add(frames[i & MASK].copy());
        return copies;
    }
}
