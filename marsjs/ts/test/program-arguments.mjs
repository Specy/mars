// Program arguments: setProgramArguments gives every later initialize an argv, argv[0] included,
// stored above the stack with $a0 = argc and $a1 = argv, while $sp keeps its initial value; words
// that do not fit are refused with a sentence for the person; Undo to the start keeps them.
import assert from 'node:assert/strict'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr', 'randomSeed',
]
const STACK_POINTER = 0x7fffeffc
const STACK_BASE = 0x7ffffffc

// Prints argc, then each argv string on a line of its own, walking argv to its NULL.
const PRINT_ARGV = `
main:   move $s0, $a0
        move $s1, $a1
        li   $v0, 1
        syscall
next:   lw   $t0, 0($s1)
        beqz $t0, done
        li   $a0, 10
        li   $v0, 11
        syscall
        move $a0, $t0
        li   $v0, 4
        syscall
        addi $s1, $s1, 4
        j    next
done:   li   $v0, 10
        syscall`

/** An assembled core whose prints go to `printed`, with undo on. */
function core(source, printed = []) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    mips.setUndoSize(1000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        printString: text => { printed.push(text) },
    })
    return mips
}

async function runToEnd(mips) {
    while (!mips.terminated) await mips.simulateWithLimit(10_000)
}

const u32 = value => value >>> 0
const word = (mips, address) => u32(new DataView(Uint8Array.from(mips.readMemoryBytes(address, 4)).buffer).getUint32(0, true))
function cString(mips, address) {
    const bytes = []
    for (let at = address; ; at++) {
        const [byte] = mips.readMemoryBytes(at, 1)
        if (byte === 0) return new TextDecoder().decode(Uint8Array.from(bytes))
        bytes.push(byte)
    }
}

// 1. argc and argv: the program reads them from $a0 and $a1 and prints every string; $sp is where
//    it always starts.
{
    const printed = []
    const mips = core(PRINT_ARGV, printed)
    mips.setProgramArguments(['./main', 'hello', 'two words', 'héllo'])
    mips.initialize(true)
    assert.equal(mips.getRegisterValue('$a0'), 4, 'argc counts argv[0]')
    assert.equal(u32(mips.stackPointer), STACK_POINTER, '$sp keeps its initial value')
    await runToEnd(mips)
    assert.equal(printed.join(''), '4\n./main\nhello\ntwo words\nhéllo')
}

// 2. The layout: strings end at the stack base, argv[0] first; the word-aligned, NULL-terminated
//    argv array sits right below them; nothing is below $sp + 16.
{
    const mips = core('main: nop')
    mips.setProgramArguments(['./main', 'hello', 'two words'])
    mips.initialize(true)
    const argv = u32(mips.getRegisterValue('$a1'))
    // 7 + 6 + 10 bytes of strings from 0x7fffffe5; 4 pointers below them, aligned down to 4
    assert.equal(argv, 0x7fffffd4)
    const pointers = [0, 1, 2, 3].map(i => word(mips, argv + 4 * i))
    assert.deepEqual(pointers, [0x7fffffe5, 0x7fffffec, 0x7ffffff2, 0])
    assert.deepEqual(pointers.slice(0, 3).map(pointer => cString(mips, pointer)), ['./main', 'hello', 'two words'])
    assert.deepEqual(Array.from(mips.readMemoryBytes(0x7fffffe4, 1)), [0], 'the padding is zero')
    assert.deepEqual(Array.from(mips.readMemoryBytes(STACK_BASE - 1, 1)), [0], 'the last string ends at the stack base')
    assert.deepEqual(Array.from(mips.readMemoryBytes(STACK_POINTER, 16)), new Array(16).fill(0),
        'the 16 bytes above $sp are left alone')
}

// 3. Only argv[0]: argc is 1 and argv[1] is NULL.
{
    const mips = core('main: nop')
    mips.setProgramArguments(['./main'])
    mips.initialize(true)
    assert.equal(mips.getRegisterValue('$a0'), 1)
    const argv = u32(mips.getRegisterValue('$a1'))
    assert.equal(cString(mips, word(mips, argv)), './main')
    assert.equal(word(mips, argv + 4), 0)
}

// 4. Never set, or set to none: nothing is stored and $a0, $a1 start at 0, as before.
{
    for (const argv of [undefined, []]) {
        const mips = core('main: nop')
        if (argv) mips.setProgramArguments(argv)
        mips.initialize(true)
        assert.deepEqual([mips.getRegisterValue('$a0'), mips.getRegisterValue('$a1')], [0, 0])
        assert.deepEqual(Array.from(mips.readMemoryBytes(STACK_BASE - 16, 16)), new Array(16).fill(0))
    }
    const cleared = core('main: nop')
    cleared.setProgramArguments(['./main', 'x'])
    cleared.setProgramArguments([])
    cleared.initialize(true)
    assert.equal(cleared.getRegisterValue('$a0'), 0, 'an empty argv stores none again')
}

// 5. Refusals, with a sentence for the person; the argv set before stays.
{
    assert.equal(MIPS.programArgumentsSpace(), 4080, 'from $sp + 16 to the stack base')
    const mips = core(PRINT_ARGV)
    mips.setProgramArguments(['./main', 'kept'])
    // 4,068 bytes of strings, two pointers and the NULL: exactly the 4,080 bytes there are
    const fits = ['./main', 'x'.repeat(4080 - 12 - 7 - 1)]
    MIPS.checkProgramArguments(fits)
    const tooLong = ['./main', 'x'.repeat(4080 - 12 - 7)]
    assert.throws(() => mips.setProgramArguments(tooLong),
        { message: 'Arguments take 4,084 bytes with the program name and argv; MIPS programs have 4,080 bytes for them' })
    assert.throws(() => MIPS.checkProgramArguments(tooLong), /MIPS programs have 4,080 bytes for them/)
    assert.throws(() => mips.setProgramArguments(['./main', ...new Array(1025).fill('')]),
        { message: 'Arguments may contain at most 1,024 words' })
    assert.throws(() => mips.setProgramArguments(['./main', 'a\0b']), { message: 'Arguments cannot contain NUL' })
    assert.throws(() => mips.setProgramArguments(['./main', 1]), { name: 'TypeError', message: 'Arguments must be strings' })
    assert.throws(() => mips.setProgramArguments(null), TypeError)
    assert.throws(() => MIPS.checkProgramArguments('./main'), TypeError)
    mips.initialize(true)
    assert.equal(mips.getRegisterValue('$a0'), 2, 'a refused argv leaves the one set before')
    assert.equal(cString(mips, word(mips, u32(mips.getRegisterValue('$a1')) + 4)), 'kept')

    mips.setProgramArguments(fits)
    mips.initialize(true)
    assert.equal(u32(mips.getRegisterValue('$a1')), STACK_POINTER + 16, 'the largest argv starts at $sp + 16')
    assert.equal(cString(mips, word(mips, u32(mips.getRegisterValue('$a1')) + 4)).length, 4060)
}

// 6. Every initialize stores them again: over a program that wrote on them, and with new words.
{
    const mips = core(`
main:   lw   $t0, 4($a1)
        sb   $zero, 0($t0)
        sw   $zero, 0($a1)
        li   $a0, 99
        li   $a1, 98`)
    mips.setProgramArguments(['./main', 'abc'])
    mips.initialize(true)
    const argv = u32(mips.getRegisterValue('$a1'))
    await runToEnd(mips)
    assert.equal(word(mips, argv), 0, 'the program overwrote argv[0]')
    assert.equal(cString(mips, word(mips, argv + 4)), '', 'and argv[1]')
    mips.initialize(true)
    assert.deepEqual([mips.getRegisterValue('$a0'), u32(mips.getRegisterValue('$a1'))], [2, argv])
    assert.equal(cString(mips, word(mips, argv)), './main')
    assert.equal(cString(mips, word(mips, argv + 4)), 'abc')

    mips.setProgramArguments(['./other', 'one', 'two'])
    assert.equal(mips.getRegisterValue('$a0'), 2, 'a new argv waits for the next start')
    mips.initialize(true)
    assert.equal(mips.getRegisterValue('$a0'), 3)
    const next = u32(mips.getRegisterValue('$a1'))
    assert.deepEqual([0, 1, 2].map(i => cString(mips, word(mips, next + 4 * i))), ['./other', 'one', 'two'])
    assert.equal(u32(mips.stackPointer), STACK_POINTER)
}

// 7. Undo back to the start keeps them: they are no step of the history.
{
    const mips = core(`
main:   lw   $t0, 4($a1)
        sb   $zero, 0($t0)
        li   $a0, 99
        li   $a1, 98
        addi $sp, $sp, -8`)
    mips.setProgramArguments(['./main', 'abc'])
    mips.initialize(true)
    const argv = u32(mips.getRegisterValue('$a1'))
    assert.equal(mips.canUndo, false, 'storing them is no step')
    await runToEnd(mips)
    assert.equal(mips.getRegisterValue('$a0'), 99)
    while (mips.canUndo) mips.undo()
    assert.deepEqual([mips.getRegisterValue('$a0'), u32(mips.getRegisterValue('$a1')), u32(mips.stackPointer)],
        [2, argv, STACK_POINTER])
    assert.equal(cString(mips, word(mips, argv + 4)), 'abc')
}
