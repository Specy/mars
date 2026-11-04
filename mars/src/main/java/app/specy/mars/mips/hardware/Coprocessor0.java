package app.specy.mars.mips.hardware;

import java.util.*;

import app.specy.mars.Globals;

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
 * Represents Coprocessor 0: the exception registers MARS has, and MIPS32's Count and Compare.
 * <p>
 * <b>Interrupts.</b> Cause's interrupt-pending bits, 8 to 15, belong to the interrupt sources, not
 * to the program: bits 8 and 9 are the host's keyboard receiver and display transmitter lines,
 * which it raises and clears as its devices ask (level-triggered, so a line stays pending until its
 * device withdraws it), and bit 15, IP7, is the timer. An interrupt is delivered between two
 * instructions when Status allows it: interrupts enabled (bit 0), no exception being handled (EXL,
 * bit 1) and the source unmasked (bits 8 to 15). The host's lines are not part of the Undo history,
 * since the host states them again after an Undo; everything else is.
 * <p>
 * <b>Pulses.</b> A device can also ask once rather than for as long as it is ready, as MARS's
 * display transmitter does: a pulse is one request, pending until an interrupt takes it, whatever
 * Status says meanwhile, and a second pulse of the same device before then is the same request.
 * Taking it spends it, so the handler's {@code eret} does not bring it back. The handler finds the
 * device's bit in Cause, held there, as MARS's Exceptions.setRegisters holds it, until the next
 * exception or interrupt is entered or the program clears the bit with {@code mtc0}; that held bit
 * asks for nothing. Pulses are part of the Undo history: one sent during an instruction or a Poke
 * goes back with it, and spending one goes back with the interrupt's entry.
 * <p>
 * <b>The timer.</b> Count ($9) counts CPU time: it goes up once every two instructions executed,
 * counted by the BackStepper's rewindable instruction count, so that Undo rewinds it and a run
 * reaches the same Count at the same instruction every time. A write sets what it reads now; its
 * register holds the offset from that count. When Count becomes equal to Compare ($11) the timer
 * sets IP7, inside the instruction that made it so, and a write to Compare clears it. The timer is
 * off until the program first writes Compare: MARS starts programs with interrupts enabled, and
 * one that never used the timer must not be interrupted when Count first wraps to Compare's 0.
 * Count reads 0 at the program's first instruction. "Becomes equal" is Count going up to Compare's
 * value, at an even instruction count: a write of either register that makes them equal sets IP7
 * at once when its instruction is an even-numbered one of the run, and otherwise Count already
 * holds the value, so the timer waits a whole turn of Count, 2^33 instructions.
 *
 * @author Pete Sanderson
 * @version August 2005
 **/

public class Coprocessor0 {
   /**
    * Coprocessor register names
    */
   public static final int VADDR = 8;
   public static final int COUNT = 9;
   public static final int COMPARE = 11;
   public static final int STATUS = 12;
   public static final int CAUSE = 13;
   public static final int EPC = 14;

   public static final int EXCEPTION_LEVEL = 1; // bit position in STATUS register
   // bits 8-15 (mask for interrupt levels) all set, bit 4 (user mode) set,
   // bit 1 (exception level) not set, bit 0 (interrupt enable) set.
   public static final int DEFAULT_STATUS_VALUE = 0x0000FF11;

   /** Cause's interrupt-pending bits, and Status's mask bits in the same positions. */
   public static final int INTERRUPT_BITS = 0x0000FF00;
   /** IP7, the timer's pending bit, in Cause. */
   public static final int TIMER_PENDING = 0x00008000;
   /** Cause's exception code field, bits 2 to 6. */
   private static final int EXCEPTION_CODE = 0x0000007C;
   /** The pending bits a device's line or pulse can take, 8 to 14: all but the timer's. */
   public static final int DEVICE_BITS = INTERRUPT_BITS & ~TIMER_PENDING;
   /** Count goes up once per this many instructions executed: every other cycle of 100 MHz. */
   public static final int INSTRUCTIONS_PER_COUNT = 2;
   /** 2^32 Counts, in instructions: how often Count comes round to the same value. */
   private static final double COUNT_PERIOD = 4294967296.0 * INSTRUCTIONS_PER_COUNT;

   private static Register[] registers = { new Register("$8 (vaddr)", 8, 0),
         new Register("$9 (count)", COUNT, 0),
         new Register("$11 (compare)", COMPARE, 0),
         new Register("$12 (status)", 12, DEFAULT_STATUS_VALUE),
         new Register("$13 (cause)", 13, 0),
         new Register("$14 (epc)", 14, 0)
   };
   private static final Register countRegister = registers[1], compareRegister = registers[2],
         statusRegister = registers[3], causeRegister = registers[4];

   /** The pending bits the host's lines hold, in Cause's positions: 0x100 keyboard, 0x200 display. */
   private static int externalLines;
   /** The pulses no interrupt has taken yet, in Cause's positions, one request each. */
   private static int pulses;
   /**
    * Set whenever deliverability may have changed: a write to a coprocessor 0 register, a line,
    * the start of a run call. The simulator looks at Status and Cause only while it is set.
    */
   public static boolean interruptCheck = true;
   /** Whether the program has written Compare, which turns the timer on. */
   public static boolean timerArmed;
   /** The instruction count at the end of which Count next becomes Compare, once worked out. */
   private static double timerMatchAt;
   /** Whether {@link #timerMatchAt} has to be worked out again: Count, Compare or the count moved. */
   private static boolean timerDirty = true;
   /** The interrupt the current or last run call took most recently: pc, cause, handler. */
   private static int[] lastTaken;

   /**
    * Method for displaying the register values for debugging.
    **/

   public static void showRegisters() {
      for (int i = 0; i < registers.length; i++) {
         System.out.println("Name: " + registers[i].getName());
         System.out.println("Number: " + registers[i].getNumber());
         System.out.println("Value: " + getValue(registers[i].getNumber()));
         System.out.println("");
      }
   }

   /**
    * Sets the value of the register given to the value given.
    * 
    * @param n   name of register to set the value of ($n, where n is reg number).
    * @param val The desired value for the register.
    * @return old value in register prior to update
    **/

   public static int updateRegister(String n, int val) {
      int oldValue = 0;
      for (int i = 0; i < registers.length; i++) {
         if (("$" + registers[i].getNumber()).equals(n) || registers[i].getName().equals(n)) {
            oldValue = registers[i].getValue();
            registers[i].setValue(val);
            interruptCheck = true;
            timerDirty = true;
            break;
         }
      }
      return oldValue;
   }

   /**
    * This method updates the register value who's number is num: the value the register holds,
    * which for Count is its offset from the instruction count, and Undo puts back the same way.
    * 
    * @param num Number of register to set the value of.
    * @param val The desired value for the register.
    * @return old value in register prior to update
    **/
   public static int updateRegister(int num, int val) {
      int old = 0;
      for (int i = 0; i < registers.length; i++) {
         if (registers[i].getNumber() == num) {
            old = (Globals.getSettingsProperties().getBackSteppingEnabled())
                  ? Globals.program.getBackStepper().addCoprocessor0Restore(num, registers[i].setValue(val), val)
                  : registers[i].setValue(val);
            interruptCheck = true;
            if (num == COUNT || num == COMPARE) timerDirty = true;
            break;
         }
      }
      return old;
   }

   /**
    * Returns the value of the register who's number is num, as a program reads it: Count as it
    * stands now, and Cause with the host's lines and pulses pending in it.
    * 
    * @param num The register number.
    * @return The value of the given register. 0 for non-implemented registers
    **/

   public static int getValue(int num) {
      if (num == COUNT) return count();
      if (num == CAUSE) return causeRegister.getValue() | externalLines | pulses;
      for (int i = 0; i < registers.length; i++) {
         if (registers[i].getNumber() == num) {
            return registers[i].getValue();
         }
      }
      return 0;
   }

   /** What the register holds, Count's offset and Cause without the host's lines: what Undo restores. */
   public static int getStoredValue(int num) {
      for (int i = 0; i < registers.length; i++) {
         if (registers[i].getNumber() == num) {
            return registers[i].getValueNoNotify();
         }
      }
      return 0;
   }

   /**
    * An {@code mtc0}, or a host writing a register as one would, journaled when {@code journal}
    * is set. Count is set to read {@code value} from the next instruction on; Compare is set,
    * turns the timer on and clears IP7; Cause keeps its pending bits, which are the sources'.
    */
   public static void moveTo(int num, int value, boolean journal) {
      switch (num) {
         case COUNT:
            write(COUNT, value - countBase(executed() + (instructionOpen() ? 1 : 0)), journal);
            return;
         case COMPARE:
            write(COMPARE, value, journal);
            if (!timerArmed) {
               if (journal) journalTimerArmed(false);
               timerArmed = true;
            }
            write(CAUSE, causeRegister.getValueNoNotify() & ~TIMER_PENDING, journal);
            timerDirty = true;
            return;
         case CAUSE:
            // the pending bits are the sources', except that a bit a pulse left for the handler
            // is cleared by writing it as 0
            int stored = causeRegister.getValueNoNotify();
            write(CAUSE, (value & ~INTERRUPT_BITS) | (stored & TIMER_PENDING) | (stored & value & DEVICE_BITS), journal);
            return;
         default:
            write(num, value, journal);
      }
   }

   private static void write(int num, int value, boolean journal) {
      if (journal) {
         if (getStoredValue(num) != value) updateRegister(num, value);
         return;
      }
      for (Register register : registers) {
         if (register.getNumber() == num) register.setValue(value);
      }
      interruptCheck = true;
      timerDirty = true;
   }

   private static void journalTimerArmed(boolean old) {
      if (Globals.getSettingsProperties().getBackSteppingEnabled()) {
         Globals.program.getBackStepper().addTimerArmedRestore(old);
      }
   }

   /** Puts back whether the timer was on, for an Undo. */
   public static void restoreTimerArmed(boolean armed) {
      timerArmed = armed;
      timerDirty = true;
   }

   // ------------------------------------------------------------------ Count and Compare

   private static double executed() {
      return Globals.program == null || Globals.program.getBackStepper() == null ? 0
            : Globals.program.getBackStepper().getExecutedCount();
   }

   private static boolean instructionOpen() {
      return Globals.program != null && Globals.program.getBackStepper() != null
            && Globals.program.getBackStepper().getCurrentInstructionSerial() != 0;
   }

   /** floor(executed / 2), modulo 2^32, as Count's 32 bits hold it without the offset. */
   private static int countBase(double executed) {
      double ticks = Math.floor(executed / INSTRUCTIONS_PER_COUNT);
      ticks -= Math.floor(ticks / 4294967296.0) * 4294967296.0;
      return (int) (ticks >= 2147483648.0 ? ticks - 4294967296.0 : ticks);
   }

   /** Count as a program reads it now: during an instruction, what the instructions before it made. */
   public static int count() {
      return countBase(executed()) + countRegister.getValueNoNotify();
   }

   /**
    * The timer, at the end of every instruction while it is on, inside that instruction: when the
    * instructions executed with this one make Count equal to Compare, IP7 is set, and the write
    * joins this instruction's Undo step. The match is worked out once, as the instruction count at
    * which it comes, and again only after Count, Compare or the count itself moved.
    */
   public static void timerTick() {
      double completed = executed() + 1;
      if (timerDirty) {
         // Count == Compare first at an even count: Count goes up as the count reaches 2t
         double target = (compareRegister.getValueNoNotify() - countRegister.getValueNoNotify()) & 0xFFFFFFFFL;
         double match = target * INSTRUCTIONS_PER_COUNT;
         if (match < completed) match += Math.ceil((completed - match) / COUNT_PERIOD) * COUNT_PERIOD;
         timerMatchAt = match;
         timerDirty = false;
      }
      if (completed != timerMatchAt) return;
      timerMatchAt += COUNT_PERIOD;
      int cause = causeRegister.getValueNoNotify();
      if ((cause & TIMER_PENDING) == 0) updateRegister(CAUSE, cause | TIMER_PENDING);
      timerDirty = false;
   }

   // ------------------------------------------------------------------ delivery

   /** The host raises a device's line: {@code code} is MARS's, 0x40 keyboard and 0x80 display. */
   public static void raiseExternal(int code) {
      externalLines |= (code << 2) & DEVICE_BITS;
      interruptCheck = true;
   }

   /** The host clears a device's line, and withdraws its pulse if one is pending. */
   public static void clearExternal(int code) {
      int bits = (code << 2) & INTERRUPT_BITS;
      externalLines &= ~bits;
      if ((pulses & bits) != 0) setPulses(pulses & ~bits);
      interruptCheck = true;
   }

   /** The host sends a device's pulse, one interrupt request: see the class comment. */
   public static void pulseExternal(int code) {
      int bits = (code << 2) & DEVICE_BITS;
      if ((pulses | bits) != pulses) setPulses(pulses | bits);
   }

   /** The pulses pending now, in Cause's positions. */
   public static int pendingPulses() {
      return pulses;
   }

   private static void setPulses(int value) {
      if (Globals.getSettingsProperties().getBackSteppingEnabled() && Globals.program != null
            && Globals.program.getBackStepper() != null) {
         app.specy.mars.simulator.BackStepper history = Globals.program.getBackStepper();
         // a pulse the host sends between two instructions, outside a Poke, is no step's
         if (history.getCurrentInstructionSerial() != 0 || history.pokeOpen()) history.addPulsesRestore(pulses);
      }
      pulses = value;
      interruptCheck = true;
   }

   /** Puts back the pulses pending, for an Undo. */
   public static void restorePulses(int value) {
      pulses = value;
      interruptCheck = true;
   }

   /**
    * The interrupts Status lets through now, as Cause's pending bits, or 0: interrupts enabled,
    * no exception being handled, and the pending source unmasked. A device bit Cause holds for a
    * handler, from a pulse already taken, asks for nothing.
    */
   public static int deliverable() {
      interruptCheck = false;
      int status = statusRegister.getValueNoNotify();
      if ((status & 1) == 0 || (status & (1 << EXCEPTION_LEVEL)) != 0) return 0;
      return ((causeRegister.getValueNoNotify() & TIMER_PENDING) | externalLines | pulses) & status & INTERRUPT_BITS;
   }

   /**
    * Enters the handler for an interrupt before the instruction at {@code pc} runs, in a step the
    * simulator has opened for it: Cause's exception code 0, EPC the interrupted instruction, which
    * has not run (so a handler returns to it with {@code eret} as it is), and EXL set. The pulses
    * among {@code pending} are spent, and Cause holds their bits for the handler instead of the
    * ones an earlier pulse left there.
    */
   public static void enterInterrupt(int pc, int pending, int handler) {
      int spent = pending & pulses;
      updateRegister(CAUSE, (causeRegister.getValueNoNotify() & ~(EXCEPTION_CODE | DEVICE_BITS)) | spent);
      if (spent != 0) setPulses(pulses & ~spent);
      updateRegister(EPC, pc);
      updateRegister(STATUS, statusRegister.getValueNoNotify() | (1 << EXCEPTION_LEVEL));
      lastTaken = new int[] { pc, pending, handler };
   }

   /** The interrupt the last run call took most recently, as pc, pending bits and handler, or empty. */
   public static int[] lastTaken() {
      return lastTaken == null ? new int[0] : lastTaken.clone();
   }

   /** A run call begins: what the host changed since is looked at, and nothing is taken yet. */
   public static void beginRun() {
      lastTaken = null;
      interruptCheck = true;
   }

   /** An Undo put state back: the count moved, and what is deliverable may have changed. */
   public static void afterUndo() {
      interruptCheck = true;
      timerDirty = true;
   }

   /** A new run: no line raised, the timer off, nothing taken. The registers are reset apart. */
   public static void resetInterrupts() {
      externalLines = 0;
      pulses = 0;
      timerArmed = false;
      timerDirty = true;
      interruptCheck = true;
      lastTaken = null;
   }

   /**
    * For getting the number representation of the register.
    * 
    * @param n The string formatted register name to look for.
    * @return The number of the register represented by the string. -1 if no match.
    **/

   public static int getNumber(String n) {
      for (int i = 0; i < registers.length; i++) {
         if (("$" + registers[i].getNumber()).equals(n) || registers[i].getName().equals(n)) {
            return registers[i].getNumber();
         }
      }
      return -1;
   }

   /**
    * For returning the set of registers.
    * 
    * @return The set of registers.
    **/

   public static Register[] getRegisters() {
      return registers;
   }

   /**
    * Coprocessor0 implements only selected registers, so the register number
    * (8, 9, 11, 12, 13, 14) does not correspond to its position in the list of registers
    * (0 to 5).
    * 
    * @param r A coprocessor0 Register
    * @return the list position of given register, -1 if not found.
    **/

   public static int getRegisterPosition(Register r) {
      for (int i = 0; i < registers.length; i++) {
         if (registers[i] == r) {
            return i;
         }
      }
      return -1;
   }

   /**
    * Get register object corresponding to given name. If no match, return null.
    * 
    * @param rname The register name, in $0 format.
    * @return The register object,or null if not found.
    **/

   public static Register getRegister(String rname) {
      for (int i = 0; i < registers.length; i++) {
         if (("$" + registers[i].getNumber()).equals(rname) || registers[i].getName().equals(rname)) {
            return registers[i];
         }
      }
      return null;
   }

   /**
    * Method to reinitialize the values of the registers.
    **/

   public static void resetRegisters() {
      for (int i = 0; i < registers.length; i++) {
         registers[i].resetValue();
      }
      interruptCheck = true;
      timerDirty = true;
   }

   /**
    * Each individual register is a separate object and Observable. This handy
    * method
    * will add the given Observer to each one.
    */
   public static void addRegistersObserver(Observer observer) {
      for (int i = 0; i < registers.length; i++) {
         registers[i].addObserver(observer);
      }
   }

   /**
    * Each individual register is a separate object and Observable. This handy
    * method
    * will delete the given Observer from each one.
    */
   public static void deleteRegistersObserver(Observer observer) {
      for (int i = 0; i < registers.length; i++) {
         registers[i].deleteObserver(observer);
      }
   }

}
