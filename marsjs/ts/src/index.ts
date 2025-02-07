//@ts-ignore
import {makeMipsfromSource as _makeMipsfromSource, initializeMIPS as _initializeMIPS} from './generated/mars'

/**
 * Represents a statement in the assembled program.
 */
export interface JsProgramStatement {
    /**
     * The line number in the original source code.
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
     * The program counter value before the action.
     */
    readonly pc: number;
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

type HandlerName =
    "openFile"
    | "closeFile"
    | "writeFile"
    | "readFile"
    | "confirm"
    | "inputDialog"
    | "outputDialog"
    | "askInt"
    | "askDouble"
    | "askFloat"
    | "askString"
    | "readInt"
    | "readDouble"
    | "readFloat"
    | "readString"
    | "readChar"
    | "logLine"
    | "log"
    | "printChar"
    | "printDouble"
    | "printFloat"
    | "printInt"
    | "printString"
    | "stdIn"
    | "stdOut"
    | "stdErr"


/**
 * Interface for interacting with a MIPS simulator.
 */
export interface JsMips {
    /**
     * Assembles the program.
     */
    assemble(): void;

    /**
     * Initializes the simulator.
     * @param startAtMain If true, starts execution at the 'main' label. Otherwise, starts at the first instruction.
     */
    initialize(startAtMain: boolean): void;

    /**
     * Executes a single instruction.
     * @returns True if the execution is complete, false otherwise.
     */
    step(): boolean;

    /**
     * Simulates the program for a limited number of instructions.
     * @param limit The maximum number of instructions to execute.
     * @returns True if the execution is complete, false otherwise.
     */
    simulateWithLimit(limit: number): boolean;

    /**
     * Simulates the program until a breakpoint is reached.
     * @param breakpoints An array of memory addresses where the simulation should pause.
     * @returns True if the execution is complete, false otherwise.
     */
    simulateWithBreakpoints(breakpoints: number[]): boolean;

    /**
     * Simulates the program with both breakpoints and a limit.
     * @param breakpoints An array of memory addresses where the simulation should pause.
     * @param limit The maximum number of instructions to execute.
     * @returns True if the execution is complete, false otherwise.
     */
    simulateWithBreakpointsAndLimit(breakpoints: number[], limit: number): boolean;

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
    registerHandler(name: HandlerName, handler: Function): void;

    /**
     * Gets the current value of the stack pointer.
     * @returns The value of the stack pointer.
     */
    getStackPointer(): number;

    /**
     * Gets the current value of the program counter.
     * @returns The value of the program counter.
     */
    getProgramCounter(): number;

    /**
     * Gets the values of all registers.
     * @returns An array containing the register values. The order of the values is implementation defined.
     */
    getRegistersValues(): number[];

    /**
     * Gets the undo stack.
     * @returns An array of `JsBackStep` objects representing the history of the simulation.
     */
    getUndoStack(): JsBackStep[];

    /**
     * Reads a sequence of bytes from memory.
     * @param address The starting memory address.
     * @param length The number of bytes to read.
     * @returns An array of bytes read from memory.
     */
    readMemoryBytes(address: number, length: number): number[];

    /**
     * Writes a sequence of bytes to memory.
     * @param address The starting memory address.
     * @param bytes An array of bytes to write to memory.
     */
    setMemoryBytes(address: number, bytes: number[]): void;

    /**
     * Gets the index of the current statement in the assembled program.
     * @returns The index of the current statement.
     */
    getCurrentStatementIndex(): number;

    /**
     * Gets the next statement to be executed.
     * @returns The next `JsProgramStatement`.
     */
    getNextStatement(): JsProgramStatement;

    /**
     * Sets the value of a register.
     * @param register The name of the register.
     * @param value The value to set the register to.
     */
    setRegisterValue(register: RegisterName, value: number): void;

    /**
     * Checks if the simulation has terminated.
     * @returns True if the simulation has terminated, false otherwise.
     */
    hasTerminated(): boolean;
}



/**
 * Creates a new MIPS simulator from the given source code.
 * @param source The source code to assemble.
 * @returns A new `JsMips` object.
 */
export function makeMipsfromSource(source: string): JsMips {
    initializeMIPS()
    return _makeMipsfromSource(source) as JsMips
}

/**
 * Initializes the MIPS simulator.
 */
function initializeMIPS(): void {
    _initializeMIPS()
}
