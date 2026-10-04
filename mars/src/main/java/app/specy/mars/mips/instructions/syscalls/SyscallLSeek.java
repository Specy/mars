package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 62 (an extension of this Core, numbered as RARS numbers its lseek): moves the position of
 * the file descriptor in $a0 by $a1 bytes from the start ($a2 = 0), the current position (1) or the
 * end (2), and returns the new position in $v0, or -1 when the seek failed.
 */
public class SyscallLSeek extends AbstractSyscall {
   public SyscallLSeek() {
      super(62, "LSeek");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      RegisterFile.updateRegister(2, SystemIO.seek(
            RegisterFile.getValue(4),
            RegisterFile.getValue(5),
            RegisterFile.getValue(6)));
   }
}
