package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 1026, unlink (an extension of this Core, numbered as Linux's asm-generic table numbers
 * it): deletes the file at the path in $a0 and returns 0 in $v0, or minus a Linux error number:
 * -2 when nothing is at the path, -21 when it is a folder.
 */
public class SyscallUnlink extends AbstractSyscall {
   public SyscallUnlink() {
      super(1026, "Unlink");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      String path = NullString.get(statement, 4);
      RegisterFile.updateRegister(2, SystemIO.unlink(path));
   }
}
