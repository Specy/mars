package app.specy.mars.mips.instructions;

import java.util.*;

import app.specy.mars.*;
import app.specy.mars.mips.constants.PseudoOps;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.mips.hardware.Stack;
import app.specy.mars.mips.instructions.syscalls.*;
import app.specy.mars.simulator.*;
import app.specy.mars.util.*;

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

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
 * The list of Instruction objects, each of which represents a MIPS instruction.
 * The instruction may either be basic (translates into binary machine code) or
 * extended (translates into sequence of one or more basic instructions).
 *
 * @author Pete Sanderson and Ken Vollmar
 * @version August 2003-5
 */

public class InstructionSet {
    private List<Instruction> instructionList;
    private List<MatchMap> opcodeMatchMaps;
    private SyscallLoader syscallLoader;


    public void setSyscallLoader(SyscallLoader loader) {
        syscallLoader = loader;
    }

    /**
     * Creates a new InstructionSet object.
     */
    public InstructionSet() {
        instructionList = new ArrayList<Instruction>();

    }

    /**
     * Retrieve the current instruction set.
     */
    public List<Instruction> getInstructionList() {
        return instructionList;

    }

    /**
     * Adds all instructions to the set. A given extended instruction may have
     * more than one Instruction object, depending on how many formats it can have.
     *
     * @see Instruction
     * @see BasicInstruction
     * @see ExtendedInstruction
     */
    public void populate() {
        /*
         * Here is where the parade begins. Every instruction is added to the set here.
         */

        // //////////////////////////////////// BASIC INSTRUCTIONS START HERE
        // ////////////////////////////////

        instructionList.add(
                new BasicInstruction("nop",
                        "No operation : Does nothing; the processor simply advances to the next instruction. Machine code is all zeroes. Useful as a placeholder or delay-slot filler.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 00000 00000 00000 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                // Hey I like this so far!
                            }
                        }));
        instructionList.add(
                new BasicInstruction("add $t1,$t2,$t3",
                        "Add (signed, overflow trap) : Sets $t1 = $t2 + $t3 using signed 32-bit arithmetic. Raises an overflow exception if the true result does not fit in 32 bits. Use 'addu' when overflow should be silently ignored.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int add1 = RegisterFile.getValue(operands[1]);
                                int add2 = RegisterFile.getValue(operands[2]);
                                int sum = add1 + add2;
                                // overflow on A+B detected when A and B have same sign and A+B has other sign.
                                if ((add1 >= 0 && add2 >= 0 && sum < 0)
                                        || (add1 < 0 && add2 < 0 && sum >= 0)) {
                                    throw new ProcessingException(statement,
                                            "arithmetic overflow", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
                                }
                                RegisterFile.updateRegister(operands[0], sum);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sub $t1,$t2,$t3",
                        "Subtract (signed, overflow trap) : Sets $t1 = $t2 - $t3 using signed 32-bit arithmetic. Raises an overflow exception if the true result does not fit in 32 bits. Use 'subu' when overflow should be silently ignored.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int sub1 = RegisterFile.getValue(operands[1]);
                                int sub2 = RegisterFile.getValue(operands[2]);
                                int dif = sub1 - sub2;
                                // overflow on A-B detected when A and B have opposite signs and A-B has B's
                                // sign
                                if ((sub1 >= 0 && sub2 < 0 && dif < 0)
                                        || (sub1 < 0 && sub2 >= 0 && dif >= 0)) {
                                    throw new ProcessingException(statement,
                                            "arithmetic overflow", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
                                }
                                RegisterFile.updateRegister(operands[0], dif);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("addi $t1,$t2,-100",
                        "Add immediate (signed, overflow trap) : Sets $t1 = $t2 + immediate, where the immediate is a signed 16-bit constant (-32768 to 32767) sign-extended to 32 bits. Raises an overflow exception if the result overflows. Use 'addiu' to suppress the overflow check.",
                        BasicInstructionFormat.I_FORMAT,
                        "001000 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int add1 = RegisterFile.getValue(operands[1]);
                                int add2 = operands[2] << 16 >> 16;
                                int sum = add1 + add2;
                                // overflow on A+B detected when A and B have same sign and A+B has other sign.
                                if ((add1 >= 0 && add2 >= 0 && sum < 0)
                                        || (add1 < 0 && add2 < 0 && sum >= 0)) {
                                    throw new ProcessingException(statement,
                                            "arithmetic overflow", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
                                }
                                RegisterFile.updateRegister(operands[0], sum);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("addu $t1,$t2,$t3",
                        "Add unsigned (no overflow trap) : Sets $t1 = $t2 + $t3. No exception is raised on overflow; the result wraps modulo 2^32. Despite the name, this works fine with signed values too when you don't need overflow detection.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                + RegisterFile.getValue(operands[2]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("subu $t1,$t2,$t3",
                        "Subtract unsigned (no overflow trap) : Sets $t1 = $t2 - $t3. No exception is raised on overflow; the result wraps modulo 2^32. The most common subtract instruction in practice.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                - RegisterFile.getValue(operands[2]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("addiu $t1,$t2,-100",
                        "Add immediate unsigned (no overflow trap) : Sets $t1 = $t2 + immediate. The immediate is a signed 16-bit value sign-extended to 32 bits; no overflow exception is raised. Despite the name the immediate is sign-extended, not zero-extended. The most common add-immediate instruction.",
                        BasicInstructionFormat.I_FORMAT,
                        "001001 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                + (operands[2] << 16 >> 16));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mult $t1,$t2",
                        "Multiply signed : Multiplies $t1 by $t2 as signed 32-bit integers. The 64-bit product is split: the upper 32 bits go into the HI register and the lower 32 bits go into the LO register. Use 'mfhi' to read HI and 'mflo' to read LO.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 011000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (long) RegisterFile.getValue(operands[0])
                                        * (long) RegisterFile.getValue(operands[1]);
                                // Register 33 is HIGH and 34 is LOW
                                RegisterFile.updateRegister(33, (int) (product >> 32));
                                RegisterFile.updateRegister(34, (int) ((product << 32) >> 32));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("multu $t1,$t2",
                        "Multiply unsigned : Multiplies $t1 by $t2 treating both as unsigned 32-bit integers. The 64-bit product is split: upper 32 bits go into HI, lower 32 bits go into LO. Use 'mfhi'/'mflo' to retrieve the results.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 011001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (((long) RegisterFile.getValue(operands[0])) << 32 >>> 32)
                                        * (((long) RegisterFile.getValue(operands[1])) << 32 >>> 32);
                                // Register 33 is HIGH and 34 is LOW
                                RegisterFile.updateRegister(33, (int) (product >> 32));
                                RegisterFile.updateRegister(34, (int) ((product << 32) >> 32));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mul $t1,$t2,$t3",
                        "Multiply (low 32-bit result to register) : Multiplies $t2 by $t3 (signed) and stores the lower 32 bits of the product directly into $t1 and also into LO. No overflow exception is raised. HI is updated with the upper 32 bits.",
                        BasicInstructionFormat.R_FORMAT,
                        "011100 sssss ttttt fffff 00000 000010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (long) RegisterFile.getValue(operands[1])
                                        * (long) RegisterFile.getValue(operands[2]);
                                RegisterFile.updateRegister(operands[0],
                                        (int) ((product << 32) >> 32));
                                // Register 33 is HIGH and 34 is LOW. Not required by MIPS; SPIM does it.
                                RegisterFile.updateRegister(33, (int) (product >> 32));
                                RegisterFile.updateRegister(34, (int) ((product << 32) >> 32));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("madd $t1,$t2",
                        "Multiply-accumulate signed : Multiplies $t1 by $t2 (signed) to form a 64-bit product, then adds that product to the current 64-bit value in HI:LO. Useful for computing dot-products or sums of products without extra move instructions.",
                        BasicInstructionFormat.R_FORMAT,
                        "011100 fffff sssss 00000 00000 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (long) RegisterFile.getValue(operands[0])
                                        * (long) RegisterFile.getValue(operands[1]);
                                // Register 33 is HIGH and 34 is LOW.
                                long contentsHiLo = Binary.twoIntsToLong(
                                        RegisterFile.getValue(33), RegisterFile.getValue(34));
                                long sum = contentsHiLo + product;
                                RegisterFile.updateRegister(33, Binary.highOrderLongToInt(sum));
                                RegisterFile.updateRegister(34, Binary.lowOrderLongToInt(sum));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("maddu $t1,$t2",
                        "Multiply-accumulate unsigned : Multiplies $t1 by $t2 treating both as unsigned 32-bit integers, then adds the 64-bit product to HI:LO. Use 'mfhi'/'mflo' to read the accumulated result.",
                        BasicInstructionFormat.R_FORMAT,
                        "011100 fffff sssss 00000 00000 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (((long) RegisterFile.getValue(operands[0])) << 32 >>> 32)
                                        * (((long) RegisterFile.getValue(operands[1])) << 32 >>> 32);
                                // Register 33 is HIGH and 34 is LOW.
                                long contentsHiLo = Binary.twoIntsToLong(
                                        RegisterFile.getValue(33), RegisterFile.getValue(34));
                                long sum = contentsHiLo + product;
                                RegisterFile.updateRegister(33, Binary.highOrderLongToInt(sum));
                                RegisterFile.updateRegister(34, Binary.lowOrderLongToInt(sum));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("msub $t1,$t2",
                        "Multiply-subtract signed : Multiplies $t1 by $t2 (signed) to form a 64-bit product, then subtracts that product from the current 64-bit value in HI:LO. Use 'mfhi'/'mflo' to read the result.",
                        BasicInstructionFormat.R_FORMAT,
                        "011100 fffff sssss 00000 00000 000100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (long) RegisterFile.getValue(operands[0])
                                        * (long) RegisterFile.getValue(operands[1]);
                                // Register 33 is HIGH and 34 is LOW.
                                long contentsHiLo = Binary.twoIntsToLong(
                                        RegisterFile.getValue(33), RegisterFile.getValue(34));
                                long diff = contentsHiLo - product;
                                RegisterFile.updateRegister(33, Binary.highOrderLongToInt(diff));
                                RegisterFile.updateRegister(34, Binary.lowOrderLongToInt(diff));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("msubu $t1,$t2",
                        "Multiply-subtract unsigned : Multiplies $t1 by $t2 treating both as unsigned 32-bit integers, then subtracts the 64-bit product from HI:LO. Use 'mfhi'/'mflo' to read the result.",
                        BasicInstructionFormat.R_FORMAT,
                        "011100 fffff sssss 00000 00000 000101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                long product = (((long) RegisterFile.getValue(operands[0])) << 32 >>> 32)
                                        * (((long) RegisterFile.getValue(operands[1])) << 32 >>> 32);
                                // Register 33 is HIGH and 34 is LOW.
                                long contentsHiLo = Binary.twoIntsToLong(
                                        RegisterFile.getValue(33), RegisterFile.getValue(34));
                                long diff = contentsHiLo - product;
                                RegisterFile.updateRegister(33, Binary.highOrderLongToInt(diff));
                                RegisterFile.updateRegister(34, Binary.lowOrderLongToInt(diff));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("div $t1,$t2",
                        "Divide signed : Divides $t1 by $t2 (signed). Sets LO to the quotient and HI to the remainder. Division by zero produces undefined results with no exception. Use 'mflo' to get the quotient; 'mfhi' to get the remainder.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 011010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[1]) == 0) {
                                    // Note: no exceptions and undefined results for zero div
                                    // COD3 Appendix A says "with overflow" but MIPS 32 instruction set
                                    // specification says "no arithmetic exception under any circumstances".
                                    return;
                                }

                                // Register 33 is HIGH and 34 is LOW
                                RegisterFile.updateRegister(33,
                                        RegisterFile.getValue(operands[0])
                                                % RegisterFile.getValue(operands[1]));
                                RegisterFile.updateRegister(34,
                                        RegisterFile.getValue(operands[0])
                                                / RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("divu $t1,$t2",
                        "Divide unsigned : Divides $t1 by $t2 treating both as unsigned 32-bit integers. Sets LO to the quotient and HI to the remainder. Division by zero produces undefined results with no exception. Use 'mflo' for the quotient; 'mfhi' for the remainder.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 011011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[1]) == 0) {
                                    // Note: no exceptions, and undefined results for zero divide
                                    return;
                                }
                                long oper1 = ((long) RegisterFile.getValue(operands[0])) << 32 >>> 32;
                                long oper2 = ((long) RegisterFile.getValue(operands[1])) << 32 >>> 32;
                                // Register 33 is HIGH and 34 is LOW
                                RegisterFile.updateRegister(33,
                                        (int) (((oper1 % oper2) << 32) >> 32));
                                RegisterFile.updateRegister(34,
                                        (int) (((oper1 / oper2) << 32) >> 32));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mfhi $t1",
                        "Move from HI : Copies the special HI register into $t1. HI holds the upper 32 bits of a multiply result or the remainder from a divide. Always read HI/LO before executing another multiply or divide, as those instructions overwrite them.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 00000 fffff 00000 010000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(33));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mflo $t1",
                        "Move from LO : Copies the special LO register into $t1. LO holds the lower 32 bits of a multiply result or the quotient from a divide. Always read HI/LO before executing another multiply or divide, as those instructions overwrite them.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 00000 fffff 00000 010010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(34));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mthi $t1",
                        "Move to HI : Copies $t1 into the special HI register, overwriting any prior multiply or divide result stored there.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff 00000 00000 00000 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(33,
                                        RegisterFile.getValue(operands[0]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mtlo $t1",
                        "Move to LO : Copies $t1 into the special LO register, overwriting any prior multiply or divide result stored there.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff 00000 00000 00000 010011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(34,
                                        RegisterFile.getValue(operands[0]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("and $t1,$t2,$t3",
                        "Bitwise AND : Sets $t1 = $t2 & $t3. Each bit of $t1 is 1 only if the corresponding bit is 1 in both $t2 and $t3. Commonly used to mask (isolate) specific bits in a value.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                & RegisterFile.getValue(operands[2]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("or $t1,$t2,$t3",
                        "Bitwise OR : Sets $t1 = $t2 | $t3. Each bit of $t1 is 1 if the corresponding bit is 1 in either $t2 or $t3 (or both). Commonly used to set specific bits in a value.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                | RegisterFile.getValue(operands[2]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("andi $t1,$t2,100",
                        "Bitwise AND immediate : Sets $t1 = $t2 & immediate. The 16-bit immediate is zero-extended (upper 16 bits become 0, not sign-extended) before the AND. Commonly used to mask off the lower 16 bits of a register or to test individual bits.",
                        BasicInstructionFormat.I_FORMAT,
                        "001100 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // ANDing with 0x0000FFFF zero-extends the immediate (high 16 bits always 0).
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                & (operands[2] & 0x0000FFFF));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("ori $t1,$t2,100",
                        "Bitwise OR immediate : Sets $t1 = $t2 | immediate. The 16-bit immediate is zero-extended before the OR. Commonly used to set specific bits or to load a small non-negative constant into a register (e.g. after 'lui').",
                        BasicInstructionFormat.I_FORMAT,
                        "001101 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // ANDing with 0x0000FFFF zero-extends the immediate (high 16 bits always 0).
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                | (operands[2] & 0x0000FFFF));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("nor $t1,$t2,$t3",
                        "Bitwise NOR : Sets $t1 = ~($t2 | $t3). Each bit of $t1 is 1 only if the corresponding bit is 0 in both $t2 and $t3. Tip: 'nor $t1,$t2,$zero' computes a bitwise NOT of $t2, since MIPS has no dedicated NOT instruction.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100111",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        ~(RegisterFile.getValue(operands[1])
                                                | RegisterFile.getValue(operands[2])));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("xor $t1,$t2,$t3",
                        "Bitwise XOR (exclusive OR) : Sets $t1 = $t2 ^ $t3. Each bit of $t1 is 1 if the corresponding bits in $t2 and $t3 differ. Used to toggle specific bits, detect differences, or implement simple encryption/checksums.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 100110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                ^ RegisterFile.getValue(operands[2]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("xori $t1,$t2,100",
                        "Bitwise XOR immediate : Sets $t1 = $t2 ^ immediate. The 16-bit immediate is zero-extended before the XOR. Useful for toggling specific bits.",
                        BasicInstructionFormat.I_FORMAT,
                        "001110 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // ANDing with 0x0000FFFF zero-extends the immediate (high 16 bits always 0).
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1])
                                                ^ (operands[2] & 0x0000FFFF));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sll $t1,$t2,10",
                        "Shift left logical : Sets $t1 = $t2 << immediate (0-31 bits). Vacated bits on the right are filled with 0. Shifting left by n is equivalent to multiplying by 2^n (without overflow checking). Example: sll $t1,$t1,2 multiplies $t1 by 4.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 sssss fffff ttttt 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1]) << operands[2]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sllv $t1,$t2,$t3",
                        "Shift left logical variable : Sets $t1 = $t2 << ($t3 & 31). Like 'sll' but the shift amount is taken from the lowest 5 bits of register $t3 rather than an immediate constant. The upper 27 bits of $t3 are ignored.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 ttttt sssss fffff 00000 000100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // Mask all but low 5 bits of register containing shamt.
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1]) << (RegisterFile.getValue(operands[2]) & 0x0000001F));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("srl $t1,$t2,10",
                        "Shift right logical : Sets $t1 = $t2 >>> immediate (0-31 bits). Vacated bits on the left are filled with 0 (unsigned shift). Shifting right by n is equivalent to unsigned division by 2^n. Use 'sra' to preserve the sign bit instead.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 sssss fffff ttttt 000010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // must zero-fill, so use ">>>" instead of ">>".
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1]) >>> operands[2]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sra $t1,$t2,10",
                        "Shift right arithmetic : Sets $t1 = $t2 >> immediate (0-31 bits). Vacated bits on the left are filled with copies of the sign bit, preserving the sign of negative numbers. Shifting right by n is equivalent to signed division by 2^n rounding toward negative infinity.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 sssss fffff ttttt 000011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // must sign-fill, so use ">>".
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1]) >> operands[2]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("srav $t1,$t2,$t3",
                        "Shift right arithmetic variable : Sets $t1 = $t2 >> ($t3 & 31). Like 'sra' but the shift amount is taken from the lowest 5 bits of register $t3. Vacated bits are sign-filled.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 ttttt sssss fffff 00000 000111",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // Mask all but low 5 bits of register containing shamt.Use ">>" to sign-fill.
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1]) >> (RegisterFile.getValue(operands[2]) & 0x0000001F));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("srlv $t1,$t2,$t3",
                        "Shift right logical variable : Sets $t1 = $t2 >>> ($t3 & 31). Like 'srl' but the shift amount is taken from the lowest 5 bits of register $t3 rather than an immediate constant. Vacated bits are zero-filled.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 ttttt sssss fffff 00000 000110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // Mask all but low 5 bits of register containing shamt.Use ">>>" to zero-fill.
                                RegisterFile.updateRegister(operands[0],
                                        RegisterFile.getValue(operands[1]) >>> (RegisterFile.getValue(operands[2]) & 0x0000001F));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lw $t1,-100($t2)",
                        "Load word : Reads the 32-bit (4-byte) value at memory address ($t2 + offset) and places it in $t1. The address must be word-aligned (divisible by 4). Example: 'lw $t0,0($sp)' loads the value at the top of the stack.",
                        BasicInstructionFormat.I_FORMAT,
                        "100011 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    RegisterFile.updateRegister(operands[0],
                                            Globals.memory.getWord(
                                                    RegisterFile.getValue(operands[2]) + operands[1]));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("ll $t1,-100($t2)",
                        "Load linked : Identical to 'lw' in this simulator (reads 32 bits from memory into $t1). In real multi-processor hardware, 'll' is the first half of an atomic read-modify-write pair with 'sc' (store conditional), allowing lock-free synchronization.",
                        BasicInstructionFormat.I_FORMAT,
                        "110000 ttttt fffff ssssssssssssssss",
                        // The ll (load link) command is supposed to be the front end of an atomic
                        // operation completed by sc (store conditional), with success or failure
                        // of the store depending on whether the memory block containing the
                        // loaded word is modified in the meantime by a different processor.
                        // Since MARS, like SPIM simulates only a single processor, the store
                        // conditional will always succeed so there is no need to do anything
                        // special here. In that case, ll is same as lw. And sc does the same
                        // thing as sw except in addition it writes 1 into the source register.
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    RegisterFile.updateRegister(operands[0],
                                            Globals.memory.getWord(
                                                    RegisterFile.getValue(operands[2]) + operands[1]));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lwl $t1,-100($t2)",
                        "Load word left (unaligned) : Loads 1-4 bytes into the most-significant (left) bytes of $t1 starting from the effective byte address and working toward the low byte of the containing word. Use together with 'lwr' to load a full 32-bit word from an unaligned address.",
                        BasicInstructionFormat.I_FORMAT,
                        "100010 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    int address = RegisterFile.getValue(operands[2]) + operands[1];
                                    int result = RegisterFile.getValue(operands[0]);
                                    for (int i = 0; i <= address % Globals.memory.WORD_LENGTH_BYTES; i++) {
                                        result = Binary.setByte(result, 3 - i, Globals.memory.getByte(address - i));
                                    }
                                    RegisterFile.updateRegister(operands[0], result);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lwr $t1,-100($t2)",
                        "Load word right (unaligned) : Loads 1-4 bytes into the least-significant (right) bytes of $t1 starting from the effective byte address and working toward the high byte of the containing word. Use together with 'lwl' to load a full 32-bit word from an unaligned address.",
                        BasicInstructionFormat.I_FORMAT,
                        "100110 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    int address = RegisterFile.getValue(operands[2]) + operands[1];
                                    int result = RegisterFile.getValue(operands[0]);
                                    for (int i = 0; i <= 3 - (address % Globals.memory.WORD_LENGTH_BYTES); i++) {
                                        result = Binary.setByte(result, i, Globals.memory.getByte(address + i));
                                    }
                                    RegisterFile.updateRegister(operands[0], result);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sw $t1,-100($t2)",
                        "Store word : Writes the 32-bit value in $t1 to memory at address ($t2 + offset). The address must be word-aligned (divisible by 4). Example: 'sw $t0,0($sp)' stores a value at the top of the stack.",
                        BasicInstructionFormat.I_FORMAT,
                        "101011 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    Globals.memory.setWord(
                                            RegisterFile.getValue(operands[2]) + operands[1],
                                            RegisterFile.getValue(operands[0]));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sc $t1,-100($t2)",
                        "Store conditional : Stores the value of $t1 into memory at address ($t2 + offset), then sets $t1 = 1 (success). In real multi-processor hardware this can fail (setting $t1 = 0) if another processor modified the target location since the preceding 'll'. Always succeeds in this simulator.",
                        BasicInstructionFormat.I_FORMAT,
                        "111000 ttttt fffff ssssssssssssssss",
                        // See comments with "ll" instruction above. "sc" is implemented
                        // like "sw", except that 1 is placed in the source register.
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    Globals.memory.setWord(
                                            RegisterFile.getValue(operands[2]) + operands[1],
                                            RegisterFile.getValue(operands[0]));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                                RegisterFile.updateRegister(operands[0], 1); // always succeeds
                            }
                        }));
        instructionList.add(
                new BasicInstruction("swl $t1,-100($t2)",
                        "Store word left (unaligned) : Writes 1-4 bytes from the most-significant (left) bytes of $t1 into memory starting at the effective byte address and working toward the low byte of the containing aligned word. Use together with 'swr' to store a full word at an unaligned address.",
                        BasicInstructionFormat.I_FORMAT,
                        "101010 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    int address = RegisterFile.getValue(operands[2]) + operands[1];
                                    int source = RegisterFile.getValue(operands[0]);
                                    for (int i = 0; i <= address % Globals.memory.WORD_LENGTH_BYTES; i++) {
                                        Globals.memory.setByte(address - i, Binary.getByte(source, 3 - i));
                                    }
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("swr $t1,-100($t2)",
                        "Store word right (unaligned) : Writes 1-4 bytes from the least-significant (right) bytes of $t1 into memory starting at the high byte of the containing aligned word and working toward the effective byte address. Use together with 'swl' to store a full word at an unaligned address.",
                        BasicInstructionFormat.I_FORMAT,
                        "101110 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    int address = RegisterFile.getValue(operands[2]) + operands[1];
                                    int source = RegisterFile.getValue(operands[0]);
                                    for (int i = 0; i <= 3 - (address % Globals.memory.WORD_LENGTH_BYTES); i++) {
                                        Globals.memory.setByte(address + i, Binary.getByte(source, i));
                                    }
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lui $t1,100",
                        "Load upper immediate : Places the 16-bit immediate into the upper 16 bits of $t1 and clears the lower 16 bits to 0. Used to build a full 32-bit constant: first 'lui $t1,upper16' then 'ori $t1,$t1,lower16'. Example: to load 0x12345678 use 'lui $t1,0x1234' then 'ori $t1,$t1,0x5678'.",
                        BasicInstructionFormat.I_FORMAT,
                        "001111 00000 fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0], operands[1] << 16);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("beq $t1,$t2,label",
                        "Branch on equal : Branches to 'label' if $t1 == $t2. Uses a PC-relative 16-bit signed offset (multiplied by 4) so the target must be within ±32 KB. Commonly the first half of an if/else test.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000100 fffff sssss tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();

                                if (RegisterFile.getValue(operands[0]) == RegisterFile.getValue(operands[1])) {
                                    processBranch(operands[2]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bne $t1,$t2,label",
                        "Branch on not equal : Branches to 'label' if $t1 != $t2. Opposite of 'beq'. Uses a PC-relative 16-bit signed offset. Commonly used to branch out of loops or skip else blocks.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000101 fffff sssss tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) != RegisterFile.getValue(operands[1])) {
                                    processBranch(operands[2]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bgez $t1,label",
                        "Branch on greater than or equal to zero : Branches to 'label' if $t1 >= 0 (signed comparison). Uses a PC-relative 16-bit signed offset. Commonly used to test the sign bit.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000001 fffff 00001 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) >= 0) {
                                    processBranch(operands[1]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bgezal $t1,label",
                        "Branch on greater than or equal to zero and link : Branches to 'label' if $t1 >= 0 (signed), and saves the return address (PC + 4) in $ra. Combines a conditional branch with a function call. Use 'jr $ra' to return.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000001 fffff 10001 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) >= 0) { // the "and link" part
                                    processReturnAddress(31);// RegisterFile.updateRegister("$ra",RegisterFile.getProgramCounter());
                                    Stack.pushCallStack(StackFrame.fromGlobalState(processBranch(operands[1])));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bgtz $t1,label",
                        "Branch on greater than zero : Branches to 'label' if $t1 > 0 (signed comparison). Uses a PC-relative 16-bit signed offset.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000111 fffff 00000 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) > 0) {
                                    processBranch(operands[1]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("blez $t1,label",
                        "Branch on less than or equal to zero : Branches to 'label' if $t1 <= 0 (signed comparison). Uses a PC-relative 16-bit signed offset.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000110 fffff 00000 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) <= 0) {
                                    processBranch(operands[1]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bltz $t1,label",
                        "Branch on less than zero : Branches to 'label' if $t1 < 0 (signed comparison). Uses a PC-relative 16-bit signed offset.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000001 fffff 00000 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) < 0) {
                                    processBranch(operands[1]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bltzal $t1,label",
                        "Branch on less than zero and link : Branches to 'label' if $t1 < 0 (signed), and saves the return address (PC + 4) in $ra. Combines a conditional branch with a function call. Use 'jr $ra' to return.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "000001 fffff 10000 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) < 0) { // the "and link" part
                                    processReturnAddress(31);// RegisterFile.updateRegister("$ra",RegisterFile.getProgramCounter());
                                    Stack.pushCallStack(StackFrame.fromGlobalState(processBranch(operands[1])));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("slt $t1,$t2,$t3",
                        "Set on less than (signed) : Sets $t1 = 1 if $t2 < $t3 (signed comparison), otherwise $t1 = 0. Useful for conditional logic without branches. Pair with 'bne $t1,$zero,label' or 'beq $t1,$zero,label' to branch on the result.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 101010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        (RegisterFile.getValue(operands[1]) < RegisterFile.getValue(operands[2]))
                                                ? 1
                                                : 0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sltu $t1,$t2,$t3",
                        "Set on less than unsigned : Sets $t1 = 1 if $t2 < $t3 using unsigned comparison, otherwise $t1 = 0. Treats both operands as unsigned 32-bit integers (0 to 4294967295). Use this when comparing memory addresses or unsigned counters.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 101011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int first = RegisterFile.getValue(operands[1]);
                                int second = RegisterFile.getValue(operands[2]);
                                if (first >= 0 && second >= 0 || first < 0 && second < 0) {
                                    RegisterFile.updateRegister(operands[0],
                                            (first < second) ? 1 : 0);
                                } else {
                                    RegisterFile.updateRegister(operands[0],
                                            (first >= 0) ? 1 : 0);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("slti $t1,$t2,-100",
                        "Set on less than immediate (signed) : Sets $t1 = 1 if $t2 < immediate (sign-extended to 32 bits), otherwise $t1 = 0. Convenient for loop bounds and range checking without a separate load instruction.",
                        BasicInstructionFormat.I_FORMAT,
                        "001010 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // 16 bit immediate value in operands[2] is sign-extended
                                RegisterFile.updateRegister(operands[0],
                                        (RegisterFile.getValue(operands[1]) < (operands[2] << 16 >> 16))
                                                ? 1
                                                : 0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sltiu $t1,$t2,-100",
                        "Set on less than immediate unsigned : Sets $t1 = 1 if $t2 < immediate using unsigned comparison. The immediate is sign-extended to 32 bits first, then interpreted as unsigned. Commonly used to test if a value is below a limit without needing a separate register.",
                        BasicInstructionFormat.I_FORMAT,
                        "001011 sssss fffff tttttttttttttttt",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int first = RegisterFile.getValue(operands[1]);
                                // 16 bit immediate value in operands[2] is sign-extended
                                int second = operands[2] << 16 >> 16;
                                if (first >= 0 && second >= 0 || first < 0 && second < 0) {
                                    RegisterFile.updateRegister(operands[0],
                                            (first < second) ? 1 : 0);
                                } else {
                                    RegisterFile.updateRegister(operands[0],
                                            (first >= 0) ? 1 : 0);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movn $t1,$t2,$t3",
                        "Move if not zero : Copies $t2 into $t1 only if $t3 != 0. Allows branchless conditional assignment — avoids a branch instruction when you only need to update a register conditionally.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 001011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[2]) != 0)
                                    RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movz $t1,$t2,$t3",
                        "Move if zero : Copies $t2 into $t1 only if $t3 == 0. Allows branchless conditional assignment — avoids a branch instruction when you only need to update a register conditionally.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttttt fffff 00000 001010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[2]) == 0)
                                    RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movf $t1,$t2",
                        "Move if FP condition flag 0 is false : Copies $t2 into $t1 if floating-point condition flag 0 is false (0). Allows integer register updates to be conditioned on the result of a previous FP comparison (e.g., c.eq.s, c.lt.s).",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss 000 00 fffff 00000 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(0) == 0)
                                    RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movf $t1,$t2,1",
                        "Move if specified FP condition flag is false : Copies $t2 into $t1 if FP condition flag N (specified by the third operand) is false (0). Allows results of multiple simultaneous FP comparisons to control integer register moves.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttt 00 fffff 00000 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(operands[2]) == 0)
                                    RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movt $t1,$t2",
                        "Move if FP condition flag 0 is true : Copies $t2 into $t1 if floating-point condition flag 0 is true (1). Allows integer register updates to be conditioned on the result of a previous FP comparison (e.g., c.eq.s, c.lt.s).",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss 000 01 fffff 00000 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(0) == 1)
                                    RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movt $t1,$t2,1",
                        "Move if specified FP condition flag is true : Copies $t2 into $t1 if FP condition flag N (specified by the third operand) is true (1). Allows results of multiple simultaneous FP comparisons to control integer register moves.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss ttt 01 fffff 00000 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(operands[2]) == 1)
                                    RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("break 100",
                        "Breakpoint with code : Raises a breakpoint exception with the given numeric code. Halts execution in this simulator. Useful for embedding debug checkpoints in code, similar to a hardware breakpoint.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 ffffffffffffffffffff 001101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException { // At this time I
                                // don't have
                                // exception
                                // processing or trap
                                // handlers
                                // so will just halt
                                // execution with a
                                // message.
                                int[] operands = statement.getOperands();
                                throw new ProcessingException(statement, "break instruction executed; code = " +
                                        operands[0] + ".", Exceptions.BREAKPOINT_EXCEPTION);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("break",
                        "Breakpoint : Raises a breakpoint exception (no code). Halts execution in this simulator. Used as an unconditional software breakpoint — the zero-code variant of 'break N'.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 00000 00000 00000 001101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException { // At this time I
                                // don't have
                                // exception
                                // processing or trap
                                // handlers
                                // so will just halt
                                // execution with a
                                // message.
                                throw new ProcessingException(statement, "break instruction executed; no code given.",
                                        Exceptions.BREAKPOINT_EXCEPTION);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("syscall",
                        "System call : Invokes an OS service identified by the integer in $v0. Common services: 1=print integer ($a0), 4=print string (addr in $a0), 5=read integer (result in $v0), 8=read string ($a0=buf addr, $a1=len), 10=exit. See the Help menu for the full list.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 00000 00000 00000 00000 001100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                findAndSimulateSyscall(RegisterFile.getValue(2), statement);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("j target",
                        "Jump unconditionally : Transfers execution to 'target'. The destination is encoded as a 26-bit word address; the upper 4 bits of PC are combined with this value, so the target must be in the same 256 MB region as the instruction following the jump.",
                        BasicInstructionFormat.J_FORMAT,
                        "000010 ffffffffffffffffffffffffff",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                processJump(
                                        ((RegisterFile.getProgramCounter() & 0xF0000000)
                                                | (operands[0] << 2)));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("jr $t1",
                        "Jump register : Transfers execution to the address stored in $t1. Most commonly used as 'jr $ra' to return from a function call made with 'jal' or 'jalr'.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff 00000 00000 00000 001000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                //pop stack if return address
                                if(operands[0] == 31){
                                    Stack.popCallStack();
                                }
                                processJump(RegisterFile.getValue(operands[0]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("jal target",
                        "Jump and link : Saves the return address (PC + 4) in $ra, then jumps to 'target'. The standard function-call instruction in MIPS. The called function returns with 'jr $ra'. Same address-range restriction as 'j'.",
                        BasicInstructionFormat.J_FORMAT,
                        "000011 ffffffffffffffffffffffffff",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                processReturnAddress(31);// RegisterFile.updateRegister(31, RegisterFile.getProgramCounter());
                                int address = ((RegisterFile.getProgramCounter() & 0xF0000000)
                                        | (operands[0] << 2));
                                Stack.pushCallStack(StackFrame.fromGlobalState(address));
                                processJump(address);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("jalr $t1,$t2",
                        "Jump and link register : Saves the return address (PC + 4) in $t1, then jumps to the address in $t2. Allows calling a function whose address is only known at runtime (function pointer). Return with 'jr $t1'.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 sssss 00000 fffff 00000 001001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                processReturnAddress(operands[0]);// RegisterFile.updateRegister(operands[0],
                                // RegisterFile.getProgramCounter());
                                Stack.pushCallStack(StackFrame.fromGlobalState(RegisterFile.getValue(operands[1])));
                                processJump(RegisterFile.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("jalr $t1",
                        "Jump and link register : Saves the return address (PC + 4) in $ra, then jumps to the address in $t1. One-operand shorthand for 'jalr $ra,$t1'. Used to call a function via a pointer stored in a register.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff 00000 11111 00000 001001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                processReturnAddress(31);// RegisterFile.updateRegister(31, RegisterFile.getProgramCounter());
                                Stack.pushCallStack(StackFrame.fromGlobalState(RegisterFile.getValue(operands[0])));
                                processJump(RegisterFile.getValue(operands[0]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lb $t1,-100($t2)",
                        "Load byte (sign-extended) : Reads one byte from memory at address ($t2 + offset), sign-extends it to 32 bits, and stores the result in $t1. A byte with value 0xFF becomes -1 (0xFFFFFFFF) in $t1. Use 'lbu' if you want zero-extension (0 to 255 range).",
                        BasicInstructionFormat.I_FORMAT,
                        "100000 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    RegisterFile.updateRegister(operands[0],
                                            Globals.memory.getByte(
                                                    RegisterFile.getValue(operands[2])
                                                            + (operands[1] << 16 >> 16)) << 24 >> 24);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lh $t1,-100($t2)",
                        "Load halfword (sign-extended) : Reads a 16-bit value from memory at address ($t2 + offset), sign-extends it to 32 bits, and stores the result in $t1. The address must be halfword-aligned (divisible by 2). Use 'lhu' for zero-extension.",
                        BasicInstructionFormat.I_FORMAT,
                        "100001 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    RegisterFile.updateRegister(operands[0],
                                            Globals.memory.getHalf(
                                                    RegisterFile.getValue(operands[2])
                                                            + (operands[1] << 16 >> 16)) << 16 >> 16);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lhu $t1,-100($t2)",
                        "Load halfword unsigned (zero-extended) : Reads a 16-bit value from memory at address ($t2 + offset), zero-extends it to 32 bits (upper 16 bits of $t1 are always 0), and stores the result in $t1. The address must be halfword-aligned (divisible by 2).",
                        BasicInstructionFormat.I_FORMAT,
                        "100101 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    // offset is sign-extended and loaded halfword value is zero-extended
                                    RegisterFile.updateRegister(operands[0],
                                            Globals.memory.getHalf(
                                                    RegisterFile.getValue(operands[2])
                                                            + (operands[1] << 16 >> 16))
                                                    & 0x0000ffff);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lbu $t1,-100($t2)",
                        "Load byte unsigned (zero-extended) : Reads one byte from memory at address ($t2 + offset), zero-extends it to 32 bits (upper 24 bits of $t1 are always 0), and stores the result in $t1. Values range from 0 to 255. Use 'lb' if you need sign-extension.",
                        BasicInstructionFormat.I_FORMAT,
                        "100100 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    RegisterFile.updateRegister(operands[0],
                                            Globals.memory.getByte(
                                                    RegisterFile.getValue(operands[2])
                                                            + (operands[1] << 16 >> 16))
                                                    & 0x000000ff);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sb $t1,-100($t2)",
                        "Store byte : Writes the lowest 8 bits of $t1 to memory at address ($t2 + offset). The upper 24 bits of $t1 are ignored. Useful for writing single characters or packed byte arrays.",
                        BasicInstructionFormat.I_FORMAT,
                        "101000 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    Globals.memory.setByte(
                                            RegisterFile.getValue(operands[2])
                                                    + (operands[1] << 16 >> 16),
                                            RegisterFile.getValue(operands[0])
                                                    & 0x000000ff);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sh $t1,-100($t2)",
                        "Store halfword : Writes the lowest 16 bits of $t1 to memory at address ($t2 + offset). The upper 16 bits of $t1 are ignored. The address must be halfword-aligned (divisible by 2).",
                        BasicInstructionFormat.I_FORMAT,
                        "101001 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    Globals.memory.setHalf(
                                            RegisterFile.getValue(operands[2])
                                                    + (operands[1] << 16 >> 16),
                                            RegisterFile.getValue(operands[0])
                                                    & 0x0000ffff);
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("clo $t1,$t2",
                        "Count leading ones : Sets $t1 to the number of consecutive 1 bits in $t2, counted from the most-significant bit downward. For example, if $t2 = 0b11100000..., then $t1 = 3. Returns 32 if all bits are 1.",
                        BasicInstructionFormat.R_FORMAT,
                        // MIPS32 requires rd (first) operand to appear twice in machine code.
                        // It has to be same as rt (third) operand in machine code, but the
                        // source statement does not have or permit third operand.
                        // In the machine code, rd and rt are adjacent, but my mask
                        // substitution cannot handle adjacent placement of the same source
                        // operand (e.g. "... sssss fffff fffff ...") because it would interpret
                        // the mask to be the total length of both (10 bits). I could code it
                        // to have 3 operands then define a pseudo-instruction of two operands
                        // to translate into this, but then both would show up in instruction set
                        // list and I don't want that. So I will use the convention of Computer
                        // Organization and Design 3rd Edition, Appendix A, and code the rt bits
                        // as 0's. The generated code does not match SPIM and would not run
                        // on a real MIPS machine but since I am providing no means of storing
                        // the binary code that is not really an issue.
                        "011100 sssss 00000 fffff 00000 100001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int value = RegisterFile.getValue(operands[1]);
                                int leadingOnes = 0;
                                int bitPosition = 31;
                                while (Binary.bitValue(value, bitPosition) == 1 && bitPosition >= 0) {
                                    leadingOnes++;
                                    bitPosition--;
                                }
                                RegisterFile.updateRegister(operands[0], leadingOnes);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("clz $t1,$t2",
                        "Count leading zeros : Sets $t1 to the number of consecutive 0 bits in $t2, counted from the most-significant bit downward. For example, if $t2 = 0b00010000..., then $t1 = 3. Returns 32 if $t2 is 0. Often used to compute floor(log2(n)) or to normalize values.",
                        BasicInstructionFormat.R_FORMAT,
                        // See comments for "clo" instruction above. They apply here too.
                        "011100 sssss 00000 fffff 00000 100000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int value = RegisterFile.getValue(operands[1]);
                                int leadingZeros = 0;
                                int bitPosition = 31;
                                while (Binary.bitValue(value, bitPosition) == 0 && bitPosition >= 0) {
                                    leadingZeros++;
                                    bitPosition--;
                                }
                                RegisterFile.updateRegister(operands[0], leadingZeros);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mfc0 $t1,$8",
                        "Move from Coprocessor 0 : Reads a Coprocessor 0 (CP0) control register and places its value in general-purpose register $t1. CP0 registers hold exception/interrupt state: register 8 (BadVAddr), 12 (Status), 13 (Cause), 14 (EPC), etc.",
                        BasicInstructionFormat.R_FORMAT,
                        "010000 00000 fffff sssss 00000 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0],
                                        Coprocessor0.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mtc0 $t1,$8",
                        "Move to Coprocessor 0 : Writes the value of general-purpose register $t1 into a Coprocessor 0 (CP0) control register. Used by exception handlers to modify the Status and Cause registers or to clear the EPC.",
                        BasicInstructionFormat.R_FORMAT,
                        "010000 00100 fffff sssss 00000 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                Coprocessor0.updateRegister(operands[1],
                                        RegisterFile.getValue(operands[0]));
                            }
                        }));

        /////////////////////// Floating Point Instructions Start Here ////////////////
        instructionList.add(
                new BasicInstruction("add.s $f0,$f1,$f3",
                        "Floating-point add (single) : Sets $f0 = $f1 + $f3 using IEEE 754 single-precision (32-bit) arithmetic. Single-precision values have about 7 significant decimal digits. The result is stored in one FP register.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fffff 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float add1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float add2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                float sum = add1 + add2;
                                // overflow detected when sum is positive or negative infinity.
                                /*
                                 * if (sum == Float.NEGATIVE_INFINITY || sum == Float.POSITIVE_INFINITY) {
                                 * throw new ProcessingException(statement,"arithmetic overflow");
                                 * }
                                 */
                                Coprocessor1.updateRegister(operands[0], Float.floatToIntBits(sum));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sub.s $f0,$f1,$f3",
                        "Floating-point subtract (single) : Sets $f0 = $f1 - $f3 using IEEE 754 single-precision (32-bit) arithmetic.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fffff 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float sub1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float sub2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                float diff = sub1 - sub2;
                                Coprocessor1.updateRegister(operands[0], Float.floatToIntBits(diff));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mul.s $f0,$f1,$f3",
                        "Floating-point multiply (single) : Sets $f0 = $f1 * $f3 using IEEE 754 single-precision (32-bit) arithmetic.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fffff 000010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float mul1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float mul2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                float prod = mul1 * mul2;
                                Coprocessor1.updateRegister(operands[0], Float.floatToIntBits(prod));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("div.s $f0,$f1,$f3",
                        "Floating-point divide (single) : Sets $f0 = $f1 / $f3 using IEEE 754 single-precision (32-bit) arithmetic. Dividing by zero produces +/-Infinity according to IEEE 754.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fffff 000011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float div1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float div2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                float quot = div1 / div2;
                                Coprocessor1.updateRegister(operands[0], Float.floatToIntBits(quot));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sqrt.s $f0,$f1",
                        "Floating-point square root (single) : Sets $f0 = sqrt($f1) using single-precision arithmetic. Returns NaN if $f1 is negative.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 000100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float value = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                int floatSqrt = 0;
                                if (value < 0.0f) {
                                    // This is subject to refinement later. Release 4.0 defines floor, ceil, trunc,
                                    // round
                                    // to act silently rather than raise Invalid Operation exception, so sqrt should
                                    // do the
                                    // same. An intermediate step would be to define a setting for FCSR Invalid
                                    // Operation
                                    // flag, but the best solution is to simulate the FCSR register itself.
                                    // FCSR = Floating point unit Control and Status Register. DPS 10-Aug-2010
                                    floatSqrt = Float.floatToIntBits(Float.NaN);
                                    // throw new ProcessingException(statement, "Invalid Operation: sqrt of negative
                                    // number");
                                } else {
                                    floatSqrt = Float.floatToIntBits((float) Math.sqrt(value));
                                }
                                Coprocessor1.updateRegister(operands[0], floatSqrt);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("floor.w.s $f0,$f1",
                        "Floor single-precision to word : Converts the single-precision float in $f1 to a 32-bit integer by rounding toward negative infinity (i.e., always truncates toward -inf), and stores the result in $f0. Use 'mfc1' to move the result to a general-purpose register.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 001111",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float floatValue = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                int floor = (int) Math.floor(floatValue);
                                // DPS 28-July-2010: Since MARS does not simulate the FSCR, I will take the
                                // default
                                // action of setting the result to 2^31-1, if the value is outside the 32 bit
                                // range.
                                if (Float.isNaN(floatValue)
                                        || Float.isInfinite(floatValue)
                                        || floatValue < (float) Integer.MIN_VALUE
                                        || floatValue > (float) Integer.MAX_VALUE) {
                                    floor = Integer.MAX_VALUE;
                                }
                                Coprocessor1.updateRegister(operands[0], floor);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("ceil.w.s $f0,$f1",
                        "Ceiling single-precision to word : Converts the single-precision float in $f1 to a 32-bit integer by rounding toward positive infinity, and stores the result in $f0.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 001110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float floatValue = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                int ceiling = (int) Math.ceil(floatValue);
                                // DPS 28-July-2010: Since MARS does not simulate the FSCR, I will take the
                                // default
                                // action of setting the result to 2^31-1, if the value is outside the 32 bit
                                // range.
                                if (Float.isNaN(floatValue)
                                        || Float.isInfinite(floatValue)
                                        || floatValue < (float) Integer.MIN_VALUE
                                        || floatValue > (float) Integer.MAX_VALUE) {
                                    ceiling = Integer.MAX_VALUE;
                                }
                                Coprocessor1.updateRegister(operands[0], ceiling);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("round.w.s $f0,$f1",
                        "Round single-precision to word : Converts the single-precision float in $f1 to the nearest 32-bit integer (round half to even, per IEEE 754), and stores the result in $f0.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 001100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException { // MIPS32
                                // documentation (and
                                // IEEE 754) states
                                // that round rounds
                                // to the nearest but
                                // when
                                // both are equally
                                // near it rounds to
                                // the even one! SPIM
                                // rounds -4.5, -5.5,
                                // 4.5 and 5.5 to
                                // (-4, -5, 5, 6).
                                // Curiously, it
                                // rounds -5.1 to -4
                                // and -5.6 to -5.
                                // Until MARS 3.5, I
                                // used Math.round,
                                // which rounds to
                                // nearest but when
                                // both are
                                // equal it rounds
                                // toward positive
                                // infinity. With
                                // Release 3.5, I
                                // painstakingly
                                // carry out the MIPS
                                // and IEEE 754
                                // standard.
                                int[] operands = statement.getOperands();
                                float floatValue = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                int below = 0, above = 0, round = Math.round(floatValue);
                                // According to MIPS32 spec, if any of these conditions is true, set
                                // Invalid Operation in the FCSR (Floating point Control/Status Register) and
                                // set result to be 2^31-1. MARS does not implement this register (as of release
                                // 3.4.1).
                                // It also mentions the "Invalid Operation Enable bit" in FCSR, that, if set,
                                // results
                                // in immediate exception instead of default value.
                                if (Float.isNaN(floatValue)
                                        || Float.isInfinite(floatValue)
                                        || floatValue < (float) Integer.MIN_VALUE
                                        || floatValue > (float) Integer.MAX_VALUE) {
                                    round = Integer.MAX_VALUE;
                                } else {
                                    Float floatObj = Float.valueOf(floatValue);
                                    // If we are EXACTLY in the middle, then round to even! To determine this,
                                    // find next higher integer and next lower integer, then see if distances
                                    // are exactly equal.
                                    if (floatValue < 0.0F) {
                                        above = floatObj.intValue(); // truncates
                                        below = above - 1;
                                    } else {
                                        below = floatObj.intValue(); // truncates
                                        above = below + 1;
                                    }
                                    if (floatValue - below == above - floatValue) { // exactly in the middle?
                                        round = (above % 2 == 0) ? above : below;
                                    }
                                }
                                Coprocessor1.updateRegister(operands[0], round);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("trunc.w.s $f0,$f1",
                        "Truncate single-precision to word : Converts the single-precision float in $f1 to a 32-bit integer by rounding toward zero (drops the fractional part), and stores the result in $f0. Equivalent to C-style (int) cast.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 001101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float floatValue = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                int truncate = (int) floatValue;// Typecasting will round toward zero, the correct action
                                // DPS 28-July-2010: Since MARS does not simulate the FSCR, I will take the
                                // default
                                // action of setting the result to 2^31-1, if the value is outside the 32 bit
                                // range.
                                if (Float.isNaN(floatValue)
                                        || Float.isInfinite(floatValue)
                                        || floatValue < (float) Integer.MIN_VALUE
                                        || floatValue > (float) Integer.MAX_VALUE) {
                                    truncate = Integer.MAX_VALUE;
                                }
                                Coprocessor1.updateRegister(operands[0], truncate);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("add.d $f2,$f4,$f6",
                        "Floating-point add (double) : Sets $f2 = $f4 + $f6 using IEEE 754 double-precision (64-bit) arithmetic. Double-precision values have about 15 significant decimal digits. All three register numbers must be even, as each double occupies a pair of FP registers.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fffff 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "all registers must be even-numbered");
                                }
                                double add1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double add2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                double sum = add1 + add2;
                                long longSum = Double.doubleToLongBits(sum);
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(longSum));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(longSum));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sub.d $f2,$f4,$f6",
                        "Floating-point subtract (double) : Sets $f2 = $f4 - $f6 using IEEE 754 double-precision (64-bit) arithmetic. All register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fffff 000001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "all registers must be even-numbered");
                                }
                                double sub1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double sub2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                double diff = sub1 - sub2;
                                long longDiff = Double.doubleToLongBits(diff);
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(longDiff));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(longDiff));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mul.d $f2,$f4,$f6",
                        "Floating-point multiply (double) : Sets $f2 = $f4 * $f6 using IEEE 754 double-precision (64-bit) arithmetic. All register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fffff 000010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "all registers must be even-numbered");
                                }
                                double mul1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double mul2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                double prod = mul1 * mul2;
                                long longProd = Double.doubleToLongBits(prod);
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(longProd));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(longProd));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("div.d $f2,$f4,$f6",
                        "Floating-point divide (double) : Sets $f2 = $f4 / $f6 using IEEE 754 double-precision (64-bit) arithmetic. All register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fffff 000011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "all registers must be even-numbered");
                                }
                                double div1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double div2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                double quot = div1 / div2;
                                long longQuot = Double.doubleToLongBits(quot);
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(longQuot));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(longQuot));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("sqrt.d $f2,$f4",
                        "Floating-point square root (double) : Sets $f2 = sqrt($f4) using double-precision arithmetic. Both register numbers must be even. Returns NaN if $f4 is negative.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 000100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double value = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                long longSqrt = 0;
                                if (value < 0.0) {
                                    // This is subject to refinement later. Release 4.0 defines floor, ceil, trunc,
                                    // round
                                    // to act silently rather than raise Invalid Operation exception, so sqrt should
                                    // do the
                                    // same. An intermediate step would be to define a setting for FCSR Invalid
                                    // Operation
                                    // flag, but the best solution is to simulate the FCSR register itself.
                                    // FCSR = Floating point unit Control and Status Register. DPS 10-Aug-2010
                                    longSqrt = Double.doubleToLongBits(Double.NaN);
                                    // throw new ProcessingException(statement, "Invalid Operation: sqrt of negative
                                    // number");
                                } else {
                                    longSqrt = Double.doubleToLongBits(Math.sqrt(value));
                                }
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(longSqrt));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(longSqrt));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("floor.w.d $f1,$f2",
                        "Floor double-precision to word : Converts the double-precision float in $f2 (even-numbered register) to a 32-bit integer by rounding toward negative infinity, storing the result in $f1.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 001111",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "second register must be even-numbered");
                                }
                                double doubleValue = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                // DPS 27-July-2010: Since MARS does not simulate the FSCR, I will take the
                                // default
                                // action of setting the result to 2^31-1, if the value is outside the 32 bit
                                // range.
                                int floor = (int) Math.floor(doubleValue);
                                if (Double.isNaN(doubleValue)
                                        || Double.isInfinite(doubleValue)
                                        || doubleValue < (double) Integer.MIN_VALUE
                                        || doubleValue > (double) Integer.MAX_VALUE) {
                                    floor = Integer.MAX_VALUE;
                                }
                                Coprocessor1.updateRegister(operands[0], floor);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("ceil.w.d $f1,$f2",
                        "Ceiling double-precision to word : Converts the double-precision float in $f2 (even-numbered register) to a 32-bit integer by rounding toward positive infinity, storing the result in $f1.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 001110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "second register must be even-numbered");
                                }
                                double doubleValue = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                // DPS 27-July-2010: Since MARS does not simulate the FSCR, I will take the
                                // default
                                // action of setting the result to 2^31-1, if the value is outside the 32 bit
                                // range.
                                int ceiling = (int) Math.ceil(doubleValue);
                                if (Double.isNaN(doubleValue)
                                        || Double.isInfinite(doubleValue)
                                        || doubleValue < (double) Integer.MIN_VALUE
                                        || doubleValue > (double) Integer.MAX_VALUE) {
                                    ceiling = Integer.MAX_VALUE;
                                }
                                Coprocessor1.updateRegister(operands[0], ceiling);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("round.w.d $f1,$f2",
                        "Round double-precision to word : Converts the double-precision float in $f2 (even-numbered register) to the nearest 32-bit integer (round half to even, per IEEE 754), storing the result in $f1.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 001100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException { // See comments in
                                // round.w.s above,
                                // concerning MIPS
                                // and IEEE 754
                                // standard.
                                // Until MARS 3.5, I
                                // used Math.round,
                                // which rounds to
                                // nearest but when
                                // both are
                                // equal it rounds
                                // toward positive
                                // infinity. With
                                // Release 3.5, I
                                // painstakingly
                                // carry out the MIPS
                                // and IEEE 754
                                // standard (round to
                                // nearest/even).
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "second register must be even-numbered");
                                }
                                double doubleValue = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                int below = 0, above = 0;
                                int round = (int) Math.round(doubleValue);
                                // See comments in round.w.s above concerning FSCR...
                                if (Double.isNaN(doubleValue)
                                        || Double.isInfinite(doubleValue)
                                        || doubleValue < (double) Integer.MIN_VALUE
                                        || doubleValue > (double) Integer.MAX_VALUE) {
                                    round = Integer.MAX_VALUE;
                                } else {
                                    Double doubleObj = Double.valueOf(doubleValue);
                                    // If we are EXACTLY in the middle, then round to even! To determine this,
                                    // find next higher integer and next lower integer, then see if distances
                                    // are exactly equal.
                                    if (doubleValue < 0.0) {
                                        above = doubleObj.intValue(); // truncates
                                        below = above - 1;
                                    } else {
                                        below = doubleObj.intValue(); // truncates
                                        above = below + 1;
                                    }
                                    if (doubleValue - below == above - doubleValue) { // exactly in the middle?
                                        round = (above % 2 == 0) ? above : below;
                                    }
                                }
                                Coprocessor1.updateRegister(operands[0], round);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("trunc.w.d $f1,$f2",
                        "Truncate double-precision to word : Converts the double-precision float in $f2 (even-numbered register) to a 32-bit integer by rounding toward zero (dropping the fractional part), storing the result in $f1.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 001101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "second register must be even-numbered");
                                }
                                double doubleValue = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                // DPS 27-July-2010: Since MARS does not simulate the FSCR, I will take the
                                // default
                                // action of setting the result to 2^31-1, if the value is outside the 32 bit
                                // range.
                                int truncate = (int) doubleValue; // Typecasting will round toward zero, the correct action.
                                if (Double.isNaN(doubleValue)
                                        || Double.isInfinite(doubleValue)
                                        || doubleValue < (double) Integer.MIN_VALUE
                                        || doubleValue > (double) Integer.MAX_VALUE) {
                                    truncate = Integer.MAX_VALUE;
                                }
                                Coprocessor1.updateRegister(operands[0], truncate);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bc1t label",
                        "Branch if FP condition flag 0 is true : Branches to 'label' if Coprocessor 1 condition flag 0 is true (1). Set by a preceding FP compare instruction such as 'c.eq.s' or 'c.lt.d'. Note: mnemonic is BC1T (branch Coprocessor-1 True), not BCLT.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "010001 01000 00001 ffffffffffffffff",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(0) == 1) {
                                    processBranch(operands[0]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bc1t 1,label",
                        "Branch if specified FP condition flag is true : Branches to 'label' if the FP condition flag specified by the immediate operand is true (1). Allows branching on any of the 8 condition flags set by FP compare instructions.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "010001 01000 fff 01 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(operands[0]) == 1) {
                                    processBranch(operands[1]);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("bc1f label",
                        "Branch if FP condition flag 0 is false : Branches to 'label' if Coprocessor 1 condition flag 0 is false (0). Use after a FP compare instruction when you want to branch on the condition NOT being met. Note: mnemonic is BC1F, not BCLF.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "010001 01000 00000 ffffffffffffffff",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(0) == 0) {
                                    processBranch(operands[0]);
                                }

                            }
                        }));
        instructionList.add(
                new BasicInstruction("bc1f 1,label",
                        "Branch if specified FP condition flag is false : Branches to 'label' if the FP condition flag specified by the immediate operand is false (0). Allows branching on the unset result of any of the 8 FP condition flags.",
                        BasicInstructionFormat.I_BRANCH_FORMAT,
                        "010001 01000 fff 00 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(operands[0]) == 0) {
                                    processBranch(operands[1]);
                                }

                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.eq.s $f0,$f1",
                        "FP compare equal (single) : Sets FP condition flag 0 to true if $f0 == $f1 (single-precision), false otherwise. Use 'bc1t' to branch when equal, or 'bc1f' to branch when not equal.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 sssss fffff 00000 110010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[0]));
                                float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                if (op1 == op2)
                                    Coprocessor1.setConditionFlag(0);
                                else
                                    Coprocessor1.clearConditionFlag(0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.eq.s 1,$f0,$f1",
                        "FP compare equal (single, flagged) : Sets FP condition flag N (specified by the first operand) to true if $f0 == $f1 (single-precision), false otherwise. Allows multiple simultaneous FP comparisons using different flags.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fff 00 11 0010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                if (op1 == op2)
                                    Coprocessor1.setConditionFlag(operands[0]);
                                else
                                    Coprocessor1.clearConditionFlag(operands[0]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.le.s $f0,$f1",
                        "FP compare less or equal (single) : Sets FP condition flag 0 to true if $f0 <= $f1 (single-precision), false otherwise.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 sssss fffff 00000 111110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[0]));
                                float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                if (op1 <= op2)
                                    Coprocessor1.setConditionFlag(0);
                                else
                                    Coprocessor1.clearConditionFlag(0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.le.s 1,$f0,$f1",
                        "FP compare less or equal (single, flagged) : Sets FP condition flag N (specified by the first operand) to true if $f0 <= $f1 (single-precision), false otherwise.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fff 00 111110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                if (op1 <= op2)
                                    Coprocessor1.setConditionFlag(operands[0]);
                                else
                                    Coprocessor1.clearConditionFlag(operands[0]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.lt.s $f0,$f1",
                        "FP compare less than (single) : Sets FP condition flag 0 to true if $f0 < $f1 (single-precision), false otherwise.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 sssss fffff 00000 111100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[0]));
                                float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                if (op1 < op2)
                                    Coprocessor1.setConditionFlag(0);
                                else
                                    Coprocessor1.clearConditionFlag(0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.lt.s 1,$f0,$f1",
                        "FP compare less than (single, flagged) : Sets FP condition flag N (specified by the first operand) to true if $f0 < $f1 (single-precision), false otherwise.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fff 00 111100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                float op1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
                                float op2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
                                if (op1 < op2)
                                    Coprocessor1.setConditionFlag(operands[0]);
                                else
                                    Coprocessor1.clearConditionFlag(operands[0]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.eq.d $f2,$f4",
                        "FP compare equal (double) : Sets FP condition flag 0 to true if $f2 == $f4 (double-precision), false otherwise. Both register numbers must be even. Use 'bc1t'/'bc1f' to branch on the result.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 sssss fffff 00000 110010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double op1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[0] + 1), Coprocessor1.getValue(operands[0])));
                                double op2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                if (op1 == op2)
                                    Coprocessor1.setConditionFlag(0);
                                else
                                    Coprocessor1.clearConditionFlag(0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.eq.d 1,$f2,$f4",
                        "FP compare equal (double, flagged) : Sets FP condition flag N (specified by the first operand) to true if $f2 == $f4 (double-precision), false otherwise. Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fff 00 110010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double op1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double op2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                if (op1 == op2)
                                    Coprocessor1.setConditionFlag(operands[0]);
                                else
                                    Coprocessor1.clearConditionFlag(operands[0]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.le.d $f2,$f4",
                        "FP compare less or equal (double) : Sets FP condition flag 0 to true if $f2 <= $f4 (double-precision), false otherwise. Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 sssss fffff 00000 111110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double op1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[0] + 1), Coprocessor1.getValue(operands[0])));
                                double op2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                if (op1 <= op2)
                                    Coprocessor1.setConditionFlag(0);
                                else
                                    Coprocessor1.clearConditionFlag(0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.le.d 1,$f2,$f4",
                        "FP compare less or equal (double, flagged) : Sets FP condition flag N (specified by the first operand) to true if $f2 <= $f4 (double-precision), false otherwise. Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fff 00 111110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double op1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double op2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                if (op1 <= op2)
                                    Coprocessor1.setConditionFlag(operands[0]);
                                else
                                    Coprocessor1.clearConditionFlag(operands[0]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.lt.d $f2,$f4",
                        "FP compare less than (double) : Sets FP condition flag 0 to true if $f2 < $f4 (double-precision), false otherwise. Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 sssss fffff 00000 111100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double op1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[0] + 1), Coprocessor1.getValue(operands[0])));
                                double op2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                if (op1 < op2)
                                    Coprocessor1.setConditionFlag(0);
                                else
                                    Coprocessor1.clearConditionFlag(0);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("c.lt.d 1,$f2,$f4",
                        "FP compare less than (double, flagged) : Sets FP condition flag N (specified by the first operand) to true if $f2 < $f4 (double-precision), false otherwise. Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fff 00 111100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                double op1 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                double op2 = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[2] + 1), Coprocessor1.getValue(operands[2])));
                                if (op1 < op2)
                                    Coprocessor1.setConditionFlag(operands[0]);
                                else
                                    Coprocessor1.clearConditionFlag(operands[0]);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("abs.s $f0,$f1",
                        "Floating-point absolute value (single) : Sets $f0 to the absolute value of $f1 (single-precision). Works by clearing the sign bit. Does not raise exceptions for NaN or infinity.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 000101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // I need only clear the high order bit!
                                Coprocessor1.updateRegister(operands[0],
                                        Coprocessor1.getValue(operands[1]) & Integer.MAX_VALUE);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("abs.d $f2,$f4",
                        "Floating-point absolute value (double) : Sets $f2 to the absolute value of $f4 (double-precision). Both register numbers must be even. Works by clearing the sign bit of the high word.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 000101",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                // I need only clear the high order bit of high word register!
                                Coprocessor1.updateRegister(operands[0] + 1,
                                        Coprocessor1.getValue(operands[1] + 1) & Integer.MAX_VALUE);
                                Coprocessor1.updateRegister(operands[0],
                                        Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("cvt.d.s $f2,$f1",
                        "Convert single-precision to double-precision : Converts the single-precision float in $f1 to double-precision (64-bit IEEE 754) and stores the result in $f2. $f2 must be even-numbered. No data is lost because double has more precision.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 100001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1) {
                                    throw new ProcessingException(statement, "first register must be even-numbered");
                                }
                                // convert single precision in $f1 to double stored in $f2
                                long result = Double.doubleToLongBits(
                                        (double) Float.intBitsToFloat(Coprocessor1.getValue(operands[1])));
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(result));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(result));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("cvt.d.w $f2,$f1",
                        "Convert integer word to double-precision : Treats the 32-bit integer stored in $f1 as a signed integer and converts it to a double-precision float stored in $f2. $f2 must be even-numbered.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10100 00000 sssss fffff 100001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1) {
                                    throw new ProcessingException(statement, "first register must be even-numbered");
                                }
                                // convert integer to double (interpret $f1 value as int?)
                                long result = Double.doubleToLongBits(
                                        (double) Coprocessor1.getValue(operands[1]));
                                Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(result));
                                Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(result));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("cvt.s.d $f1,$f2",
                        "Convert double-precision to single-precision : Converts the double-precision float in $f2 to single-precision and stores the result in $f1. $f2 must be even-numbered. Precision may be lost since single has fewer significant digits.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 100000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // convert double precision in $f2 to single stored in $f1
                                if (operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "second register must be even-numbered");
                                }
                                double val = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                Coprocessor1.updateRegister(operands[0], Float.floatToIntBits((float) val));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("cvt.s.w $f0,$f1",
                        "Convert integer word to single-precision : Treats the 32-bit integer stored in $f1 as a signed integer and converts it to a single-precision float stored in $f0.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10100 00000 sssss fffff 100000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // convert integer to single (interpret $f1 value as int?)
                                Coprocessor1.updateRegister(operands[0],
                                        Float.floatToIntBits((float) Coprocessor1.getValue(operands[1])));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("cvt.w.d $f1,$f2",
                        "Convert double-precision to integer word : Converts the double-precision float in $f2 to a signed 32-bit integer by truncation and stores the result in $f1. $f2 must be even-numbered. Use 'mfc1' to move the integer result to a general-purpose register.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 100100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // convert double precision in $f2 to integer stored in $f1
                                if (operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "second register must be even-numbered");
                                }
                                double val = Double.longBitsToDouble(Binary.twoIntsToLong(
                                        Coprocessor1.getValue(operands[1] + 1), Coprocessor1.getValue(operands[1])));
                                Coprocessor1.updateRegister(operands[0], (int) val);
                            }
                        }));
        instructionList.add(
                new BasicInstruction("cvt.w.s $f0,$f1",
                        "Convert single-precision to integer word : Converts the single-precision float in $f1 to a signed 32-bit integer by truncation and stores the result in $f0. Use 'mfc1' to move the integer result to a general-purpose register.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 100100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                // convert single precision in $f1 to integer stored in $f0
                                Coprocessor1.updateRegister(operands[0],
                                        (int) Float.intBitsToFloat(Coprocessor1.getValue(operands[1])));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mov.d $f2,$f4",
                        "Move double-precision FP register : Copies the double-precision value in $f4 into $f2. Both register numbers must be even. Does not perform any conversion.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 000110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movf.d $f2,$f4",
                        "Move double-precision FP if condition flag 0 is false : Copies the double-precision value in $f4 into $f2 if FP condition flag 0 is false (0). Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 000 00 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                if (Coprocessor1.getConditionFlag(0) == 0) {
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movf.d $f2,$f4,1",
                        "Move double-precision FP if specified condition flag is false : Copies the double-precision value in $f4 into $f2 if FP condition flag N (specified by the immediate) is false (0). Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttt 00 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                if (Coprocessor1.getConditionFlag(operands[2]) == 0) {
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movt.d $f2,$f4",
                        "Move double-precision FP if condition flag 0 is true : Copies the double-precision value in $f4 into $f2 if FP condition flag 0 is true (1). Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 000 01 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                if (Coprocessor1.getConditionFlag(0) == 1) {
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movt.d $f2,$f4,1",
                        "Move double-precision FP if specified condition flag is true : Copies the double-precision value in $f4 into $f2 if FP condition flag N (specified by the immediate) is true (1). Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttt 01 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                if (Coprocessor1.getConditionFlag(operands[2]) == 1) {
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movn.d $f2,$f4,$t3",
                        "Move double-precision FP if integer register not zero : Copies the double-precision value in $f4 into $f2 if $t3 != 0. Both FP register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fffff 010011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                if (RegisterFile.getValue(operands[2]) != 0) {
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movz.d $f2,$f4,$t3",
                        "Move double-precision FP if integer register is zero : Copies the double-precision value in $f4 into $f2 if $t3 == 0. Both FP register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 ttttt sssss fffff 010010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                if (RegisterFile.getValue(operands[2]) == 0) {
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1, Coprocessor1.getValue(operands[1] + 1));
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mov.s $f0,$f1",
                        "Move single-precision FP register : Copies the single-precision value in $f1 into $f0. Does not perform any conversion.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 000110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movf.s $f0,$f1",
                        "Move single-precision FP if condition flag 0 is false : Copies the single-precision value in $f1 into $f0 if FP condition flag 0 is false (0).",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 000 00 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(0) == 0)
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movf.s $f0,$f1,1",
                        "Move single-precision FP if specified condition flag is false : Copies the single-precision value in $f1 into $f0 if FP condition flag N (specified by the immediate) is false (0).",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttt 00 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(operands[2]) == 0)
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movt.s $f0,$f1",
                        "Move single-precision FP if condition flag 0 is true : Copies the single-precision value in $f1 into $f0 if FP condition flag 0 is true (1).",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 000 01 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(0) == 1)
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movt.s $f0,$f1,1",
                        "Move single-precision FP if specified condition flag is true : Copies the single-precision value in $f1 into $f0 if FP condition flag N (specified by the immediate) is true (1).",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttt 01 sssss fffff 010001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (Coprocessor1.getConditionFlag(operands[2]) == 1)
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movn.s $f0,$f1,$t3",
                        "Move single-precision FP if integer register not zero : Copies the single-precision value in $f1 into $f0 if $t3 != 0.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fffff 010011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[2]) != 0)
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("movz.s $f0,$f1,$t3",
                        "Move single-precision FP if integer register is zero : Copies the single-precision value in $f1 into $f0 if $t3 == 0.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 ttttt sssss fffff 010010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[2]) == 0)
                                    Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mfc1 $t1,$f1",
                        "Move from FP register to integer register : Copies the raw 32-bit bit-pattern stored in FP register $f1 into integer register $t1. Useful for reading a single-precision float's bits or the result of 'floor.w.s', 'cvt.w.s', etc.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 00000 fffff sssss 00000 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                RegisterFile.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("mtc1 $t1,$f1",
                        "Move from integer register to FP register : Copies the 32-bit value in integer register $t1 into FP register $f1 without any conversion. Useful for initializing a FP register with a specific bit pattern or loading a freshly-computed integer before 'cvt.s.w'.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 00100 fffff sssss 00000 000000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                Coprocessor1.updateRegister(operands[1], RegisterFile.getValue(operands[0]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("neg.d $f2,$f4",
                        "Floating-point negate (double) : Sets $f2 to the negation of $f4 (double-precision) by flipping the sign bit. Both register numbers must be even.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10001 00000 sssss fffff 000111",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                                    throw new ProcessingException(statement, "both registers must be even-numbered");
                                }
                                // flip the sign bit of the second register (high order word) of the pair
                                int value = Coprocessor1.getValue(operands[1] + 1);
                                Coprocessor1.updateRegister(operands[0] + 1,
                                        ((value < 0) ? (value & Integer.MAX_VALUE) : (value | Integer.MIN_VALUE)));
                                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("neg.s $f0,$f1",
                        "Floating-point negate (single) : Sets $f0 to the negation of $f1 (single-precision) by flipping the sign bit.",
                        BasicInstructionFormat.R_FORMAT,
                        "010001 10000 00000 sssss fffff 000111",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int value = Coprocessor1.getValue(operands[1]);
                                // flip the sign bit
                                Coprocessor1.updateRegister(operands[0],
                                        ((value < 0) ? (value & Integer.MAX_VALUE) : (value | Integer.MIN_VALUE)));
                            }
                        }));
        instructionList.add(
                new BasicInstruction("lwc1 $f1,-100($t2)",
                        "Load word to FP register : Loads a 32-bit value from memory at address ($t2 + offset) directly into FP register $f1. Typically used to load a single-precision float from memory. Address must be word-aligned.",
                        BasicInstructionFormat.I_FORMAT,
                        "110001 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    Coprocessor1.updateRegister(operands[0],
                                            Globals.memory.getWord(
                                                    RegisterFile.getValue(operands[2]) + operands[1]));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(// no printed reference, got opcode from SPIM
                new BasicInstruction("ldc1 $f2,-100($t2)",
                        "Load doubleword to FP register pair : Loads a 64-bit value from memory at address ($t2 + offset) into the even FP register pair $f2/$f3. Used to load a double-precision float from memory. Address must be doubleword-aligned and $f2 must be even-numbered.",
                        BasicInstructionFormat.I_FORMAT,
                        "110101 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1) {
                                    throw new ProcessingException(statement, "first register must be even-numbered");
                                }
                                // IF statement added by DPS 13-July-2011.
                                if (!Globals.memory.doublewordAligned(RegisterFile.getValue(operands[2]) + operands[1])) {
                                    throw new ProcessingException(statement,
                                            new AddressErrorException("address not aligned on doubleword boundary ",
                                                    Exceptions.ADDRESS_EXCEPTION_LOAD,
                                                    RegisterFile.getValue(operands[2]) + operands[1]));
                                }

                                try {
                                    Coprocessor1.updateRegister(operands[0],
                                            Globals.memory.getWord(
                                                    RegisterFile.getValue(operands[2]) + operands[1]));
                                    Coprocessor1.updateRegister(operands[0] + 1,
                                            Globals.memory.getWord(
                                                    RegisterFile.getValue(operands[2]) + operands[1] + 4));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("swc1 $f1,-100($t2)",
                        "Store word from FP register : Writes the 32-bit value in FP register $f1 to memory at address ($t2 + offset). Typically used to store a single-precision float to memory. Address must be word-aligned.",
                        BasicInstructionFormat.I_FORMAT,
                        "111001 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                try {
                                    Globals.memory.setWord(
                                            RegisterFile.getValue(operands[2]) + operands[1],
                                            Coprocessor1.getValue(operands[0]));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        instructionList.add( // no printed reference, got opcode from SPIM
                new BasicInstruction("sdc1 $f2,-100($t2)",
                        "Store doubleword from FP register pair : Writes the 64-bit value in the even FP register pair $f2/$f3 to memory at address ($t2 + offset). Used to store a double-precision float to memory. Address must be doubleword-aligned and $f2 must be even-numbered.",
                        BasicInstructionFormat.I_FORMAT,
                        "111101 ttttt fffff ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (operands[0] % 2 == 1) {
                                    throw new ProcessingException(statement, "first register must be even-numbered");
                                }
                                // IF statement added by DPS 13-July-2011.
                                if (!Globals.memory.doublewordAligned(RegisterFile.getValue(operands[2]) + operands[1])) {
                                    throw new ProcessingException(statement,
                                            new AddressErrorException("address not aligned on doubleword boundary ",
                                                    Exceptions.ADDRESS_EXCEPTION_STORE,
                                                    RegisterFile.getValue(operands[2]) + operands[1]));
                                }
                                try {
                                    Globals.memory.setWord(
                                            RegisterFile.getValue(operands[2]) + operands[1],
                                            Coprocessor1.getValue(operands[0]));
                                    Globals.memory.setWord(
                                            RegisterFile.getValue(operands[2]) + operands[1] + 4,
                                            Coprocessor1.getValue(operands[0] + 1));
                                } catch (AddressErrorException e) {
                                    throw new ProcessingException(statement, e);
                                }
                            }
                        }));
        //////////////////////////// THE TRAP INSTRUCTIONS & ERET
        //////////////////////////// ////////////////////////////
        instructionList.add(
                new BasicInstruction("teq $t1,$t2",
                        "Trap if equal : Raises a trap exception if $t1 == $t2. In MARS this halts execution. On real hardware it triggers a trap handler. Useful as a guarded assertion or to catch forbidden conditions (e.g., division by zero guard).",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 110100",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) == RegisterFile.getValue(operands[1])) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("teqi $t1,-100",
                        "Trap if equal to immediate : Raises a trap exception if $t1 == immediate (sign-extended to 32 bits). Immediate variant of 'teq'. Useful for checking a register against a known constant at runtime.",
                        BasicInstructionFormat.I_FORMAT,
                        "000001 fffff 01100 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) == (operands[1] << 16 >> 16)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tne $t1,$t2",
                        "Trap if not equal : Raises a trap exception if $t1 != $t2. In MARS this halts execution. Opposite of 'teq'.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 110110",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) != RegisterFile.getValue(operands[1])) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tnei $t1,-100",
                        "Trap if not equal to immediate : Raises a trap exception if $t1 != immediate (sign-extended to 32 bits).",
                        BasicInstructionFormat.I_FORMAT,
                        "000001 fffff 01110 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) != (operands[1] << 16 >> 16)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tge $t1,$t2",
                        "Trap if greater or equal (signed) : Raises a trap exception if $t1 >= $t2 (signed comparison).",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 110000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) >= RegisterFile.getValue(operands[1])) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tgeu $t1,$t2",
                        "Trap if greater or equal (unsigned) : Raises a trap exception if $t1 >= $t2 using unsigned comparison. Treats both values as unsigned 32-bit integers.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 110001",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int first = RegisterFile.getValue(operands[0]);
                                int second = RegisterFile.getValue(operands[1]);
                                // if signs same, do straight compare; if signs differ & first negative then
                                // first greater else second
                                if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first >= second) : (first < 0)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tgei $t1,-100",
                        "Trap if greater or equal to immediate (signed) : Raises a trap if $t1 >= immediate (sign-extended to 32 bits).",
                        BasicInstructionFormat.I_FORMAT,
                        "000001 fffff 01000 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) >= (operands[1] << 16 >> 16)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tgeiu $t1,-100",
                        "Trap if greater or equal to immediate (unsigned) : Raises a trap if $t1 >= immediate using unsigned comparison. The immediate is sign-extended to 32 bits then interpreted as unsigned.",
                        BasicInstructionFormat.I_FORMAT,
                        "000001 fffff 01001 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int first = RegisterFile.getValue(operands[0]);
                                // 16 bit immediate value in operands[1] is sign-extended
                                int second = operands[1] << 16 >> 16;
                                // if signs same, do straight compare; if signs differ & first negative then
                                // first greater else second
                                if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first >= second) : (first < 0)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tlt $t1,$t2",
                        "Trap if less than (signed) : Raises a trap exception if $t1 < $t2 (signed comparison).",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 110010",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) < RegisterFile.getValue(operands[1])) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tltu $t1,$t2",
                        "Trap if less than (unsigned) : Raises a trap exception if $t1 < $t2 using unsigned comparison.",
                        BasicInstructionFormat.R_FORMAT,
                        "000000 fffff sssss 00000 00000 110011",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int first = RegisterFile.getValue(operands[0]);
                                int second = RegisterFile.getValue(operands[1]);
                                // if signs same, do straight compare; if signs differ & first positive then
                                // first is less else second
                                if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first < second) : (first >= 0)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tlti $t1,-100",
                        "Trap if less than immediate (signed) : Raises a trap exception if $t1 < immediate (sign-extended to 32 bits).",
                        BasicInstructionFormat.I_FORMAT,
                        "000001 fffff 01010 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                if (RegisterFile.getValue(operands[0]) < (operands[1] << 16 >> 16)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("tltiu $t1,-100",
                        "Trap if less than immediate (unsigned) : Raises a trap exception if $t1 < immediate using unsigned comparison. The immediate is sign-extended to 32 bits then interpreted as unsigned.",
                        BasicInstructionFormat.I_FORMAT,
                        "000001 fffff 01011 ssssssssssssssss",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                int[] operands = statement.getOperands();
                                int first = RegisterFile.getValue(operands[0]);
                                // 16 bit immediate value in operands[1] is sign-extended
                                int second = operands[1] << 16 >> 16;
                                // if signs same, do straight compare; if signs differ & first positive then
                                // first is less else second
                                if ((first >= 0 && second >= 0 || first < 0 && second < 0) ? (first < second) : (first >= 0)) {
                                    throw new ProcessingException(statement,
                                            "trap", Exceptions.TRAP_EXCEPTION);
                                }
                            }
                        }));
        instructionList.add(
                new BasicInstruction("eret",
                        "Exception return : Returns from an exception or interrupt handler. Restores the PC from the EPC register (Coprocessor 0 register 14) and clears the Exception Level bit (bit 1) in the Status register, re-enabling interrupts and returning to user mode.",
                        BasicInstructionFormat.R_FORMAT,
                        "010000 1 0000000000000000000 011000",
                        new SimulationCode() {
                            public void simulate(ProgramStatement statement) throws ProcessingException {
                                // set EXL bit (bit 1) in Status register to 0 and set PC to EPC
                                Coprocessor0.updateRegister(Coprocessor0.STATUS,
                                        Binary.clearBit(Coprocessor0.getValue(Coprocessor0.STATUS),
                                                Coprocessor0.EXCEPTION_LEVEL));
                                RegisterFile.setProgramCounter(Coprocessor0.getValue(Coprocessor0.EPC));
                            }
                        }));

        ////////////// READ PSEUDO-INSTRUCTION SPECS FROM DATA FILE AND ADD
        ////////////// //////////////////////
        addPseudoInstructions();

        // Initialization step. Create token list for each instruction example. This is
        // used by parser to determine user program correct syntax.
        for (int i = 0; i < instructionList.size(); i++) {
            Instruction inst = (Instruction) instructionList.get(i);
            inst.createExampleTokenList();
        }

        HashMap maskMap = new HashMap();
        List<MatchMap> matchMaps = new ArrayList<MatchMap>();
        for (int i = 0; i < instructionList.size(); i++) {
            Object rawInstr = instructionList.get(i);
            if (rawInstr instanceof BasicInstruction) {
                BasicInstruction basic = (BasicInstruction) rawInstr;
                Integer mask = Integer.valueOf(basic.getOpcodeMask());
                Integer match = Integer.valueOf(basic.getOpcodeMatch());
                HashMap matchMap = (HashMap) maskMap.get(mask);
                if (matchMap == null) {
                    matchMap = new HashMap();
                    maskMap.put(mask, matchMap);
                    matchMaps.add(new MatchMap(mask, matchMap));
                }
                matchMap.put(match, basic);
            }
        }
        Collections.sort(matchMaps);
        this.opcodeMatchMaps = matchMaps;
    }

    public BasicInstruction findByBinaryCode(int binaryInstr) {
        List<MatchMap> matchMaps = this.opcodeMatchMaps;
        for (int i = 0; i < matchMaps.size(); i++) {
            MatchMap map = (MatchMap) matchMaps.get(i);
            BasicInstruction ret = map.find(binaryInstr);
            if (ret != null)
                return ret;
        }
        return null;
    }

    /*
     * METHOD TO ADD PSEUDO-INSTRUCTIONS
     */

    private void addPseudoInstructions() {

        String pseudoOp, template, firstTemplate, token;
        String description;
        StringTokenizer tokenizer;
        for (String line : PseudoOps.PSEUDO_OPS) {
            // skip over: comment lines, empty lines, lines starting with blank.
            if (!line.startsWith("#") && !line.startsWith(" ")
                    && line.length() > 0) {
                description = "";
                tokenizer = new StringTokenizer(line, "\t");
                pseudoOp = tokenizer.nextToken();
                template = "";
                firstTemplate = null;
                while (tokenizer.hasMoreTokens()) {
                    token = tokenizer.nextToken();
                    if (token.startsWith("#")) {
                        // Optional description must be last token in the line.
                        description = token.substring(1);
                        break;
                    }
                    if (token.startsWith("COMPACT")) {
                        // has second template for Compact (16-bit) memory config -- added DPS 3 Aug
                        // 2009
                        firstTemplate = template;
                        template = "";
                        continue;
                    }
                    template = template + token;
                    if (tokenizer.hasMoreTokens()) {
                        template = template + "\n";
                    }
                }
                ExtendedInstruction inst = (firstTemplate == null)
                        ? new ExtendedInstruction(pseudoOp, template, description)
                        : new ExtendedInstruction(pseudoOp, firstTemplate, template, description);
                instructionList.add(inst);
                // if (firstTemplate != null) System.out.println("\npseudoOp:
                // "+pseudoOp+"\ndefault template:\n"+firstTemplate+"\ncompact
                // template:\n"+template);
            }
        }
    }

    /**
     * Given an operator mnemonic, will return the corresponding Instruction
     * object(s)
     * from the instruction set. Uses straight linear search technique.
     *
     * @param name operator mnemonic (e.g. addi, sw,...)
     * @return list of corresponding Instruction object(s), or null if not found.
     */
    public ArrayList matchOperator(String name) {
        ArrayList matchingInstructions = null;
        // Linear search for now....
        for (int i = 0; i < instructionList.size(); i++) {
            if (((Instruction) instructionList.get(i)).getName().equalsIgnoreCase(name)) {
                if (matchingInstructions == null)
                    matchingInstructions = new ArrayList();
                matchingInstructions.add(instructionList.get(i));
            }
        }
        return matchingInstructions;
    }

    /**
     * Given a string, will return the Instruction object(s) from the instruction
     * set whose operator mnemonic prefix matches it. Case-insensitive. For example
     * "s" will match "sw", "sh", "sb", etc. Uses straight linear search technique.
     *
     * @param name a string
     * @return list of matching Instruction object(s), or null if none match.
     */
    public ArrayList prefixMatchOperator(String name) {
        ArrayList matchingInstructions = null;
        // Linear search for now....
        if (name != null) {
            for (int i = 0; i < instructionList.size(); i++) {
                if (((Instruction) instructionList.get(i)).getName().toLowerCase().startsWith(name.toLowerCase())) {
                    if (matchingInstructions == null)
                        matchingInstructions = new ArrayList();
                    matchingInstructions.add(instructionList.get(i));
                }
            }
        }
        return matchingInstructions;
    }

    /*
     * Method to find and invoke a syscall given its service number. Each syscall
     * function is represented by an object in an array list. Each object is of
     * a class that implements Syscall or extends AbstractSyscall.
     */

    private void findAndSimulateSyscall(int number, ProgramStatement statement)
            throws ProcessingException {
        Syscall service = syscallLoader.findSyscall(number);
        if (service != null) {
            service.simulate(statement);
            return;
        }
        throw new ProcessingException(statement,
                "invalid or unimplemented syscall service: " +
                        number + " ",
                Exceptions.SYSCALL_EXCEPTION);
    }

    /*
     * Method to process a successful branch condition. DO NOT USE WITH JUMP
     * INSTRUCTIONS! The branch operand is a relative displacement in words
     * whereas the jump operand is an absolute address in bytes.
     *
     * The parameter is displacement operand from instruction.
     *
     * Handles delayed branching if that setting is enabled.
     */
    // 4 January 2008 DPS: The subtraction of 4 bytes (instruction length) after
    // the shift has been removed. It is left in as commented-out code below.
    // This has the effect of always branching as if delayed branching is enabled,
    // even if it isn't. This mod must work in conjunction with
    // ProgramStatement.java, buildBasicStatementFromBasicInstruction() method near
    // the bottom (currently line 194, heavily commented).

    private int processBranch(int displacement) {
        if (Globals.getSettingsProperties().getDelayedBranchingEnabled()) {
            int address = RegisterFile.getProgramCounter() + (displacement << 2);
            // Register the branch target address (absolute byte address).
            DelayedBranch.register(address);
            return address;
        } else {
            // Decrement needed because PC has already been incremented
            int address = RegisterFile.getProgramCounter()
                    + (displacement << 2);// - Instruction.INSTRUCTION_LENGTH);
            RegisterFile.setProgramCounter(address);
            return address;
        }
    }

    /*
     * Method to process a jump. DO NOT USE WITH BRANCH INSTRUCTIONS!
     * The branch operand is a relative displacement in words
     * whereas the jump operand is an absolute address in bytes.
     *
     * The parameter is jump target absolute byte address.
     *
     * Handles delayed branching if that setting is enabled.
     */

    private void processJump(int targetAddress) {
        if (Globals.getSettingsProperties().getDelayedBranchingEnabled()) {
            DelayedBranch.register(targetAddress);
        } else {
            RegisterFile.setProgramCounter(targetAddress);
        }
    }

    /*
     * Method to process storing of a return address in the given
     * register. This is used only by the "and link"
     * instructions: jal, jalr, bltzal, bgezal. If delayed branching
     * setting is off, the return address is the address of the
     * next instruction (e.g. the current PC value). If on, the
     * return address is the instruction following that, to skip over
     * the delay slot.
     *
     * The parameter is register number to receive the return address.
     */

    private int processReturnAddress(int register) {
        int value = RegisterFile.getProgramCounter() +
                ((Globals.getSettingsProperties().getDelayedBranchingEnabled()) ? Instruction.INSTRUCTION_LENGTH : 0);
        RegisterFile.updateRegister(register, value);
        return value;
    }

    private static class MatchMap implements Comparable {
        private int mask;
        private int maskLength; // number of 1 bits in mask
        private HashMap matchMap;

        public MatchMap(int mask, HashMap matchMap) {
            this.mask = mask;
            this.matchMap = matchMap;

            int k = 0;
            int n = mask;
            while (n != 0) {
                k++;
                n &= n - 1;
            }
            this.maskLength = k;
        }

        public boolean equals(Object o) {
            return o instanceof MatchMap && mask == ((MatchMap) o).mask;
        }

        public int compareTo(Object other) {
            MatchMap o = (MatchMap) other;
            int d = o.maskLength - this.maskLength;
            if (d == 0)
                d = this.mask - o.mask;
            return d;
        }

        public BasicInstruction find(int instr) {
            int match = Integer.valueOf(instr & mask);
            return (BasicInstruction) matchMap.get(match);
        }
    }
}
