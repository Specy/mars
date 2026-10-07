package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.Globals;
import app.specy.mars.ProcessingException;
import app.specy.mars.ProgramStatement;
import app.specy.mars.mips.hardware.AddressErrorException;
import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.util.Utf8;

import java.io.ByteArrayOutputStream;

/**
 * The NUL-terminated strings syscalls take from memory, read as UTF-8, as RARS reads them. MARS 4.5
 * reads one byte per character instead (Latin-1), so a string's text is the same here in a
 * literal, in print string (4) and through write (15), a documented deviation from MARS.
 */
public final class NullString {
    private NullString() {
    }

    /**
     * The NUL-terminated string at the address in {@code register}, decoded from UTF-8 as Java
     * decodes it.
     *
     * @throws ProcessingException when a byte of it cannot be read
     */
    public static String get(ProgramStatement statement, int register) throws ProcessingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            read(RegisterFile.getValue(register), bytes);
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }
        return decode(bytes);
    }

    /**
     * Reads the bytes of the string at {@code address} up to its NUL, which is left out, into
     * {@code bytes}, the way the program reads memory.
     *
     * @throws AddressErrorException when a byte cannot be read; the bytes before it are in {@code bytes}
     */
    public static void read(int address, ByteArrayOutputStream bytes) throws AddressErrorException {
        int value = Globals.memory.getByte(address);
        while (value != 0) {
            bytes.write(value);
            address++;
            value = Globals.memory.getByte(address);
        }
    }

    /** The text of the bytes {@link #read} collected. */
    public static String decode(ByteArrayOutputStream bytes) {
        return Utf8.decode(bytes.toByteArray());
    }
}
