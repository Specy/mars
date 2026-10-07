// Numbers as MARS prints and reads them on Java 21: every golden vector that
// fixtures/java-numbers/GoldenVectors.java wrote on JDK 21 goes through print float and double
// (2, 3) and read int, float and double (5, 6, 7), and must match.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { MIPS } from '../dist/index.mjs'

const vectors = JSON.parse(await readFile(new URL('./fixtures/java-numbers/vectors.json', import.meta.url), 'utf8'))
assert.match(vectors.java, /^21\./, 'the vectors come from Java 21')

// The IO handlers are shared by every core, so one set serves the whole file.
let printed = ''
let lines = []
const handlers = {
    printString: text => { printed += text },
    readInt: () => lines.shift(),
    readFloat: () => lines.shift(),
    readDouble: () => lines.shift(),
}

function core(source) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    for (const [name, handler] of Object.entries(handlers)) mips.registerHandler(name, handler)
    return mips
}

async function finish(mips) {
    while (!mips.terminated) await mips.simulateWithLimit(1_000_000)
}

/** Little-endian bytes of 32 bit words. */
function wordBytes(words) {
    return words.flatMap(word => [word & 0xff, (word >>> 8) & 0xff, (word >>> 16) & 0xff, (word >>> 24) & 0xff])
}

/** The hexadecimal words, low word first for a double, as ldc1 and sdc1 order $f0 and $f1. */
function wordsOf(hex) {
    return hex.length === 8 ? [parseInt(hex, 16)] : [parseInt(hex.slice(8), 16), parseInt(hex.slice(0, 8), 16)]
}

function hexOf(bytes, offset, size) {
    const word = index => (bytes[offset + index] | (bytes[offset + index + 1] << 8) | (bytes[offset + index + 2] << 16) | (bytes[offset + index + 3] << 24)) >>> 0
    const hex = word => word.toString(16).padStart(8, '0')
    return size === 4 ? hex(word(0)) : hex(word(4)) + hex(word(0))
}

// Printing: a loop prints the $s1 values stored at `values`, one per line.
async function checkPrinting(name, cases, load, service, size) {
    const mips = core(`
        .data
        .align 3
values: .space ${cases.length * size}
        .text
main:   la    $s0, values
loop:   beqz  $s1, done
        ${load} $f12, 0($s0)
        li    $v0, ${service}
        syscall
        li    $a0, 10
        li    $v0, 11
        syscall
        addiu $s0, $s0, ${size}
        addiu $s1, $s1, -1
        j     loop
done:   li    $v0, 10
        syscall
`)
    mips.initialize(true)
    mips.setMemoryBytes(mips.getAddressOfLabel('values'), wordBytes(cases.flatMap(([bits]) => wordsOf(bits))))
    mips.setRegisterValue('$s1', cases.length)
    printed = ''
    await finish(mips)
    const actual = printed.split('\n')
    assert.equal(actual.length, cases.length + 1)
    cases.forEach(([bits, expected], index) => assert.equal(actual[index], expected, `${name} of ${bits}`))
}

await checkPrinting('print float', vectors.floatToString, 'lwc1', 2, 4)
await checkPrinting('print double', vectors.doubleToString, 'ldc1', 3, 8)

// Reading: a loop reads $s1 lines and stores each value's bits at `results`. A line the reference
// rejects stops the program at the syscall, so each of those runs on its own.
const SERVICE = { parseInt: [5, 'sw $v0', 4, 'integer'], parseFloat: [6, 'swc1 $f0', 4, 'float'], parseDouble: [7, 'sdc1 $f0', 8, 'double'] }

async function checkReading(name) {
    const [service, store, size, kind] = SERVICE[name]
    const valid = vectors[name].filter(([, expected]) => expected !== null)
    const invalid = vectors[name].filter(([, expected]) => expected === null)
    const mips = core(`
        .data
        .align 3
results: .space ${valid.length * size}
        .text
main:   la    $s0, results
loop:   beqz  $s1, done
        li    $v0, ${service}
        syscall
        ${store}, 0($s0)
        addiu $s0, $s0, ${size}
        addiu $s1, $s1, -1
        j     loop
done:   li    $v0, 10
        syscall
`)
    mips.initialize(true)
    mips.setRegisterValue('$s1', valid.length)
    lines = valid.map(([line]) => line)
    await finish(mips)
    assert.equal(lines.length, 0, `${name}: every line was read`)
    const bytes = Array.from(mips.readMemoryBytes(mips.getAddressOfLabel('results'), valid.length * size))
    valid.forEach(([line, expected], index) => {
        const actual = hexOf(bytes, index * size, size)
        const wanted = typeof expected === 'number' ? (expected >>> 0).toString(16).padStart(8, '0') : expected
        assert.equal(actual, wanted, `${name} of ${JSON.stringify(line)}`)
    })

    const rejecting = core(`
        .text
main:   li    $v0, ${service}
        syscall
        li    $v0, 10
        syscall
`)
    const message = `Runtime exception at 0x00400004: invalid ${kind} input (syscall ${service})`
    for (const [line] of invalid) {
        rejecting.initialize(true)
        lines = [line]
        await assert.rejects(() => finish(rejecting), error => {
            assert.ok(String(error.message).includes(message), `${name} of ${JSON.stringify(line)}: ${error.message}`)
            return true
        })
    }
    return [valid.length, invalid.length]
}

const counts = {}
for (const name of ['parseInt', 'parseFloat', 'parseDouble']) counts[name] = await checkReading(name)

console.log(`ok - Java 21 number text: ${vectors.floatToString.length} floats and ${vectors.doubleToString.length} doubles printed, ` +
    Object.entries(counts).map(([name, [valid, invalid]]) => `${name} ${valid} read and ${invalid} rejected`).join(', '))
