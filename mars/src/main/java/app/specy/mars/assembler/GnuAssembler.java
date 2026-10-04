package app.specy.mars.assembler;

import app.specy.mars.*;
import app.specy.mars.mips.fs.*;
import app.specy.mars.mips.hardware.*;
import app.specy.mars.mips.instructions.*;
import app.specy.mars.util.SystemIO;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Bounded, static GNU compiler assembly for the MIPS32 output of GCC. Each translation unit is
 * parsed on its own: parsing never writes memory, instruction expansions have fixed sizes before
 * section layout, and expressions retain their section identity until fixup resolution. Units
 * are then linked with ld semantics: sections of one family are placed in unit order, a unit's
 * own symbols resolve before global ones, a strong global definition beats a weak one, an
 * undefined weak reference is zero, and only the first unit's copy of a COMDAT group is kept.
 * This is not an ELF linker: there are no object files, relocation records or linker scripts.
 * <p>
 * MARS runs with delayed branching off: a taken branch never executes the instruction after it,
 * and a link saves the address of that instruction. Code assembled with {@code .set noreorder}
 * must therefore hold a nop in every delay slot, which is what GCC writes with
 * -fno-delayed-branch, and a call then returns to its nop. In reorder mode, where GNU as would
 * fill the slot itself, no slot is emitted at all. Data is little-endian, as MARS memory is, and
 * data directives align themselves as GNU as does for MIPS.
 */
public final class GnuAssembler {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    // Section families, in layout order. Text is the only executable family, writable
    // families start at INIT and zeroed ones at BSS.
    private static final int TEXT = 0, RODATA = 1, INIT = 2, FINI = 3, DATA = 4, BSS = 5, COMMON = 6;
    private static final String NAME_TEXT = "[.$A-Za-z_][.$A-Za-z_0-9]*";
    private static final Pattern NAME = Pattern.compile(NAME_TEXT);
    private static final Pattern LABEL = Pattern.compile("^(" + NAME_TEXT + "|[0-9]+)\\s*:");
    private static final Pattern LEX = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|#[^\\n]*|[.$A-Za-z_%][.$A-Za-z_0-9%]*|(?:0[xX][0-9a-fA-F]+|[0-9]+[bf]?)|[^\\s,]");
    private static final Pattern MODIFIER = Pattern.compile("%(hi|lo)\\(");
    private static final Pattern ANY_MODIFIER = Pattern.compile("%([a-zA-Z_0-9]+)\\(");
    private static final Pattern SUPPORTED_MODIFIER = Pattern.compile("hi|lo");
    private static final Pattern ASSIGNMENT = Pattern.compile("^(" + NAME_TEXT + ")\\s*=\\s*(.+)$");
    private static final Pattern SET_DIRECTIVE = Pattern.compile("^\\.(?:set|equ)\\s+(" + NAME_TEXT + ")\\s*,\\s*(.+)$");
    private static final Pattern INCLUDE = Pattern.compile("\\.include(?:\\s.*)?");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern NUMERIC_REFERENCE = Pattern.compile("[0-9]+[bf]");
    /** Symbol types; a C++ inline variable is a gnu_unique_object, which a static link treats as its COMDAT group does. */
    private static final Pattern SYMBOL_TYPE = Pattern.compile("[@%](function|object|notype|gnu_unique_object)");
    private static final Pattern IGNORED_CFI = Pattern.compile("\\.cfi_(startproc|endproc|def_cfa_offset|def_cfa|def_cfa_register|offset|restore|remember_state|restore_state|sections|undefined|same_value|return_column|signal_frame|adjust_cfa_offset|escape)");
    private static final Pattern TEXT_SECTION = Pattern.compile("\\.text(?:\\..*)?");
    private static final Pattern RODATA_SECTION = Pattern.compile("\\.(rodata|srodata|rdata)(?:\\..*)?");
    private static final Pattern DATA_SECTION = Pattern.compile("\\.(data|sdata)(?:\\..*)?");
    private static final Pattern BSS_SECTION = Pattern.compile("\\.(bss|sbss)(?:\\..*)?");
    private static final Pattern DEBUG_SECTION = Pattern.compile("\\.(debug_.*|zdebug_.*|comment|note\\.GNU-stack|mdebug\\..*|gnu\\.attributes|pdr|llvm_addrsig)");
    private static final Pattern SECTION_FLAGS = Pattern.compile("[awxMSG]*");
    private static final Pattern ENTRY_SIZE = Pattern.compile("[1-9][0-9]*");
    /** Loads and stores whose second operand is an address, {@code offset(base)} or a bare expression. */
    private static final Pattern MEMORY_ACCESS = Pattern.compile("lb|lbu|lh|lhu|lw|lwl|lwr|ll|sb|sh|sw|swl|swr|sc|lwc1|ldc1|swc1|sdc1");
    /** Basic instructions whose last operand is a PC-relative target, a 16-bit word offset from the delay slot. */
    private static final Pattern BRANCH = Pattern.compile("beq|bne|bgez|bgezal|bgtz|blez|bltz|bltzal|bc1t|bc1f");
    /** Source operations followed by a delay slot. */
    private static final Pattern DELAYED = Pattern.compile("b|bal|beqz|bnez|beq|bne|bgez|bgezal|bgtz|blez|bltz|bltzal|bc1t|bc1f|j|jal|jr|jalr|bge|bgeu|bgt|bgtu|ble|bleu|blt|bltu");
    /** Template codes that need a constant, an address or the delayed branching setting at expansion. */
    private static final Pattern VALUE_TEMPLATE = Pattern.compile(".*(LL|LH|VL|VH|BROFF|DBNOP|S32).*");
    private static final Pattern USES_AT = Pattern.compile("\\$(1|at)(?![0-9A-Za-z])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Immediates the instruction zero-extends, so a %lo field is written as its unsigned half. */
    private static final Pattern UNSIGNED_IMMEDIATE = Pattern.compile("andi|ori|xori");
    private static final Pattern CONDITION_CODE = Pattern.compile("\\$fcc([0-7])");
    /** A constructor or destructor array section with an explicit priority. */
    private static final Pattern PRIORITIZED_ARRAY = Pattern.compile("\\.(?:init|fini)_array\\.(\\d{1,5})");
    /** A lowered conditional branch: its operation, its operands before the target, and its target. */
    private static final Pattern CONDITIONAL_BRANCH = Pattern.compile("(beq|bne|bgez|bltz|bgtz|blez|bgezal|bltzal|bc1t|bc1f) (?:(.+),)?([^,]+)");
    /** The branch on the opposite condition; a branch and link becomes one that skips a jal. */
    private static final Map<String, String> INVERSE_BRANCH = new HashMap<>();
    static {
        INVERSE_BRANCH.put("beq", "bne"); INVERSE_BRANCH.put("bne", "beq");
        INVERSE_BRANCH.put("bgez", "bltz"); INVERSE_BRANCH.put("bltz", "bgez");
        INVERSE_BRANCH.put("bgtz", "blez"); INVERSE_BRANCH.put("blez", "bgtz");
        INVERSE_BRANCH.put("bgezal", "bltz"); INVERSE_BRANCH.put("bltzal", "bgez");
        INVERSE_BRANCH.put("bc1t", "bc1f"); INVERSE_BRANCH.put("bc1f", "bc1t");
    }
    private static final Pattern ARCHITECTURE = Pattern.compile("(?:arch=)?mips(?:0|1|2|32|32r2)");
    /** An operand or expression atom: a number, which is skipped, or a name; a leading % marks an address modifier. */
    private static final Pattern REFERENCE = Pattern.compile("0[xX][0-9a-fA-F]+|[0-9]+[bf]?|%?[.$A-Za-z_][.$A-Za-z_0-9]*");

    private final ErrorList errors = new ErrorList();
    private final List<Unit> units = new ArrayList<>();
    /** The definition every unit sees for a global name: strong, or else the first weak one. */
    private final Map<String, Symbol> globals = new LinkedHashMap<>();
    private final List<Fragment> textGaps = new ArrayList<>();
    /** The merged range of each array family, whose ends are the ld-provided bounds. */
    private final Map<Integer, Section> arrayRanges = new HashMap<>();
    private final RuntimeLibrary library;
    /** A global the link must define, as ld's entry symbol; it also pulls its library member. */
    private final String entrySymbol;
    /**
     * Whether a MARS-dialect program's global symbol table also supplies definitions: true when
     * library members are linked after a program the legacy assembler is assembling.
     */
    private boolean legacyGlobals;
    /** The file of that program, named when a member defines one of its globals again. */
    private String legacyFile;
    private Section legacyText, legacyData;

    private final class Section {
        final Unit unit;
        final String name, flags, type, entrySize, group;
        final int family;
        int size, alignment;
        long base;
        /** A later unit's copy of a COMDAT group that an earlier unit already supplied. */
        boolean duplicateGroup;
        /**
         * Where an .init_array or .fini_array section goes among the others: `.init_array.00200` is
         * priority 200, and the plain section comes after every numbered one, as ld sorts them.
         */
        int priority = 65536;
        final List<Fragment> fragments = new ArrayList<>();
        /** Labels defined since the last emission here, which GNU as moves to the next alignment. */
        final List<Symbol> pendingLabels = new ArrayList<>();
        Section(Unit unit, String name, String flags, String type, String entrySize, String group, int family) {
            this.unit = unit; this.name = name; this.flags = flags; this.type = type; this.family = family;
            this.entrySize = entrySize; this.group = group;
            alignment = family == TEXT ? 4 : 1;
        }
        boolean executable() { return family == TEXT; }
        boolean discarded() { return family < 0 || duplicateGroup; }
        boolean zeroed() { return type.equals("@nobits"); }
    }
    private static final class Fragment {
        final Section section;
        final int line, offset, size;
        final String instruction;
        final List<String> expressions;
        final int width;
        final byte[] bytes;
        final int fill;
        Fragment(Section section, int line, int offset, int size, String instruction,
                 List<String> expressions, int width, byte[] bytes, int fill) {
            this.section = section; this.line = line; this.offset = offset; this.size = size;
            this.instruction = instruction; this.expressions = expressions; this.width = width;
            this.bytes = bytes; this.fill = fill;
        }
        long address() { return section.base + offset; }
    }
    private static final class Symbol {
        final Unit unit;
        final String name;
        final Section section;
        final int line;
        final String alias;
        /** Not final: GNU as moves a label down to the alignment that follows it. */
        int offset;
        Symbol(Unit unit, String name, Section section, int offset, int line, String alias) {
            this.unit = unit; this.name = name; this.section = section; this.offset = offset; this.line = line; this.alias = alias;
        }
    }
    private static final class Value {
        final BigInteger number;
        final Section section;
        final boolean symbolic;
        Value(BigInteger number, Section section, boolean symbolic) {
            this.number = number; this.section = section; this.symbolic = symbolic;
        }
    }
    private static final class Invalid extends RuntimeException {
        Invalid(String message) { super(message); }
    }

    /** Assembles one translation unit. */
    public GnuAssembler(MIPSprogram program) { this(List.of(program), null, null); }
    /** Assembles and links translation units in link order. */
    public GnuAssembler(List<MIPSprogram> programs) { this(programs, null, null); }
    /**
     * Assembles and links translation units in link order, followed by the library members they
     * need. {@code library} and {@code entrySymbol} may be null.
     */
    public GnuAssembler(List<MIPSprogram> programs, RuntimeLibrary library, String entrySymbol) {
        this.library = library;
        this.entrySymbol = entrySymbol == null || entrySymbol.isEmpty() ? null : entrySymbol;
        for (MIPSprogram program : programs) units.add(new Unit(program, units.size()));
    }
    public ErrorList getErrors() { return errors; }

    /** The global symbols one GNU unit defines and the ones it needs from elsewhere. */
    public static final class UnitSymbols {
        /** Globals the unit defines, strong or weak. */
        public final String[] defined;
        /** The subset of {@code defined} that is weak. */
        public final String[] weak;
        /** Globals the unit uses without defining them, other than weak references. */
        public final String[] references;
        UnitSymbols(String[] defined, String[] weak, String[] references) {
            this.defined = defined; this.weak = weak; this.references = references;
        }
    }

    /**
     * Parses one GNU unit without laying it out, for building a library index: which globals it
     * defines and which it pulls from elsewhere. The instruction set must be initialized.
     */
    public static UnitSymbols analyze(String path, String source) throws ProcessingException {
        MemoryFileSystem files = new MemoryFileSystem();
        files.write(path, source);
        MIPSprogram program = new MIPSprogram();
        program.prepareForAssembly(path, files, AssemblerProfile.GNU_COMPILER_V1);
        GnuAssembler assembler = new GnuAssembler(program);
        Unit unit = assembler.units.get(0);
        unit.parseAll();
        assembler.fail();
        List<String> defined = new ArrayList<>(unit.definedGlobals()), weak = new ArrayList<>();
        for (String name : defined) if (unit.weak.contains(name)) weak.add(name);
        return new UnitSymbols(defined.toArray(new String[0]), weak.toArray(new String[0]),
                unit.strongReferences().toArray(new String[0]));
    }

    /** Expand includes without the educational tokenizer rejecting GNU expressions. */
    public static List<TokenList> prepare(MIPSprogram program, MIPSFileSystem files) throws ProcessingException {
        ArrayList<SourceLine> lines = new ArrayList<>();
        ErrorList errors = new ErrorList();
        expand(program, files, new ArrayList<>(), lines, errors);
        program.setSourceLineList(lines);
        List<TokenList> tokens = new ArrayList<>();
        Map<String, TokenTypes> types = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            SourceLine line = lines.get(i);
            TokenList list = new TokenList();
            list.setProcessedLine(line.getSource());
            Matcher matcher = LEX.matcher(line.getSource());
            while (matcher.find()) {
                String text = matcher.group();
                TokenTypes type = types.get(text);
                if (type == null) {
                    try { type = TokenTypes.matchTokenType(text); }
                    catch (RuntimeException exception) { type = null; }
                    if (type == null || type == TokenTypes.ERROR) type = TokenTypes.IDENTIFIER;
                    types.put(text, type);
                }
                Token token = new Token(type, text, program, i + 1, matcher.start() + 1);
                token.setOriginal(line.getMIPSprogram(), line.getLineNumber());
                list.add(token);
            }
            tokens.add(list);
        }
        if (errors.errorsOccurred()) throw new ProcessingException(errors);
        return tokens;
    }
    private static void expand(MIPSprogram source, MIPSFileSystem files, List<String> stack,
                               ArrayList<SourceLine> output, ErrorList errors) {
        if (stack.size() >= 64 || stack.contains(source.getFilename())) {
            throw new IllegalArgumentException("Recursive or excessive include: " + source.getFilename());
        }
        stack.add(source.getFilename());
        List<String> lines = source.getCurrentSourceList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String text = uncomment(line).trim();
            output.add(new SourceLine(line, source, i + 1));
            if (!INCLUDE.matcher(text).matches()) continue;
            try {
                List<String> args = arguments(text.substring(8));
                require(args.size() == 1, ".include requires one quoted path");
                String path = SourcePath.resolveInclude(source.getFilename(), unquote(args.get(0)));
                require(!stack.contains(path) && stack.size() < 64, "Recursive or excessive include: " + path);
                MIPSprogram child = new MIPSprogram();
                child.readSource(path, files.read(path));
                expand(child, files, stack, output, errors);
            } catch (RuntimeException exception) {
                errors.add(new ErrorMessage(source, i + 1, 1, exception.getMessage()));
            }
        }
        stack.remove(stack.size() - 1);
    }

    /** Parses every unit, links them and writes the program to memory. Returns the executable statements by address. */
    public ArrayList<ProgramStatement> assemble() throws ProcessingException {
        for (Unit unit : units) unit.parseAll();
        fail();
        Set<String> required = new LinkedHashSet<>();
        if (entrySymbol != null) required.add(entrySymbol);
        resolveMembers(required);
        return link(Integer.toUnsignedLong(Memory.textBaseAddress), Integer.toUnsignedLong(Memory.dataBaseAddress));
    }

    /**
     * Links the library members a MARS-dialect program needs, after that program's first pass:
     * {@code undefined} are the globals it uses without defining, and the members are placed after
     * its text and data. The members' globals enter the global symbol table, so the program's
     * second pass resolves against them; a member can use the program's own globals in turn, and a
     * global the program defines is never pulled from the library. Returns no statements when no
     * member defines any of the names.
     */
    public ArrayList<ProgramStatement> linkAfter(Set<String> undefined, int textEnd, int dataEnd, String programFile) throws ProcessingException {
        legacyGlobals = true;
        legacyFile = programFile;
        resolveMembers(undefined);
        if (units.isEmpty()) return new ArrayList<>();
        return link(Integer.toUnsignedLong(textEnd), Integer.toUnsignedLong(dataEnd));
    }

    /**
     * Pulls library members until every strong global the units use is defined or no member
     * defines it, in rounds, so the link order is the order members were first needed.
     */
    private void resolveMembers(Set<String> required) throws ProcessingException {
        if (library == null) return;
        Set<String> loaded = new HashSet<>();
        for (Unit unit : units) loaded.add(unit.program.getFilename());
        boolean pulled = true;
        while (pulled) {
            pulled = false;
            Set<String> defined = new HashSet<>();
            Set<String> wanted = new LinkedHashSet<>(required);
            for (Unit unit : units) {
                defined.addAll(unit.definedGlobals());
                wanted.addAll(unit.strongReferences());
                // A weak reference pulls nothing, except to what the library says it always
                // supplies: GCC refers to __cxa_pure_virtual weakly from every abstract class's vtable.
                for (String name : unit.weakReferences()) if (library.resolvesWeak(name)) wanted.add(name);
            }
            for (String name : wanted) {
                if (defined.contains(name)) continue;
                if (legacyGlobals && Globals.symbolTable.getAddress(name) != SymbolTable.NOT_FOUND) continue;
                String path = library.memberFor(name);
                if (path == null || !loaded.add(path)) continue;
                try {
                    Unit unit = new Unit(library.load(path), units.size());
                    units.add(unit);
                    unit.parseAll();
                    pulled = true;
                } catch (ProcessingException exception) {
                    for (ErrorMessage message : exception.errors().getErrorMessages()) errors.add(message);
                }
            }
            fail();
        }
    }

    private ArrayList<ProgramStatement> link(long textBase, long dataBase) throws ProcessingException {
        // A conditional branch reaches +-128 KiB. GCC splits a longer one itself, but a branch can
        // only be found to be far once the program is laid out, and making it longer moves
        // everything after it, alignment included, so the units are parsed again with every far
        // branch in its long form (the inverted branch over a j) and laid out again, until no
        // branch is far. Nothing has been written to memory or to a symbol table before this
        // loop ends.
        for (int round = 0; ; round++) {
            globals.clear();
            textGaps.clear();
            arrayRanges.clear();
            Set<String> suppliedGroups = new HashSet<>();
            for (Unit unit : units) {
                Set<String> groups = new HashSet<>();
                for (Section section : unit.sections.values()) {
                    if (section.group == null) continue;
                    section.duplicateGroup = suppliedGroups.contains(section.group);
                    groups.add(section.group);
                }
                suppliedGroups.addAll(groups);
            }
            for (Unit unit : units) unit.exportGlobals();
            if (entrySymbol != null && !globals.containsKey(entrySymbol))
                errors.add(new ErrorMessage(units.get(0).program, 1, 1, "Undefined entry symbol: " + entrySymbol));
            fail();
            attempt(units.get(0), 1, () -> layout(textBase, dataBase));
            fail();
            boolean relaxed = false;
            for (Unit unit : units) relaxed |= unit.relaxFarBranches();
            if (!relaxed || round >= 16) break;
            List<Unit> again = new ArrayList<>();
            for (Unit unit : units) {
                Unit next = new Unit(unit.program, unit.index);
                next.longBranches.addAll(unit.longBranches);
                next.parseAll();
                again.add(next);
            }
            units.clear();
            units.addAll(again);
            fail();
        }
        for (Unit unit : units) unit.bindSymbols();
        for (Map.Entry<String, Symbol> global : globals.entrySet()) {
            Symbol symbol = global.getValue();
            symbol.unit.attempt(symbol.line, () -> {
                Value value = symbol.unit.valueOf(symbol, new HashSet<>());
                if (value.number.signum() >= 0 && value.number.bitLength() <= 32) {
                    Token token = new Token(TokenTypes.IDENTIFIER, symbol.name, symbol.unit.program, symbol.line, 1);
                    Globals.symbolTable.addSymbol(token, value.number.intValue(), value.section != null && !value.section.executable(), errors);
                }
            });
        }
        fail();
        ArrayList<ProgramStatement> machine = new ArrayList<>();
        for (Fragment gap : textGaps) gap.section.unit.attempt(gap.line, () -> gap.section.unit.emit(gap, machine));
        for (Unit unit : units) unit.emitAll(machine);
        fail();
        machine.sort((first, second) -> Long.compare(Integer.toUnsignedLong(first.getAddress()), Integer.toUnsignedLong(second.getAddress())));
        for (ProgramStatement statement : machine) statement.getSourceMIPSprogram().getParsedList().add(statement);
        SystemIO.resetFiles();
        return machine;
    }
    private void attempt(Unit unit, int line, Runnable action) { unit.attempt(line, action); }
    private void fail() throws ProcessingException { if (errors.errorsOccurred()) throw new ProcessingException(errors); }
    private static void require(boolean condition, String message) { if (!condition) throw new Invalid(message); }
    private static boolean matches(Pattern pattern, String text) { return pattern.matcher(text).matches(); }
    /** The array family whose bound ld provides under this name when nothing defines it, or -1. */
    private static int arrayBound(String name) {
        switch (name) {
            case "__init_array_start": case "__init_array_end": return INIT;
            case "__fini_array_start": case "__fini_array_end": return FINI;
            default: return -1;
        }
    }
    /** A general or floating point register, as MARS names them. */
    private static boolean isRegister(String text) {
        return text.length() > 1 && text.charAt(0) == '$' &&
                (RegisterFile.getUserRegister(text) != null || Coprocessor1.getRegister(text) != null);
    }
    private static boolean isGeneralRegister(String text) {
        return text.length() > 1 && text.charAt(0) == '$' && RegisterFile.getUserRegister(text) != null;
    }
    private static boolean isZeroRegister(String text) { return text.equals("$0") || text.equals("$zero"); }
    /** Whether an address lies in the segment its section belongs to; a MARS-dialect global may be a kernel one. */
    private static boolean mapped(Section section, int address) {
        return section.executable() ? Memory.inTextSegment(address) || Memory.inKernelTextSegment(address)
                : Memory.inDataSegment(address) || Memory.inKernelDataSegment(address);
    }

    /** Places each family's sections in unit order, then each unit's sections in order of first appearance. */
    private void layout(long textBase, long dataBase) {
        long text = textBase, data = dataBase;
        long total = 0;
        for (int family = TEXT; family <= COMMON; family++) {
            Section range = null;
            if (family == INIT || family == FINI) {
                // The bounds start after the first member's alignment, as ld places them.
                range = new Section(units.get(0), family == INIT ? ".init_array" : ".fini_array", "aw", "@progbits", null, null, family);
                arrayRanges.put(family, range);
            }
            List<Section> placed = new ArrayList<>();
            for (Unit unit : units) for (Section section : unit.sections.values())
                if (section.family == family && !section.discarded()) placed.add(section);
            // A stable sort: equal priorities keep link order.
            if (range != null) placed.sort(Comparator.comparingInt(section -> section.priority));
            for (Section section : placed) {
                long position = family == TEXT ? text : data;
                position = (position + section.alignment - 1) & -(long)section.alignment;
                section.base = position;
                if (range != null && range.size == 0 && range.base == 0) range.base = position;
                if (family == TEXT && position > text) {
                    require(position - text <= MAX_BYTES, "Text section gap exceeds emission limit");
                    int line = section.fragments.isEmpty() ? 1 : section.fragments.get(0).line;
                    textGaps.add(new Fragment(section, line, (int)(text - position), (int)(position - text), null, null, 0, null, 0));
                    total += position - text;
                }
                long end = position + section.size;
                require(end <= 0xffffffffL && (section.size == 0 || (family == TEXT ? Memory.inTextSegment((int)(end - 1)) : Memory.inDataSegment((int)(end - 1)))), "Section exceeds mapped memory: " + section.name);
                if (family == TEXT) text = end; else data = end;
                total += section.size;
            }
            if (range != null) {
                if (range.base == 0) range.base = data;
                range.size = (int)(data - range.base);
            }
        }
        require(total <= MAX_BYTES, "Assembly exceeds 16 MiB emission limit");
        require(data <= Integer.toUnsignedLong(Memory.heapBaseAddress), "Static data reaches the heap at 0x" + Integer.toHexString(Memory.heapBaseAddress));
    }

    /** Where a MARS-dialect program's global lives, for the checks a section supplies: text or data. */
    private Section legacySection(int address) {
        if (Memory.inTextSegment(address) || Memory.inKernelTextSegment(address)) {
            if (legacyText == null) legacyText = new Section(null, "(program text)", "ax", "@progbits", null, null, TEXT);
            return legacyText;
        }
        if (legacyData == null) legacyData = new Section(null, "(program data)", "aw", "@progbits", null, null, DATA);
        return legacyData;
    }

    /** One translation unit: its own sections, local symbols, numeric labels and directives state. */
    private final class Unit {
        final MIPSprogram program;
        final int index;
        final LinkedHashMap<String, Section> sections = new LinkedHashMap<>();
        /** The most recently declared section of each name, for re-entry without flags. */
        final Map<String, Section> sectionsByName = new HashMap<>();
        final LinkedHashMap<String, Symbol> symbols = new LinkedHashMap<>();
        final Map<String, Symbol> forwardAliases = new HashMap<>();
        final Map<String, List<Symbol>> numeric = new HashMap<>();
        final Set<String> declared = new LinkedHashSet<>();
        final Set<String> weak = new HashSet<>();
        final Set<String> definedCommon = new HashSet<>();
        /** Lines whose conditional branch a previous layout found out of range, emitted long. */
        final Set<Integer> longBranches = new HashSet<>();
        final Deque<Section> sectionStack = new ArrayDeque<>();
        /** The .set options that matter here, and the stack .set push and .set pop keep of them. */
        boolean reorder = true, at = true, macro = true;
        final Deque<boolean[]> optionStack = new ArrayDeque<>();
        /** GNU as for MIPS aligns .half, .word, .dword, .float and .double until .align 0. */
        boolean autoAlign = true;
        /** The section whose last instruction was a branch in noreorder mode, still owed its delay slot. */
        Section delaySlot;
        Section current, previous;

        Unit(MIPSprogram program, int index) { this.program = program; this.index = index; }

        void attempt(int line, Runnable action) {
            if (errors.errorLimitExceeded()) return;
            try { action.run(); }
            catch (Invalid | IllegalArgumentException exception) { errors.add(new ErrorMessage(program, line, 1, exception.getMessage())); }
        }

        void parseAll() {
            program.createParsedList();
            program.createMacroPool();
            current = section(".text", null, null, null, null);
            List<String> lines = program.getCurrentSourceList();
            // Absolute aliases may be declared after a layout count or li. Their actual
            // section/location is bound in parse(); this index is used only in absolute contexts.
            for (int i = 0; i < lines.size(); i++) {
                String text = uncomment(lines.get(i)).trim();
                Matcher assignment = ASSIGNMENT.matcher(text);
                Matcher directive = SET_DIRECTIVE.matcher(text);
                Matcher match = assignment.matches() ? assignment : directive.matches() ? directive : null;
                if (match != null) forwardAliases.putIfAbsent(match.group(1), new Symbol(this, match.group(1), null, 0, i + 1, match.group(2)));
            }
            for (int i = 0; i < lines.size(); i++) {
                final int line = i + 1;
                attempt(line, () -> parse(uncomment(lines.get(line - 1)).trim(), line));
            }
        }

        /** The global and weak names this unit defines. */
        Set<String> definedGlobals() {
            Set<String> names = new LinkedHashSet<>();
            for (String name : declared) {
                Symbol symbol = symbols.get(name);
                if (symbol != null && (symbol.section == null || symbol.section.family >= 0)) names.add(name);
            }
            return names;
        }

        /**
         * The names this unit uses without defining them, other than weak references, which ld
         * never pulls an archive member for. A name declared global and not defined counts as used.
         */
        Set<String> strongReferences() {
            Set<String> names = referencedNames();
            names.removeIf(name -> symbols.containsKey(name) || weak.contains(name));
            return names;
        }

        /** The names this unit refers to weakly without defining them, which ld never pulls a member for. */
        Set<String> weakReferences() {
            Set<String> names = referencedNames();
            names.removeIf(name -> symbols.containsKey(name) || !weak.contains(name));
            return names;
        }

        private Set<String> referencedNames() {
            Set<String> names = new LinkedHashSet<>();
            for (Section section : sections.values()) {
                if (section.family < 0) continue;
                for (Fragment fragment : section.fragments) {
                    if (fragment.instruction != null) {
                        int space = fragment.instruction.indexOf(' ');
                        if (space >= 0) collectNames(fragment.instruction.substring(space + 1), names);
                    }
                    if (fragment.expressions != null) for (String expression : fragment.expressions) collectNames(expression, names);
                }
            }
            for (Symbol symbol : symbols.values())
                if (symbol.alias != null && (symbol.section == null || symbol.section.family >= 0)) collectNames(symbol.alias, names);
            names.addAll(declared);
            return names;
        }
        private void collectNames(String text, Set<String> names) {
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                String name = matcher.group();
                char first = name.charAt(0);
                // Numbers, local numeric labels, address modifiers, the location counter and registers are not symbols.
                if (first == '%' || Character.isDigit(first) || name.equals(".") || isRegister(name) || matches(CONDITION_CODE, name)) continue;
                names.add(name);
            }
        }

        /**
         * Marks the lines whose conditional branch the current layout puts out of range, and says
         * whether any were new. A target that does not resolve is left for emission to report.
         */
        boolean relaxFarBranches() {
            boolean found = false;
            for (Section section : sections.values()) {
                if (!section.executable() || section.discarded()) continue;
                for (Fragment fragment : section.fragments) {
                    if (fragment.instruction == null || longBranches.contains(fragment.line)) continue;
                    Matcher branch = CONDITIONAL_BRANCH.matcher(fragment.instruction);
                    if (!branch.matches()) continue;
                    try {
                        Value target = evaluate(branch.group(3), fragment.line, section, fragment.offset, false);
                        // The offset counts words from the delay slot: +-128 KiB.
                        long delta = target.number.longValue() - (fragment.address() + 4);
                        if (delta < -(1L << 17) || delta >= (1L << 17)) {
                            longBranches.add(fragment.line);
                            found = true;
                        }
                    } catch (Invalid | IllegalArgumentException ignored) {
                        // Reported, with its line, when the instruction is emitted.
                    }
                }
            }
            return found;
        }

        /** Offers this unit's global and weak definitions to the other units. */
        void exportGlobals() {
            for (String name : declared) {
                Symbol symbol = symbols.get(name);
                if (symbol == null || symbol.section != null && symbol.section.discarded()) continue;
                attempt(symbol.line, () -> {
                    boolean strong = !weak.contains(name);
                    if (legacyGlobals && Globals.symbolTable.getAddress(name) != SymbolTable.NOT_FOUND) {
                        // The program's own definition comes first, as an object file's does before an archive.
                        require(!strong, "Multiple definition of " + name + ", first defined in " + legacyFile);
                        return;
                    }
                    Symbol existing = globals.get(name);
                    if (existing == null || strong && existing.unit.weak.contains(name)) { globals.put(name, symbol); return; }
                    require(!strong || existing.unit.weak.contains(name), "Multiple definition of " + name + ", first defined in " + existing.unit.program.getFilename());
                });
            }
        }

        /** Records this unit's symbols in its local table and checks that its non-weak declarations resolve. */
        void bindSymbols() {
            // Compiler-internal and synthetic names go last, so that a lookup by address finds a
            // function or object name before the $LFB alias GCC places on the same address.
            List<Symbol> ordered = new ArrayList<>();
            for (Symbol symbol : symbols.values()) if (!internal(symbol.name)) ordered.add(symbol);
            for (Symbol symbol : symbols.values()) if (internal(symbol.name)) ordered.add(symbol);
            for (Symbol symbol : ordered) attempt(symbol.line, () -> {
                if (symbol.section != null && symbol.section.discarded()) return;
                // A weak definition another unit overrides is not this name's address.
                if (declared.contains(symbol.name) && globals.get(symbol.name) != symbol) return;
                Value value = resolve(symbol.name, symbol.line, symbol.section, symbol.offset, false, new HashSet<>());
                if (value.number.signum() >= 0 && value.number.bitLength() <= 32) {
                    Token token = new Token(TokenTypes.IDENTIFIER, symbol.name, program, symbol.line, 1);
                    program.getLocalSymbolTable().addSymbol(token, value.number.intValue(), value.section != null && !value.section.executable(), errors);
                }
            });
            for (String name : declared) if (!weak.contains(name)) attempt(1, () -> resolve(name, 1, current, 0, false, new HashSet<>()));
        }
        private boolean internal(String name) { return name.startsWith("$L") || name.startsWith(".L") || name.startsWith("__gnu_"); }

        void emitAll(ArrayList<ProgramStatement> machine) {
            for (Section section : sections.values()) {
                if (section.discarded()) continue;
                for (Fragment fragment : section.fragments) attempt(fragment.line, () -> emit(fragment, machine));
            }
        }

        private void parse(String text, int line) {
            require(!hasStatementSeparator(text), "Multiple GNU statements on one line are unsupported");
            Matcher label = LABEL.matcher(text);
            while (label.find()) {
                String name = label.group(1);
                Symbol symbol = new Symbol(this, name, current, current.size, line, null);
                if (matches(DIGITS, name)) {
                    numeric.computeIfAbsent(name, key -> new ArrayList<>()).add(symbol);
                    current.pendingLabels.add(symbol);
                    symbol = new Symbol(this, "__gnu_numeric_" + line + "_" + name, current, current.size, line, null);
                }
                define(symbol);
                current.pendingLabels.add(symbol);
                text = text.substring(label.end()).trim();
                label = LABEL.matcher(text);
            }
            if (text.isEmpty()) return;
            Matcher assignment = ASSIGNMENT.matcher(text);
            if (assignment.matches()) {
                if (!current.discarded()) define(new Symbol(this, assignment.group(1), current, current.size, line, assignment.group(2)));
                return;
            }
            int split = text.indexOf(' '), tab = text.indexOf('\t');
            if (split < 0 || tab >= 0 && tab < split) split = tab;
            String op = split < 0 ? text : text.substring(0, split);
            String rest = split < 0 ? "" : text.substring(split).trim();
            List<String> args = arguments(rest);
            if (op.equals(".include")) return;
            if (op.equals(".text") || op.equals(".data") || op.equals(".bss") || op.equals(".rodata") ||
                    op.equals(".rdata") || op.equals(".sdata") || op.equals(".sbss")) {
                require(args.isEmpty(), "Section addresses/subsections are unsupported");
                switchSection(section(op, null, null, null, null)); return;
            }
            if (op.equals(".section") || op.equals(".pushsection")) {
                Section next = sectionDirective(args);
                if (op.equals(".pushsection")) sectionStack.push(current);
                switchSection(next); return;
            }
            if (op.equals(".popsection") || op.equals(".previous")) {
                require(args.isEmpty(), "Unexpected section operands");
                require(op.equals(".previous") ? previous != null : !sectionStack.isEmpty(), "Section stack underflow");
                switchSection(op.equals(".previous") ? previous : sectionStack.pop()); return;
            }
            // Assembler options and ABI declarations apply wherever they appear.
            if (op.equals(".set") && args.size() == 1) { option(args.get(0)); return; }
            if (op.equals(".module")) { module(args); return; }
            if (op.equals(".nan")) {
                require(args.size() == 1 && (args.get(0).equals("legacy") || args.get(0).equals("2008")), "Unsupported NaN encoding: " + rest);
                return;
            }
            if (op.equals(".gnu_attribute")) { attribute(args, line); return; }
            if (op.equals(".option")) { require(args.size() == 1 && args.get(0).equals("pic0"), "Unsupported GNU option: " + rest); return; }
            // Only audited nonallocatable families can be discarded. Their labels are
            // retained so references from runtime sections produce a specific error.
            if (current.family < 0) return;
            if (op.equals(".set") || op.equals(".equ")) {
                require(args.size() == 2 && matches(NAME, args.get(0)), "Invalid alias definition");
                define(new Symbol(this, args.get(0), current, current.size, line, args.get(1))); return;
            }
            if (op.equals(".globl") || op.equals(".global") || op.equals(".weak")) {
                require(!args.isEmpty(), "Missing symbol declaration");
                for (String name : args) {
                    require(matches(NAME, name), "Invalid symbol name");
                    declared.add(name);
                    if (op.equals(".weak")) weak.add(name);
                }
                return;
            }
            if (op.equals(".local") || op.equals(".hidden") || op.equals(".protected") || op.equals(".internal")) {
                require(!args.isEmpty(), "Missing symbol declaration");
                for (String name : args) require(matches(NAME, name), "Invalid symbol name");
                return;
            }
            if (op.equals(".extern")) {
                // A declaration only: GNU as treats every undefined name as external.
                require(!args.isEmpty() && args.size() <= 2 && matches(NAME, args.get(0)), "Invalid external declaration");
                return;
            }
            if (op.equals(".type")) {
                require(args.size() == 2 && matches(SYMBOL_TYPE, args.get(1)), "Unsupported symbol type"); return;
            }
            if (op.equals(".size")) {
                require(args.size() == 2, "Invalid symbol size");
                // Evaluate after layout, just like data fixups, without emitting bytes.
                append(line, 0, null, Collections.singletonList(args.get(1)), 0, null, 0); return;
            }
            // Function bounds and frame descriptions for debuggers and unwinders.
            if (op.equals(".ent") || op.equals(".aent") || op.equals(".end") || op.equals(".frame") ||
                    op.equals(".mask") || op.equals(".fmask") || op.equals(".insn")) return;
            if (op.equals(".file") || op.equals(".loc") || op.equals(".ident") || op.equals(".addrsig") || op.equals(".addrsig_sym") ||
                    matches(IGNORED_CFI, op)) return;
            if (op.equals(".abicalls") || op.equals(".cpload") || op.equals(".cprestore") || op.equals(".cpsetup") ||
                    op.equals(".cpreturn") || op.equals(".cplocal") || op.equals(".cpadd") || op.equals(".gpword") || op.equals(".gpvalue")) {
                throw new Invalid("Position-independent code is outside GNU compiler v1: " + op + " (compile with -mno-abicalls -fno-pic)");
            }
            if (op.equals(".comm") || op.equals(".lcomm")) {
                require(args.size() >= 2 && args.size() <= 3, "Invalid common allocation");
                require(matches(NAME, args.get(0)), "Invalid common symbol name");
                require(definedCommon.add(args.get(0)), "Competing common definitions: " + args.get(0));
                int size = count(args.get(1), line), align = args.size() > 2 ? count(args.get(2), line) : Math.max(1, Integer.highestOneBit(Math.min(size, 16)));
                require(align > 0 && (align & (align - 1)) == 0, "Common alignment must be a power of two");
                Section saved = current;
                current = section(".common", "aw", "@nobits", null, null);
                align(align, 0, MAX_BYTES, line, false);
                define(new Symbol(this, args.get(0), current, current.size, line, null));
                append(line, size, null, null, 0, null, 0);
                current = saved; return;
            }
            if (op.equals(".align") || op.equals(".p2align") || op.equals(".balign")) {
                require(args.size() >= 1 && args.size() <= 3, "Invalid alignment operands");
                int alignment = count(args.get(0), line);
                if (op.equals(".align") && alignment == 0) {
                    // GNU as for MIPS: .align 0 stops data directives aligning themselves until the next section change.
                    autoAlign = false; return;
                }
                if (!op.equals(".balign")) { require(alignment <= 24, "Alignment exponent exceeds limit"); alignment = 1 << alignment; }
                require(alignment > 0, "Alignment must be positive");
                int fill = args.size() > 1 && !args.get(1).isEmpty() ? count(args.get(1), line) : -1;
                require(fill <= 255, "Alignment fill must be a byte");
                int max = args.size() > 2 ? count(args.get(2), line) : MAX_BYTES;
                // .align also turns automatic alignment back on and, as GNU as for MIPS does,
                // pulls the labels right before it down to the aligned address.
                if (op.equals(".align")) autoAlign = true;
                align(alignment, fill, max, line, op.equals(".align")); return;
            }
            if (op.equals(".zero") || op.equals(".space") || op.equals(".skip")) {
                require(args.size() >= 1 && args.size() <= 2 && !current.executable(), "Invalid data allocation");
                int fill = args.size() > 1 ? count(args.get(1), line) : 0;
                require(fill <= 255 && (!current.zeroed() || fill == 0), "Invalid fill in zeroed section");
                append(line, count(args.get(0), line), null, null, 0, null, fill); return;
            }
            if (op.equals(".ascii") || op.equals(".asciz") || op.equals(".asciiz") || op.equals(".string")) {
                require(!current.executable() && !args.isEmpty(), "Strings require a data section");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (String arg : args) {
                    byte[] payload = stringBytes(arg);
                    bytes.write(payload, 0, payload.length);
                    if (!op.equals(".ascii")) bytes.write(0);
                }
                byte[] payload = bytes.toByteArray();
                require(!current.zeroed() || allZero(payload), "Nonzero data in zeroed section");
                append(line, payload.length, null, null, 0, payload, 0); return;
            }
            if (op.equals(".float") || op.equals(".single") || op.equals(".double")) {
                require(!args.isEmpty() && !current.executable() && !current.zeroed(), "Invalid floating data");
                int width = op.equals(".double") ? 8 : 4;
                naturalAlignment(width, line);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (String arg : args) {
                    long bits;
                    try { bits = width == 4 ? Float.floatToRawIntBits(Float.parseFloat(arg)) & 0xffffffffL : Double.doubleToRawLongBits(Double.parseDouble(arg)); }
                    catch (NumberFormatException exception) { throw new Invalid("Invalid floating literal: " + arg); }
                    for (int i = 0; i < width; i++) bytes.write((int)(bits >>> (8 * i)) & 255);
                }
                append(line, bytes.size(), null, null, 0, bytes.toByteArray(), 0); return;
            }
            int width = integerWidth(op);
            if (width != 0) {
                require(!args.isEmpty() && !current.executable(), "Integer data requires a data section");
                // .2byte, .4byte and .8byte are the generic directives, which never align.
                if (!op.endsWith("byte")) naturalAlignment(width, line);
                append(line, Math.multiplyExact(width, args.size()), null, args, width, null, 0); return;
            }
            require(!op.startsWith("."), "Unsupported GNU directive: " + op);
            require(current.executable(), "Instruction outside executable section");
            require(current.size % 4 == 0, "Instruction address is not four-byte aligned");
            // GNU as allows spaces inside an operand, as in 8 ( $sp ); none of them is significant.
            for (int i = 0; i < args.size(); i++) if (args.get(i).indexOf(' ') >= 0 || args.get(i).indexOf('\t') >= 0) args.set(i, WHITESPACE.matcher(args.get(i)).replaceAll(""));
            if (delaySlot == current) {
                delaySlot = null;
                require(op.equals("nop"), "Filled branch delay slots are unsupported: " + op + " (MARS runs without delayed branching, so the slot must hold a nop)");
            }
            for (String instruction : lower(op, args, line)) {
                Matcher branch = CONDITIONAL_BRANCH.matcher(instruction);
                if (longBranches.contains(line) && branch.matches()) {
                    // b<inverse> operands,past; j target (jal for a branch and link); past:
                    String past = "__gnu_relax_" + line + "_" + current.size, operation = branch.group(1);
                    define(new Symbol(this, past, current, current.size + 8, line, null));
                    append(line, 4, INVERSE_BRANCH.get(operation) + " " + (branch.group(2) == null ? "" : branch.group(2) + ",") + past, null, 0, null, 0);
                    append(line, 4, (operation.endsWith("al") ? "jal " : "j ") + branch.group(3), null, 0, null, 0);
                } else append(line, 4, instruction, null, 0, null, 0);
            }
            if (!reorder && matches(DELAYED, op)) delaySlot = current;
        }
        private void define(Symbol symbol) {
            require(!symbols.containsKey(symbol.name), "Duplicate/reassigned symbol: " + symbol.name);
            symbols.put(symbol.name, symbol);
        }
        private void switchSection(Section next) { previous = current; current = next; autoAlign = true; }
        /** `.section name[,"flags"[,@type[,entry size][,group,comdat]]]` */
        private Section sectionDirective(List<String> args) {
            require(!args.isEmpty(), "Unsupported section declaration");
            String name = unquoteOptional(args.get(0));
            String flags = args.size() > 1 ? unquote(args.get(1)) : null;
            String type = args.size() > 2 ? args.get(2) : null;
            int next = 3;
            String entrySize = null, group = null;
            if (flags != null && flags.contains("M") && args.size() > next) entrySize = args.get(next++);
            if (flags != null && flags.contains("G")) {
                require(args.size() > next, "Group section flags require a group name: " + name);
                group = args.get(next++);
                require(matches(NAME, group), "Invalid section group name: " + group);
                // Without "comdat" a group is never deduplicated, which no supported compiler emits.
                require(args.size() > next && args.get(next++).equals("comdat"), "Only COMDAT section groups are supported: " + name);
            }
            require(args.size() <= next, "Unsupported section declaration");
            if (flags == null) {
                Section known = sectionsByName.get(name);
                if (known != null) return known;
            }
            return section(name, flags, type, entrySize, group);
        }
        private Section section(String name, String flags, String type, String entrySize, String group) {
            int family = matches(TEXT_SECTION, name) ? TEXT : matches(RODATA_SECTION, name) ? RODATA :
                    name.equals(".init_array") || name.startsWith(".init_array.") && matches(PRIORITIZED_ARRAY, name) ? INIT :
                    name.equals(".fini_array") || name.startsWith(".fini_array.") && matches(PRIORITIZED_ARRAY, name) ? FINI :
                    matches(DATA_SECTION, name) ? DATA : matches(BSS_SECTION, name) ? BSS : name.equals(".common") ? COMMON : -1;
            boolean debug = matches(DEBUG_SECTION, name);
            require(family >= 0 || debug, "Unsupported section: " + name);
            boolean array = family == INIT || family == FINI;
            String actualFlags = flags == null ? family == TEXT ? "ax" : family == RODATA ? "a" : family >= INIT ? "aw" : "" : flags;
            String actualType = type == null ? family >= BSS ? "@nobits" : "@progbits" : type.replace('%', '@');
            if (array && actualType.equals(family == INIT ? "@init_array" : "@fini_array")) actualType = "@progbits";
            require(matches(SECTION_FLAGS, actualFlags) && (actualType.equals("@progbits") || actualType.equals("@nobits")), "Unsupported section flags/type: " + name);
            require(family >= 0 ? actualFlags.contains("a") && actualFlags.contains("x") == (family == TEXT) && actualFlags.contains("w") == (family >= INIT) : !actualFlags.contains("a"), "Conflicting section flags: " + name);
            require(actualType.equals(family >= BSS ? "@nobits" : "@progbits"), "Conflicting section type: " + name);
            require(group == null || family >= 0, "Section group on a discarded section: " + name);
            if (actualFlags.contains("M")) require(entrySize != null && matches(ENTRY_SIZE, entrySize) && Integer.parseInt(entrySize) <= 256, "Mergeable section requires a bounded entry size");
            else require(entrySize == null, "Unexpected section entry size");
            require(!actualFlags.contains("S") || actualFlags.contains("M"), "String section requires merge flag");
            String key = group == null ? name : name + "\0" + group;
            Section found = sections.get(key);
            if (found != null) {
                if (flags != null) require(found.flags.equals(actualFlags) && found.type.equals(actualType) && Objects.equals(found.entrySize, entrySize), "Conflicting repeated section declaration: " + name);
                return found;
            }
            Section created = new Section(this, name, actualFlags, actualType, entrySize, group, family);
            Matcher prioritized = PRIORITIZED_ARRAY.matcher(name);
            if (prioritized.matches()) created.priority = Integer.parseInt(prioritized.group(1));
            sections.put(key, created);
            sectionsByName.put(name, created);
            return created;
        }
        private void append(int line, int size, String instruction, List<String> expressions, int width, byte[] bytes, int fill) {
            require(size >= 0 && size <= MAX_BYTES - current.size, "Section exceeds 16 MiB limit");
            current.fragments.add(new Fragment(current, line, current.size, size, instruction, expressions, width, bytes, fill));
            current.size += size;
            if (size > 0) current.pendingLabels.clear();
        }
        /**
         * Pads the current section to {@code alignment}. With {@code moveLabels}, as GNU as for
         * MIPS does for .align and for self-aligning data, the labels defined right before move
         * down to the aligned address; .p2align and .balign leave them where they are.
         */
        private void align(int alignment, int fill, int max, int line, boolean moveLabels) {
            require((alignment & (alignment - 1)) == 0, "Alignment must be a power of two");
            current.alignment = Math.max(current.alignment, alignment);
            int skip = (alignment - current.size % alignment) % alignment;
            if (!moveLabels) current.pendingLabels.clear();
            if (skip > max) return;
            require(!current.executable() || skip % 4 == 0, "Executable padding must contain whole instruction words");
            require(!current.zeroed() || fill <= 0, "Nonzero padding in zeroed section");
            List<Symbol> labels = new ArrayList<>(current.pendingLabels);
            append(line, skip, null, null, 0, null, fill < 0 ? current.executable() ? -1 : 0 : fill);
            for (Symbol label : labels) label.offset = current.size;
            // Still right before whatever comes next.
            current.pendingLabels.clear();
            current.pendingLabels.addAll(labels);
        }
        /** Aligns data to its natural size, unless .align 0 turned that off. */
        private void naturalAlignment(int width, int line) {
            if (autoAlign && width > 1) align(width, 0, MAX_BYTES, line, true);
        }
        private int count(String expression, int line) {
            Value value = evaluate(expression, line, current, current.size, true);
            require(value.number.signum() >= 0 && value.number.compareTo(BigInteger.valueOf(MAX_BYTES)) <= 0, "Layout value is out of range");
            return value.number.intValue();
        }
        private void option(String name) {
            switch (name) {
                case "push": optionStack.push(new boolean[] {reorder, at, macro}); break;
                case "pop": {
                    require(!optionStack.isEmpty(), "Option stack underflow");
                    boolean[] saved = optionStack.pop();
                    reorder = saved[0]; at = saved[1]; macro = saved[2]; break;
                }
                case "reorder": reorder = true; break;
                case "noreorder": reorder = false; break;
                case "at": at = true; break;
                case "noat": at = false; break;
                case "macro": macro = true; break;
                case "nomacro": macro = false; break;
                // Settings that leave every supported encoding unchanged.
                case "nomips16": case "nomicromips": case "nomips3d": case "nomdmx": case "nodsp": case "nodspr2":
                case "nomt": case "nomsa": case "novirt": case "volatile": case "novolatile": case "hardfloat":
                case "softfloat": case "singlefloat": case "doublefloat": case "oddspreg": case "nooddspreg": case "fp=32":
                    break;
                default:
                    require(matches(ARCHITECTURE, name), "Unsupported GNU option: .set " + name);
            }
        }
        private void module(List<String> args) {
            require(args.size() == 1, "Invalid .module");
            String value = args.get(0);
            if (value.startsWith("fp=")) {
                require(value.equals("fp=32"), "Unsupported floating point register mode: " + value + " (MARS pairs even and odd registers, as fp=32 does)");
                return;
            }
            switch (value) {
                case "oddspreg": case "nooddspreg": case "hardfloat": case "softfloat": case "singlefloat": case "doublefloat":
                case "nomips16": case "nomicromips": case "nomips3d": case "nomdmx": case "nodsp": case "nodspr2": case "nomt": case "nomsa": case "novirt":
                    return;
                default:
                    require(value.startsWith("arch=") && matches(ARCHITECTURE, value), "Unsupported module option: " + value);
            }
        }
        private void attribute(List<String> args, int line) {
            require(args.size() == 2, "Invalid GNU attribute");
            int tag = count(args.get(0), line), value = count(args.get(1), line);
            // Tag_GNU_MIPS_ABI_FP: any, double, single or soft float; the 64-bit register modes are unsupported.
            require(tag == 4 && value <= 3, "Unsupported GNU attribute: " + args.get(0) + ", " + args.get(1));
        }

        private List<String> lower(String op, List<String> args, int line) {
            for (int i = 0; i < args.size(); i++) {
                // Condition codes are numbered flags to MARS, and $s8 is $fp.
                Matcher code = CONDITION_CODE.matcher(args.get(i));
                if (code.matches()) args.set(i, code.group(1));
                else if (args.get(i).equals("$s8")) args.set(i, "$fp");
            }
            String joined = String.join(",", args);
            switch (op) {
                case "nop": require(args.isEmpty(), "nop takes no operands"); return List.of("nop");
                case "move":
                    require(args.size() == 2 && isGeneralRegister(args.get(0)) && isGeneralRegister(args.get(1)), "move requires two registers");
                    return List.of("or " + joined + ",$0");
                case "li": {
                    require(args.size() == 2 && isGeneralRegister(args.get(0)), "li requires register and constant");
                    BigInteger value = evaluate(args.get(1), line, current, current.size, true).number;
                    require(value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0 && value.compareTo(BigInteger.valueOf(0xffffffffL)) <= 0, "li value exceeds register width");
                    return materialize(args.get(0), value.intValue());
                }
                case "la":
                    require(args.size() == 2 && isGeneralRegister(args.get(0)) && !args.get(1).endsWith(")"), "Unsupported address pseudo operands");
                    return List.of("lui " + args.get(0) + ",%hi(" + args.get(1) + ")", "addiu " + args.get(0) + "," + args.get(0) + ",%lo(" + args.get(1) + ")");
                case "b": require(args.size() == 1, "b requires one operand"); return List.of("beq $0,$0," + joined);
                case "bal": require(args.size() == 1, "bal requires one operand"); return List.of("bgezal $0," + joined);
                case "beqz": case "bnez":
                    require(args.size() == 2, "Invalid zero branch operands");
                    return List.of((op.equals("beqz") ? "beq " : "bne ") + args.get(0) + ",$0," + args.get(1));
                case "neg": case "negu":
                    require(args.size() == 2, op + " requires two operands");
                    return List.of((op.equals("neg") ? "sub " : "subu ") + args.get(0) + ",$0," + args.get(1));
                case "not": require(args.size() == 2, "not requires two operands"); return List.of("nor " + joined + ",$0");
                case "j": if (args.size() == 1 && isGeneralRegister(args.get(0))) return List.of("jr " + joined); break;
                case "jal": if (args.size() >= 1 && isGeneralRegister(args.get(args.size() - 1))) return List.of("jalr " + joined); break;
                case "div": case "divu":
                    // GCC writes the instruction as op $0,rs,rt; without $0 it is GNU as's checking macro.
                    if (args.size() == 3) {
                        require(isZeroRegister(args.get(0)), "Unsupported division macro: " + op + " " + joined);
                        return List.of(op + " " + args.get(1) + "," + args.get(2));
                    }
                    break;
                case "teq": case "tne": case "tge": case "tgeu": case "tlt": case "tltu":
                    // MARS encodes these without the code field, which only a trap handler reads.
                    if (args.size() == 3) {
                        require(count(args.get(2), line) <= 1023, "Trap code exceeds 10 bits");
                        return List.of(op + " " + args.get(0) + "," + args.get(1));
                    }
                    break;
                case "slt": case "sltu": case "add": case "addu": case "and": case "or": case "xor":
                    // GNU as accepts an immediate third operand and assembles the immediate form.
                    if (args.size() == 3 && !isRegister(args.get(2))) {
                        String immediate = op.equals("slt") ? "slti" : op.equals("sltu") ? "sltiu" : op.equals("add") ? "addi" : op.equals("addu") ? "addiu" : op + "i";
                        return List.of(immediate + " " + joined);
                    }
                    break;
                case "sll": case "srl": case "sra": case "rotr":
                    // A register shift amount selects the variable form, as GNU as does.
                    if (args.size() == 3 && isGeneralRegister(args.get(2))) return List.of(op + "v " + joined);
                    break;
                case "l.s": return lower("lwc1", args, line);
                case "l.d": return lower("ldc1", args, line);
                case "s.s": return lower("swc1", args, line);
                case "s.d": return lower("sdc1", args, line);
                default: break;
            }
            if (matches(MEMORY_ACCESS, op) && args.size() == 2 && !args.get(1).endsWith(")")) {
                // A bare address, reached through $at as GNU as does.
                require(at, "Address memory pseudo needs $at, which .set noat reserved");
                require(op.charAt(0) == 'l' || !args.get(0).equals("$1") && !args.get(0).equals("$at"), "Address store pseudo cannot store $at");
                return List.of("lui $1,%hi(" + args.get(1) + ")", op + " " + args.get(0) + ",%lo(" + args.get(1) + ")($1)");
            }
            // A basic instruction's operand expressions are resolved after layout. The form is chosen
            // by operand count, as a mnemonic can be a basic instruction with one count and a
            // pseudo-instruction with another (lw $t1,($t2) beside lw $t1,-100($t2)).
            ArrayList<?> matches = Globals.instructionSet.matchOperator(op);
            require(matches != null && !matches.isEmpty(), "Unsupported instruction: " + op);
            ArrayList<Instruction> pseudos = new ArrayList<>();
            boolean basicOfOtherArity = false;
            for (Object candidate : matches) {
                Instruction instruction = (Instruction)candidate;
                boolean arity = operandCount(instruction) == args.size();
                if (instruction instanceof BasicInstruction) {
                    if (arity) return List.of(args.isEmpty() ? op : op + " " + joined);
                    basicOfOtherArity = true;
                } else if (arity) pseudos.add(instruction);
            }
            // A basic instruction with no pseudo of this arity: encoding reports the operands.
            if (pseudos.isEmpty() && basicOfOtherArity) return List.of(args.isEmpty() ? op : op + " " + joined);
            // Register-only pseudo-instructions use MARS's fixed templates, which GNU as's macros
            // match (seq, sge, abs, rol, bge, ulw, mfc1.d, ...). A label operand is tokenized as a
            // name and substituted as written, since MARS's own LAB looks it up by address.
            String last = args.isEmpty() ? "" : args.get(args.size() - 1);
            boolean label = !last.isEmpty() && (matches(NUMERIC_REFERENCE, last) || !isRegister(last) && NAME.matcher(last).lookingAt());
            String source = op + " " + (label ? String.join(",", args.subList(0, args.size() - 1)) + (args.size() > 1 ? "," : "") + "__gnu_label" : joined);
            TokenList tokens = new Tokenizer(program).tokenizeLine(line, source, errors, false);
            Instruction instruction = OperandFormat.bestOperandMatch(tokens, pseudos.isEmpty() ? new ArrayList<Object>(matches) : pseudos);
            require(instruction instanceof ExtendedInstruction && OperandFormat.tokenOperandMatch(tokens, instruction, errors), "Unsupported pseudo instruction: " + op);
            List<String> result = new ArrayList<>();
            for (Object entry : ((ExtendedInstruction)instruction).getBasicIntructionTemplateList()) {
                String template = (String)entry;
                require(!matches(VALUE_TEMPLATE, template), "Unsupported pseudo instruction: " + op);
                require(at || !USES_AT.matcher(template).find(), op + " needs $at, which .set noat reserved");
                String expanded = ExtendedInstruction.makeTemplateSubstitutions(program, template, tokens);
                int labelAt = expanded.indexOf("LAB");
                if (labelAt >= 0) expanded = expanded.substring(0, labelAt) + last + expanded.substring(labelAt + 3);
                // Templates keep the spacing of their table, which a whitespace-led line would turn
                // into an empty operation.
                result.add(WHITESPACE.matcher(expanded.trim()).replaceAll(" ").replace(", ", ","));
            }
            return result;
        }
        /** How many operands an instruction's example takes: `c.lt.d 1,$f2,$f4` takes three. */
        private int operandCount(Instruction instruction) {
            String example = instruction.getExampleFormat().trim();
            int space = example.indexOf(' ');
            return space < 0 ? 0 : arguments(example.substring(space + 1)).size();
        }
        /** GNU as's li: one instruction where the value allows, else lui then ori. */
        private List<String> materialize(String rd, int value) {
            if (value >= -32768 && value <= 32767) return List.of("addiu " + rd + ",$0," + value);
            if (value >= 0 && value <= 65535) return List.of("ori " + rd + ",$0," + value);
            int high = value >>> 16, low = value & 0xffff;
            if (low == 0) return List.of("lui " + rd + "," + high);
            return List.of("lui " + rd + "," + high, "ori " + rd + "," + rd + "," + low);
        }

        private void emit(Fragment fragment, ArrayList<ProgramStatement> machine) {
            if (fragment.instruction != null) {
                String[] synthetic = new String[1];
                String text = resolveInstruction(fragment, synthetic);
                try { encode(text, fragment, machine); }
                finally {
                    // The target label was only needed to encode the offset.
                    if (synthetic[0] != null) program.getLocalSymbolTable().removeSymbol(new Token(TokenTypes.IDENTIFIER, synthetic[0], program, fragment.line, 1));
                }
                return;
            }
            if (fragment.expressions != null) {
                for (int index = 0; index < fragment.expressions.size(); index++) {
                    Value value = evaluate(fragment.expressions.get(index), fragment.line, fragment.section, fragment.offset + index * fragment.width, false);
                    if (fragment.width == 0) { require(value.number.signum() >= 0, "Negative symbol size"); continue; }
                    int bits = fragment.width * 8;
                    boolean fits = value.number.compareTo(ONE.shiftLeft(bits - 1).negate()) >= 0 && value.number.compareTo(ONE.shiftLeft(bits)) < 0;
                    require(!value.symbolic || fits, "Symbol fixup overflows " + bits + " bits");
                    if (!fits) errors.add(new ErrorMessage(ErrorMessage.WARNING, program, fragment.line, 1, "Integer literal truncated to " + bits + " bits"));
                    require(!fragment.section.zeroed() || value.number.signum() == 0, "Nonzero initializer in zeroed section");
                    for (int byteIndex = 0; byteIndex < fragment.width; byteIndex++) writeByte(fragment.address() + index * fragment.width + byteIndex, value.number.shiftRight(byteIndex * 8).intValue() & 255);
                }
                return;
            }
            if (fragment.section.executable()) {
                for (int i = 0; i < fragment.size; i += 4) {
                    Fragment word = new Fragment(fragment.section, fragment.line, fragment.offset + i, 4, null, null, 0, null, fragment.fill);
                    if (fragment.fill == -1) encode("nop", word, machine);
                    else {
                        int binary = fragment.fill * 0x01010101;
                        ProgramStatement statement = ProgramStatement.rawPadding(binary, (int)word.address(), program, program.getSourceLineList().get(fragment.line - 1));
                        store(statement, machine);
                    }
                }
            } else for (int i = 0; i < fragment.size; i++) writeByte(fragment.address() + i, fragment.bytes == null ? fragment.fill : fragment.bytes[i] & 255);
        }
        private void writeByte(long address, int value) {
            // Memory is cleared before every Build, and sections are only ever placed above what
            // is already written, so a zero byte is already there.
            if (value == 0) return;
            try { Globals.memory.set((int)address, value, 1); }
            catch (AddressErrorException exception) { throw new Invalid(exception.getMessage()); }
        }
        /**
         * Replaces address modifiers and operand expressions by numbers, and a branch or jump
         * target by a label whose address encodes the offset; {@code synthetic} receives that
         * label's name, to be dropped once the statement is encoded.
         */
        private String resolveInstruction(Fragment fragment, String[] synthetic) {
            String text = fragment.instruction.trim();
            Matcher anyModifier = ANY_MODIFIER.matcher(text);
            while (anyModifier.find()) require(matches(SUPPORTED_MODIFIER, anyModifier.group(1)), "Unsupported address modifier: %" + anyModifier.group(1));
            int space = 0;
            while (space < text.length() && !Character.isWhitespace(text.charAt(space))) space++;
            String op = text.substring(0, space);
            Matcher matcher = MODIFIER.matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                String kind = matcher.group(1), expression = modifierExpression(text, start);
                int end = start + kind.length() + expression.length() + 3;
                BigInteger x = evaluate(expression, fragment.line, fragment.section, fragment.offset, false).number;
                require(x.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0 && x.compareTo(BigInteger.valueOf(0xffffffffL)) <= 0, "Address modifier exceeds 32-bit range");
                int value = x.intValue();
                boolean high = kind.equals("hi");
                require(high == op.equals("lui"), "Address modifier used in wrong operand format");
                // %hi rounds up when the low half is negative, since the %lo user sign-extends it.
                int field = high ? ((value + 0x8000) >>> 16) & 0xffff : matches(UNSIGNED_IMMEDIATE, op) ? value & 0xffff : (short)value;
                text = text.substring(0, start) + field + text.substring(end);
                matcher = MODIFIER.matcher(text);
            }
            String rest = space < text.length() ? text.substring(space + 1).trim() : "";
            List<String> args = arguments(rest);
            boolean target = matches(BRANCH, op) || op.equals("j") || op.equals("jal");
            if (target) {
                int targetIndex = args.size() - 1;
                require(targetIndex >= 0, "Missing branch target");
                Value value = evaluate(args.get(targetIndex), fragment.line, fragment.section, fragment.offset, false);
                require(value.section != null && value.section.executable() && value.number.and(BigInteger.valueOf(3)).signum() == 0, "Branch target is not an aligned executable label");
                long address = fragment.address(), destination = value.number.longValue();
                if (matches(BRANCH, op)) {
                    // The offset counts words from the delay slot, as MARS and the hardware both do.
                    long delta = destination - (address + 4);
                    require(delta >= -(1L << 17) && delta < (1L << 17), "Branch target is out of range");
                } else require(((address + 4) & 0xF0000000L) == (destination & 0xF0000000L), "Jump target is outside the 256 MiB region of the jump");
                synthetic[0] = "__gnu_branch_" + address;
                program.getLocalSymbolTable().addSymbol(new Token(TokenTypes.IDENTIFIER, synthetic[0], program, fragment.line, 1), (int)destination, false, errors);
                args.set(targetIndex, synthetic[0]);
            }
            for (int i = 0; i < args.size() - (target ? 1 : 0); i++) {
                String arg = args.get(i);
                if (isRegister(arg)) continue;
                if (arg.endsWith(")")) {
                    int paren = arg.lastIndexOf('(');
                    require(paren >= 0 && isGeneralRegister(arg.substring(paren + 1, arg.length() - 1)), "Invalid base register");
                    String expr = arg.substring(0, paren);
                    BigInteger value = evaluate(expr.isEmpty() ? "0" : expr, fragment.line, fragment.section, fragment.offset, false).number;
                    require(value.compareTo(BigInteger.valueOf(-32768)) >= 0 && value.compareTo(BigInteger.valueOf(32767)) <= 0, "Memory offset does not fit 16 bits: " + expr);
                    args.set(i, value + arg.substring(paren));
                } else {
                    BigInteger value = evaluate(arg, fragment.line, fragment.section, fragment.offset, false).number;
                    require(value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0 && value.compareTo(BigInteger.valueOf(0xffffffffL)) <= 0, "Operand exceeds 32 bits: " + arg);
                    args.set(i, value.toString());
                }
            }
            return args.isEmpty() ? op : op + " " + String.join(",", args);
        }
        /**
         * The tokens MARS's tokenizer makes of an instruction this assembler resolved, built
         * directly: its operands are only registers, decimal integers, parenthesized base
         * registers and labels, so their types follow from their text without the tokenizer's
         * string copies.
         */
        private TokenList tokenize(String text, int line) {
            TokenList tokens = new TokenList();
            int position = 0, length = text.length();
            while (position < length) {
                char c = text.charAt(position);
                if (c == ' ' || c == ',') { position++; continue; }
                if (c == '(' || c == ')') {
                    tokens.add(new Token(c == '(' ? TokenTypes.LEFT_PAREN : TokenTypes.RIGHT_PAREN, String.valueOf(c), program, line, position + 1));
                    position++; continue;
                }
                int start = position;
                while (position < length && " ,()".indexOf(text.charAt(position)) < 0) position++;
                String value = text.substring(start, position);
                tokens.add(new Token(tokens.isEmpty() ? TokenTypes.OPERATOR : operandType(value), value, program, line, start + 1));
            }
            return tokens;
        }
        /** As TokenTypes.matchTokenType classifies a register, a decimal integer or a label. */
        private TokenTypes operandType(String value) {
            char first = value.charAt(0);
            if (first == '$') {
                Register register = RegisterFile.getUserRegister(value);
                if (register != null) return register.getName().equals(value) ? TokenTypes.REGISTER_NAME : TokenTypes.REGISTER_NUMBER;
                return Coprocessor1.getRegister(value) != null ? TokenTypes.FP_REGISTER_NAME : TokenTypes.IDENTIFIER;
            }
            if (first != '-' && (first < '0' || first > '9')) return TokenTypes.IDENTIFIER;
            int number = (int)Long.parseLong(value);
            if (number >= 0 && number <= 31) return TokenTypes.INTEGER_5;
            if (number >= DataTypes.MIN_UHALF_VALUE && number <= DataTypes.MAX_UHALF_VALUE) return TokenTypes.INTEGER_16U;
            if (number >= DataTypes.MIN_HALF_VALUE && number <= DataTypes.MAX_HALF_VALUE) return TokenTypes.INTEGER_16;
            return TokenTypes.INTEGER_32;
        }
        private void encode(String text, Fragment fragment, ArrayList<ProgramStatement> machine) {
            TokenList tokens = tokenize(text, fragment.line);
            require(!tokens.isEmpty(), "Empty instruction expansion");
            ArrayList<?> matches = Globals.instructionSet.matchOperator(tokens.get(0).getValue());
            require(matches != null && !matches.isEmpty(), "Unsupported instruction: " + text);
            Instruction instruction = OperandFormat.bestOperandMatch(tokens, matches);
            require(instruction instanceof BasicInstruction && OperandFormat.tokenOperandMatch(tokens, instruction, errors), "Invalid basic instruction: " + text);
            SourceLine source = program.getSourceLineList().get(fragment.line - 1);
            ProgramStatement statement = new ProgramStatement(program, source.getSource(), program.getTokenList().get(fragment.line - 1), tokens,
                    instruction, (int)fragment.address(), source.getSourcePath(), source.getLineNumber(), List.of());
            statement.buildBasicStatementFromBasicInstruction(errors);
            statement.buildMachineStatementFromBasicStatement(errors);
            store(statement, machine);
        }
        private void store(ProgramStatement statement, ArrayList<ProgramStatement> machine) {
            try { Globals.memory.setStatement(statement.getAddress(), statement); }
            catch (AddressErrorException exception) { throw new Invalid(exception.getMessage()); }
            machine.add(statement);
        }

        private Value evaluate(String expression, int line, Section section, int offset, boolean absolute) {
            Value value = new Expression(expression, line, section, offset, absolute, new HashSet<>()).parse();
            if (value.section != null) {
                require(value.number.signum() >= 0 && value.number.bitLength() <= 32, "Symbol address exceeds the Core's mapped 32-bit domain");
                require(mapped(value.section, value.number.intValue()), "Symbol address is outside mapped memory");
            }
            return value;
        }
        /**
         * A local name resolves to this unit's definition. A name the unit declares global or
         * weak resolves to the link's winning definition, as an ld relocation would, so a weak
         * definition another unit overrides, or a COMDAT copy an earlier unit supplied, is not
         * used. After a MARS-dialect program, its globals come before the members' own. A name
         * defined nowhere falls back to an ld-provided array bound, and an undefined weak
         * reference is zero.
         */
        private Value resolve(String name, int line, Section section, int offset, boolean absolute, Set<String> visiting) {
            Symbol symbol;
            if (matches(NUMERIC_REFERENCE, name)) {
                List<Symbol> candidates = numeric.getOrDefault(name.substring(0, name.length() - 1), List.of());
                symbol = null;
                for (Symbol candidate : candidates) {
                    if (name.endsWith("b") && candidate.line <= line) symbol = candidate;
                    if (name.endsWith("f") && candidate.line > line) { symbol = candidate; break; }
                }
            } else symbol = symbols.get(name);
            if (!absolute && (symbol == null || declared.contains(name))) {
                Symbol global = globals.get(name);
                if (global != null && global != symbol) return global.unit.valueOf(global, visiting);
                if (global == null && legacyGlobals) {
                    int address = Globals.symbolTable.getAddress(name);
                    if (address != SymbolTable.NOT_FOUND)
                        return new Value(BigInteger.valueOf(Integer.toUnsignedLong(address)), legacySection(address), true);
                }
            }
            if (symbol == null && absolute) symbol = forwardAliases.get(name);
            if (symbol == null && !absolute) {
                int bounds = arrayBound(name);
                if (bounds >= 0) {
                    Section range = arrayRanges.get(bounds);
                    return new Value(BigInteger.valueOf(range.base + (name.endsWith("_end") ? range.size : 0)), range, true);
                }
                if (weak.contains(name)) return new Value(ZERO, null, true);
            }
            require(symbol != null, "Unresolved symbol: " + name);
            return valueOf(symbol, absolute, visiting);
        }
        Value valueOf(Symbol symbol, Set<String> visiting) { return valueOf(symbol, false, visiting); }
        private Value valueOf(Symbol symbol, boolean absolute, Set<String> visiting) {
            if (symbol.alias != null) {
                // Units can reuse local names, so the cycle check names the defining unit too.
                String key = index + ":" + symbol.name;
                require(visiting.size() < 64, "Alias nesting exceeds 64 levels");
                require(visiting.add(key), "Cyclic symbol alias: " + symbol.name);
                Value value = new Expression(symbol.alias, symbol.line, symbol.section, symbol.offset, absolute, visiting).parse();
                visiting.remove(key); return value;
            }
            require(!absolute, "Layout expression depends on a section/symbol: " + symbol.name);
            require(!symbol.section.discarded(), "Runtime reference to discarded section: " + symbol.name);
            return new Value(BigInteger.valueOf(symbol.section.base + symbol.offset), symbol.section, true);
        }
        private final class Expression {
            final String text;
            final int line, offset;
            final Section section;
            final boolean absolute;
            final Set<String> visiting;
            int position;
            int depth;
            Expression(String text, int line, Section section, int offset, boolean absolute, Set<String> visiting) {
                this.text = text; this.line = line; this.section = section; this.offset = offset; this.absolute = absolute; this.visiting = visiting;
                require(text.length() <= 4096, "Expression exceeds 4096 characters");
            }
            Value parse() { Value value = sum(); white(); require(position == text.length(), "Unsupported expression: " + text); return value; }
            void white() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
            Value sum() {
                Value left = unary(); white();
                while (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                    boolean subtract = text.charAt(position++) == '-'; Value right = unary();
                    Section result = left.section;
                    if (subtract) {
                        if (right.section != null) { require(left.section == right.section, "Symbol difference requires the same section"); result = null; }
                    } else {
                        require(left.section == null || right.section == null, "Cannot add two section addresses");
                        if (right.section != null) result = right.section;
                    }
                    left = new Value(subtract ? left.number.subtract(right.number) : left.number.add(right.number), result, left.symbolic || right.symbolic); white();
                }
                return left;
            }
            Value unary() {
                require(++depth <= 64, "Expression nesting exceeds 64 levels");
                try { return atom(); } finally { depth--; }
            }
            Value atom() {
                white(); require(position < text.length(), "Missing expression operand");
                char c = text.charAt(position);
                if (c == '+' || c == '-') {
                    position++; Value value = unary(); require(c != '-' || value.section == null, "Cannot negate a section address");
                    return new Value(c == '-' ? value.number.negate() : value.number, value.section, value.symbolic);
                }
                if (c == '(') { position++; Value value = sum(); white(); require(position < text.length() && text.charAt(position++) == ')', "Unclosed expression"); return value; }
                int start = position;
                while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position)) || ".$_".indexOf(text.charAt(position)) >= 0)) position++;
                require(position > start, "Unsupported expression: " + text);
                String atom = text.substring(start, position);
                if (atom.equals(".")) { require(!absolute, "Layout expression depends on current location"); return new Value(BigInteger.valueOf(section.base + offset), section, true); }
                if (matches(NUMERIC_REFERENCE, atom) || !Character.isDigit(atom.charAt(0))) return resolve(atom, line, section, offset, absolute, visiting);
                try {
                    boolean hex = atom.startsWith("0x") || atom.startsWith("0X"), binary = atom.startsWith("0b") || atom.startsWith("0B");
                    BigInteger number = hex ? new BigInteger(atom.substring(2), 16) : binary ? new BigInteger(atom.substring(2), 2) : new BigInteger(atom, atom.startsWith("0") && atom.length() > 1 ? 8 : 10);
                    require(number.bitLength() <= 256, "Integer expression exceeds 256-bit limit"); return new Value(number, null, false);
                } catch (NumberFormatException exception) { throw new Invalid("Invalid integer: " + atom); }
            }
        }
    }

    private static int integerWidth(String op) {
        switch (op) {
            case ".byte": return 1;
            case ".half": case ".hword": case ".short": case ".2byte": return 2;
            case ".word": case ".int": case ".long": case ".4byte": return 4;
            case ".quad": case ".dword": case ".8byte": return 8;
            default: return 0;
        }
    }
    private static String modifierExpression(String text, int start) {
        int open = text.indexOf('(', start), level = 1, end = open + 1;
        while (end < text.length() && level > 0) { char c = text.charAt(end++); if (c == '(') level++; if (c == ')') level--; }
        require(level == 0, "Unclosed address modifier"); return text.substring(open + 1, end - 1);
    }
    private static List<String> arguments(String text) {
        List<String> result = new ArrayList<>();
        if (text.trim().isEmpty()) return result;
        boolean quote = false, escape = false;
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (quote && c == '\\') { escape = true; continue; }
            if (c == '"') quote = !quote;
            if (!quote) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                require(depth >= 0, "Unbalanced operands");
                if (c == ',' && depth == 0) { result.add(text.substring(start, i).trim()); start = i + 1; }
            }
        }
        require(!quote && depth == 0, "Unclosed string or operand");
        result.add(text.substring(start).trim()); return result;
    }
    private static String uncomment(String text) {
        boolean quote = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (quote && c == '\\') { escape = true; continue; }
            if (c == '"') quote = !quote;
            if (!quote && c == '#') return text.substring(0, i);
        }
        return text;
    }
    private static boolean hasStatementSeparator(String text) {
        boolean quote = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (quote && c == '\\') { escape = true; continue; }
            if (c == '"') quote = !quote;
            if (c == ';' && !quote) return true;
        }
        return false;
    }
    private static String unquote(String text) { require(text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""), "Expected a quoted string"); return text.substring(1, text.length() - 1); }
    private static String unquoteOptional(String text) { return text.startsWith("\"") ? unquote(text) : text; }
    private static boolean allZero(byte[] bytes) { for (byte value : bytes) if (value != 0) return false; return true; }
    private static byte[] stringBytes(String text) {
        String source = unquote(text);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c != '\\') { literal.append(c); continue; }
            byte[] utf = literal.toString().getBytes(StandardCharsets.UTF_8); output.write(utf, 0, utf.length); literal.setLength(0);
            require(++i < source.length(), "Incomplete string escape");
            c = source.charAt(i);
            int value;
            if (c >= '0' && c <= '7') {
                value = c - '0'; int digits = 1;
                while (digits < 3 && i + 1 < source.length() && source.charAt(i + 1) >= '0' && source.charAt(i + 1) <= '7') { value = value * 8 + source.charAt(++i) - '0'; digits++; }
                require(value <= 255, "Octal string escape exceeds one byte");
            } else if (c == 'x') {
                value = 0; int digits = 0;
                while (i + 1 < source.length() && Character.digit(source.charAt(i + 1), 16) >= 0) { value = value * 16 + Character.digit(source.charAt(++i), 16); digits++; require(value <= 255, "Hex string escape exceeds one byte"); }
                require(digits > 0, "Empty hex string escape");
            } else {
                switch (c) {
                    case 'n': value = 10; break; case 'r': value = 13; break; case 't': value = 9; break;
                    case 'b': value = 8; break; case 'f': value = 12; break; case 'v': value = 11; break;
                    case 'a': value = 7; break; case '"': value = 34; break; case '\\': value = 92; break;
                    default: throw new Invalid("Unsupported string escape: \\" + c);
                }
            }
            output.write(value);
        }
        byte[] utf = literal.toString().getBytes(StandardCharsets.UTF_8); output.write(utf, 0, utf.length);
        return output.toByteArray();
    }
}
