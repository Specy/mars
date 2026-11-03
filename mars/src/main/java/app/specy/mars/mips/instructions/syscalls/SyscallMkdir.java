package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 1030, mkdir (an extension of this Core, numbered as Linux's asm-generic table numbers
 * it): makes a folder at the path in $a0 and returns 0 in $v0, or minus a Linux error number:
 * -17 when something is already there, -2 when the folder it would go in does not exist.
 */
public class SyscallMkdir extends AbstractSyscall {
   public SyscallMkdir() {
      super(1030, "Mkdir");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      String path = NullString.get(statement, 4);
      RegisterFile.updateRegister(2, SystemIO.makeFolder(path));
   }
}
