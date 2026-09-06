// Smoke test for the published artifact: assembles and runs a small MIPS
// program through dist/, so it covers the whole Java -> TeaVM -> TypeScript
// chain rather than just type-checking. Run `npm run build` first.
import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const dist = new URL('../dist/index.mjs', import.meta.url)
if (!existsSync(fileURLToPath(dist))) {
    console.error('dist/index.mjs is missing - run `npm run build` (or `npm run build:all`) first.')
    process.exit(1)
}

const { MIPS, registerHandlers, unimplementedHandler } = await import(dist)

const SOURCE = `
    .data
msg:    .asciiz "sum = "

    .text
    .globl main
main:
    li   $t0, 0             # accumulator
    li   $t1, 1             # counter
loop:
    add  $t0, $t0, $t1
    addi $t1, $t1, 1
    ble  $t1, 10, loop      # sum 1..10 == 55

    li   $v0, 4             # print_string
    la   $a0, msg
    syscall

    li   $v0, 1             # print_int
    move $a0, $t0
    syscall

    li   $v0, 10            # exit
    syscall
`

const WARNINGS_ONLY_SOURCE = `
    .data
val: .byte 300
    .text
main:
    li $v0, 10
    syscall
`

const REAL_ERROR_SOURCE = `
    .text
main:
    bogus_instruction
`

// Every handler must be registered; the ones this program cannot reach throw
// so an unexpected syscall fails the test instead of silently doing nothing.
const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'askDouble', 'askFloat', 'askInt', 'askString', 'readDouble',
    'readFloat', 'readInt', 'readString', 'readChar', 'logLine', 'log', 'printChar',
    'printDouble', 'printFloat', 'printInt', 'printString', 'sleep', 'time', 'stdIn', 'stdOut',
    'stdErr',
]

const warningProgram = MIPS.makeMipsFromSource(WARNINGS_ONLY_SOURCE)
const warningsOnly = warningProgram.assemble()
assert.equal(warningsOnly.hasErrors, false, `warnings-only assembly failed: ${warningsOnly.report}`)
assert.equal(warningsOnly.hasWarnings, true, 'warnings-only assembly should report warnings')
assert.ok(warningsOnly.errors.length >= 1, 'warnings-only assembly should include at least one diagnostic')
assert.equal(warningsOnly.errors.every(error => error.isWarning === true), true, 'every warnings-only diagnostic should expose isWarning: true')

warningProgram.initialize(true)
let warningSteps = 0
while (!warningProgram.terminated && warningSteps < 10) {
    await warningProgram.step()
    warningSteps++
}
assert.ok(warningProgram.terminated, 'warnings-only program should remain runnable')

const realError = MIPS.makeMipsFromSource(REAL_ERROR_SOURCE).assemble()
assert.equal(realError.hasErrors, true, 'invalid assembly should report an error')
assert.ok(realError.errors.some(error => error.isWarning === false), 'invalid assembly should expose isWarning: false')

const output = []
const mips = MIPS.makeMipsFromSource(SOURCE)

registerHandlers(mips, {
    ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
    printInt: value => output.push(String(value)),
    printString: value => output.push(value),
    printChar: value => output.push(value),
})

const assembled = mips.assemble()
assert.equal(assembled.hasErrors, false, `assembly failed: ${assembled.report}`)
assert.equal(assembled.hasWarnings, false, `clean assembly produced warnings: ${assembled.report}`)

mips.initialize(true)

// step() resolves on a microtask, and settles later still if an IO handler
// returned a promise, so the loop has to await each instruction.
let steps = 0
while (!mips.terminated && steps < 10_000) {
    await mips.step()
    steps++
}

assert.ok(mips.terminated, `program did not terminate within ${steps} steps`)
assert.equal(output.join(''), 'sum = 55')
assert.equal(mips.getRegisterValue('$t0'), 55)
assert.equal(mips.getRegisterValue('$t1'), 11)
assert.ok(mips.getUndoStack().length > 0, 'undo stack should record executed steps')
assert.ok(MIPS.getInstructionSet().length > 0, 'instruction set should not be empty')

// Peripherals: the framebuffer range, a memory-mapped register and program time. The program
// stores three words into static data, reads the register word back, sleeps and asks for the time.
const FRAMEBUFFER = 0x10010000
// Deliberately the unsigned form: a memory-mapped address does not fit a positive int, and the
// wrapper has to accept it anyway or an observer registered from it could never match an access.
const REGISTER = 0xffff0000
// What the guest holds, and so what an observer is handed.
const SIGNED_REGISTER = 0xffff0000 | 0

const PERIPHERAL_SOURCE = `
    .text
    .globl main
main:
    li   $t0, 0x10010000
    li   $t1, 0x00ff0012
    sw   $t1, 0($t0)
    sw   $t1, 4($t0)
    sb   $t1, 8($t0)        # a byte store must report length 1

    lui  $t2, 0xffff
    lw   $t3, 0($t2)        # read of the observed register
    li   $t4, 7
    sw   $t4, 0($t2)        # write of the observed register

    li   $v0, 32            # sleep
    li   $a0, 25
    syscall

    li   $v0, 30            # time
    syscall
    move $s0, $a0

    li   $v0, 10
    syscall
`

const writes = []
const registerReads = []
const registerWrites = []
const slept = []
let clock = 1000

const peripherals = MIPS.makeMipsFromSource(PERIPHERAL_SOURCE)
registerHandlers(peripherals, {
    ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
    // A virtual clock: sleeping advances it instead of waiting, the scripted-run shape.
    sleep: milliseconds => {
        slept.push(milliseconds)
        clock += milliseconds
    },
    time: () => clock,
})

// Registering before assembling is allowed; notifications start once a program is assembled.
const framebufferHandle = peripherals.addMemoryWriteObserver(
    FRAMEBUFFER,
    FRAMEBUFFER + 8,
    (address, length, value) => writes.push([address, length, value])
)
const registerHandle = peripherals.addMemoryAccessObserver(
    REGISTER,
    (address, value) => registerReads.push([address, value]),
    (address, value) => registerWrites.push([address, value])
)
assert.equal(peripherals.countMemoryObservers(), 2)

const peripheralAssembly = peripherals.assemble()
assert.equal(peripheralAssembly.hasErrors, false, `peripheral assembly failed: ${peripheralAssembly.report}`)

// A peripheral preloads its register the way a keyboard would; that write must stay invisible.
peripherals.setPeripheralWord(REGISTER, 0x41)
assert.equal(registerWrites.length, 0, 'setPeripheralWord must not notify the observer')

peripherals.initialize(true)
let peripheralSteps = 0
while (!peripherals.terminated && peripheralSteps < 10_000) {
    await peripherals.step()
    peripheralSteps++
}
assert.ok(peripherals.terminated, 'peripheral program did not terminate')

assert.deepEqual(writes, [
    [FRAMEBUFFER, 4, 0x00ff0012],
    [FRAMEBUFFER + 4, 4, 0x00ff0012],
    // A byte store reports only the byte it wrote, not the whole register.
    [FRAMEBUFFER + 8, 1, 0x12],
], 'framebuffer writes should be reported in order, with the width of each store')
assert.deepEqual(registerReads, [[SIGNED_REGISTER, 0x41]], 'the register read should report the preloaded value')
assert.deepEqual(registerWrites, [[SIGNED_REGISTER, 7]], 'the register write should report the stored value')

// readMemoryBytes is host inspection, so it must not look like a program read.
assert.deepEqual(Array.from(peripherals.readMemoryBytes(REGISTER, 1)), [7])
assert.equal(registerReads.length, 1, 'readMemoryBytes must not notify a read observer')

assert.deepEqual(slept, [25], 'syscall 32 should reach the sleep handler with $a0')
assert.equal(peripherals.getRegisterValue('$s0'), 1025, 'syscall 30 should report the handler clock')

// Undo replays the memory restores through the same stores, so observers hear them: an adapter
// that follows the notifications alone still ends up with the rolled-back image.
const writesBeforeUndo = writes.length
while (peripherals.canUndo) {
    peripherals.undo()
}
assert.deepEqual(writes.slice(writesBeforeUndo), [
    [FRAMEBUFFER + 8, 1, 0],
    [FRAMEBUFFER + 4, 4, 0],
    [FRAMEBUFFER, 4, 0],
], 'undo should report each restored word, newest first, as an ordinary write')

// Assembling clears memory but not the registrations, so a rebuilt program is still observed.
const writesBeforeRebuild = writes.length
const reassembled = peripherals.assemble()
assert.equal(reassembled.hasErrors, false, `reassembly failed: ${reassembled.report}`)
peripherals.setPeripheralWord(REGISTER, 0x42)
peripherals.initialize(true)
let rebuiltSteps = 0
while (!peripherals.terminated && rebuiltSteps < 10_000) {
    await peripherals.step()
    rebuiltSteps++
}
assert.deepEqual(writes.slice(writesBeforeRebuild, writesBeforeRebuild + 3), [
    [FRAMEBUFFER, 4, 0x00ff0012],
    [FRAMEBUFFER + 4, 4, 0x00ff0012],
    [FRAMEBUFFER + 8, 1, 0x12],
], 'observers should survive assemble() and initialize()')

peripherals.removeMemoryObserver(framebufferHandle)
assert.equal(peripherals.countMemoryObservers(), 1)
peripherals.removeMemoryObserver(registerHandle)
assert.equal(peripherals.countMemoryObservers(), 0)

// Observers are shared by every instance, so a leftover registration would fire for the next
// program; removing them all is what a fresh build should do.
peripherals.removeMemoryObservers()

console.log(`ok - ran ${steps} instructions, printed "${output.join('')}"`)
console.log(`ok - peripherals: ${writes.length} observed writes, slept ${slept.join(',')}ms, clock ${clock}`)
