package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.*;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.util.*;

import java.io.ByteArrayOutputStream;

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
     * Performs syscall function to print string stored starting at address in $a0, up to its NUL
     * byte whatever its length, as MARS does, decoded from UTF-8 (MARS reads one byte per
     * character). The string is printed in one piece; when a byte of it cannot be read, the text
     * read before it is printed first, as MARS prints one character at a time.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            // won't stop until NULL byte reached!
            NullString.read(RegisterFile.getValue(4), bytes);
        } catch (AddressErrorException e) {
            print(bytes);
            throw new ProcessingException(statement, e);
        }
        print(bytes);
    }

    private static void print(ByteArrayOutputStream bytes) {
        if (bytes.size() > 0) {
            SystemIO.printString(NullString.decode(bytes));
        }
    }
}
