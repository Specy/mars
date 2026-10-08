package app.specy.mars.mips.instructions.syscalls;
import app.specy.mars.*;
import app.specy.mars.mips.hardware.*;
/** Runtime instruction counter, low word in a0 and high word in a1. */
public class SyscallCpuTicks extends AbstractSyscall {
    public SyscallCpuTicks() { super(1101, "CpuTicks"); }
    public void simulate(ProgramStatement statement) {
        long count = Globals.program.getBackStepper().getInstructionsExecuted();
        RegisterFile.updateRegister(4, (int)count);
        RegisterFile.updateRegister(5, (int)(count >>> 32));
    }
}
