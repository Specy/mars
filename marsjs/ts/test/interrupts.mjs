// Interrupts: the device lines a host raises (Cause bits 8 and 9), gated by Status as MIPS32 gates
// them, and the timer, MIPS32's Count ($9) and Compare ($11) raising IP7. Entry is a step of its
// own at the interrupted instruction, so a Step lands on the handler's first instruction at
// 0x80000180, a breakpoint there stops a run, and one Undo returns to the instruction it
// interrupted with EPC, Cause and Status put back. Also the GNU profile's .ktext and .kdata.
import assert from 'node:assert/strict'
import { MIPS, MIPS_COPROCESSOR0_REGISTER_NUMBERS, StopReason, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const HANDLER = 0x80000180 | 0
const KEYBOARD = 0x100, DISPLAY = 0x200, TIMER = 0x8000

function core(lines, options = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm', options)
    mips.setUndoSize(1000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report ?? result.errors?.map(e => e.message).join('\n'))
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}

const cp0 = (mips, number) => mips.getCoprocessor0Values()[MIPS_COPROCESSOR0_REGISTER_NUMBERS.indexOf(number)]
const taken = (mips) => Array.from(mips.getInterruptTaken())
const label = (mips, name) => mips.getAddressOfLabel(name)
const reg = (mips, name) => mips.getRegisterValue(name)

async function stepTo(mips, address, limit = 100) {
    for (let i = 0; i < limit && mips.programCounter !== address; i++) await mips.step()
    assert.equal(mips.programCounter, address)
}

assert.deepEqual([...MIPS_COPROCESSOR0_REGISTER_NUMBERS], [8, 9, 11, 12, 13, 14])

// The keyboard lab's shape: a handler at 0x80000180 counting interrupts in $s1, and a loop.
const DEVICE_PROGRAM = [
    '        .text',
    'main:   addi $s0, $s0, 1',
    '        j main',
    '        .ktext 0x80000180',
    'handler: addi $s1, $s1, 1',
    '        eret',
]

// 1. A raised line is taken before the next instruction, a step of its own: the Step lands on the
// handler, EPC is the instruction that did not run, Cause has code 0 and the pending bit, Status
// has EXL; one Undo puts all of it back.
{
    const mips = core(DEVICE_PROGRAM)
    await mips.step() // addi
    await mips.step() // j
    assert.equal(mips.programCounter, label(mips, 'main'))
    const before = { status: cp0(mips, 12), cause: cp0(mips, 13), epc: cp0(mips, 14) }
    mips.raiseExternalInterrupt(0x40)
    assert.equal(cp0(mips, 13) & 0xff00, KEYBOARD, 'Cause shows the raised line')

    assert.equal(await mips.step(), StopReason.MAX_STEPS)
    assert.equal(mips.getLastRunInstructionCount(), 1, 'the entry counts as an instruction')
    assert.equal(mips.programCounter, HANDLER, 'the Step lands on the handler')
    assert.deepEqual(taken(mips), [label(mips, 'main'), KEYBOARD, HANDLER])
    assert.equal(cp0(mips, 14), label(mips, 'main'), 'EPC is the interrupted instruction')
    assert.equal(cp0(mips, 13) & 0x7c, 0, 'exception code 0, an interrupt')
    assert.equal(cp0(mips, 12) & 2, 2, 'EXL set')
    assert.equal(reg(mips, '$s0'), 1, 'the interrupted addi has not run')

    mips.undo()
    assert.equal(mips.programCounter, label(mips, 'main'))
    assert.equal(cp0(mips, 12), before.status)
    assert.equal(cp0(mips, 14), before.epc)
    assert.equal(cp0(mips, 13) & ~0xff00, before.cause & ~0xff00)
    assert.equal(reg(mips, '$s0'), 1)

    // still raised: taken again; then level-triggered after eret until cleared
    await mips.step()
    assert.equal(mips.programCounter, HANDLER)
    await mips.step() // addi $s1
    await mips.step() // eret
    assert.equal(mips.programCounter, label(mips, 'main'))
    await mips.step()
    assert.equal(mips.programCounter, HANDLER, 'a pending line is taken again after eret')
    mips.clearExternalInterrupt(0x40)
    await mips.step()
    await mips.step()
    await mips.step()
    assert.equal(reg(mips, '$s0'), 2, 'cleared: the program runs on')
    assert.equal(reg(mips, '$s1'), 2)
}

// 2. Gating: masked in Status's IM, or interrupts disabled, or EXL set, the line waits pending.
{
    const mips = core([
        '        .text',
        'main:   mfc0 $t0, $12',
        '        andi $t0, $t0, 0xfeff',   // mask IP0, the keyboard
        '        mtc0 $t0, $12',
        'spin:   j spin',
        '        .ktext 0x80000180',
        '        eret',
    ])
    await mips.simulateWithLimit(3)
    mips.raiseExternalInterrupt(0x40)
    assert.equal(await mips.simulateWithLimit(50), StopReason.MAX_STEPS)
    assert.deepEqual(taken(mips), [], 'masked: never taken')
    mips.raiseExternalInterrupt(0x80)
    await mips.step()
    assert.deepEqual(taken(mips), [label(mips, 'spin'), DISPLAY, HANDLER], 'the display is not masked')
    // inside the handler EXL holds the still pending line back: the step runs eret
    await mips.step()
    assert.deepEqual(taken(mips), [], 'EXL set: not taken inside the handler')
    assert.equal(mips.programCounter, label(mips, 'spin'))
}
{
    const mips = core([
        '        .text',
        'main:   li $t0, 0xff10',          // every level unmasked, but interrupts disabled
        '        mtc0 $t0, $12',
        'spin:   j spin',
        '        .ktext 0x80000180',
        '        eret',
    ])
    await mips.step()
    await mips.step()
    mips.raiseExternalInterrupt(0x40)
    assert.equal(await mips.simulateWithLimit(20), StopReason.MAX_STEPS)
    assert.deepEqual(taken(mips), [], 'IE clear: never taken')
}

// 3. Two lines pending together are one entry, both bits in Cause; a breakpoint on the handler's
// first instruction stops a run.
{
    const mips = core(DEVICE_PROGRAM)
    await mips.simulateWithLimit(10)
    mips.raiseExternalInterrupt(0x40)
    mips.raiseExternalInterrupt(0x80)
    assert.equal(await mips.simulateWithBreakpointsAndLimit([HANDLER], 100), StopReason.BREAKPOINT)
    assert.equal(mips.programCounter, HANDLER)
    assert.equal(cp0(mips, 13) & 0xff00, KEYBOARD | DISPLAY)
}

// 4. No handler: an interrupt stops the program with an error saying so.
{
    const mips = core(['main: j main'])
    mips.raiseExternalInterrupt(0x40)
    await assert.rejects(mips.step(), /no handler at 0x80000180/)
}

// 5. Count goes up once every two instructions, from 0 at initialize, and Undo rewinds it; mtc0
// sets what it reads; Pokes too.
{
    const mips = core([
        'main:   nop',
        '        nop',
        '        nop',
        '        nop',
        '        mfc0 $t0, $9',
        '        li $t1, 1000',
        '        mtc0 $t1, $9',
        '        mfc0 $t2, $9',
        '        nop',
        '        nop',
        '        mfc0 $t3, $9',
    ])
    assert.equal(cp0(mips, 9), 0)
    await mips.simulateWithLimit(3)
    assert.equal(cp0(mips, 9), 1, 'three instructions: Count 1')
    await mips.step()
    assert.equal(cp0(mips, 9), 2)
    mips.undo()
    mips.undo()
    assert.equal(cp0(mips, 9), 1, 'Undo rewinds Count')
    await mips.simulateWithLimit(100)
    assert.equal(reg(mips, '$t0'), 2, 'mfc0 after four instructions reads 2')
    assert.equal(reg(mips, '$t2'), 1000, 'mfc0 after mtc0 reads what was written')
    assert.equal(reg(mips, '$t3'), 1002, 'and counts on from it, a tick every even instruction count')
    for (let i = 0; i < 4; i++) mips.undo()
    assert.equal(cp0(mips, 9), 1000, 'back before mfc0 $t2: the count mtc0 set')
    mips.undo()
    assert.equal(cp0(mips, 9), 3, 'mtc0 undone: six instructions, Count 3')
    // a Poke of Count sets what it reads, and its Undo puts the old count back
    mips.initialize(true)
    await mips.simulateWithLimit(4)
    mips.beginPoke()
    mips.setCoprocessor0Value(9, 500)
    mips.endPoke()
    assert.equal(cp0(mips, 9), 500)
    await mips.simulateWithLimit(2)
    assert.equal(cp0(mips, 9), 501)
    mips.undo()
    mips.undo()
    mips.undo()
    assert.equal(cp0(mips, 9), 2, 'the Poke undone')
}

// The timer program: Compare = Count + 10 ticks, the handler counts in $s1 and re-arms.
const TIMER_PROGRAM = [
    '        .text',
    'main:   mfc0 $t0, $9',
    '        addiu $t0, $t0, 10',
    '        mtc0 $t0, $11',
    'spin:   addiu $s0, $s0, 1',
    '        j spin',
    '        .ktext 0x80000180',
    'handler: addiu $s1, $s1, 1',
    '        mfc0 $k0, $13',
    '        mfc0 $k1, $11',
    '        addiu $k1, $k1, 10',
    '        mtc0 $k1, $11',
    '        eret',
]

// 6. The timer is off until Compare is written: Count meeting Compare's 0 raises nothing.
{
    const mips = core(['main: j main'])
    mips.setCoprocessor0Value(9, -3) // Count wraps to 0 in a few instructions
    assert.equal(await mips.simulateWithLimit(20), StopReason.MAX_STEPS)
    assert.equal(cp0(mips, 13) & TIMER, 0, 'no IP7 without a write to Compare')
    assert.deepEqual(taken(mips), [])
}

// 7. Count == Compare raises IP7 at the same instruction every run, inside it (one Undo removes it),
// the interrupt is taken, the handler's Compare write clears IP7 and re-arms 10 ticks later.
{
    const mips = core(TIMER_PROGRAM)
    let steps = 0
    while (mips.programCounter !== HANDLER && steps < 100) {
        await mips.step()
        steps++
    }
    assert.equal(mips.programCounter, HANDLER)
    // mfc0 read Count 0 at instruction 1; Compare = 10; Count reaches 10 after 20 instructions,
    // then one more step is the entry
    assert.equal(steps, 21, 'Count reached Compare after 20 instructions, the entry is the 21st')
    assert.equal(cp0(mips, 13) & TIMER, TIMER)
    assert.equal(cp0(mips, 14), taken(mips)[0])
    assert.equal(taken(mips)[1], TIMER)
    const epc = cp0(mips, 14)

    // Undo out of the handler, then once more: the IP7 the 20th instruction set goes too
    mips.undo()
    assert.equal(mips.programCounter, epc)
    assert.equal(cp0(mips, 13) & TIMER, TIMER, 'still pending: it was set by the instruction before')
    mips.undo()
    assert.equal(cp0(mips, 13) & TIMER, 0, 'undoing the instruction that reached Compare clears IP7')
    assert.equal(cp0(mips, 9), 9)
    // and stepping forward takes it at the same point
    await mips.step()
    await mips.step()
    assert.equal(mips.programCounter, HANDLER)

    // the handler re-arms: one interrupt every 10 Counts, 20 instructions, as the program runs
    assert.equal(await mips.simulateWithLimit(6), StopReason.MAX_STEPS)
    assert.equal(cp0(mips, 13) & TIMER, 0, 'the handler\'s Compare write cleared IP7')
    assert.equal(await mips.simulateWithLimit(200), StopReason.MAX_STEPS)
    // Count 20 at 40 instructions, 30 at 60 and so on: entries at 40, 60, ..., 220 of 227
    assert.equal(reg(mips, '$s1'), 11, 'one interrupt per 20 instructions, the handler\'s included')
}

// 8. Undo of the first Compare write turns the timer off again: a Poke of Compare here.
for (const undone of [false, true]) {
    const mips = core(['main: j main'])
    mips.setCoprocessor0Value(12, 0) // interrupts off: only IP7 is looked at
    mips.beginPoke()
    mips.setCoprocessor0Value(11, 5)
    mips.endPoke()
    if (undone) mips.undo()
    assert.equal(cp0(mips, 11), undone ? 0 : 5)
    // Count meets Compare, 5 or the 0 the Undo put back, within four instructions
    mips.setCoprocessor0Value(9, undone ? -2 : 3)
    await mips.simulateWithLimit(10)
    assert.equal(cp0(mips, 13) & TIMER, undone ? 0 : TIMER, undone ? 'the timer is off again' : 'the Poke armed it')
}

// 9. Exceptions reach the handler: break, teq and an address error, each in its own step.
{
    const mips = core([
        '        .text',
        'main:   break',
        'trap:   teq $zero, $zero',
        'load:   lw $t0, 1($zero)',
        'done:   li $v0, 10',
        '        syscall',
        '        .ktext 0x80000180',
        '        mfc0 $k0, $13',
        '        sw $k0, 0($sp)',
        '        addiu $sp, $sp, -4',
        '        mfc0 $k0, $14',
        '        addiu $k0, $k0, 4',
        '        mtc0 $k0, $14',
        '        eret',
    ])
    await mips.step()
    assert.equal(mips.programCounter, HANDLER, 'break goes to the handler')
    assert.equal((cp0(mips, 13) >> 2) & 31, 9)
    assert.equal(await mips.simulateWithBreakpointsAndLimit([HANDLER], 100), StopReason.BREAKPOINT)
    assert.equal((cp0(mips, 13) >> 2) & 31, 13, 'teq: a trap')
    assert.equal(await mips.simulateWithBreakpointsAndLimit([HANDLER], 100), StopReason.BREAKPOINT)
    assert.equal((cp0(mips, 13) >> 2) & 31, 4, 'the misaligned load: an address error')
    assert.equal(await mips.simulateWithLimit(100), StopReason.NORMAL_TERMINATION)
}

// 10. Initialize clears the lines, the timer and its IP7.
{
    const mips = core(TIMER_PROGRAM)
    await mips.simulateWithLimit(15)
    mips.raiseExternalInterrupt(0x40)
    mips.initialize(true)
    assert.equal(cp0(mips, 13), 0)
    assert.equal(cp0(mips, 9), 0)
    await mips.simulateWithLimit(2)
    assert.deepEqual(taken(mips), [], 'the line raised before initialize is gone')
}

// 11. The GNU profile places .ktext at 0x80000180 and .kdata at 0x90000000, apart from user text,
// and rejects a j or jal between them with an error that says to use jalr.
{
    const gnu = { assemblerProfile: 'gnu-compiler-v1' }
    const mips = core([
        '        .text',
        '        .globl main',
        'main:   addiu $s0, $s0, 1',
        '        b main',
        '        nop',
        '        .section .ktext,"ax",@progbits',
        'handler: lui $k0, %hi(count)',
        '        lw $k1, %lo(count)($k0)',
        '        addiu $k1, $k1, 1',
        '        sw $k1, %lo(count)($k0)',
        '        eret',
        '        .kdata',
        'count:  .word 0',
    ], gnu)
    assert.equal(label(mips, 'handler'), HANDLER)
    assert.equal(label(mips, 'count'), 0x90000000 | 0)
    assert.equal(label(mips, 'main'), 0x00400000)
    mips.raiseExternalInterrupt(0x40)
    await mips.step()
    assert.equal(mips.programCounter, HANDLER)
    mips.clearExternalInterrupt(0x40)
    await mips.simulateWithLimit(5)
    assert.equal(mips.readMemoryBytes(0x90000000 | 0, 1)[0], 1, '.kdata is written by the handler')

    const crossing = MIPS.makeMipsFromFiles({ 'main.s': [
        '        .text',
        '        .globl main',
        'main:   jal handler',
        '        nop',
        '        .ktext',
        'handler: eret',
    ].join('\n') }, 'main.s', gnu)
    const result = crossing.assemble()
    assert.equal(result.hasErrors, true)
    assert.match(result.errors.map(e => e.message).join('\n'), /outside the 256 MiB region.*jalr/)

    const addressed = MIPS.makeMipsFromFiles({ 'main.s': '.globl main\nmain: nop\n.ktext 0x80000200\nnop\n' }, 'main.s', gnu)
    assert.match(addressed.assemble().errors.map(e => e.message).join('\n'), /\.ktext is placed at 0x80000180/)
    const same = MIPS.makeMipsFromFiles({ 'main.s': '.globl main\nmain: nop\n.ktext 0x80000180\nk: eret\n' }, 'main.s', gnu)
    assert.equal(same.assemble().hasErrors, false, 'the address MARS programs give is accepted')
}

console.log('interrupts: ok')
