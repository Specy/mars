package app.specy.mars.mips.instructions;

import java.util.*;

import app.specy.mars.*;
import app.specy.mars.mips.instructions.syscalls.*;
import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.util.*;

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/****************************************************************************/
/*
 * This class provides functionality to bring external Syscall definitions
 * into MARS. This permits anyone with knowledge of the Mars public interfaces,
 * in particular of the Memory and Register classes, to write custom MIPS
 * syscall
 * functions. This is adapted from the ToolLoader class, which is in turn
 * adapted
 * from Bret Barker's GameServer class from the book "Developing Games In Java".
 */

public class SyscallLoader {
   private List<Syscall> syscallList;
   private MIPSIO io;

   public SyscallLoader(MIPSIO io) {
      this.io = io;
      loadSyscalls();
   }

   SyscallLoader add(Syscall syscall) {
      syscallList.add(syscall);
      return this;
   }

   private void loadSyscalls() {
      this.syscallList = new ArrayList<Syscall>();
      add(new SyscallClose());
      add(new SyscallConfirmDialog(io));
      add(new SyscallExit());
      add(new SyscallExit2());
      add(new SyscallInputDialogDouble(io));
      add(new SyscallInputDialogFloat(io));
      add(new SyscallInputDialogInt(io));
      add(new SyscallInputDialogString(io));
      add(new SyscallMessageDialog(io));
      add(new SyscallMessageDialogDouble(io));
      add(new SyscallMessageDialogFloat(io));
      add(new SyscallMessageDialogInt(io));
      add(new SyscallMessageDialogString(io));

      add(new SyscallOpen());
      add(new SyscallPrintChar());
      add(new SyscallPrintDouble());
      add(new SyscallPrintFloat());
      add(new SyscallPrintInt());
      add(new SyscallPrintIntBinary());
      add(new SyscallPrintIntHex());
      add(new SyscallPrintIntUnsigned());
      add(new SyscallPrintString());
      add(new SyscallRandDouble());
      add(new SyscallRandFloat());
      add(new SyscallRandInt());
      add(new SyscallRandIntRange());
      add(new SyscallRead());
      add(new SyscallWrite());
      add(new SyscallReadChar());
      add(new SyscallReadDouble());
      add(new SyscallReadFloat());
      add(new SyscallReadInt());
      add(new SyscallReadString());
      add(new SyscallSbrk());
      add(new SyscallTime());

      syscallList = processSyscallNumberOverrides(syscallList);
      return;
   }

   // Will get any syscall number override specifications from MARS config file and
   // process them. This will alter syscallList entry for affected names.
   private List<Syscall> processSyscallNumberOverrides(List<Syscall> syscallList) {
      List<SyscallNumberOverride> overrides = new Globals().getSyscallOverrides();
      for (SyscallNumberOverride override : overrides) {
         boolean match = false;
         for (Syscall syscall : syscallList) {
            if (override.getName().equals(syscall.getName())) {
               // we have a match to service name, assign new number
               syscall.setNumber(override.getNumber());
               match = true;
            }
         }
         if (!match) {
            String message = "Error: syscall name '" + override.getName() +
                    "' in config file does not match any name in syscall list";
            System.out.println(message);
            throw new RuntimeException(message);
         }
      }
      // Wait until end to check for duplicate numbers. To do so earlier
      // would disallow for instance the exchange of numbers between two
      // services. This is N-squared operation but N is small.
      // This will also detect duplicates that accidently occur from addition
      // of a new Syscall subclass to the collection, even if the config file
      // does not contain any overrides.
      Syscall syscallA, syscallB;
      boolean duplicates = false;
      for (int i = 0; i < syscallList.size(); i++) {
         syscallA = (Syscall) syscallList.get(i);
         for (int j = i + 1; j < syscallList.size(); j++) {
            syscallB = (Syscall) syscallList.get(j);
            if (syscallA.getNumber() == syscallB.getNumber()) {
               System.out.println("Error: syscalls " + syscallA.getName() + " and " +
                     syscallB.getName() + " are both assigned same number " + syscallA.getNumber());
               duplicates = true;
            }
         }
      }
      if (duplicates) {
         System.out.println("Cannot continue with duplicate syscall numbers.");
         throw new RuntimeException("Duplicate syscall numbers");
      }
      return syscallList;
   }

   /*
    * Method to find Syscall object associated with given service number.
    * Returns null if no associated object found.
    */
   Syscall findSyscall(int number) {
      // linear search is OK since number of syscalls is small.
      Syscall service, match = null;
      if (syscallList == null) {
         loadSyscalls();
      }
      for (int index = 0; index < syscallList.size(); index++) {
         service = (Syscall) syscallList.get(index);
         if (service.getNumber() == number) {
            match = service;
         }
      }
      return match;
   }
}
