package app.specy.mars.mips.hardware;


/** One frame of the call stack: see {@link Stack}, which reuses frames from call to call. */
public class StackFrame {
    private int pc;
    private int toAddress;
    private int sp;
    private int fp;
    private int[] registers;

    public StackFrame(
            int pc,
            int sp,
            int fp,
            int toAddress,
            int[] registers
    ) {
        this.pc = pc;
        this.sp = sp;
        this.fp = fp;
        this.toAddress = toAddress;
        this.registers = registers;
    }

    StackFrame() {
    }

    /**
     * Fills this frame with the state of a call to {@code returnAddress} made now, into the register
     * array it already has. No register is read as the program reads it, so no observer hears of it.
     */
    void capture(int returnAddress) {
        pc = RegisterFile.getProgramCounter();
        sp = RegisterFile.getStackPointerRegister().getValueNoNotify();
        fp = RegisterFile.getFramePointerRegister().getValueNoNotify();
        toAddress = returnAddress;
        Register[] file = RegisterFile.getRegisters();
        if (registers == null || registers.length != file.length) registers = new int[file.length];
        for (int i = 0; i < file.length; i++) registers[i] = file[i].getValueNoNotify();
    }

    /** A frame of its own, which reusing this one cannot change. */
    StackFrame copy() {
        return new StackFrame(pc, sp, fp, toAddress, registers.clone());
    }

    public int getPC() {
        return pc;
    }

    public int getToAddress() {
        return toAddress;
    }

    public int getSP() {
        return sp;
    }

    public int getFP() {
        return fp;
    }

    public int[] getRegisters() {
        return registers;
    }

}
