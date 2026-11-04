// The written-blocks map (`trackWrites`, `takeWrittenBlocks`) and `readMemoryWords`: what a host
// mirroring a framebuffer reads once per update instead of being called on every store. Every way
// memory is written must mark it, since a store the map missed is a pixel the host never redraws.
import assert from 'node:assert/strict'
import { MIPS, StopReason, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const BASE = 0x10010000
const KB = 1024
/** 64 blocks from the static data base, the range most tests track. */
const END = BASE + 64 * KB

function core(lines, handlers = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(1000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        ...handlers,
    })
    mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}

const taken = (mips) => Array.from(mips.takeWrittenBlocks())

/** Steps the program to its end, returning the blocks each instruction wrote. */
async function stepAll(mips) {
    const steps = []
    while (!mips.terminated) {
        await mips.step()
        steps.push(taken(mips))
    }
    return steps
}

// Every store instruction, each into a block of its own, marks that block and nothing else.
const STORES = [
    'main:  li $t0, 0x10010000',
    '       li $t1, 0x11223344',
    '       mtc1 $t1, $f0',
    '       sw $t1, 0($t0)',          // block 0
    '       addiu $t2, $t0, 1024',
    '       sh $t1, 2($t2)',          // block 1
    '       addiu $t2, $t0, 2048',
    '       sb $t1, 1023($t2)',       // block 2, its last byte
    '       addiu $t2, $t0, 3072',
    '       sc $t1, 0($t2)',          // block 3
    '       addiu $t2, $t0, 4096',
    '       swl $t1, 1($t2)',         // block 4
    '       addiu $t2, $t0, 5120',
    '       swr $t1, 2($t2)',         // block 5
    '       addiu $t2, $t0, 6144',
    '       swc1 $f0, 0($t2)',        // block 6
    '       addiu $t2, $t0, 7168',
    '       sdc1 $f2, 1016($t2)',     // block 7, its last doubleword
    '       li $v0, 10',
    '       syscall',
]
const STORE_BLOCKS = [0, 1, 2, 3, 4, 5, 6, 7]
{
    const mips = core(STORES)
    mips.trackWrites(BASE, END)
    const steps = await stepAll(mips)
    assert.deepEqual(steps.filter(blocks => blocks.length > 0), STORE_BLOCKS.map(block => [block]),
        'each store marks the one block it wrote, and no other instruction marks any')
    assert.deepEqual(taken(mips), [], 'taking the map empties it')

    // Undo's restores go through the same stores: undoing the stores marks the same blocks
    const undone = []
    while (mips.getUndoDepth() > 0) {
        mips.undo()
        undone.push(...taken(mips))
    }
    assert.deepEqual(undone.sort((a, b) => a - b), STORE_BLOCKS, 'undo marks what it restored')
}

// A run marks every block it wrote, once, and they come out in ascending order.
{
    const mips = core(STORES)
    mips.trackWrites(BASE, END)
    assert.equal(await mips.simulateWithLimit(1000), StopReason.NORMAL_TERMINATION)
    assert.deepEqual(taken(mips), STORE_BLOCKS)
}

// A store straddling two blocks marks both. Blocks count from the tracked start, so a start two
// bytes past a word boundary puts a boundary inside the word every `sw` here writes.
{
    const start = BASE + 2
    const mips = core([
        'main:  li $t0, 0x10010000',
        '       li $t1, -1',
        '       sw $t1, 1024($t0)',       // bytes 1022 to 1025 of the range: blocks 0 and 1
        '       sw $t1, 0($t0)',          // two bytes before the range, two in it: block 0
        '       sw $t1, -4($t0)',         // wholly before the range: nothing
        '       li $t2, 0x10020000',      // the range's last two bytes and two past it
        '       sw $t1, 0($t2)',          // block 63
        '       sw $t1, 4($t2)',          // wholly past the range: nothing
        '       li $v0, 10',
        '       syscall',
    ])
    mips.trackWrites(start, start + 64 * KB)
    const steps = await stepAll(mips)
    assert.deepEqual(steps.filter(blocks => blocks.length > 0), [[0, 1], [0], [63]],
        'the straddling store marks both blocks, a partial one marks its part, the others nothing')
}

// A syscall writing memory marks what it wrote, a byte at a time, across a block boundary here.
{
    const mips = core([
        'main:  li $a0, 0x100123fe',      // two bytes before block 9
        '       li $a1, 16',
        '       li $v0, 8',
        '       syscall',
        '       li $v0, 10',
        '       syscall',
    ], { readString: () => 'hello' })
    mips.trackWrites(BASE, END)
    await mips.simulateWithLimit(1000)
    assert.deepEqual(taken(mips), [8, 9])
}

// Host writes: setMemoryBytes outside and inside a Poke, the Poke's Undo, and a peripheral's word.
{
    const mips = core(['main: li $v0, 10', '      syscall'])
    mips.trackWrites(BASE, END)
    mips.setMemoryBytes(BASE + 10 * KB, [1, 2, 3])
    assert.deepEqual(taken(mips), [10], 'a host write')
    mips.beginPoke()
    mips.setMemoryBytes(BASE + 11 * KB + 1023, [5, 6])
    assert.equal(mips.endPoke(), true)
    assert.deepEqual(taken(mips), [11, 12], 'a Poke, straddling two blocks a byte each')
    mips.undo()
    assert.deepEqual(taken(mips), [11, 12], 'the Poke undone')
    mips.setPeripheralWord(BASE + 13 * KB, 7)
    assert.deepEqual(taken(mips), [13], "a peripheral's word, which no observer hears")
    mips.setPeripheralWord(BASE + 64 * KB, 7)
    assert.deepEqual(taken(mips), [], 'just past the range')
}

// The assembler's data, a range at the top of memory and its two address forms, and the map
// clearing: on taking, on a new range, when tracking stops, and at assembly, which keeps the range.
{
    const mips = core(['main: li $v0, 10', '      syscall'])
    mips.trackWrites(0xffff0000, 0)
    mips.setPeripheralWord(0xffff0100, 1)
    mips.setPeripheralWord(0xfffffffc | 0, 1)
    assert.deepEqual(taken(mips), [0, 63], 'a range ending at the top of memory, end 0')

    mips.trackWrites(0xffff0000 | 0, 0)
    mips.setPeripheralWord(0xffff0400, 1)
    mips.trackWrites(0xffff0000, 0)
    assert.deepEqual(taken(mips), [], 'a new range forgets what was written')

    mips.trackWrites(BASE, END)
    mips.setMemoryBytes(BASE, [1])
    mips.trackWrites(0, 0)
    mips.setMemoryBytes(BASE, [2])
    assert.deepEqual(taken(mips), [], 'stopping forgets, and nothing is noted while stopped')

    mips.trackWrites(BASE, END)
    mips.setMemoryBytes(BASE + 5 * KB, [1])
    const next = MIPS.makeMipsFromFiles({ 'main.asm': [
        '.data',
        '.space 15360',
        '.word 1',                    // block 15
        '.text',
        'main:  li $t0, 0x10010000',
        '       sw $t0, 0($t0)',
        '       li $v0, 10',
        '       syscall',
    ].join('\n') }, 'main.asm')
    assert.equal(next.assemble().hasErrors, false)
    assert.deepEqual(taken(next), [15], "assembling forgets what was written before, and marks the program's data")
    registerHandlers(next, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    next.initialize(true)
    await next.simulateWithLimit(100)
    assert.deepEqual(taken(next), [0], 'the range survives assembly, as observers do')

    assert.throws(() => mips.trackWrites(BASE + 8, BASE), 'a range ending before it starts')
}

// readMemoryWords reads what a load reads, the way readMemoryBytes reads bytes.
{
    const mips = core(STORES)
    await mips.simulateWithLimit(1000)
    const words = mips.readMemoryWords(BASE, 1)
    assert.ok(words instanceof Int32Array)
    assert.deepEqual(Array.from(words), [0x11223344])

    const littleEndian = (bytes) => Array.from({ length: bytes.length / 4 }, (_, i) =>
        (bytes[4 * i] & 0xff) | ((bytes[4 * i + 1] & 0xff) << 8) | ((bytes[4 * i + 2] & 0xff) << 16) | (bytes[4 * i + 3] << 24))
    const same = (address, count, message) => assert.deepEqual(
        Array.from(mips.readMemoryWords(address, count)), littleEndian(mips.readMemoryBytes(address | 0, count * 4)), message)
    same(BASE, 3000, 'the data segment, across blocks of its table, some never written')
    same(BASE + 0x30000, 4, 'memory never written reads as zeros')
    same(0x7fffeff0, 8, 'the stack')
    same(0x00400000, 6, 'the text segment, as the encodings of its statements')
    same(0x10000000, 16, 'below the static data base')
    assert.deepEqual(Array.from(mips.readMemoryWords(BASE + 8 * KB - 4, 0)), [], 'no words')

    mips.setPeripheralWord(0xffff0000, 0x12345678)
    assert.deepEqual(Array.from(mips.readMemoryWords(0xffff0000, 1)), [0x12345678], 'a high address')
    assert.deepEqual(Array.from(mips.readMemoryWords(0xffff0000 | 0, 1)), [0x12345678], 'and its signed form')

    assert.throws(() => mips.readMemoryWords(BASE + 2, 1), 'an address off a word boundary')
    assert.throws(() => mips.readMemoryWords(0, 1), 'an address outside memory')
}

console.log('written-blocks: ok')
