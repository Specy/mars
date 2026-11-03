package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.Globals;
import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.AddressErrorException;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.SystemIO;

/**
 * Service 1065, getdents (an extension of this Core, numbered as Linux's asm-generic table numbers
 * it): reads the entries of the folder open on the descriptor in $a0 as Linux's
 * {@code linux_dirent} records into the buffer in $a1, at most $a2 bytes, and returns in $v0 the
 * number of bytes stored, 0 at the end of the folder, or minus a Linux error number: -22 for a
 * negative size or a buffer too small for the next record. The host lays the records out; the
 * syscall stores the bytes it answers.
 */
public class SyscallGetdents extends AbstractSyscall {
   public SyscallGetdents() {
      super(1065, "Getdents");
   }

   public void simulate(ProgramStatement statement) throws ProcessingException {
      int fd = RegisterFile.getValue(4);
      int address = RegisterFile.getValue(5);
      int size = RegisterFile.getValue(6);
      // A larger buffer is filled no further than this, as a getdents may always return fewer bytes.
      int length = Math.min(Math.max(size, 0), SystemIO.GETDENTS_LIMIT);
      byte[] bytes = new byte[length];
      int result = SystemIO.readFolder(fd, bytes, size < 0 ? size : length);
      if (result > 0) {
         try {
            for (int index = 0; index < Math.min(result, bytes.length); index++) {
               Globals.memory.setByte(address + index, bytes[index]);
            }
         } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
         }
      }
      RegisterFile.updateRegister(2, result);
   }
}
