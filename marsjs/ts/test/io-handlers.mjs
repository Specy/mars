// The IO handler contract: print services reach printString as formatted text, reads hand over
// raw text that the syscalls parse, dialogs answer null for Cancel, file writes report their
// count, and bytes cross as plain arrays of numbers.
import assert from 'node:assert/strict'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr', 'randomSeed',
]

/** Runs a program with only `handlers` implemented; any other handler call fails the run. */
async function run(source, handlers) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        ...handlers,
    })
    mips.initialize(true)
    while (!mips.terminated) await mips.simulateWithLimit(10_000)
    return mips
}

const register = (mips, name) => mips.getRegisterValue(name)

// Every print service writes its formatted text through printString, one call per syscall.
{
    const printed = []
    await run(`
        .data
text:   .asciiz "two\\twords"
one:    .float 1.0
small:  .double 1e-5
        .text
main:   li   $a0, -42
        li   $v0, 1
        syscall
        li   $a0, 65
        li   $v0, 11
        syscall
        la   $a0, text
        li   $v0, 4
        syscall
        li   $a0, -1
        li   $v0, 34
        syscall
        li   $a0, 5
        li   $v0, 35
        syscall
        li   $a0, -1
        li   $v0, 36
        syscall
        l.s  $f12, one
        li   $v0, 2
        syscall
        l.d  $f12, small
        li   $v0, 3
        syscall
        li   $v0, 10
        syscall
`, { printString: text => printed.push(text) })
    assert.deepEqual(printed, ['-42', 'A', 'two\twords', '0xffffffff', '00000000000000000000000000000101',
        '4294967295', '1.0', '1.0E-5'])
}

// Read char takes the first character of the answer, so Enter alone reads as 10.
{
    const answers = ['\n', 'abc', 'é']
    const mips = await run(`
        .text
main:   li   $v0, 12
        syscall
        move $s0, $v0
        li   $v0, 12
        syscall
        move $s1, $v0
        li   $v0, 12
        syscall
        move $s2, $v0
        li   $v0, 10
        syscall
`, { readChar: (...args) => { assert.deepEqual(args, []); return answers.shift() } })
    assert.deepEqual([register(mips, '$s0'), register(mips, '$s1'), register(mips, '$s2')], [10, 97, 0xe9])
    // An empty answer is what MARS rejects: nothing was typed.
    await assert.rejects(() => run(`
        .text
main:   li   $v0, 12
        syscall
`, { readChar: () => '' }), /Runtime exception at 0x00400004: invalid char input \(syscall 12\)/)
    // A read handler must answer with text.
    await assert.rejects(() => run(`
        .text
main:   li   $v0, 5
        syscall
`, { readInt: () => 5 }), /Handler readInt did not return a string/)
}

// Dialogs: null is Cancel for the input dialogs (status -2), confirm forwards 2, the syscalls
// parse the text as MARS does, and the program waits for a message dialog to settle.
{
    const inputs = [null, ' 5', '-17', null, ' 0x1p3f ', null, null]
    const messages = []
    const settled = []
    const mips = await run(`
        .data
msg:    .asciiz "Value? "
buffer: .space 16
small:  .float 0.001
tiny:   .double 1e-5
        .text
main:   la   $a0, msg
        li   $v0, 51
        syscall
        move $s0, $a0
        move $s1, $a1
        la   $a0, msg
        li   $v0, 51
        syscall
        move $s2, $a1
        la   $a0, msg
        li   $v0, 51
        syscall
        move $s3, $a0
        move $s4, $a1
        la   $a0, msg
        li   $v0, 52
        syscall
        move $s5, $a1
        la   $a0, msg
        li   $v0, 52
        syscall
        mfc1 $s6, $f0
        move $s7, $a1
        la   $a0, msg
        li   $v0, 53
        syscall
        move $t0, $a1
        la   $a0, msg
        la   $a1, buffer
        li   $a2, 16
        li   $v0, 54
        syscall
        move $t1, $a1
        la   $a0, msg
        li   $v0, 50
        syscall
        move $t2, $a0
        la   $a0, msg
        l.s  $f12, small
        li   $v0, 57
        syscall
        la   $a0, msg
        l.d  $f12, tiny
        li   $v0, 58
        syscall
        li   $v0, 10
        syscall
`, {
        inputDialog: message => { assert.equal(message, 'Value? '); return inputs.shift() },
        confirm: () => 2,
        outputDialog: (message, type) => {
            assert.equal(settled.length, messages.length, 'the previous message dialog settled first')
            messages.push([message, type])
            return new Promise(resolve => setTimeout(() => { settled.push(message); resolve() }, 5))
        },
    })
    assert.equal(inputs.length, 0)
    assert.deepEqual([register(mips, '$s0'), register(mips, '$s1')], [0, -2], 'int dialog: Cancel')
    assert.equal(register(mips, '$s2'), -1, 'int dialog: Integer.parseInt does not trim')
    assert.deepEqual([register(mips, '$s3'), register(mips, '$s4')], [-17, 0], 'int dialog: a number')
    assert.equal(register(mips, '$s5'), -2, 'float dialog: Cancel')
    assert.deepEqual([register(mips, '$s6'), register(mips, '$s7')], [0x41000000, 0], 'float dialog: Java grammar')
    assert.equal(register(mips, '$t0'), -2, 'double dialog: Cancel')
    assert.equal(register(mips, '$t1'), -2, 'string dialog: Cancel')
    assert.equal(register(mips, '$t2'), 2, 'confirm: Cancel')
    assert.deepEqual(messages, [['Value? 0.001', 1], ['Value? 1.0E-5', 1]])
    assert.equal(settled.length, 2)
    // undefined is not an answer: only null cancels.
    await assert.rejects(() => run(`
        .data
msg:    .asciiz "?"
        .text
main:   la   $a0, msg
        li   $v0, 51
        syscall
`, { inputDialog: () => undefined }), /Handler inputDialog did not return a string or null/)
}

// Files and standard streams: bytes cross as plain arrays of numbers from 0 to 255, a write
// reports its count or -1, a read answers [count, bytes] with -1 for a failure.
{
    const calls = []
    const writes = [5, -1]
    const reads = [[3, [0, 128, 255]], [-1, []]]
    const mips = await run(`
        .data
name:   .asciiz "data.bin"
bytes:  .byte 104, 105, 0, 233, 255
buffer: .space 8
        .text
main:   la   $a0, name
        li   $a1, 1
        li   $a2, 0
        li   $v0, 13
        syscall
        move $s0, $v0
        move $a0, $s0
        la   $a1, bytes
        li   $a2, 5
        li   $v0, 15
        syscall
        move $s1, $v0
        move $a0, $s0
        la   $a1, bytes
        li   $a2, 5
        li   $v0, 15
        syscall
        move $s2, $v0
        move $a0, $s0
        la   $a1, buffer
        li   $a2, 8
        li   $v0, 14
        syscall
        move $s3, $v0
        move $a0, $s0
        la   $a1, buffer
        li   $a2, 8
        li   $v0, 14
        syscall
        move $s4, $v0
        li   $a0, 1
        la   $a1, bytes
        li   $a2, 2
        li   $v0, 15
        syscall
        move $s5, $v0
        li   $a0, 2
        la   $a1, bytes
        li   $a2, 5
        li   $v0, 15
        syscall
        move $a0, $s0
        li   $v0, 16
        syscall
        li   $v0, 10
        syscall
`, {
        openFile: (...args) => { calls.push(['openFile', ...args]); return 3 },
        writeFile: (...args) => { calls.push(['writeFile', ...args]); return writes.shift() },
        readFile: (...args) => { calls.push(['readFile', ...args]); return reads.shift() },
        stdOut: (...args) => { calls.push(['stdOut', ...args]) },
        stdErr: (...args) => { calls.push(['stdErr', ...args]) },
        closeFile: (...args) => { calls.push(['closeFile', ...args]) },
    })
    for (const [name, ...args] of calls) {
        for (const argument of args) {
            if (typeof argument === 'object') {
                assert.ok(Array.isArray(argument) && argument.every(byte => Number.isInteger(byte) && byte >= 0 && byte <= 255),
                    `${name} receives bytes as numbers from 0 to 255: ${JSON.stringify(argument)}`)
            }
        }
    }
    assert.deepEqual(calls, [
        ['openFile', 'data.bin', 1, false],
        ['writeFile', 3, [104, 105, 0, 233, 255]],
        ['writeFile', 3, [104, 105, 0, 233, 255]],
        ['readFile', 3, 8],
        ['readFile', 3, 8],
        ['stdOut', [104, 105]],
        ['stdErr', [104, 105, 0, 233, 255]],
        ['closeFile', 3],
    ])
    assert.equal(register(mips, '$s1'), 5, 'a write reports the count the handler answered')
    assert.equal(register(mips, '$s2'), -1, 'a failed write reports -1')
    assert.equal(register(mips, '$s3'), 3, 'a read reports its count')
    assert.deepEqual(Array.from(mips.readMemoryBytes(mips.getAddressOfLabel('buffer'), 3)), [0, 128, 255])
    assert.equal(register(mips, '$s4'), -1, 'a failed read reports -1, not the end of the file')
    assert.equal(register(mips, '$s5'), 2, 'standard output reports its count')
}

console.log('ok - IO handlers: print text, raw reads, read char, dialog Cancel, file counts and failures, bytes as numbers')
