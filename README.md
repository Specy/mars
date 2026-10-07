[![npm](https://img.shields.io/npm/v/@specy/mips.svg)](https://www.npmjs.com/package/@specy/mips)

# MARS

This is a fork of the [original MARS editor](https://github.com/dpetersanderson/MARS). 

It has been made to decouple the UI from the core simulator.

The repository is split into two parts:
- `mars` Which contains the core simulator and class interfaces to implement IO.
- `marsjs` A Typescript library that compiles the simulator to JavaScript and provides a simple interface to interact with it.

If you are looking for the original MARS, you can find it [here](https://dpetersanderson.github.io/)

If you are looking for the mips docs, you can find it [here](https://dpetersanderson.github.io/Help/MarsHelpIntro.html)


# Mips-js
This is a Typescript implementation of a MIPS simulator made by compiling the [original MARS editor](https://github.com/dpetersanderson/MARS) to Javascript.
It is part of a family of javascript assembly interpreters/simulators:

- MIPS: [git repo](https://github.com/Specy/mars),  [npm package](https://www.npmjs.com/package/@specy/mips)
- RISC-V: [git repo](https://github.com/Specy/rars), [npm package](https://www.npmjs.com/package/@specy/risc-v)
- X86: [git repo](https://github.com/Specy/x86-js), [npm package](https://www.npmjs.com/package/@specy/x86)
- M68K: [git repo](https://github.com/Specy/s68k), [npm package](https://www.npmjs.com/package/@specy/s68k)


## Installation

```bash
npm install @specy/mips
```

## Usage

Create a simulator from a virtual source tree and the canonical path of its entry file. Assembly
starts at that file and expands `.include` directives transitively; files that are not reached are
ignored.

Before running the simulator, you must assemble and initialize it.  You can then step through the program, simulate with breakpoints, or simulate with a limit.

Floating point literals are read as Java 21 reads them, correctly rounded: `.double` stores
`Double.parseDouble` of its token and `.float` that double rounded to a float, as MARS does, while
the `gnu-compiler-v1` profile reads `.float` straight to single precision, as GNU as does.

⚠️**WARNING**⚠️ You must have only one instance of the simulator at a time. Memory, registers, and other state are shared between instances.

```typescript
import { makeMipsFromFiles, JsMips, RegisterName, BackStepAction } from '@specy/mips';

const files = {
  'src/main.asm': `
    .include "lib/exit.asm"
    .text
    .globl main

  main:
    exit
  `,
  'src/lib/exit.asm': `
    .macro exit
      li $v0, 10
      syscall
    .end_macro
  `,
} as const;

const mipsSimulator: JsMips = makeMipsFromFiles(files, 'src/main.asm');

mipsSimulator.assemble();
mipsSimulator.initialize(true); // Start at 'main'

while (!mipsSimulator.terminated) {
    await mipsSimulator.step();
}

const pc = mipsSimulator.programCounter;
const v0 = mipsSimulator.getRegisterValue('$v0');

console.log(`Program Counter: ${pc}`);
console.log(`$v0: ${v0}`);

// Accessing memory:
const data = mipsSimulator.readMemoryBytes(0xffff0000, 4); // Read 4 bytes from address 0x1000
mipsSimulator.setMemoryBytes(0xffff0000, [0x01, 0x02, 0x03, 0x04]); // Write 4 bytes to address 0x1000

// Registering Handlers (for syscalls and other events): every print syscall writes its text here.
mipsSimulator.registerHandler("printString", (text: string) => {
    process.stdout.write(text);
});

// Handlers may also be async: returning a promise suspends the simulation until it settles,
// so IO can be backed by an async API without blocking the event loop.
mipsSimulator.registerHandler("readInt", async () => {
    return await promptUserForALine(); // the syscall parses the line itself
});

// Accessing the undo stack. Every step reports both sides of the write it undoes: `param2` is
// the value the write replaced and `newValue` the value it left, both as the simulator saw them
// at the moment of the write.
const undoStack = mipsSimulator.getUndoStack();
undoStack.forEach(step => {
    if (step.action === BackStepAction.REGISTER_RESTORE) {
        console.log(`Register ${step.param1} went from ${step.param2} to ${step.newValue} at PC ${step.pc}`);
    }
});


// Simulating with breakpoints:
const breakpoints = [0x00400004, 0x00400008]; // Example breakpoint addresses
await mipsSimulator.simulateWithBreakpoints(breakpoints);

//Simulating with a limit
const limit = 100
await mipsSimulator.simulateWithLimit(limit);

//Simulating with breakpoints and a limit
await mipsSimulator.simulateWithBreakpointsAndLimit(breakpoints, limit);

//Setting Register Values:
mipsSimulator.setRegisterValue("$t0", 42);
```

## IO handlers

Every syscall that needs to talk to the outside world goes through a handler you register. A
handler can return its result directly, or return a promise of it:

```typescript
mipsSimulator.registerHandler("readInt", () => "42");                // synchronous
mipsSimulator.registerHandler("readString", () => fetchLine());      // asynchronous
```

When a handler returns a promise the simulation suspends at that instruction and resumes once the
promise settles, so nothing has to be shoehorned into a synchronous API. Because of that,
`step`, `simulate`, `simulateWithLimit`, `simulateWithBreakpoints` and
`simulateWithBreakpointsAndLimit` all return a promise. Everything else on `JsMips` (registers, memory, statements, the undo stack)
stays synchronous.

If a handler throws, its promise rejects or it answers what its type does not allow, the pending
`step`/`simulate*` call rejects with a `RuntimeError` of kind `handler`, whose `cause` is what the
handler threw or rejected with (see [Running and stopping](#running-and-stopping)).

Synchronous handlers never yield to the event loop: the simulation runs straight through, and the
returned promise settles on a microtask. Overlapping calls are queued and run one after another,
never concurrently.

The syscalls behave as MARS 4.5 does on Java 21, so handlers only move text and bytes:

*   Every print syscall formats its value and writes the text through `printString`. Print float
    and print double write what `Float.toString` and `Double.toString` write: `1.0`, `0.001`,
    `1.0E-4`, `1.0E7`, `-0.0`, `NaN`, `Infinity`.
*   `readInt`, `readFloat` and `readDouble` answer with the line typed. The syscall trims it and
    parses it as `Integer.parseInt`, `Float.parseFloat` and `Double.parseDouble` do, hexadecimal
    floats and an `f` or `d` suffix included, and stops the program on anything else:
    `Runtime exception at 0x00400004: invalid integer input (syscall 5)`.
*   `readChar` answers with the character typed, Enter as `"\n"`. The syscall takes the first
    UTF-16 unit, and an empty answer stops the program with `invalid char input (syscall 12)`.
*   `inputDialog` answers `null` when the user cancels, which dialogs 51 to 54 report to the
    program as status -2; `confirm` answers 2 for Cancel. The program waits for `outputDialog` to
    settle.
*   Bytes cross as plain arrays of numbers from 0 to 255. `writeFile` answers with the number of
    bytes written, or -1, and the program receives that count; `readFile` and `stdIn` answer
    `[count, bytes]`, with a count of 0 at the end of the input and -1 for a failed read.

```typescript
mipsSimulator.registerHandler("inputDialog", (message) => window.prompt(message)); // null on Cancel
mipsSimulator.registerHandler("writeFile", (descriptor, bytes) => files.write(descriptor, Uint8Array.from(bytes)));
mipsSimulator.registerHandler("stdIn", async (length) => {
    const bytes = await terminal.readBytes(length);
    return [bytes.length, Array.from(bytes)];
});
```

| Handler | Syscalls | Receives | Answers |
| --- | --- | --- | --- |
| `printString` | 1 to 4, 11, 34 to 36 | the text to print | nothing |
| `readInt`, `readFloat`, `readDouble`, `readString` | 5 to 8 | nothing | the line typed |
| `readChar` | 12 | nothing | the character typed |
| `openFile` | 13 | path, flags, whether to append | the file descriptor, or -1 |
| `readFile` | 14 | file descriptor, most bytes to read | `[count, bytes]` |
| `writeFile` | 15 | file descriptor, bytes | bytes written, or -1 |
| `closeFile` | 16 | file descriptor | nothing |
| `seekFile` | 62 | file descriptor, offset, whence | the new position, or -1 |
| `stdIn` | 14 on descriptor 0 | most bytes to read | `[count, bytes]` |
| `stdOut`, `stdErr` | 15 on descriptors 1 and 2 | bytes | nothing |
| `confirm` | 50 | message | `ConfirmResult` |
| `inputDialog` | 51 to 54 | message | the text entered, or `null` |
| `outputDialog` | 55 to 59 | message, `DialogType` | nothing |
| `sleep` | 32 | milliseconds | nothing |
| `time` | 30 | nothing | milliseconds |
| `randomSeed` (optional) | 41 to 44, a generator's first use | the generator's number | a seed from 0 to 2^48 - 1 |

## Text

Text is UTF-8 throughout, a deviation from MARS 4.5, which stores a string literal and reads and
writes strings one byte per character (Latin-1). A string's text is then the same in a literal,
through print string (4) and through write (15), and the same as the UTF-8 a C compiler emits:

*   `.ascii` and `.asciiz` store a literal by code point. Besides MARS's escapes, a literal takes
    RARS's `\u` escape, four hexadecimal digits naming one UTF-16 unit, and two units that make a
    surrogate pair name one character: `"\ud83d\ude00"` stores the same four bytes as `"😀"`.
*   Print string (4), the path of open (13) and the messages of the dialogs (50 to 59) are read as
    UTF-8, the way Java 21 decodes it: a malformed sequence prints as U+FFFD.
*   Read string (8) and input dialog string (54) store the text as UTF-8, measuring the buffer in
    bytes, as RARS does: a buffer of n bytes holds at most n - 1 bytes of text, so a character can be
    cut where the buffer ends. An unpaired surrogate is stored as `?`, as Java encodes it.
*   Print char (11) prints the character numbered by the low byte of `$a0`, and read char (12)
    answers the first UTF-16 unit typed: 233 for `é`, 8364 for `€` and 0xd83d, a high surrogate,
    for `😀`. Both work as in MARS and RARS.
*   Print string (4) prints a string of any length, up to its NUL byte, as MARS 4.5 does. A byte it
    cannot read stops the program with MARS's address error, after printing the text read before it.

## Random numbers

The random services draw from `java.util.Random`, implemented exactly, so a program that seeds a
generator with syscall 40 prints the numbers MARS prints: `nextInt` (41), `nextInt(bound)` with
Java's rejection of over-represented values (42), `nextFloat` (43) and `nextDouble` (44). Each
number in `$a0` names a generator of its own.

A generator the program has not seeded starts, on its first use, from the seed the optional
`randomSeed` handler answers: a whole number from 0 to 2^48 - 1, as `new Random(seed)` takes it.
Without the handler it starts from host randomness, as in MARS; a scripted run answers with a fixed
seed instead and gets the same numbers every time. Handlers are shared by every `JsMips` instance,
and registering `undefined` removes one.

```typescript
mipsSimulator.registerHandler("randomSeed", () => 42);      // the same numbers on every run
mipsSimulator.registerHandler("randomSeed", undefined);     // host randomness again
```

The generators belong to the run: `initialize` forgets them, so each starts from a new seed. Undo
puts a generator back as it was before the service ran, so Undo then Step draws the same number.

## Running and stopping

Every run call (`step`, `simulate`, `simulateWithLimit`, `simulateWithBreakpoints`,
`simulateWithBreakpointsAndLimit`) resolves to a `StopReason`, the same enum `@specy/risc-v`
exports:

| `StopReason` | When |
| --- | --- |
| `MAX_STEPS` | the instruction limit was reached; a `step` ends on it |
| `BREAKPOINT` | the program counter reached a breakpoint address, whose instruction has not run |
| `NORMAL_TERMINATION` | exit (10) or exit2 (17) ran |
| `CLIFF_TERMINATION` | the program ran off the end of its code; the call that runs the last instruction says so |

`terminated` is read from the program's state, so it is right after `undo()` too: it is true once
an exit has run or there is no statement at the program counter, and `getNextStatement()` then
returns `null`. A program that has exited runs nothing more: every run call resolves to
`NORMAL_TERMINATION` again until `undo()` or `initialize()`. `exitCode` is exit2's `$a0` once it
has run and 0 otherwise, after exit and after running off the end too; `initialize` resets it and
undoing the exit puts back the code before it. `getStopReason()` reports the last run call's
reason, `NONE` after `initialize`.

A runtime failure rejects the run call with a `RuntimeError`, an `Error` with typed fields:

```typescript
try {
    await mipsSimulator.simulate();
} catch (error) {
    if (isRuntimeError(error)) {
        // error.kind: 'exception' | 'syscall' | 'handler' | 'internal'
        // error.address, error.sourcePath, error.line (one-based, null without a statement)
        console.log(`${error.sourcePath}:${error.line}: ${error.message}`);
        // main.asm:7: Runtime exception at 0x00400018: invalid or unimplemented syscall service: 99
    }
}
```

`exception` is a MIPS exception the program had no handler for (an address error, an overflow, a
trap, an instruction fetch outside the program), `syscall` a service that refused its number,
arguments or input, `handler` a host handler or memory observer that threw, rejected or broke its
contract (its `cause` is what it threw), and `internal` the simulator failing. The message is
MARS's own. A failure leaves `terminated` false and `getStopReason()` at `EXCEPTION`; any other
rejection, such as a run call on a program that did not assemble, is the plain error it is.

## Memory layout

Programs run in MARS's memory layout: text from 0x00400000, static data from 0x10010000, the heap
from 0x10040000, the stack down from 0x7fffeffc and memory-mapped IO from 0xffff0000. The data
segment, which static data and the heap share, ends at 0x10400000.

*   A MARS-dialect program keeps this layout whatever its size, as in MARS: data past
    0x10040000 shares its addresses with the heap.
*   A `gnu-compiler-v1` program's heap follows its static data (`.data`, `.rodata`, `.bss` and
    common symbols): once they reach past 0x10040000, the heap starts at the first 4 KiB page after
    them, where ld and a kernel put the break, and sbrk (9) hands out that address first. Static data
    must fit in the data segment, 4,128,768 bytes from 0x10010000; a program with more fails to
    assemble with `Static data ends at 0x10410000, past the end of the data segment at 0x10400000: ...`.
*   Library members linked after a MARS-dialect program must end below 0x10040000, or the program
    fails to assemble with `Static data reaches the heap at 0x10040000`.

`getHeapStart()` reports where the heap starts, and `initialize` empties it again. An allocator
built on sbrk follows the heap wherever it starts.

## API

### `makeMipsFromFiles(files: MIPSSourceSet, entryFile: string): JsMips`

Creates a `JsMips` instance from a snapshotted virtual source tree. Source paths are canonical,
root-relative, case-sensitive POSIX paths such as `src/main.asm`. Relative includes resolve from the
including file, and includes beginning with `/` resolve from the virtual root.

### `JsMips` Interface

#### Methods

*   `assemble()`: Assembles the program.
*   `initialize(startAtMain: boolean)`: Initializes the simulator. If `startAtMain` is true, execution begins at the `main` label; otherwise, it starts at the first instruction.
*   `step(): Promise<StopReason>`: Executes a single instruction. See [Running and stopping](#running-and-stopping) for every run call's `StopReason` and `RuntimeError`.
*   `simulate(): Promise<StopReason>`: Simulates the program until it stops.
*   `simulateWithLimit(limit: number): Promise<StopReason>`: Simulates the program for a maximum of `limit` instructions.
*   `simulateWithBreakpoints(breakpoints: number[]): Promise<StopReason>`: Simulates the program until a breakpoint is reached.  `breakpoints` is an array of memory addresses.
*   `simulateWithBreakpointsAndLimit(breakpoints: number[], limit: number): Promise<StopReason>`: Simulates the program until a breakpoint is reached or the limit is reached.
*   `getStopReason(): StopReason`: Why the last run call stopped, `NONE` after `initialize`.
*   `getHeapStart(): number`: Where the heap, and so sbrk's first block, starts: 0x10040000, or the
    first page after static data in a `gnu-compiler-v1` program whose static data reaches past it.
    See [Memory layout](#memory-layout).
*   `exitCode: number`: exit2's code once it has run, 0 otherwise.
*   `getRegisterValue(register: RegisterName): number`: Returns the value of the specified register.
*   `registerHandler(name: HandlerName, handler: Function): void`: Registers a handler function for a specific event (e.g., syscalls).  See the `HandlerName` type for possible event names. A handler may return its result directly or return a promise of it, see [IO handlers](#io-handlers).
*   `getStackPointer(): number`: Returns the current value of the stack pointer.
*   `getProgramCounter(): number`: Returns the current value of the program counter.
*   `getRegistersValues(): number[]`: Returns an array of all register values.
*   `getUndoStack(): JsBackStep[]`: Returns the undo stack, which contains information about previous simulation steps. Each step reports the value the write replaced (`param2`) beside the value it wrote (`newValue`).
*   `readMemoryBytes(address: number, length: number): number[]`: Reads `length` bytes from memory starting at `address`.
*   `setMemoryBytes(address: number, bytes: number[]): void`: Writes `bytes` to memory starting at `address`.
*   `getTokenizedLines(): MipsTokenizedLine[]`: Returns the flattened tokenized lines with their original source paths and one-based line numbers.
*   `getParsedStatements(): JsProgramStatement[]`: Returns the parsed source statements.
*   `getCompiledStatements(): JsProgramStatement[]`: Returns every generated machine statement.
*   `getStatementsAtSourceLocation(sourcePath: string, sourceLine: number): JsProgramStatement[]`: Returns every machine statement generated by one original source line, including pseudo-instruction and macro expansions.
*   `getNextStatement(): JsProgramStatement | null`: Returns the next `JsProgramStatement` to be executed, or `null` once the program has terminated.
*   `setRegisterValue(register: RegisterName, value: number): void`: Sets the value of the specified register.
*   `terminated: boolean`: Whether the program has ended, by an exit or by running off the end, read from its state so that it is right after `undo()`.

#### Types

*   `RegisterName`: Type for MIPS register names (e.g., `$zero`, `$v0`, `$ra`).
*   `BackStepAction`: Enum representing the types of undo actions.
*   `MIPSSourceSet`: Read-only mapping from canonical source paths to source text.
*   `MipsTokenizedLine`: A tokenized line with its original and processed source text and source location.
*   `JsProgramStatement`: Interface representing a statement in the assembled program.
*   `JsBackStep`: Interface representing a back step in the simulation.
*   `HandlerName`: Type representing the name of a handler function.
*   `StopReason`: Enum of the reasons a run call stops.
*   `RuntimeError`: The typed error a run call rejects with when the program fails; `isRuntimeError(error)` tells it apart.
