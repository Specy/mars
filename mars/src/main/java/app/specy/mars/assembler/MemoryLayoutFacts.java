package app.specy.mars.assembler;

import java.util.ArrayList;
import java.util.List;

/** Assembly facts retained after linking, including data-only library units. */
public final class MemoryLayoutFacts {
    private static final List<int[]> items = new ArrayList<>();
    private static final List<String> sections = new ArrayList<>();
    private static final List<String> files = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static final List<int[]> symbols = new ArrayList<>();
    public static int stackTop, previousSp;
    public static void clear() { items.clear(); sections.clear(); names.clear(); files.clear(); symbols.clear(); }
    /** kind: 0 code, 1 initialized data, 2 reserved; alignment is the fifth field. */
    public static void item(int address, int length, int kind, String section, int alignment) {
        if (length <= 0) return;
        int index = sections.indexOf(section);
        if (index < 0) { index = sections.size(); sections.add(section); }
        items.add(new int[] { address, length, kind, index, alignment });
    }
    public static void symbol(String name, int address, boolean data, boolean library, String file) {
        for (int i = 0; i < names.size(); i++) if (names.get(i).equals(name) && symbols.get(i)[0] == address) return;
        names.add(name); files.add(file); symbols.add(new int[] { address, data ? 1 : 0, library ? 1 : 0 });
    }
    private static int[] flatten(List<int[]> values, int width) {
        int[] result = new int[values.size() * width];
        for (int i = 0; i < values.size(); i++) System.arraycopy(values.get(i), 0, result, i * width, width);
        return result;
    }
    public static int[] items() { return flatten(items, 5); }
    public static String[] sections() { return sections.toArray(new String[0]); }
    public static String[] files() { return files.toArray(new String[0]); }
    public static String[] names() { return names.toArray(new String[0]); }
    public static int[] symbols() { return flatten(symbols, 3); }
    public static void resetStack(int sp) { stackTop = previousSp = sp; }
    public static void refreshStack(int sp) {
        double current = sp < 0 ? sp + 4294967296.0 : sp;
        double previous = previousSp < 0 ? previousSp + 4294967296.0 : previousSp;
        int top = Math.abs(current - previous) > 4096 || Integer.compareUnsigned(sp, stackTop) > 0 ? sp : stackTop;
        if (top != stackTop) app.specy.mars.Globals.program.getBackStepper().addStackTopRestore(stackTop);
        stackTop = top; previousSp = sp;
    }
}
