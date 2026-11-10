// `run(options)`: the run call a Step over and a Step out make. `untilCallDepth: n` stops the call
// with CALL_DEPTH, before running the instruction at the program counter, whenever the call stack
// is n frames deep or less: when the call begins and after every instruction, ahead of the
// breakpoint, pause and limit tests, the end of the program winning. `skipBreakpointAtPc: false`
// stops on a breakpoint at the program counter before anything runs; by default the call runs it.
// And after a step that only entered an interrupt's handler, getInterruptTaken says so.
import assert from 'node:assert/strict'
import { MIPS, StopReason, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const HANDLER = 0x80000180 | 0

function core(lines, handlers = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(10000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, { ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])), ...handlers })
    mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}
const label = (mips, name) => mips.getAddressOfLabel(name)
const reg = (mips, name) => mips.getRegisterValue(name)

const FACTORIAL = [
    'main:  li $a0, 5',
    'call:  jal fact',
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
/** A core parked on fdone in fact(1), five frames deep: three instructions from its return. */
async function innermost() {
    const mips = core(FACTORIAL)
    assert.equal(await mips.simulateWithBreakpoints([label(mips, 'fdone')]), StopReason.BREAKPOINT)
    assert.equal(mips.getCallDepth(), 5)
    return mips
}

// 1. A Step over the call in main: one step enters fact, the run finishes it on `after`.
{
    const mips = core(FACTORIAL)
    await mips.step()
    assert.equal(mips.programCounter, label(mips, 'call'))
    await mips.step()
    assert.equal(mips.getCallDepth(), 1)
    assert.equal(await mips.run({ untilCallDepth: 0, skipBreakpointAtPc: false }), StopReason.CALL_DEPTH)
    assert.equal(mips.programCounter, label(mips, 'after'))
    assert.equal(reg(mips, '$v0'), 120)
    assert.equal(mips.getCallDepth(), 0)
    assert.equal(mips.getStopReason(), StopReason.CALL_DEPTH)
    assert.equal(StopReason.CALL_DEPTH, 7, 'the same number as @specy/risc-v')
}

// 2. Step out of the innermost level, then out of three at once: the run stops at the return that
// takes the depth there, not at an inner one to the same address.
{
    const mips = await innermost()
    assert.equal(await mips.run({ untilCallDepth: 4 }), StopReason.CALL_DEPTH)
    assert.deepEqual([mips.programCounter, mips.getCallDepth(), reg(mips, '$v0')], [label(mips, 'fret'), 4, 1])
    assert.equal(await mips.run({ untilCallDepth: 1 }), StopReason.CALL_DEPTH)
    assert.deepEqual([mips.programCounter, mips.getCallDepth(), reg(mips, '$v0')], [label(mips, 'fret'), 1, 24],
        'back in fact(5), with fact(4) returned')
    // already that shallow: nothing runs
    assert.equal(await mips.run({ untilCallDepth: 1 }), StopReason.CALL_DEPTH)
    assert.equal(mips.getLastRunInstructionCount(), 0)
    assert.equal(mips.programCounter, label(mips, 'fret'))
    // one Undo goes back one instruction, the return, whose frame comes back
    mips.undo()
    assert.equal(mips.getCallDepth(), 2)
    assert.equal(mips.programCounter, label(mips, 'fdone') + 8)
}

// 3. Reached exactly on the last instruction of the limit: CALL_DEPTH, not MAX_STEPS.
{
    const mips = await innermost()
    assert.equal(await mips.run({ untilCallDepth: 4, limit: 2 }), StopReason.MAX_STEPS)
    assert.equal(mips.getCallDepth(), 5)
    assert.equal(await mips.run({ untilCallDepth: 4, limit: 1 }), StopReason.CALL_DEPTH)
    assert.equal(mips.getLastRunInstructionCount(), 1)
    assert.equal(mips.programCounter, label(mips, 'fret'))
}

// 4. A breakpoint on the instruction it returns to: CALL_DEPTH, which is what the step needed.
{
    const mips = await innermost()
    assert.equal(await mips.run({ untilCallDepth: 4, breakpoints: [label(mips, 'fret')] }), StopReason.CALL_DEPTH)
    assert.equal(mips.programCounter, label(mips, 'fret'))
}

// 5. A breakpoint inside the routine stops the step there, its first instruction included when
// the run does not skip the breakpoint at the program counter.
{
    const mips = core(FACTORIAL)
    await mips.step()
    await mips.step() // into fact, on its first instruction
    const fact = label(mips, 'fact')
    assert.equal(await mips.run({ untilCallDepth: 0, breakpoints: [fact], skipBreakpointAtPc: false }), StopReason.BREAKPOINT)
    assert.equal(mips.getLastRunInstructionCount(), 0, 'nothing ran')
    assert.deepEqual([mips.programCounter, mips.getCallDepth()], [fact, 1])
    assert.equal(await mips.run({ untilCallDepth: 0, breakpoints: [fact] }), StopReason.BREAKPOINT, 'by default it runs, to the next call')
    assert.deepEqual([mips.programCounter, mips.getCallDepth()], [fact, 2])
    assert.equal(await mips.run({ untilCallDepth: 0, breakpoints: [label(mips, 'fdone')] }), StopReason.BREAKPOINT)
    assert.equal(mips.getCallDepth(), 5)
    assert.equal(await mips.run({ untilCallDepth: 0 }), StopReason.CALL_DEPTH)
    assert.equal(reg(mips, '$v0'), 120)
}

// 6. A breakpoint at the program counter with nothing else: skipped by default, as the simulate
// calls do, and a stop with nothing run when not.
{
    const mips = core(FACTORIAL)
    const main = label(mips, 'main')
    assert.equal(await mips.run({ breakpoints: [main], skipBreakpointAtPc: false }), StopReason.BREAKPOINT)
    assert.deepEqual([mips.getLastRunInstructionCount(), mips.programCounter], [0, main])
    assert.equal(await mips.run({ breakpoints: [main] }), StopReason.NORMAL_TERMINATION)
    assert.equal(reg(mips, '$s7'), 120)
    // the end of the program wins over everything
    assert.equal(await mips.run({ untilCallDepth: 3, breakpoints: [main], skipBreakpointAtPc: false }), StopReason.NORMAL_TERMINATION)
    // no options at all is simulate()
    mips.initialize(true)
    assert.equal(await mips.run(), StopReason.NORMAL_TERMINATION)
    mips.initialize(true)
    assert.equal(await mips.run({ limit: 3 }), StopReason.MAX_STEPS)
    assert.equal(mips.getLastRunInstructionCount(), 3)
    assert.equal(await mips.run({ limit: 0 }), StopReason.NORMAL_TERMINATION, 'a limit of 0 is none')
}

// 7. The call as the program's last instruction: the program ends when the routine returns.
{
    const mips = core([
        '      .globl main',
        'f:    addiu $t0, $t0, 1',
        '      jr $ra',
        'main: jal f',
    ])
    assert.equal(mips.programCounter, label(mips, 'main'))
    await mips.step()
    assert.equal(mips.getCallDepth(), 1)
    assert.equal(await mips.run({ untilCallDepth: 0, skipBreakpointAtPc: false }), StopReason.CLIFF_TERMINATION)
    assert.equal(mips.terminated, true)
    assert.equal(mips.getCallDepth(), 0)
    assert.equal(await mips.run({ untilCallDepth: 0 }), StopReason.CLIFF_TERMINATION, 'and stays ended')
}

// 8. A pause inside the routine stops the step there; the next run finishes it.
{
    let mips
    mips = core([
        'main: jal say',
        'back: li $v0, 10',
        '      syscall',
        'say:  li $a0, 1',
        '      li $v0, 32',
        '      syscall',
        '      jr $ra',
    ], { sleep: () => mips.pause() })
    await mips.step()
    assert.equal(await mips.run({ untilCallDepth: 0 }), StopReason.PAUSE)
    assert.equal(mips.getCallDepth(), 1)
    assert.equal(await mips.run({ untilCallDepth: 0 }), StopReason.CALL_DEPTH)
    assert.equal(mips.programCounter, label(mips, 'back'))
}

// 9. A step that only enters an interrupt's handler: getInterruptTaken says so, the handler is a
// frame, and running back to the depth before ends on the interrupted instruction, not yet run.
{
    const mips = core([
        '        .text',
        'main:   addiu $s0, $s0, 1',
        '        addiu $s0, $s0, 1',
        '        li $v0, 10',
        '        syscall',
        '        .ktext 0x80000180',
        'handler: addiu $s1, $s1, 1',
        '        eret',
    ])
    await mips.step()
    assert.equal(mips.getInterruptTaken().length, 0, 'an instruction')
    mips.raiseExternalInterrupt(0x40)
    await mips.step()
    assert.deepEqual(Array.from(mips.getInterruptTaken()), [label(mips, 'main') + 4, 0x100, HANDLER], 'only the entry')
    mips.clearExternalInterrupt(0x40)
    assert.deepEqual(mips.getCallStack().map(frame => frame.kind), ['interrupt'])
    assert.equal(await mips.run({ untilCallDepth: 0, skipBreakpointAtPc: false }), StopReason.CALL_DEPTH)
    assert.deepEqual([mips.programCounter, reg(mips, '$s0'), reg(mips, '$s1')], [label(mips, 'main') + 4, 1, 1])
    assert.equal(mips.getInterruptTaken().length, 0, 'reset by every run call')
}

// 10. Options the Java side could not read are refused.
{
    const mips = core(FACTORIAL)
    assert.throws(() => mips.run(null), TypeError)
    assert.throws(() => mips.run({ limit: NaN }), TypeError)
    assert.throws(() => mips.run({ untilCallDepth: '1' }), TypeError)
    assert.throws(() => mips.run({ breakpoints: 4 }), TypeError)
    assert.throws(() => mips.run({ skipBreakpointAtPc: 0 }), TypeError)
    // a fraction is rounded down, as @specy/risc-v reads it
    assert.equal(await mips.run({ limit: 2.5 }), StopReason.MAX_STEPS)
    assert.equal(mips.getLastRunInstructionCount(), 2)
    // a negative depth asks for nothing
    assert.equal(await mips.run({ untilCallDepth: -1 }), StopReason.NORMAL_TERMINATION)
}

console.log('run-until-call-depth: ok')
