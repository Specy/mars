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
First create an instance of the simulator with the `makeMipsfromSource` function.

Before running the simulator, you must assemble and initialize it.  You can then step through the program, simulate with breakpoints, or simulate with a limit.

⚠️**WARNING**⚠️ You must have only one instance of the simulator at a time. Memory, registers, and other state are shared between instances.

```typescript
import { makeMipsfromSource, JsMips, RegisterName, BackStepAction } from '@specy/mips';

const sourceCode = `
  .text
  .globl main

main:
  li $v0, 10      # Exit program
  syscall
`;

const mipsSimulator: JsMips = makeMipsfromSource(sourceCode);

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

// Registering Handlers (for syscalls and other events):
mipsSimulator.registerHandler("printInt", (value: number) => {
    console.log("printInt syscall called with:", value);
});

// Handlers may also be async: returning a promise suspends the simulation until it settles,
// so IO can be backed by an async API without blocking the event loop.
mipsSimulator.registerHandler("readInt", async () => {
    return await promptUserForANumber();
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
mipsSimulator.registerHandler("readInt", () => 42);                  // synchronous
mipsSimulator.registerHandler("readString", () => fetchLine());      // asynchronous
```

When a handler returns a promise the simulation suspends at that instruction and resumes once the
promise settles, so nothing has to be shoehorned into a synchronous API. Because of that,
`step`, `simulateWithLimit`, `simulateWithBreakpoints` and `simulateWithBreakpointsAndLimit` all
return a promise. Everything else on `JsMips` (registers, memory, statements, the undo stack)
stays synchronous.

If a handler's promise rejects, the pending `step`/`simulate*` call rejects as well, with the
rejection reason in the message.

Synchronous handlers never yield to the event loop: the simulation runs straight through, and the
returned promise settles on a microtask. Overlapping calls are queued and run one after another,
never concurrently.

## API

### `makeMipsfromSource(source: string): JsMips`

Creates a new `JsMips` instance from MIPS assembly source code.

### `JsMips` Interface

#### Methods

*   `assemble()`: Assembles the program.
*   `initialize(startAtMain: boolean)`: Initializes the simulator. If `startAtMain` is true, execution begins at the `main` label; otherwise, it starts at the first instruction.
*   `step(): Promise<boolean>`: Executes a single instruction. Resolves to `true` if the execution is complete, `false` otherwise.
*   `simulateWithLimit(limit: number): Promise<boolean>`: Simulates the program for a maximum of `limit` instructions. Resolves to `true` if the execution is complete, `false` otherwise.
*   `simulateWithBreakpoints(breakpoints: number[]): Promise<boolean>`: Simulates the program until a breakpoint is reached.  `breakpoints` is an array of memory addresses. Resolves to `true` if the execution is complete, `false` otherwise.
*   `simulateWithBreakpointsAndLimit(breakpoints: number[], limit: number): Promise<boolean>`: Simulates the program until a breakpoint is reached or the limit is reached. Resolves to `true` if the execution is complete, `false` otherwise.
*   `getRegisterValue(register: RegisterName): number`: Returns the value of the specified register.
*   `registerHandler(name: HandlerName, handler: Function): void`: Registers a handler function for a specific event (e.g., syscalls).  See the `HandlerName` type for possible event names. A handler may return its result directly or return a promise of it, see [IO handlers](#io-handlers).
*   `getStackPointer(): number`: Returns the current value of the stack pointer.
*   `getProgramCounter(): number`: Returns the current value of the program counter.
*   `getRegistersValues(): number[]`: Returns an array of all register values.
*   `getUndoStack(): JsBackStep[]`: Returns the undo stack, which contains information about previous simulation steps.
*   `readMemoryBytes(address: number, length: number): number[]`: Reads `length` bytes from memory starting at `address`.
*   `setMemoryBytes(address: number, bytes: number[]): void`: Writes `bytes` to memory starting at `address`.
*   `getCurrentStatementIndex(): number`: Returns the index of the current statement in the assembled program.
*   `getNextStatement(): JsProgramStatement`: Returns the next `JsProgramStatement` to be executed.
*   `setRegisterValue(register: RegisterName, value: number): void`: Sets the value of the specified register.
*   `hasTerminated(): boolean`: Returns `true` if the simulation has terminated, `false` otherwise.

#### Types

*   `RegisterName`: Type for MIPS register names (e.g., `$zero`, `$v0`, `$ra`).
*   `BackStepAction`: Enum representing the types of undo actions.
*   `JsProgramStatement`: Interface representing a statement in the assembled program.
*   `JsBackStep`: Interface representing a back step in the simulation.
*   `HandlerName`: Type representing the name of a handler function.