package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.*;
import app.specy.mars.mips.hardware.*;
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

/**
 * Service to display string stored starting at address in $a0 onto the console.
 */

public class SyscallPrintString extends AbstractSyscall {
    /**
     * Build an instance of the Print String syscall. Default service number
     * is 4 and name is "PrintString".
     */
    public SyscallPrintString() {
        super(4, "PrintString");
    }

    /**
     * Performs syscall function to print string stored starting at address in $a0.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        int byteAddress = RegisterFile.getValue(4);
        int maxChars = 65536; // arbitrary upper limit
        char ch = 0;
        try {
            ch = (char) Globals.memory.getByte(byteAddress);
            // won't stop until NULL byte reached or maximum characters printed
            while (ch != 0) {
                maxChars--;
                if (maxChars < 0) {
                    throw new ProcessingException(statement,
                            "String length exceeds system limit of 65536 characters");
                }
                SystemIO.printChar(ch);
                byteAddress++;
                ch = (char) Globals.memory.getByte(byteAddress);
            }
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }
    }
}