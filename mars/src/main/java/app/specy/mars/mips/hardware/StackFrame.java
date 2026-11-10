package app.specy.mars.mips.hardware;


/** One frame of the call stack: see {@link Stack}, which reuses frames from call to call. */
public class StackFrame {
    /** A frame pushed by a call: {@code jal}, {@code jalr}, a taken {@code bal}, {@code bgezal}, {@code bltzal}. */
    public static final int KIND_CALL = 0;
    /** A frame pushed when an exception enters the program's handler. */
    public static final int KIND_EXCEPTION = 1;
    /** A frame pushed when an interrupt enters the program's handler. */
    public static final int KIND_INTERRUPT = 2;

    private int pc;
    private int callSite;
    private int toAddress;
    private int returnAddress;
    private int sp;
    private int fp;
    private int kind;
    private int[] registers;

    public StackFrame(
            int pc,
            int callSite,
            int sp,
            int fp,
            int toAddress,
            int returnAddress,
            int kind,
            int[] registers
    ) {
        this.pc = pc;
        this.callSite = callSite;
        this.sp = sp;
        this.fp = fp;
        this.toAddress = toAddress;
        this.returnAddress = returnAddress;
        this.kind = kind;
        this.registers = registers;
    }

    StackFrame() {
    }

    /**
     * Fills this frame with the state of a call or handler entry made now, into the register array
     * it already has. No register is read as the program reads it, so no observer hears of it.
     *
     * @param pc            the program counter the frame records: for a call the program counter
     *                      as the calling instruction has left it so far, the address after it;
     *                      for a handler EPC, where it returns
     * @param callSite      the instruction that made the entry: the call, the instruction that
     *                      raised the exception, or the one the interrupt came before
     * @param toAddress     the routine or handler entered
     * @param returnAddress where the call or handler returns to
     * @param kind          {@link #KIND_CALL}, {@link #KIND_EXCEPTION} or {@link #KIND_INTERRUPT}
     */
    void capture(int pc, int callSite, int toAddress, int returnAddress, int kind) {
        this.pc = pc;
        this.callSite = callSite;
        sp = RegisterFile.getStackPointerRegister().getValueNoNotify();
        fp = RegisterFile.getFramePointerRegister().getValueNoNotify();
        this.toAddress = toAddress;
        this.returnAddress = returnAddress;
        this.kind = kind;
        Register[] file = RegisterFile.getRegisters();
        if (registers == null || registers.length != file.length) registers = new int[file.length];
        for (int i = 0; i < file.length; i++) registers[i] = file[i].getValueNoNotify();
    }

    /** A frame of its own, which reusing this one cannot change. */
    StackFrame copy() {
        return new StackFrame(pc, callSite, sp, fp, toAddress, returnAddress, kind, registers.clone());
    }

    public int getPC() {
        return pc;
    }

    /** The instruction that made the entry: the call, or the one the handler was entered at. */
    public int getCallSite() {
        return callSite;
    }

    public int getToAddress() {
        return toAddress;
    }

    /** Where the call or handler returns to: the address after the call, or EPC for a handler. */
    public int getReturnAddress() {
        return returnAddress;
    }

    public int getSP() {
        return sp;
    }

    public int getFP() {
        return fp;
    }

    /** {@link #KIND_CALL}, {@link #KIND_EXCEPTION} or {@link #KIND_INTERRUPT}. */
    public int getKind() {
        return kind;
    }

    public int[] getRegisters() {
        return registers;
    }

}
