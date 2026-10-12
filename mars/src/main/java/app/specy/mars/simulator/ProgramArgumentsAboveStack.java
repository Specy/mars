package app.specy.mars.simulator;

import app.specy.mars.Globals;
import app.specy.mars.mips.hardware.AddressErrorException;
import app.specy.mars.mips.hardware.Memory;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.Utf8;

/**
 * A program's {@code argv}, {@code argv[0]} included, stored where it moves no register but
 * {@code $a0} and {@code $a1}: in the gap MARS leaves between the initial stack pointer
 * ({@code 0x7fffeffc} by default) and the stack base ({@code 0x7ffffffc}).
 * <p>
 * {@link ProgramArgumentList#storeProgramArguments()} writes the strings there too, but pushes
 * {@code argc} and the pointers below {@code $sp} and lowers it, which changes the initial stack
 * pointer of every program run with arguments. Here everything stays above it:
 * <ul>
 * <li>the strings, UTF-8 and each ending in a NUL, {@code argv[0]} first, end at the stack base;</li>
 * <li>below them, aligned down to a word, the NULL-terminated array of word-sized pointers to them,
 * any byte between its end and the first string zero;</li>
 * <li>{@code $a0} is {@code argc} and {@code $a1} the array's address; {@code $sp} keeps its value.</li>
 * </ul>
 * The data must end at least {@link #STACK_POINTER_CLEARANCE} bytes above the initial stack
 * pointer. Addresses come from the current memory configuration.
 */
public final class ProgramArgumentsAboveStack {
    /** The most words after {@code argv[0]}, the limit the x86 Core has too. */
    public static final int MAX_ARGUMENTS = 1024;

    /**
     * Bytes just above the initial stack pointer left to the program: the word {@code $sp} points
     * at, which a program that stores at {@code 0($sp)} before moving it writes, and the rest of
     * the 16-byte argument area an o32 callee may write above its caller's {@code $sp}.
     */
    public static final int STACK_POINTER_CLEARANCE = 16;

    private static final int POINTER_BYTES = Memory.WORD_LENGTH_BYTES;

    private ProgramArgumentsAboveStack() {
    }

    /** Bytes the program name, the Arguments and their {@code argv} array may take, at most. */
    public static long space() {
        return Math.max(0, top() - bottom());
    }

    /**
     * Why {@code argv} cannot be stored, as a sentence for the person who typed it, or null when it
     * can. An empty {@code argv} stores nothing and is no problem.
     */
    public static String problem(String[] argv) {
        if (argv == null) return "Arguments must be strings";
        for (String word : argv) {
            if (word == null) return "Arguments must be strings";
        }
        if (argv.length - 1 > MAX_ARGUMENTS) {
            return "Arguments may contain at most " + grouped(MAX_ARGUMENTS) + " words";
        }
        for (String word : argv) {
            if (word.indexOf('\0') >= 0) return "Arguments cannot contain NUL";
        }
        if (argv.length == 0) return null;
        long argvAddress = argvAddress(encode(argv));
        if (argvAddress < bottom()) {
            return "Arguments take " + grouped(top() - argvAddress)
                    + " bytes with the program name and argv; MIPS programs have " + grouped(space())
                    + " bytes for them";
        }
        return null;
    }

    /**
     * Writes {@code argv} above the stack and sets {@code $a0} and {@code $a1}, directly: no write
     * is recorded for undo, so undoing to the program's start keeps them. An empty {@code argv}
     * changes nothing.
     *
     * @throws IllegalArgumentException with {@link #problem(String[])}'s sentence when it has one
     */
    public static void store(String[] argv) {
        String problem = problem(argv);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (argv.length == 0) return;
        byte[][] strings = encode(argv);
        long argvAddress = argvAddress(strings);
        int address = (int) stringsAddress(strings);
        int[] pointers = new int[strings.length];
        try {
            for (int i = 0; i < strings.length; i++) {
                pointers[i] = address;
                for (byte b : strings[i]) {
                    Globals.memory.set(address++, b & 0xff, 1);
                }
                Globals.memory.set(address++, 0, 1);
            }
            int slot = (int) argvAddress;
            for (int pointer : pointers) {
                Globals.memory.set(slot, pointer, POINTER_BYTES);
                slot += POINTER_BYTES;
            }
            Globals.memory.set(slot, 0, POINTER_BYTES);
            slot += POINTER_BYTES;
            // the alignment's padding, which a run before this one may have written over
            for (int end = pointers[0]; slot < end; slot++) {
                Globals.memory.set(slot, 0, 1);
            }
        } catch (AddressErrorException e) {
            throw new IllegalStateException("The program's arguments could not be stored: " + e.getMessage(), e);
        }
        // straight to the registers, past the back stepper, as storeProgramArguments does
        RegisterFile.getUserRegister("$a0").setValue(argv.length);
        RegisterFile.getUserRegister("$a1").setValue((int) argvAddress);
    }

    /** One past the last byte the data may take: the stack base. */
    private static long top() {
        return Integer.toUnsignedLong(Memory.stackBaseAddress);
    }

    /** The lowest address the data may take. */
    private static long bottom() {
        return Integer.toUnsignedLong(Memory.stackPointer) + STACK_POINTER_CLEARANCE;
    }

    private static byte[][] encode(String[] argv) {
        byte[][] strings = new byte[argv.length][];
        for (int i = 0; i < argv.length; i++) {
            strings[i] = Utf8.encode(argv[i]);
        }
        return strings;
    }

    private static long stringsAddress(byte[][] strings) {
        long bytes = 0;
        for (byte[] string : strings) {
            bytes += string.length + 1;
        }
        return top() - bytes;
    }

    private static long argvAddress(byte[][] strings) {
        long start = stringsAddress(strings) - (long) (strings.length + 1) * POINTER_BYTES;
        return Math.floorDiv(start, POINTER_BYTES) * POINTER_BYTES;
    }

    /** {@code 4080} as {@code 4,080}. */
    private static String grouped(long value) {
        String digits = Long.toString(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(',');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
