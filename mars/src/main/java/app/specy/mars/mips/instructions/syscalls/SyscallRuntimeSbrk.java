package app.specy.mars.mips.instructions.syscalls;
import app.specy.mars.*;
import app.specy.mars.mips.hardware.*;
/** Runtime extension: recoverable allocation failure; service 9 retains reference semantics. */
public class SyscallRuntimeSbrk extends AbstractSyscall {
    public SyscallRuntimeSbrk() { super(1100, "RuntimeSbrk"); }
    public void simulate(ProgramStatement statement) {
        int address;
        try { address = Globals.memory.allocateBytesFromHeap(RegisterFile.getValue(4)); }
        catch (IllegalArgumentException failure) { address = -1; }
        RegisterFile.updateRegister(2, address);
    }
}
