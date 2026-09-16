//@ts-ignore
import {makeMipsFromFiles as _makeMipsFromFiles, initializeMIPS as _initializeMIPS, getInstructionSet as _getInstructionSet} from './generated/mars'


export type JsInstructionToken = {
    /** One-based column in the processed source line. */
    sourceColumn: number;
    value: string;
    type: string
}



/*
public abstract int openFile(String filename, int flags, boolean append) throws MIPSIOError;
    public abstract void closeFile(int fileDescriptor) throws MIPSIOError;
    public abstract void writeFile(int fileDescriptor, byte[] buffer) throws MIPSIOError;
    public abstract int readFile(int fileDescriptor, byte[] destination, int length) throws MIPSIOError;


    // 0 ---> meaning Yes
    // 1 ---> meaning No
    // 2 ---> meaning Cancel
    public abstract int confirm(String message);

    public abstract String inputDialog(String message);

     *  ERROR_MESSAGE = 0
     *  INFORMATION_MESSAGE = 1
     *  WARNING_MESSAGE = 2
     *  QUESTION_MESSAGE = 3
public abstract void outputDialog(String message, int type);

public abstract double askDouble(String message);

public abstract float askFloat(String message);

public abstract int askInt(String message);

public abstract String askString(String message);

public abstract double readDouble();

public abstract float readFloat();

public abstract int readInt();

public abstract String readString();

public abstract char readChar();

public abstract void logLine(String message);

public abstract void log(String message);

public abstract void printChar(char c);

public abstract void printDouble(double d);

public abstract void printFloat(float f);

public abstract void printInt(int i);

public abstract void printString(String l);


public abstract void sleep(int milliseconds);

public abstract double time();

public abstract void stdIn(byte[] buffer, int length);

public abstract void stdOut(byte[] buffer);

public abstract void stdErr(byte[] buffer);
 */

export enum DialogType {
    ERROR_MESSAGE = 0,
    INFORMATION_MESSAGE = 1,
    WARNING_MESSAGE = 2,
    QUESTION_MESSAGE = 3
}

export enum ConfirmResult {
    YES = 0,
    NO = 1,
    CANCEL = 2
}

export type HandlerMap = {
    openFile: {in: [filename: string, flags: number, append: boolean], out: number}
    closeFile: {in: [fileDescriptor: number], out: void}
    writeFile: {in: [fileDescriptor: number, buffer: number[]], out: void}
    readFile: {in: [fileDescriptor: number, destination: number[], length: number], out: [readOrEof: number, buffer: number[]]}
    confirm: {in: [message: string], out: ConfirmResult}
    inputDialog: {in: [message: string], out: string}
    outputDialog: {in: [message: string, type: DialogType], out: void}
    askDouble: {in: [message: string], out: number}
    askFloat: {in: [message: string], out: number}
    askInt: {in: [message: string], out: number}
    askString: {in: [message: string], out: string}
    readDouble: {in: [], out: number}
    readFloat: {in: [], out: number}
    readInt: {in: [], out: number}
    readString: {in: [], out: string}
    readChar: {in: [], out: string}
    logLine: {in: [message: string], out: void}
    log: {in: [message: string], out: void}
    printChar: {in: [c: string], out: void}
    printDouble: {in: [d: number], out: void}
    printFloat: {in: [f: number], out: void}
    printInt: {in: [i: number], out: void}
    printString: {in: [l: string], out: void}
    /**
     * Syscall 32: the program asks to be suspended for this many milliseconds. Return a promise
     * that settles when the wait is over to suspend the simulation without blocking the host.
     */
    sleep: {in: [milliseconds: number], out: void}
    /**
     * Syscall 30: the program time in milliseconds, split by the syscall into $a0 (low word) and
     * $a1 (high word). Answer with `Date.now()` for a live run, or with a virtual clock for a
     * scripted one, so that elapsed-time output stays reproducible.
     */
    time: {in: [], out: number}
    stdIn: {in: [buffer: number[], length: number], out: void}
    stdOut: {in: [buffer: number[]], out: void}
    stdErr: {in: [buffer: number[]], out: void}
}

/**
 * Notified after a write anywhere in an observed address range.
 *
 * `length` is the width of the access in bytes (4, 2 or 1) and `value` is what the program stored,
 * so a byte or halfword store reports only the bytes it touched; a peripheral that mirrors whole
 * words should re-read the containing word with `readMemoryBytes` rather than trust `value`.
 *
 * Both `address` and `value` are signed 32 bit integers, as the guest holds them: the register at
 * `0xffff000c` arrives as `-65524`, and a pixel word with its high bit set arrives negative too.
 * Apply `>>> 0` wherever the unsigned form is wanted.
 */
export type MemoryWriteObserver = (address: number, length: number, value: number) => void

/**
 * Notified after a read or a write of one observed word, with the same signed 32 bit numbers as
 * `MemoryWriteObserver`. `value` is the value the program read or stored; on a read it is what
 * memory held *before* the observer ran, so a register whose value is consumed by reading it must
 * be reloaded from the handler for the next read.
 */
export type MemoryAccessObserver = (address: number, value: number) => void

/** Identifies one registration, for `removeMemoryObserver`. */
export type MemoryObserverHandle = number


export type JsInstruction = {
    name: string;
    example: string;
    description: string;
    tokens: JsInstructionToken[];
}

export type MipsTokenizedLine = {
    sourcePath: string;
    /** One-based line in `sourcePath`. */
    sourceLine: number;
    /** Exact line supplied in the source set. */
    source: string;
    /** Line after assembler substitutions such as `.eqv`. */
    processedSource: string;
    tokens: JsInstructionToken[]
}

export type MIPSSourceLocation = {
    sourcePath: string
    /** One-based line in `sourcePath`. */
    sourceLine: number
}

export type MIPSSourceSet = Readonly<Record<string, string>>

export type MIPSAssembleError = {
    isWarning: boolean
    message: string
    macroExpansionTrace: MIPSSourceLocation[]
    sourcePath: string
    /** One-based line in `sourcePath`. */
    sourceLine: number
    /** One-based column in `sourcePath`. Zero only for diagnostics without a source location. */
    sourceColumn: number
}

export type MIPSAssembleResult = {
    report: string
    errors: MIPSAssembleError[]
    /** True only when at least one diagnostic is a real error; warnings alone leave the assembled program runnable. */
    hasErrors: boolean
    /** True when at least one diagnostic is a warning. */
    hasWarnings: boolean
}


/**
 * The coprocessor 0 register numbers, in the order `getCoprocessor0Values` returns their values
 * and `setCoprocessor0Value` names them: `$8 (vaddr)`, `$12 (status)`, `$13 (cause)`,
 * `$14 (epc)`. Coprocessor 0 implements only these four, so a register's number is not its
 * position in the array.
 */
export const MIPS_COPROCESSOR0_REGISTER_NUMBERS = [8, 12, 13, 14] as const

export class MIPS {
    public static makeMipsFromFiles = makeMipsFromFiles
    public static initializeMIPS = initializeMIPS
    public static getInstructionSet(){
        return _getInstructionSet() as JsInstruction[]
    }
}

export type JsMipsStackFrame = {
    /**
     * The program counter value at the moment the stack frame was created.
     */
    pc: number;
    /**
     * The address of the target instruction.
     */
    toAddress: number;
    /**
     * The stack pointer value at the moment the stack frame was created.
     */
    sp: number;
    /**
     * The frame pointer value at the moment the stack frame was created.
     */
    fp: number;
    /**
     * The values of all registers at the moment the stack frame was created.
     */
    registers: number[];
}

/**
 * Represents a statement in the assembled program.
 */
export interface JsProgramStatement {
    /** Canonical path of the original source file. */
    readonly sourcePath: string;
    /**
     * The one-based line number in the original source file.
     */
    readonly sourceLine: number;
    /**
     * The memory address of the instruction.
     */
    readonly address: number;
    /**
     * The binary representation of the instruction.
     */
    readonly binaryStatement: number;
    /**
     * The original source code line.
     */
    readonly source: string;
    /**
     * The machine code representation of the instruction.
     */
    readonly machineStatement: string;


    /**
     * The assembly representation of the instruction.
     */
    readonly assemblyStatement: string;
}

/**
 * Enum representing the types of "undo" actions.
 */
export enum BackStepAction {
    MEMORY_RESTORE_RAW_WORD,
    MEMORY_RESTORE_WORD,
    MEMORY_RESTORE_HALF,
    MEMORY_RESTORE_BYTE,
    REGISTER_RESTORE,
    PC_RESTORE,
    COPROC0_REGISTER_RESTORE,
    COPROC1_REGISTER_RESTORE,
    COPROC1_CONDITION_CLEAR,
    COPROC1_CONDITION_SET,
    DO_NOTHING, // instruction does not write anything.
    /**
     * A whole Poke: every value one `beginPoke`/`endPoke` transaction wrote, restored together.
     * A Poke is a single back step, so it takes one slot of the undo size whatever it wrote.
     */
    POKE,
}

/**
 * Represents a back step in the simulation undo stack.
 */
export interface JsBackStep {
    /**
     * The action performed (e.g., register write, memory write).
     */
    readonly action: BackStepAction;

    /**
     * Information about the action
     */
    readonly param1: number;

    /**
     * Information about the action
     */
    readonly param2: number;

    /**
     * The other half of what this step restores: the value the write left, as the simulator saw it
     * at the moment of the write, beside the `param2` it replaced.
     *
     * - `REGISTER_RESTORE`, `COPROC0_REGISTER_RESTORE` and `COPROC1_REGISTER_RESTORE`: the whole
     *   value the write put in the register.
     * - `MEMORY_RESTORE_RAW_WORD` and `MEMORY_RESTORE_WORD`: the word left at the address.
     *   `MEMORY_RESTORE_HALF` and `MEMORY_RESTORE_BYTE`: the half or byte left there, in the low
     *   order bits, so that both values are read at the width the write was made.
     * - `PC_RESTORE`: the address the instruction set the program counter to, while `param1` is
     *   the address the restore puts back (`param2` is unused there, as it has always been).
     * - 0 for the actions that restore no value: `COPROC1_CONDITION_SET`, `COPROC1_CONDITION_CLEAR`,
     *   `DO_NOTHING` and `POKE`, whose own `writes` already carry both sides of every value.
     *
     * A signed 32 bit int like `param2`, the way every register getter of this package reports a
     * value; read it unsigned with `newValue >>> 0`.
     */
    readonly newValue: number;
    /**
     * The program counter value before the action, or -1 for an action that belongs to no
     * instruction: a Poke, or a host write made before anything ran.
     */
    readonly pc: number;

    /**
     * Whether this step is a whole Poke rather than one effect of an instruction. It is the
     * discriminator this list needs, since a Poke and a pre-run host write both carry `pc` -1.
     */
    readonly isPoke: boolean;
}

/**
 * One value a Poke changed: a register, named the way this package's getters spell it, or a run of
 * consecutive memory bytes. `old` is what the simulator held when the write happened and `new` what
 * it held when the Poke was closed, so a value written twice inside one Poke reports its final
 * state.
 */
export type JsPokeWrite = JsPokeRegisterWrite | JsPokeMemoryWrite

/**
 * A register a Poke wrote. `name` is the register's own name: `$t0` for a general register, `$f2`
 * for an FPU register, `$13 (cause)` for a coprocessor 0 register - the spellings
 * `getRegistersValues`, `getCoprocessor1Values` and `getCoprocessor0Values` document - and
 * `flag 3` for one of the FPU condition flags. Both values are signed 32 bit ints, as every
 * register getter reports them; a condition flag is 0 or 1.
 */
export type JsPokeRegisterWrite = {
    readonly type: 'register'
    readonly name: string
    readonly old: number
    readonly new: number
}

/**
 * A run of consecutive memory bytes a Poke wrote, `address` being the first one, unsigned, and the
 * two arrays holding one byte (0 to 255) per address. A Poke that writes addresses that are not
 * adjacent reports one of these per run, by ascending address. Unlike `readMemoryBytes`, these are
 * ordinary JS arrays.
 */
export type JsPokeMemoryWrite = {
    readonly type: 'memory'
    readonly address: number
    readonly old: number[]
    readonly new: number[]
}

/**
 * One entry of the undo history: everything a single `undo()` reverts, which is either one executed
 * instruction or one Poke. Entries, their back steps and their writes are ordinary objects with own
 * properties, so a whole history can be cloned, serialized or deep-compared as it comes. An instruction that recorded several back steps - a `jal` restoring both
 * `$ra` and the program counter, a multiply writing `hi` and `lo` - is one entry with several
 * `steps`.
 */
export type JsUndoGroup = JsInstructionUndoGroup | JsPokeUndoGroup

/** One executed instruction, at the address `pc`. */
export type JsInstructionUndoGroup = {
    readonly kind: 'instruction'
    readonly pc: number
    /** The instruction's back steps, newest first. */
    readonly steps: JsBackStep[]
    /** Always empty: only a Poke reports writes. */
    readonly writes: readonly []
}

/**
 * One Poke: register or memory values written by the host between two instructions, recorded as a
 * step of its own. It belongs to no instruction, so `pc` is -1 and undoing it restores exactly what
 * `writes` lists, leaving the program counter, the call stack and everything else alone.
 */
export type JsPokeUndoGroup = {
    readonly kind: 'poke'
    readonly pc: -1
    /** The single back step the Poke is: one slot of the history, whatever the Poke wrote. */
    readonly steps: [JsBackStep]
    readonly writes: JsPokeWrite[]
}

/**
 * All MIPS register names.
 */
export type RegisterName =
    '$zero'
    | '$at'
    | '$v0'
    | '$v1'
    | '$a0'
    | '$a1'
    | '$a2'
    | '$a3'
    | '$t0'
    | '$t1'
    | '$t2'
    | '$t3'
    | '$t4'
    | '$t5'
    | '$t6'
    | '$t7'
    | '$s0'
    | '$s1'
    | '$s2'
    | '$s3'
    | '$s4'
    | '$s5'
    | '$s6'
    | '$s7'
    | '$t8'
    | '$t9'
    | '$k0'
    | '$k1'
    | '$gp'
    | '$sp'
    | '$fp'
    | '$ra';

type HandlerName = keyof HandlerMap


/**
 * A handler may return its result directly, or a promise of it. When a handler returns a promise
 * the simulation suspends until it settles, so IO can be backed by an async API (prompting the
 * user, reading a file, awaiting a worker) without blocking the event loop. If the promise
 * rejects, the pending `step`/`simulate*` call rejects too.
 */
export type HandlerMapFns = {
    [K in HandlerName]: (...args: HandlerMap[K]['in']) => HandlerMap[K]['out'] | Promise<HandlerMap[K]['out']>
}

export function registerHandlers(mips: JsMips, handlers: HandlerMapFns) {
    for (const [name, handler] of Object.entries(handlers)) {
        mips.registerHandler(name as HandlerName, handler as (...args: HandlerMap[HandlerName]['in']) => HandlerMap[HandlerName]['out'] | Promise<HandlerMap[HandlerName]['out']>)
    }
}

export function unimplementedHandler(name: HandlerName) {
    return function () {
        throw new Error(`Handler ${name} is not implemented`)
    }
}

/**
 * Interface for interacting with a MIPS simulator.
 */
export interface JsMips {
    /**
     * Assembles the program.
     */
    assemble(): MIPSAssembleResult;

    /**
     * Initializes the simulator.
     * @param startAtMain If true, starts execution at the 'main' label. Otherwise, starts at the first instruction.
     */
    initialize(startAtMain: boolean): void;

    /**
     * Executes a single instruction.
     *
     * Resolves to true if the execution is complete, false otherwise. The promise settles on a
     * microtask unless an IO handler returned a promise, in which case it settles once that
     * handler and the rest of the instruction have finished.
     */
    step(): Promise<boolean>;


    /**
     * Gets the 8 FPU condition flags, element i being flag i, each 0 or 1. They are the FPU
     * register file's status flags: a compare instruction such as `c.lt.s` writes one.
     */
    getConditionFlags(): number[];

    /**
     * Sets one FPU condition flag. Outside a Poke the write is direct: it records no undo step, so
     * a preset value never becomes an entry the simulation can step back over. Inside a Poke it
     * joins the open transaction, as `flag N`.
     * @param flag The flag number, 0 to 7. Any other number throws.
     * @param value True to set the flag to 1, false to clear it to 0.
     */
    setConditionFlag(flag: number, value: boolean): void;

    /**
     * Gets the raw 32 bit patterns of the FPU (coprocessor 1) registers: element i is `$fi`, so
     * the array is always 32 long and in register order.
     *
     * The values are bit patterns, not numbers: read element i through `Float32Array`/`DataView`
     * for the single precision value. A double occupies an even/odd register pair, the low word
     * in the even register and the high word in the odd one that follows it, as MARS does: the
     * double held in `$f2` has element 2 as its low 32 bits and element 3 as its high 32 bits,
     * and an odd register alone holds no double.
     *
     * Each element is a signed 32 bit int, exactly like `getRegistersValues`: a value with the
     * top bit set reads back negative, so use `value >>> 0` for its unsigned form. The array
     * itself is an `Int32Array` at runtime, as `getRegistersValues` is, so `Array.isArray` is
     * false and `slice`/`map` return another `Int32Array` (whose `map` result is truncated back
     * to int32): copy it with `Array.from` when a real array is needed.
     */
    getCoprocessor1Values(): number[];

    /**
     * Sets one FPU register to a raw 32 bit pattern. Outside a Poke the write is direct: it records
     * no undo step, exactly like `setRegisterValue`. Inside a Poke it joins the open transaction.
     * @param index The register number, 0 to 31, `$f0` to `$f31`. It must be a whole number;
     * anything else, including a fractional index, throws.
     * @param value The 32 bit pattern to store.
     */
    setCoprocessor1Value(index: number, value: number): void;

    /**
     * Gets the values of the four implemented coprocessor 0 registers, in the fixed order
     * `$8 (vaddr)`, `$12 (status)`, `$13 (cause)`, `$14 (epc)` - the register numbers in
     * `MIPS_COPROCESSOR0_REGISTER_NUMBERS`. `status` reads 0x0000FF11 until an exception
     * changes it.
     *
     * Each element is a signed 32 bit int, exactly like `getRegistersValues`: a value with the
     * top bit set reads back negative, so use `value >>> 0` for its unsigned form. The array
     * itself is an `Int32Array` at runtime, as `getRegistersValues` is, so `Array.isArray` is
     * false and `slice`/`map` return another `Int32Array` (whose `map` result is truncated back
     * to int32): copy it with `Array.from` when a real array is needed.
     */
    getCoprocessor0Values(): number[];

    /**
     * Sets one coprocessor 0 register. Outside a Poke the write is direct: it records no undo step.
     * Inside a Poke it joins the open transaction.
     * @param number The register number, one of `MIPS_COPROCESSOR0_REGISTER_NUMBERS`
     * (8, 12, 13 or 14). Any other number throws. This is the register's MIPS number, not its
     * position in `getCoprocessor0Values()`.
     * @param value The 32 bit value to store.
     */
    setCoprocessor0Value(number: number, value: number): void;


    /**
     * Sets the size of the undo stack, must be called before assembling the program.
     * @param size
     */
    setUndoSize(size: number): void;

    /**
     * Undoes the newest entry of the history: the last instruction executed, or the last Poke made,
     * whichever is on top. Undoing a Poke restores every value it wrote and touches nothing else.
     */
    undo(): void;


    /**
     * Gets the statement at the given address.
     * @param address
     */
    getStatementAtAddress(address: number): JsProgramStatement;

    /** Gets every machine statement generated from an original source location. */
    getStatementsAtSourceLocation(sourcePath: string, sourceLine: number): JsProgramStatement[];


    getTokenizedLines(): MipsTokenizedLine[]
    /**
     * Checks if the simulation can be undone.
     * @returns True if the simulation can be undone, false otherwise.
     * */
    canUndo: boolean;



    /**
     * Gets the call stack.
     * @returns An array of memory addresses representing the call stack.
     */
    getCallStack(): JsMipsStackFrame[]



    /**
     * Gets the compiled statements.
     * @returns An array of `JsProgramStatement` objects representing the compiled program.
     */
    getCompiledStatements(): JsProgramStatement[]


    getParsedStatements(): JsProgramStatement[]


    getHi(): number;

    getLo(): number;


    /**
     * Gets the label at the given address.
     * @param address The memory address.
     * @returns The label at the given address, or null if no label is found.
     */
    getLabelAtAddress(address: number): string | null

    /**
     * Sets whether the undo feature is enabled.
     * @param enabled True to enable the undo feature, false to disable it.
     */
    setUndoEnabled(enabled: boolean): void;

    /**
     * Simulates the program for a limited number of instructions.
     * @param limit The maximum number of instructions to execute.
     * @returns A promise resolving to true if the execution is complete, false otherwise.
     */
    simulateWithLimit(limit: number): Promise<boolean>;

    /**
     * Simulates the program until a breakpoint is reached.
     * @param breakpoints An array of memory addresses where the simulation should pause.
     * @returns A promise resolving to true if the execution is complete, false otherwise.
     */
    simulateWithBreakpoints(breakpoints: number[]): Promise<boolean>;

    /**
     * Simulates the program with both breakpoints and a limit.
     * @param breakpoints An array of memory addresses where the simulation should pause.
     * @param limit The maximum number of instructions to execute.
     * @returns A promise resolving to true if the execution is complete, false otherwise.
     */
    simulateWithBreakpointsAndLimit(breakpoints: number[], limit: number): Promise<boolean>;

    /**
     * Gets the value of a register.
     * @param register The name of the register.
     * @returns The value of the register.
     */
    getRegisterValue(register: RegisterName): number;

    /**
     * Registers a handler function for a specific event or condition.
     * @param name The name of the event or condition.
     * @param handler The handler function to be called when the event occurs. The function signature depends on the event name.
     */
    registerHandler<T extends HandlerName>(name: T, handler: (...args: HandlerMap[T]['in']) => HandlerMap[T]['out'] | Promise<HandlerMap[T]['out']>): void;

    /**
     * Gets the current value of the stack pointer.
     * @returns The value of the stack pointer.
     */
    stackPointer: number;

    /**
     * Gets the current value of the program counter.
     * @returns The value of the program counter.
     */
    programCounter: number;

    /**
     * Gets the values of all registers.
     * @returns An array containing the register values. The order of the values is implementation defined.
     */
    getRegistersValues(): number[];

    /**
     * Gets the undo stack, one element per back step, newest first. An instruction usually occupies
     * several of them - a `jal` restores both `$ra` and the program counter - while a Poke is
     * exactly one, the element with `isPoke` set, whatever it wrote. Use `getUndoGroups` to read
     * the history the way `undo()` pops it, one entry per instruction or Poke.
     * @returns An array of `JsBackStep` objects representing the history of the simulation.
     */
    getUndoStack(): JsBackStep[];

    /**
     * Gets the undo history grouped the way `undo()` pops it: one entry per executed instruction or
     * per Poke, newest first, each carrying the back steps it is made of. A Poke entry also carries
     * what it changed, with the old and the new value of each write.
     */
    getUndoGroups(): JsUndoGroup[];

    /**
     * Opens a Poke: a register or memory value changed by the host between two instructions,
     * recorded in this history as a step of its own.
     *
     * Until `endPoke` the setters - `setRegisterValue`, `setCoprocessor1Value`,
     * `setCoprocessor0Value`, `setConditionFlag` and `setMemoryBytes` - journal what they write
     * into the open transaction, however many of them are called, and `endPoke` records the lot as
     * one history entry that one `undo()` reverts and that takes one slot of the undo size, whether
     * it wrote one byte or a hundred. Outside a transaction the same setters stay direct and record
     * nothing, which is what presetting state needs.
     *
     * Throws if a Poke is already open, or if a `step`/`simulate*` call is still in flight.
     */
    beginPoke(): void;

    /**
     * Closes the open Poke.
     * @returns True if it recorded one history entry, false if nothing changed - or if undo is
     * disabled or its size is 0, in which case the writes stand but cannot be undone.
     * Throws if no Poke is open.
     */
    endPoke(): boolean;

    /** Whether a Poke is open, so that the setters journal rather than writing straight through. */
    pokeOpen(): boolean;

    /**
     * Reads a sequence of bytes from memory.
     *
     * Reading through this method notifies no memory observer: inspecting memory from the host is
     * not the program reading it, so a memory viewer never drives a memory-mapped register.
     * @param address The starting memory address.
     * @param length The number of bytes to read.
     * @returns An array of bytes read from memory.
     */
    readMemoryBytes(address: number, length: number): number[];

    /**
     * Writes a sequence of bytes to memory.
     *
     * Unlike `readMemoryBytes`, this writes the way the program does, so write observers are
     * notified and a memory mapped display repaints. It records no undo step of its own: a host
     * write is not an instruction, and recording it would make the next `undo()` revert it together
     * with the instruction that ran before it. Inside a Poke the same write joins the open
     * transaction instead, and a byte already holding the value written is skipped entirely.
     *
     * Use `setPeripheralWord` for a device keeping its own register up to date.
     * @param address The starting memory address.
     * @param bytes An array of bytes to write to memory.
     */
    setMemoryBytes(address: number, bytes: number[]): void;

    /**
     * Writes one word as a peripheral would: no observer is notified and no undo step is recorded,
     * because the write is not the program acting. This is how a device model refreshes a
     * memory-mapped register - a ready bit, a pending character - without feeding its own observer
     * or consuming undo history.
     * @param address The word address, which must be word-aligned. Either form of a high address
     * is accepted: `0xffff0000` and `0xffff0000 | 0` name the same word.
     * @param value The 32 bit value to store, raw, without byte-order adjustment.
     */
    setPeripheralWord(address: number, value: number): void;

    /**
     * Observes every write in an address range, the shape a framebuffer wants.
     *
     * Both addresses must be word-aligned, `endAddress` is inclusive and covers its whole word, and
     * the range may not cross 0x80000000 (split it in two registrations instead); a range that
     * breaks any of these throws. Either form of a high address is accepted: `0xffff0000` and
     * `0xffff0000 | 0` name the same word. The handler runs synchronously inside the storing instruction, so
     * it must be cheap and must not write back into its own range; a returned promise is ignored.
     *
     * Observers live on the simulator's memory, which assembling and initializing only clear the
     * contents of, so a registration survives `assemble()` and `initialize()`. For the same reason
     * it is shared by every `JsMips` instance: register once per page, or remove the previous
     * registration before registering again for a newly built program.
     *
     * Notifications only start once a program has been assembled, and undo notifies too: restoring
     * memory during `undo()` goes through the same stores, so an observed range reports the
     * restored values as ordinary writes.
     * @returns A handle for `removeMemoryObserver`.
     */
    addMemoryWriteObserver(startAddress: number, endAddress: number, handler: MemoryWriteObserver): MemoryObserverHandle;

    /**
     * Observes reads and writes of a single word, the shape a memory-mapped register wants. The
     * address must be word-aligned. Pass `null` for a direction you do not care about. The same
     * lifetime and synchronous-handler rules as `addMemoryWriteObserver` apply.
     * @returns A handle for `removeMemoryObserver`.
     */
    addMemoryAccessObserver(address: number, onRead: MemoryAccessObserver | null, onWrite: MemoryAccessObserver | null): MemoryObserverHandle;

    /**
     * Removes one registration. An unknown handle is ignored.
     */
    removeMemoryObserver(handle: MemoryObserverHandle): void;

    /**
     * Removes every registration.
     */
    removeMemoryObservers(): void;

    /**
     * The number of live registrations, across every `JsMips` instance.
     */
    countMemoryObservers(): number;

    /**
     * Gets the next statement to be executed.
     * @returns The next `JsProgramStatement`.
     */
    getNextStatement(): JsProgramStatement;

    /**
     * Sets the value of a register. Outside a Poke the write is direct: it records no undo step.
     * Inside a Poke it joins the open transaction, except for `$zero`, which holds no value and so
     * is left alone.
     * @param register The name of the register.
     * @param value The value to set the register to.
     */
    setRegisterValue(register: RegisterName, value: number): void;

    /**
     * Checks if the simulation has terminated.
     * @returns True if the simulation has terminated, false otherwise.
     */
    terminated: boolean;
}



/**
 * Creates a MIPS simulator from a virtual source tree and its entry file.
 *
 * Source paths are canonical, root-relative, case-sensitive POSIX paths. The source set is
 * snapshotted by this call; only the entry file and files reached through `.include` are assembled.
 * @param files Source text keyed by canonical source path.
 * @param entryFile Canonical path of the file from which include expansion starts.
 * @returns A new `JsMips` object.
 */
export function makeMipsFromFiles(files: MIPSSourceSet, entryFile: string): JsMips {
    if (files === null || typeof files !== 'object' || Array.isArray(files)) {
        throw new TypeError('Source set must be an object')
    }
    if (typeof entryFile !== 'string') {
        throw new TypeError('Entry file must be a string')
    }
    const entries = Object.entries(files)
    for (const [sourcePath, source] of entries) {
        if (typeof source !== 'string') {
            throw new TypeError(`Source content must be a string: ${sourcePath}`)
        }
    }
    initializeMIPS()
    return _makeMipsFromFiles(
        entries.map(([sourcePath]) => sourcePath),
        entries.map(([, source]) => source),
        entryFile,
    ) as JsMips
}

/**
 * Initializes the MIPS simulator.
 */
function initializeMIPS(): void {
    _initializeMIPS()
}
