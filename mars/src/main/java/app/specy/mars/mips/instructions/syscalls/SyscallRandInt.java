package app.specy.mars.mips.instructions.syscalls;

import java.util.Random;

import app.specy.mars.*;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.simulator.*;
import app.specy.mars.util.*;

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/**
 * Service to return a random integer.
 *
 */

public class SyscallRandInt extends AbstractSyscall {
   /**
    * Build an instance of the syscall with its default service number and name.
    */
   public SyscallRandInt() {
      super(41, "RandInt");
   }

   /**
    * System call to the random number generator.
    * Return in $a0 the next pseudorandom, uniformly distributed int value from
    * this random number generator's sequence.
    */
   public void simulate(ProgramStatement statement) throws ProcessingException {
      // Input arguments: $a0 = index of pseudorandom number generator
      // Return: $a0 = the next pseudorandom, uniformly distributed int value from
      // this random number generator's sequence.
      Integer index = new Integer(RegisterFile.getValue(4));
      Random stream = (Random) RandomStreams.randomStreams.get(index);
      if (stream == null) {
         stream = new Random(); // create a non-seeded stream
         RandomStreams.randomStreams.put(index, stream);
      }
      RegisterFile.updateRegister(4, stream.nextInt());
   }

}
