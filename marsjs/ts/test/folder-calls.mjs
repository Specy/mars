// The folder calls, services 1026 to 1065, numbered as Linux's asm-generic table numbers them: each
// reaches its handler with the program's arguments, and the handler's answer, minus a Linux error
// number included, lands in $v0 unchanged. stat and getdents store the bytes the handler lays out,
// and Undo takes those bytes and $v0 back as it does a read's.
import assert from 'node:assert/strict'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]

/** Assembles and starts a program with only `handlers` implemented. */
function load(source, handlers, undo = false) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    if (undo) mips.setUndoSize(100)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        ...handlers,
    })
    if (undo) mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}

async function run(source, handlers) {
    const mips = load(source, handlers)
    while (!mips.terminated) await mips.simulateWithLimit(10_000)
    return mips
}

const register = (mips, name) => mips.getRegisterValue(name)
const label = (mips, name) => mips.getAddressOfLabel(name)
const memory = (mips, address, length) => Array.from(mips.readMemoryBytes(address, length))

// Each number reaches its handler with its paths, and $v0 gets the answer as it is.
{
    const calls = []
    const answers = { unlink: -21, mkdir: -17, rmdir: -39, rename: 0 }
    const mips = await run(`
        .data
one:    .asciiz "logs/a.txt"
two:    .asciiz "dé/b"
        .text
main:   la   $a0, one
        li   $v0, 1026
        syscall
        move $s0, $v0
        la   $a0, two
        li   $v0, 1030
        syscall
        move $s1, $v0
        la   $a0, one
        li   $v0, 1031
        syscall
        move $s2, $v0
        la   $a0, one
        la   $a1, two
        li   $v0, 1034
        syscall
        move $s3, $v0
        li   $v0, 10
        syscall
`, Object.fromEntries(Object.entries(answers).map(([name, answer]) =>
        [name, (...args) => { calls.push([name, ...args]); return answer }])))
    assert.deepEqual(calls, [
        ['unlink', 'logs/a.txt'],
        ['mkdir', 'dé/b'],
        ['rmdir', 'logs/a.txt'],
        ['rename', 'logs/a.txt', 'dé/b'],
    ])
    assert.deepEqual([register(mips, '$s0'), register(mips, '$s1'), register(mips, '$s2'), register(mips, '$s3')],
        [-21, -17, -39, 0])
}

// stat stores the handler's bytes at the buffer and answers 0; a failure stores nothing.
{
    const structure = Array.from({ length: 104 }, (_, index) => (index * 7 + 1) & 0xff)
    const asked = []
    const mips = await run(`
        .data
found:  .asciiz "here"
gone:   .asciiz "gone"
        .align 2
buffer: .space 128
other:  .space 128
        .text
main:   la   $a0, found
        la   $a1, buffer
        li   $v0, 1038
        syscall
        move $s0, $v0
        la   $a0, gone
        la   $a1, other
        li   $v0, 1038
        syscall
        move $s1, $v0
        li   $v0, 10
        syscall
`, {
        stat: path => {
            asked.push(path)
            return path === 'here' ? [0, structure] : [-2, []]
        },
    })
    assert.deepEqual(asked, ['here', 'gone'])
    assert.equal(register(mips, '$s0'), 0)
    assert.equal(register(mips, '$s1'), -2)
    assert.deepEqual(memory(mips, label(mips, 'buffer'), 104), structure)
    assert.deepEqual(memory(mips, label(mips, 'buffer') + 104, 4), [0, 0, 0, 0])
    assert.deepEqual(memory(mips, label(mips, 'other'), 128), new Array(128).fill(0))
}

// getdents asks for the buffer's size, at most 65536, stores the count of bytes it answers and no
// more, and passes a negative answer through; a negative size is -22 without asking.
{
    const asked = []
    const records = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
    const answers = [[8, records], [0, []], [-20, [1, 2, 3]], [3, records]]
    const mips = await run(`
        .data
        .align 2
buffer: .space 64
        .text
main:   li   $a0, 3
        la   $a1, buffer
        li   $a2, 64
        li   $v0, 1065
        syscall
        move $s0, $v0
        li   $a0, 3
        la   $a1, buffer
        li   $a2, 64
        li   $v0, 1065
        syscall
        move $s1, $v0
        li   $a0, 4
        la   $a1, buffer
        li   $a2, 64
        li   $v0, 1065
        syscall
        move $s2, $v0
        li   $a0, 3
        la   $a1, buffer
        li   $a2, -1
        li   $v0, 1065
        syscall
        move $s3, $v0
        li   $a0, 3
        la   $a1, buffer
        li   $a2, 0x7fffffff
        li   $v0, 1065
        syscall
        move $s4, $v0
        li   $v0, 10
        syscall
`, {
        getdents: (fd, length) => {
            asked.push([fd, length])
            return answers.shift()
        },
    })
    assert.deepEqual(asked, [[3, 64], [3, 64], [4, 64], [3, 65536]])
    assert.deepEqual([register(mips, '$s0'), register(mips, '$s1'), register(mips, '$s2'),
        register(mips, '$s3'), register(mips, '$s4')], [8, 0, -20, -22, 3])
    // the last answer stored its three bytes over the first answer's eight
    assert.deepEqual(memory(mips, label(mips, 'buffer'), 10), [1, 2, 3, 4, 5, 6, 7, 8, 0, 0])
}

// A handler whose promise rejects reaches the program as -5, Linux's EIO, for every folder call.
{
    const reject = () => Promise.reject(new Error('host down'))
    const mips = await run(`
        .data
path:   .asciiz "x"
        .align 2
buffer: .space 32
        .text
main:   la   $a0, path
        li   $v0, 1026
        syscall
        move $s0, $v0
        la   $a0, path
        li   $v0, 1030
        syscall
        move $s1, $v0
        la   $a0, path
        li   $v0, 1031
        syscall
        move $s2, $v0
        la   $a0, path
        la   $a1, path
        li   $v0, 1034
        syscall
        move $s3, $v0
        la   $a0, path
        la   $a1, buffer
        li   $v0, 1038
        syscall
        move $s4, $v0
        li   $a0, 3
        la   $a1, buffer
        li   $a2, 32
        li   $v0, 1065
        syscall
        move $s5, $v0
        li   $v0, 10
        syscall
`, { unlink: reject, mkdir: reject, rmdir: reject, rename: reject, stat: reject, getdents: reject })
    assert.deepEqual(['$s0', '$s1', '$s2', '$s3', '$s4', '$s5'].map(name => register(mips, name)),
        [-5, -5, -5, -5, -5, -5])
}

// A handler answering the wrong shape fails the run, as any broken handler does.
{
    const mips = load(`
        .data
path:   .asciiz "x"
        .align 2
buffer: .space 32
        .text
main:   la   $a0, path
        la   $a1, buffer
        li   $v0, 1038
        syscall
        li   $v0, 10
        syscall
`, { stat: () => 0 })
    await assert.rejects(mips.simulateWithLimit(100), error => error.kind === 'handler'
        && /stat must return a tuple/.test(error.message))
}

// Undo of stat and getdents puts the buffer and $v0 back as they were.
{
    const mips = load(`
        .data
path:   .asciiz "here"
        .align 2
buffer: .space 32
        .text
main:   li   $v0, 77
        la   $a0, path
        la   $a1, buffer
        li   $v0, 1038
        syscall
        li   $v0, 77
        li   $a0, 3
        la   $a1, buffer
        li   $a2, 32
        li   $v0, 1065
        syscall
        li   $v0, 10
        syscall
`, {
        stat: () => [0, [9, 9, 9, 9, 9, 9]],
        getdents: () => [4, [5, 5, 5, 5]],
    }, true)
    const buffer = label(mips, 'buffer')
    const stepTo = async service => {
        while (!(mips.getNextStatement().source.includes('syscall') && register(mips, '$v0') === service))
            await mips.step()
    }
    await stepTo(1038)
    await mips.step()
    assert.equal(register(mips, '$v0'), 0)
    assert.deepEqual(memory(mips, buffer, 6), [9, 9, 9, 9, 9, 9])
    mips.undo()
    assert.equal(register(mips, '$v0'), 1038)
    assert.deepEqual(memory(mips, buffer, 6), [0, 0, 0, 0, 0, 0])
    await mips.step()
    await stepTo(1065)
    await mips.step()
    assert.equal(register(mips, '$v0'), 4)
    assert.deepEqual(memory(mips, buffer, 6), [5, 5, 5, 5, 9, 9])
    mips.undo()
    assert.equal(register(mips, '$v0'), 1065)
    assert.deepEqual(memory(mips, buffer, 6), [9, 9, 9, 9, 9, 9])
}

console.log('ok - MIPS: the folder calls reach their handlers and store what they answer')
