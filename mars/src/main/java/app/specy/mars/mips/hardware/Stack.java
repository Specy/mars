package app.specy.mars.mips.hardware;

import app.specy.mars.Globals;

import java.util.ArrayList;
import java.util.List;

/**
 * The call stack a debugger shows: a frame is pushed by every call ({@code jal}, {@code jalr},
 * {@code bal}, {@code bgezal}, {@code bltzal}) and popped by a {@code jr $ra}.
 * <p>
 * Compiled C calls as often as every few instructions, so the frames are kept and reused, each
 * with its own copy of the registers, rather than made for each call: a new frame and its new
 * register array were the largest allocation the simulator made while a program ran. A host gets
 * copies, which later calls cannot change.
 * <p>
 * Both are part of the Undo history: undoing a call takes its frame off again, and undoing a return
 * puts back the frame it popped, which the return's entry keeps until then
 * ({@link app.specy.mars.simulator.BackStepper#addReturnRestore}). The frame object the call stack's
 * slot takes instead is one the history no longer needs, so calls and returns still allocate no
 * frames once the history is full.
 */
public class Stack {

    private static StackFrame[] frames = new StackFrame[0];
    private static int depth;

    /**
     * Pushes a frame for a call to {@code toAddress} made now: the program counter, the stack and
     * frame pointers and every register as the calling instruction has left them so far.
     */
    public static void pushCallStack(int toAddress) {
        if (depth == frames.length) grow();
        StackFrame frame = frames[depth];
        if (frame == null) frames[depth] = frame = new StackFrame();
        frame.capture(toAddress);
        depth++;
        if (Globals.getSettingsProperties().getBackSteppingEnabled()) {
            Globals.program.getBackStepper().addCallRestore(toAddress);
        }
    }

    /** Pops the newest frame, if there is one. */
    public static void popCallStack() {
        if (depth == 0) return;
        depth--;
        if (Globals.getSettingsProperties().getBackSteppingEnabled()) {
            // the history keeps the popped frame to put back, and hands over one it no longer needs
            frames[depth] = Globals.program.getBackStepper().addReturnRestore(frames[depth]);
        }
    }

    /** Undoes a call: the frame it pushed comes off again, its object kept for the next call. */
    public static void undoCall() {
        if (depth > 0) depth--;
    }

    /**
     * Undoes a return: puts back {@code frame}, the one the return popped.
     *
     * @return the frame object the slot held meanwhile, which nothing else holds, for the history
     * to hand out again; or null
     */
    public static StackFrame undoReturn(StackFrame frame) {
        if (depth == frames.length) grow();
        StackFrame spare = frames[depth];
        frames[depth++] = frame;
        return spare;
    }

    private static void grow() {
        StackFrame[] grown = new StackFrame[Math.max(16, frames.length * 2)];
        System.arraycopy(frames, 0, grown, 0, frames.length);
        frames = grown;
    }

    public static void clearCallStack() {
        depth = 0;
    }

    /** Copies of the frames, oldest first. */
    public static List<StackFrame> getCallStack() {
        List<StackFrame> copies = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) copies.add(frames[i].copy());
        return copies;
    }
}
