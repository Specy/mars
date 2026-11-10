// When the call stack pushes a frame and when the frame is gone, which is what `getCallDepth()`
// and a run's `untilCallDepth` read. Pushed by jal, by jalr unless it links into $zero, by a taken
// bal, bgezal or bltzal, and by an exception or interrupt entering the handler. Gone (1) at a
// `jr $ra`, down to the frame returning where it jumps or the innermost call frame, never a
// handler's; (2) when any instruction arrives at the innermost frame's return address with $sp at
// or above where it was at the call; (3) at `eret`, down to the newest handler frame. Every program
// is stepped to its end, then undone one instruction at a time, and each Undo must give back the
// depth and the frames of the step before.
import assert from 'node:assert/strict'
import { MIPS, StopReason, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const HANDLER = 0x80000180 | 0

function core(lines) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(10000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}

const label = (mips, name) => mips.getAddressOfLabel(name)
const frames = mips => Array.from(mips.getCallStack(), frame => ({
    pc: frame.pc, site: frame.callSite, to: frame.toAddress, ret: frame.returnAddress, kind: frame.kind, sp: frame.sp,
    registers: Array.from(frame.registers),
}))
const state = mips => JSON.stringify({ depth: mips.getCallDepth(), frames: frames(mips) })
const brief = mips => frames(mips).map(({ to, ret, kind }) => ({ to, ret, kind }))

/**
 * Steps the program to its end, `before(step)` first on each step, recording the call stack after
 * every step; then undoes every step, checking that each Undo gives back the state before it.
 * Returns the depths after each step and the program counter before it.
 */
async function walk(mips, before = () => {}) {
    const states = [state(mips)]
    const pcs = []
    while (!mips.terminated) {
        before(states.length - 1)
        pcs.push(mips.programCounter)
        await mips.step()
        const now = state(mips)
        const { depth, frames } = JSON.parse(now)
        assert.equal(depth, frames.length, 'the depth is the frames held')
        states.push(now)
        assert.ok(states.length < 2000, 'the program ends')
    }
    for (let step = states.length - 1; step > 0; step--) {
        mips.undo()
        assert.equal(state(mips), states[step - 1], `undoing step ${step}`)
    }
    return { depths: states.map(s => JSON.parse(s).depth), pcs, states }
}

/** Steps until the program counter is `address`. */
async function stepTo(mips, address, limit = 1000) {
    for (let i = 0; i < limit && mips.programCounter !== address; i++) await mips.step()
    assert.equal(mips.programCounter, address)
}

// 1. A recursion with a stack frame: one frame a level, back to none, and the frames say where
// each call returns.
const FACTORIAL = [
    'main:  li $a0, 5',
    '       jal fact',
    'after: move $s7, $v0',
    '       li $v0, 10',
    '       syscall',
    'fact:  addiu $sp, $sp, -8',
    '       sw $ra, 4($sp)',
    '       sw $a0, 0($sp)',
    '       li $v0, 1',
    '       slti $t0, $a0, 2',
    '       bne $t0, $zero, fdone',
    '       addiu $a0, $a0, -1',
    '       jal fact',
    'fret:  lw $a0, 0($sp)',
    '       mul $v0, $v0, $a0',
    'fdone: lw $ra, 4($sp)',
    '       addiu $sp, $sp, 8',
    '       jr $ra',
]
{
    const mips = core(FACTORIAL)
    let deepest = null
    const { depths } = await walk(mips, () => {
        if (mips.getCallDepth() === 5 && deepest === null) deepest = brief(mips)
    })
    assert.equal(Math.max(...depths), 5, 'fact(5) to fact(1)')
    assert.equal(depths.at(-1), 0)
    assert.deepEqual(deepest, [
        { to: label(mips, 'fact'), ret: label(mips, 'after'), kind: 'call' },
        ...Array(4).fill({ to: label(mips, 'fact'), ret: label(mips, 'fret'), kind: 'call' }),
    ])
    // each level is one frame up and one down: the depth never jumps
    for (let i = 1; i < depths.length; i++) assert.ok(Math.abs(depths[i] - depths[i - 1]) <= 1)
}

// 2. Nested calls: outer keeps $ra in $s0 and never moves $sp, and calls inner twice.
const NESTED = [
    'main:  jal outer',
    'm1:    li $v0, 10',
    '       syscall',
    'outer: move $s0, $ra',
    '       jal inner',
    'o1:    jal inner',
    'o2:    move $ra, $s0',
    '       jr $ra',
    'inner: addiu $t0, $t0, 1',
    '       jr $ra',
]
{
    const mips = core(NESTED)
    const { depths } = await walk(mips)
    // jal outer, move, jal inner, addiu, jr, jal inner, addiu, jr, move, jr, li, syscall
    assert.deepEqual(depths, [0, 1, 1, 2, 2, 1, 2, 2, 1, 1, 0, 0, 0])
    await stepTo(mips, label(mips, 'inner'))
    assert.deepEqual(brief(mips), [
        { to: label(mips, 'outer'), ret: label(mips, 'm1'), kind: 'call' },
        { to: label(mips, 'inner'), ret: label(mips, 'o1'), kind: 'call' },
    ])
}

// 3. bgezal and bltzal: no frame when not taken; a frame returning after the branch when taken,
// whose pc is that address too, not the branch target.
{
    const mips = core([
        'main: li $t0, -1',
        '      bgezal $t0, sub',
        '      bltzal $t0, sub',
        'b1:   li $t0, 1',
        '      bltzal $t0, sub',
        '      bgezal $t0, sub',
        'b2:   li $v0, 10',
        '      syscall',
        'sub:  jr $ra',
    ])
    await mips.step()
    await mips.step()
    assert.equal(mips.getCallDepth(), 0, 'a bgezal not taken pushes nothing')
    await mips.step()
    assert.deepEqual(frames(mips).map(({ pc, site, to, ret, kind }) => ({ pc, site, to, ret, kind })),
        [{ pc: label(mips, 'b1'), site: label(mips, 'b1') - 4, to: label(mips, 'sub'), ret: label(mips, 'b1'), kind: 'call' }],
        'a taken bltzal')
    await mips.step() // jr $ra
    assert.equal(mips.getCallDepth(), 0)
    await mips.step()
    await mips.step()
    assert.equal(mips.getCallDepth(), 0, 'a bltzal not taken pushes nothing')
    await mips.step()
    assert.deepEqual(frames(mips).map(({ pc, ret }) => ({ pc, ret })), [{ pc: label(mips, 'b2'), ret: label(mips, 'b2') }],
        'a taken bgezal returns after the branch')
    mips.initialize(true)
    const { depths } = await walk(mips)
    assert.deepEqual(depths, [0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0])
}

// 4. bal to the next instruction, to read the program counter: pushed and gone in one instruction,
// which records both, so that one Undo puts back nothing at all.
{
    const mips = core([
        'main: jal sub',
        'm1:   li $v0, 10',
        '      syscall',
        'sub:  move $t1, $ra',
        '      bal next',
        'next: move $t0, $ra',
        '      move $ra, $t1',
        '      jr $ra',
    ])
    await mips.step()
    await mips.step()
    await mips.step() // bal next
    assert.equal(mips.programCounter, label(mips, 'next'))
    assert.equal(mips.getCallDepth(), 1, 'bal next is over as soon as it has run')
    const group = mips.getUndoGroups()[0]
    assert.deepEqual(group.steps.map(step => step.action).filter(action => action >= 102), [103, 102],
        'a call and a return in one instruction, newest first')
    mips.undo()
    assert.equal(mips.getCallDepth(), 1)
    assert.equal(mips.getCallStack()[0].toAddress, label(mips, 'sub'))
    mips.initialize(true)
    const { depths } = await walk(mips)
    assert.deepEqual(depths, [0, 1, 1, 1, 1, 1, 0, 0, 0])
}

// 5. A return through another register, a changed return address, a tail call, and jalr $zero.
{
    const mips = core([
        'main:  jal viat0',
        'r1:    jal skip',
        '       nop',              // inline "data" the routine skips
        '       nop',
        'r2:    jal first',
        'r3:    jal plain',
        'r4:    la $t9, r5',
        '       jalr $zero, $t9',  // a jump: no frame
        'r5:    li $v0, 10',
        '       syscall',
        'viat0: move $t0, $ra',
        '       jr $t0',           // gone on arrival at r1
        'skip:  addiu $ra, $ra, 8',
        '       jr $ra',           // returns past the nops: no frame matches, the innermost goes
        'first: addiu $t1, $t1, 1',
        '       j second',         // a tail call: the frame stays until second returns
        'second: addiu $t2, $t2, 1',
        '       jr $ra',
        'plain: jalr $zero, $ra',  // a return that is not jr $ra: gone on arrival, nothing pushed
    ])
    const { depths, pcs } = await walk(mips)
    const at = name => pcs.indexOf(label(mips, name)) + 1 // the depth after that instruction ran
    assert.equal(depths[at('viat0')], 1)
    assert.equal(depths[at('viat0') + 1], 0, 'jr $t0 returns')
    assert.equal(depths[at('skip') + 1], 0, 'a jr $ra past the return address returns')
    assert.equal(pcs[at('skip') + 1], label(mips, 'r2'))
    assert.equal(depths[at('first') + 1], 1, 'j second keeps the frame')
    assert.equal(depths[at('second') + 1], 0, 'second returns to the caller of first')
    assert.equal(depths[at('plain')], 0, 'jalr $zero, $ra pushes nothing and ends the call')
    assert.equal(depths[pcs.indexOf(label(mips, 'r5'))], 0, 'jalr $zero used as a jump pushes nothing')
    assert.ok(depths.every(depth => depth <= 1))
}

// 5b. A routine left by a jump keeps its frame until the jr $ra of its caller, which pops both in
// one instruction, and one Undo puts both back.
{
    const mips = core([
        'main:  jal a',
        'm1:    li $v0, 10',
        '       syscall',
        'a:     move $s0, $ra',
        '       jal b',
        'a1:    nop',              // b's return address, never reached
        'a2:    move $ra, $s0',
        '       jr $ra',
        'b:     j a2',             // leaves without returning
    ])
    const { depths, pcs, states } = await walk(mips)
    const jr = pcs.indexOf(label(mips, 'a2') + 4)
    assert.deepEqual(JSON.parse(states[jr]).frames.map(frame => frame.ret), [label(mips, 'm1'), label(mips, 'a1')],
        'b\'s frame stays after the jump')
    assert.equal(depths[jr + 1], 0, 'the jr $ra to m1 pops b\'s frame and a\'s')
}

// 6. Handlers are frames: an interrupt's and an exception's, returning to EPC, with and without
// calls inside, left with eret; a jr $ra inside the handler never pops the handler's frame.
const HANDLED = [
    '        .text',
    'main:   jal work',
    'm1:     li $t1, 1',
    '        teq $t1, $t1',        // an exception, taken by the handler
    'm2:     li $v0, 10',
    '        syscall',
    'work:   addiu $s0, $s0, 1',   // the interrupt arrives here
    '        addiu $s0, $s0, 1',
    '        jr $ra',
    '        .ktext 0x80000180',
    'handler: mfc0 $k0, $13',
    '        andi $k0, $k0, 0x7c',
    '        beq $k0, $zero, irq',
    '        mfc0 $k0, $14',       // an exception: return past the teq
    '        addiu $k0, $k0, 4',
    '        mtc0 $k0, $14',
    '        eret',
    'irq:    move $k1, $ra',
    '        jal kf',              // a call inside the handler
    '        la $ra, hret',
    '        jr $ra',              // not a return from the handler: pops nothing
    'hret:   move $ra, $k1',
    '        eret',
    'kf:     jr $ra',
]
{
    const mips = core(HANDLED)
    const raise = step => {
        if (mips.programCounter === label(mips, 'work') && step < 3) mips.raiseExternalInterrupt(0x40)
        else mips.clearExternalInterrupt(0x40)
    }
    const { depths, pcs, states } = await walk(mips, raise)
    const entry = pcs.indexOf(HANDLER)
    const irq = JSON.parse(states[entry]).frames
    assert.deepEqual(irq.map(({ to, ret, kind }) => ({ to, ret, kind })), [
        { to: label(mips, 'work'), ret: label(mips, 'm1'), kind: 'call' },
        { to: HANDLER, ret: label(mips, 'work'), kind: 'interrupt' },
    ], 'the interrupt is a frame returning to the instruction it interrupted')
    assert.equal(irq[1].pc, label(mips, 'work'))
    assert.equal(irq[1].site, label(mips, 'work'), 'made at the instruction it came before')
    assert.equal(irq[0].site, label(mips, 'main'), 'a call is made at the call')
    assert.equal(depths[pcs.indexOf(label(mips, 'kf'))], 3, 'the handler calls kf')
    assert.equal(depths[pcs.indexOf(label(mips, 'hret'))], 2, 'the jr $ra in the handler leaves its frame')
    assert.equal(depths[pcs.indexOf(label(mips, 'hret') + 4) + 1], 1, 'eret ends it')
    const teq = pcs.lastIndexOf(HANDLER)
    assert.notEqual(teq, entry)
    const exception = JSON.parse(states[teq]).frames
    assert.deepEqual(exception.map(({ to, ret, kind }) => ({ to, ret, kind })), [
        { to: HANDLER, ret: label(mips, 'm1') + 4, kind: 'exception' },
    ], 'the exception is a frame returning to EPC, the teq')
    assert.equal(exception[0].site, label(mips, 'm1') + 4, 'made at the teq')
    assert.equal(depths.at(-1), 0)
}

// 7. eret pops the calls the handler made down to its own frame, and with no handler frame
// (eret used as a jump) pops nothing.
{
    const mips = core([
        '        .text',
        'main:   jal work',
        'm1:     la $t0, m2',
        '        mtc0 $t0, $14',
        '        jal jump',
        'm1b:    nop',
        'm2:     li $v0, 10',
        '        syscall',
        'work:   li $t1, 1',
        '        teq $t1, $t1',
        '        jr $ra',
        'jump:   eret',               // to m2: no handler frame, nothing popped
        '        .ktext 0x80000180',
        'handler: move $k1, $ra',
        '        jal deep',
        'hback:  eret',
        'deep:   move $ra, $k1',
        '        mfc0 $k0, $14',
        '        addiu $k0, $k0, 4',
        '        mtc0 $k0, $14',
        '        eret',               // pops deep's frame and the handler's
    ])
    const { depths, pcs } = await walk(mips)
    const eret = pcs.lastIndexOf(label(mips, 'deep') + 16)
    assert.equal(depths[eret], 3, 'work, the handler and deep')
    assert.equal(depths[eret + 1], 1, 'back in work')
    const jump = pcs.indexOf(label(mips, 'jump'))
    assert.equal(depths[jump], 1)
    assert.equal(depths[jump + 1], 1, 'an eret with no handler frame pops nothing')
}

// 8. A breakpoint, a pause and the limit leave the frames as they are, and initialize empties them.
{
    const mips = core(FACTORIAL)
    assert.equal(await mips.simulateWithBreakpoints([label(mips, 'fdone')]), StopReason.BREAKPOINT)
    assert.equal(mips.getCallDepth(), 5)
    assert.equal(mips.getCallStack(2).length, 2, 'the newest two')
    assert.deepEqual(mips.getCallStack(2).map(frame => frame.sp), mips.getCallStack().slice(3).map(frame => frame.sp))
    assert.equal(mips.getCallStack(0).length, 0)
    assert.equal(mips.getCallStack(99).length, 5)
    assert.throws(() => mips.getCallStack(-1), TypeError)
    assert.throws(() => mips.getCallStack(1.5), TypeError)
    mips.initialize(true)
    assert.equal(mips.getCallDepth(), 0)
    assert.equal(mips.getCallStack().length, 0)
}

console.log('call-frame-rules: ok')
