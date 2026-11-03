package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 1031, rmdir (an extension of this Core, numbered as Linux's asm-generic table numbers
 * it): removes the empty folder at the path in $a0 and returns 0 in $v0, or minus a Linux error
 * number: -39 when the folder is not empty, -20 when the path is a file.
 */
public class SyscallRmdir extends AbstractSyscall {
   public SyscallRmdir() {
      super(1031, "Rmdir");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      String path = NullString.get(statement, 4);
      RegisterFile.updateRegister(2, SystemIO.removeFolder(path));
   }
}
