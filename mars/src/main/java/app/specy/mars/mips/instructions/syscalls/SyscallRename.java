package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 1034, rename (an extension of this Core, numbered as Linux's asm-generic table numbers
 * it): moves the file at the path in $a0 to the path in $a1 and returns 0 in $v0, or minus a Linux
 * error number.
 */
public class SyscallRename extends AbstractSyscall {
   public SyscallRename() {
      super(1034, "Rename");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      String from = NullString.get(statement, 4);
      String to = NullString.get(statement, 5);
      RegisterFile.updateRegister(2, SystemIO.rename(from, to));
   }
}
