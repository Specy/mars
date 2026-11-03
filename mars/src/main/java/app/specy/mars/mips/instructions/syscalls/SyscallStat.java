package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.Globals;
import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.AddressErrorException;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 1038, stat (an extension of this Core, numbered as Linux's asm-generic table numbers it):
 * stores Linux's {@code struct stat64} for the path in $a0 at the buffer in $a1 and returns 0 in
 * $v0, or minus a Linux error number, storing nothing: -2 when nothing is at the path. The host
 * lays the structure out; the syscall stores the bytes it answers.
 */
public class SyscallStat extends AbstractSyscall {
   /** Room for the structure: Linux's is 104 bytes on MIPS. */
   private static final int CAPACITY = 256;

   public SyscallStat() {
      super(1038, "Stat");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      String path = NullString.get(statement, 4);
      int address = RegisterFile.getValue(5);
      byte[] bytes = new byte[CAPACITY];
      int result = SystemIO.stat(path, bytes);
      if (result >= 0) {
         try {
            for (int index = 0; index < Math.min(result, bytes.length); index++) {
               Globals.memory.setByte(address + index, bytes[index]);
            }
         } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
         }
      }
      RegisterFile.updateRegister(2, result >= 0 ? 0 : result);
   }
}
