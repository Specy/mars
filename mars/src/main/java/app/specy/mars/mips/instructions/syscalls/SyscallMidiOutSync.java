package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.*;
import app.specy.mars.util.*;

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

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
 * Service to play a simulated MIDI tone and wait until it has finished playing.
 *
 */

public class SyscallMidiOutSync extends AbstractSyscall {
   /**
    * Build an instance of the MIDI (simulated) out synchronous syscall. Default service number
    * is 33 and name is "MidiOutSync".
    */
   public SyscallMidiOutSync() {
      super(33, "MidiOutSync");
   }

   /**
    * Performs syscall function to send MIDI output to the sound card, then wait for the tone's
    * duration. The arguments, their ranges and their defaults are MIDI out's (31). The wait is a
    * sleep (32) of that duration, so the IO environment suspends the simulation exactly as it does
    * for a sleep.
    */
   public void simulate(ProgramStatement statement) throws ProcessingException {
      int duration = SyscallMidiOut.play();
      SystemIO.sleep(duration);
   }

}
