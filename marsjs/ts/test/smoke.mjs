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

// Every handler must be registered; the ones this program cannot reach throw
// so an unexpected syscall fails the test instead of silently doing nothing.
const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'askDouble', 'askFloat', 'askInt', 'askString', 'readDouble',
    'readFloat', 'readInt', 'readString', 'readChar', 'logLine', 'log', 'printChar',
    'printDouble', 'printFloat', 'printInt', 'printString', 'sleep', 'stdIn', 'stdOut', 'stdErr',
]

const output = []
const mips = MIPS.makeMipsFromSource(SOURCE)

registerHandlers(mips, {
    ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
    printInt: value => output.push(String(value)),
    printString: value => output.push(value),
    printChar: value => output.push(value),
})

const assembled = mips.assemble()
assert.equal(assembled.hasErrors, false, `assembly failed: ${JSON.stringify(assembled.errors)}`)

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

console.log(`ok - ran ${steps} instructions, printed "${output.join('')}"`)
