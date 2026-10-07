# Mips-js

A JavaScript library for simulating MIPS assembly code.  This library provides an interface to assemble, execute, and inspect the state of a MIPS simulator.  It's built by compiling the [mars](https://github.com/dpetersanderson/MARS) simulator with [TeaVM](https://teavm.org/), with some glue code on top to make it easier to use.
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

The optional third factory argument selects the assembly profile, the entry symbol and the libraries to link:

```typescript
const core = makeMipsFromFiles(files, 'main.s', { assemblerProfile: 'gnu-compiler-v1' });
```

Omission selects `mars`, the educational MARS dialect with its macros and pseudo instructions, which is unchanged. `MIPS.assemblerProfiles` lists the supported profiles; every present invalid profile throws. `gnu-compiler-v1` is a bounded static GNU assembler for the MIPS32 output of GCC (`-march=mips32 -mabi=32 -mno-abicalls -fno-pic -G0 -fno-delayed-branch -mfp32 -mhard-float -EL`): independent named `.text*`, `.rodata*`/`.rdata`, `.data*`, `.bss*` and common sections, little-endian data with GNU as's MIPS self-alignment of `.half`/`.word`/`.dword`/`.float`/`.double` (`.align 0` turns it off), byte-valued string escapes, bounded expressions and aliases, numeric labels, `%hi`/`%lo`, PC-relative branches and absolute jumps. GCC's macros get GNU as's fixed expansions (`move`, `li`, `la`, `b`, `bal`, `beqz`, `bnez`, `neg`, `negu`, `not`, `slt`/`sltu` with an immediate, `sll`/`srl`/`sra` with a register amount, `div $0,…`, a bare load or store address through `$at`), other register-only macros use MARS's fixed templates (`seq`, `sge`, `abs`, `rol`, `bge`, `ulw`, `mfc1.d`, …), and `$fccN` condition codes are accepted. `.set` options that leave encodings unchanged (`noreorder`, `nomacro`, `noat`, `nomips16`, `push`/`pop`, …), `.module fp=32`, `.nan`, `.gnu_attribute 4,1`, `.ent`/`.end`/`.frame`/`.mask` and debug sections are accepted; MIPS16, microMIPS, 64-bit FPU modes, position-independent code, unsupported relocations and unresolved symbols are errors.

MARS executes branches without delay slots: a taken branch skips the instruction after it, and a link saves that instruction's address. In `.set noreorder` code every delay slot must therefore hold a `nop`, which is what GCC writes with `-fno-delayed-branch`, and a call returns to its `nop`; a filled slot is an error. In reorder mode no slot is emitted at all. Conditional traps lose their code field, which MARS does not encode.

Units link with ld semantics: global and weak symbols (an undefined weak reference is zero), COMDAT groups (the first copy is kept), `.init_array`/`.fini_array` (and their `.NNNNN` priority sections, in priority order before the plain one) with ld-provided `__init_array_start`/`__init_array_end` bounds, and global labels in the global symbol table. A conditional branch whose target ends up beyond its ±128 KiB reach is relaxed into the inverted branch over a `j`. `libraries` are archives of `gnu-compiler-v1` members with an index naming the member that defines each global: a member is pulled only for a global the program uses and does not define, transitively, after the program's own sections; a weak reference pulls nothing unless the library lists the symbol in `resolveWeak`. A MARS-dialect program can call members too: they are placed after its text and data, a global it defines is never pulled, and a program that pulls no member assembles byte for byte as before. `entrySymbol` (for example `_start`) must be defined by the link, pulls its own member, and is where `initialize` starts execution. `MIPS.analyzeGnuUnit(path, source)` reports what one unit defines and needs, for building an index. Errors read `Unresolved symbol: name` on the referencing line, `Multiple definition of name, first defined in path` and `Undefined entry symbol: name`.

`getAddressOfLabel(name)` returns a defined label/alias address after successful assembly, or `-1` when absent. Profile state is per program; memory and execution state retain the single-instance constraint below.

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

// Accessing the undo stack:
const undoStack = mipsSimulator.getUndoStack();
undoStack.forEach(step => {
    if (step.action === BackStepAction.REGISTER_RESTORE) {
        console.log(`Register restored at PC ${step.pc}`);
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

Program time is a handler too, so a run can be given a clock of its own: `sleep` answers syscall 32
and `time` answers syscall 30. A live run resolves `sleep` on a timer and returns `Date.now()` from
`time`; a scripted run can settle `sleep` immediately, advance a virtual clock by the requested
milliseconds and return that clock instead, which keeps elapsed-time output reproducible.

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

## Memory observers

A memory-mapped device - a framebuffer, a keyboard register - is modelled by observing the memory
the program reads and writes:

```ts
// Every write in a framebuffer: (address, length, value), with the width of the store in bytes.
const frame = mipsSimulator.addMemoryWriteObserver(0x10010000, 0x10012ffc, (address, length, value) => {
    screen.markDirty(address)
})

// One memory-mapped register: reads and writes, either of which may be null.
const receiver = mipsSimulator.addMemoryAccessObserver(
    0xffff0004,
    () => keyboard.consumeCharacter(),
    null
)

mipsSimulator.removeMemoryObserver(frame)
mipsSimulator.removeMemoryObservers()
```

*   Addresses must be word-aligned, `endAddress` is inclusive and covers its whole word, and a range
    may not cross `0x80000000`; a registration that breaks any of these throws. Either form of a
    high address is accepted: `0xffff0000` and `0xffff0000 | 0` name the same word.
*   Handlers are given signed 32 bit integers, as the guest holds them: the register at
    `0xffff000c` arrives as `-65524`, and a pixel word with its high bit set arrives negative.
    Apply `>>> 0` wherever the unsigned form is wanted.
*   Handlers run synchronously inside the instruction that caused the access, so they must be cheap
    and must not write back into their own range. A returned promise is ignored, unlike an IO
    handler's.
*   An observer is notified *after* the access, with the value the program read or stored. A
    register whose value is consumed by reading it must therefore be reloaded from the handler,
    with `setPeripheralWord`, for the next read.
*   Observers live on the simulator's memory, which assembling and initializing only clear the
    contents of, so a registration survives `assemble()` and `initialize()` and - like a registered
    IO handler - is shared by every `JsMips` instance. Notifications start once a program has been
    assembled.
*   `undo()` restores memory through the same stores, so an observed range reports the restored
    values as ordinary writes and a device that follows notifications alone stays in step.

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
*   `getUndoStack(): JsBackStep[]`: Returns the undo stack, which contains information about previous simulation steps.
*   `readMemoryBytes(address: number, length: number): number[]`: Reads `length` bytes from memory starting at `address`. Notifies no memory observer: inspecting memory from the host is not the program reading it.
*   `setMemoryBytes(address: number, bytes: number[]): void`: Writes `bytes` to memory starting at `address`, the way the program does: write observers are notified and, while undo is enabled, an undo step is recorded per byte.
*   `setPeripheralWord(address: number, value: number): void`: Writes one word-aligned word as a peripheral would, notifying no observer and recording no undo step. See [memory observers](#memory-observers).
*   `addMemoryWriteObserver(startAddress: number, endAddress: number, handler): number`: Observes every write in an address range. See [memory observers](#memory-observers).
*   `addMemoryAccessObserver(address: number, onRead, onWrite): number`: Observes reads and writes of one word. See [memory observers](#memory-observers).
*   `removeMemoryObserver(handle: number): void`: Removes one registration.
*   `removeMemoryObservers(): void`: Removes every registration.
*   `countMemoryObservers(): number`: The number of live registrations.
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

### Dynamic instruction identities

`getCurrentInstructionSerial(): string | null` names the instruction currently executing. Read it
inside a host handler (including an awaited handler) to journal host effects. The eventual
`JsInstructionUndoGroup.serial` and each `JsBackStep.serial` carry exactly that identity. Core-only
services, exits, write-free instructions and failed instructions also receive their own identity.
Pokes have a distinct `JsPokeUndoGroup.serial`; the current-instruction getter remains null in a Poke.
Serials are opaque positive decimal strings, allocated monotonically for the lifetime of this module
across instances, Undo, initialize and reassembly. Do not convert them to JavaScript numbers. At
serial-space exhaustion execution fails rather than reusing an identity.

History keeps only the bounded restore stack. The Undo size still counts raw restore slots, with a
Poke taking one slot; oldest instructions are evicted whole. An instruction larger than the capacity
is discarded whole, so a retained group always represents a complete rollback. `initialize` clears
retained history and Poke journals. Executing an instruction or opening a Poke with recording
disabled clears older retained history, preventing Undo across an unrecorded gap. No-history
instructions still expose a serial inside handlers. Match and retain host frames by serial against
`getUndoGroups`/`getUndoGroupsRange`, never by PC; drop frames whose groups are no longer retained.

`initialize` is rejected while an instruction or Poke is active.
