// The call stack is part of the Undo history: after an undo() it holds the frames it held before
// the step, whatever the step did to it. Undoing a call takes its frame off again, undoing a
// return puts back the frame it popped, with the registers of its own call, though the call stack
// reuses its frames from call to call; undoing anything else leaves it alone, a branch at the
// address calls return to included. Checked against the frames recorded on the way forward, after
// every step of a walk back and forth through calls, returns, a function pointer, a recursion and a
// timer interrupt whose handler calls, with a history large enough for the whole run and with one
// so small that its entries are reused all the time.
import assert from 'node:assert/strict'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]

const SOURCE = [
    '        .text',
    'main:   li $s0, 1',
    '        li $t0, 20',
    '        mtc0 $t0, $11',        // the timer interrupts once Count reaches 20, inside r
    '        jal a',                // a calls b: two frames
    '        li $s0, 2',
    '        jal b',                // b at the depth a had: a's frame object again
    '        la $t9, b',
    '        li $s0, 3',
    '        jalr $t9',             // a call through a pointer
    '        li $s0, 4',
    '        bgezal $zero, b',      // and a branch that links
    '        li $a0, 4',
    '        jal r',                // a recursion, whose calls return to a branch
    '        li $v0, 10',
    '        syscall',
    'a:      addiu $sp, $sp, -4',
    '        sw $ra, 0($sp)',
    '        li $s0, 10',
    '        jal b',
    '        lw $ra, 0($sp)',
    '        addiu $sp, $sp, 4',
    '        jr $ra',
    'b:      addiu $s1, $s1, 1',
    '        jr $ra',
    'r:      addiu $sp, $sp, -8',
    '        sw $ra, 4($sp)',
    '        beq $a0, $zero, rdone',
    '        addiu $a0, $a0, -1',
    '        jal r',
    '        b rdone',              // where every frame of r returns to
    'rdone:  lw $ra, 4($sp)',
    '        addiu $sp, $sp, 8',
    '        jr $ra',
    '        .ktext 0x80000180',
    'handler: move $k1, $ra',
    '        jal kf',               // the handler calls too
    '        move $ra, $k1',
    '        li $k0, 0x7fffffff',
    '        mtc0 $k0, $11',        // which clears the timer's interrupt
    '        eret',
    'kf:     addiu $s2, $s2, 1',
    '        jr $ra',
].join('\n')

function core(undoSize) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': SOURCE }, 'main.asm')
    mips.setUndoSize(undoSize)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}

/** Everything a frame says, so that two call stacks compare by their text. */
const frames = mips => JSON.stringify(Array.from(mips.getCallStack(), frame => ({
    pc: frame.pc, to: frame.toAddress, sp: frame.sp, fp: frame.fp, registers: Array.from(frame.registers),
})))

// The run forward: the frames after each step, and where the stack was deepest.
const forward = core(4096)
const HANDLER = 0x80000180 | 0
const states = [frames(forward)]
let handled = -1
while (!forward.terminated) {
    await forward.step()
    states.push(frames(forward))
    if (forward.programCounter === HANDLER) handled = states.length - 1
    assert.ok(states.length < 1000, 'the program ends')
}
const depths = states.map(state => JSON.parse(state).length)
const tops = states.map(state => JSON.parse(state).at(-1)?.to)
assert.equal(Math.max(...depths), 5, 'r five times deep')
assert.ok(handled > 0, 'the timer interrupted the program')
assert.ok(depths[handled] >= 2 && tops[handled] === forward.getAddressOfLabel('r'), 'inside r')
assert.equal(tops[handled + 2], forward.getAddressOfLabel('kf'), 'and the handler called kf')
assert.equal(forward.getRegisterValue('$s2'), 1)
assert.equal(depths.at(-1), 0, 'every call returned')

// One undo() at a time back to the start: each one puts back the frames of the step before.
for (let step = states.length - 1; step > 0; step--) {
    forward.undo()
    assert.equal(frames(forward), states[step - 1], `undoing step ${step}`)
}
assert.equal(forward.canUndo, false)
// and forward again, through frames the history put back
for (let step = 1; step < states.length; step++) {
    await forward.step()
    assert.equal(frames(forward), states[step], `stepping ${step} again`)
}
// and back once more, through entries whose frames went back on the stack the first time
for (let step = states.length - 1; step > 0; step--) {
    forward.undo()
    assert.equal(frames(forward), states[step - 1], `undoing step ${step} again`)
}

/** A walk of steps and undos, the same every run: the frames always match those of the run forward. */
async function walk(mips, moves, undoSize) {
    let random = 12345
    const next = () => (random = (random * 1103515245 + 12345) & 0x7fffffff) / 0x80000000
    let at = 0
    let undone = 0
    for (let move = 0; move < moves; move++) {
        const back = at > 0 && mips.canUndo && next() < 0.45
        if (back) {
            mips.undo()
            at--
            undone++
        } else {
            if (mips.terminated) continue
            await mips.step()
            at++
        }
        assert.equal(frames(mips), states[at], `${undoSize}: move ${move}, at step ${at}`)
    }
    return undone
}
assert.ok(await walk(core(4096), 3000, 4096) > 1000)
// A history of six entries reuses its entries, and with them the frames of the returns, all the time.
assert.ok(await walk(core(6), 3000, 6) > 500)

// A poke leaves the call stack alone, and so does undoing it.
{
    const mips = core(4096)
    for (let step = 0; step < handled + 2; step++) await mips.step()
    const before = frames(mips)
    assert.ok(JSON.parse(before).length >= 3, 'inside kf, called from the handler inside r')
    mips.beginPoke()
    mips.setRegisterValue('$s0', 99)
    mips.setRegisterValue('$ra', 0x00400000)
    assert.equal(mips.endPoke(), true)
    assert.equal(frames(mips), before, 'a poke')
    mips.undo()
    assert.equal(frames(mips), before, 'its undo')
    mips.undo()
    assert.equal(frames(mips), states[handled + 1], 'and the instruction under it')
}

// A history that is off keeps no frames, and the stack still pops: undo() has nothing to take back.
{
    const off = core(4096)
    off.setUndoEnabled(false)
    for (let step = 1; step < states.length; step++) {
        await off.step()
        assert.equal(frames(off), states[step], `without a history, step ${step}`)
    }
}

console.log('call-stack-undo: ok')
