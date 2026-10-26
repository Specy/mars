// A handler ending the run call it runs in with `pause()`, and the instruction counts every run call
// reports: what a host needs to end its call where a program's frame ends, at a sleep, and to know
// how far it got.
import assert from 'node:assert/strict'
import { MIPS, StopReason, isRuntimeError, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed',
]
const TEXT = 0x00400000

/** A core for the source lines whose sleep pauses the call, initialized. */
function core(lines, sleep = (mips) => mips.pause()) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(1000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        sleep: () => sleep(mips),
    })
    mips.initialize(true)
    return mips
}

const LOOP = [
    'main: li $t0, 0',
    'loop: addi $t0, $t0, 1',
    '      li $v0, 32',
    '      li $a0, 5',
    '      syscall',
    'next: j loop',
]
const SYSCALL = TEXT + 16
const NEXT = TEXT + 20

// 1. A pause from the sleep handler ends the call after the syscall, with the count of what ran.
{
    const mips = core(LOOP)
    assert.equal(mips.getLastRunInstructionCount(), 0, 'nothing has run yet')
    assert.equal(await mips.simulateWithLimit(1000), StopReason.PAUSE)
    assert.equal(mips.getStopReason(), StopReason.PAUSE)
    assert.equal(mips.getLastRunInstructionCount(), 5, 'li, addi, li, li and the syscall')
    assert.equal(mips.programCounter, NEXT, 'the syscall has run, the instruction after it has not')
    assert.equal(mips.terminated, false)
    assert.equal(mips.getRegisterValue('$t0'), 1)

    // the next call resumes there and runs one more iteration up to the next sleep
    assert.equal(await mips.simulateWithLimit(1000), StopReason.PAUSE)
    assert.equal(mips.getLastRunInstructionCount(), 5, 'j, addi, li, li and the syscall')
    assert.equal(mips.getRegisterValue('$t0'), 2)

    // each paused instruction is one Undo entry like any other
    mips.undo()
    assert.equal(mips.programCounter, SYSCALL, 'undo puts the program back on the syscall')
}

// 2. An async handler pauses once the promise settles and the instruction has finished.
{
    const mips = core(LOOP, async (mips) => {
        mips.pause()
        await new Promise((resolve) => setTimeout(resolve, 5))
    })
    assert.equal(await mips.simulateWithLimit(1000), StopReason.PAUSE)
    assert.equal(mips.programCounter, NEXT)
}

// 3. A breakpoint on the instruction after the paused one wins: the call that resumes would run that
// instruction before checking anything, so a pause reported there would walk through the breakpoint.
{
    const mips = core(LOOP)
    assert.equal(await mips.simulateWithBreakpointsAndLimit([NEXT], 1000), StopReason.BREAKPOINT)
    assert.equal(mips.programCounter, NEXT)
    assert.equal(mips.getLastRunInstructionCount(), 5)
}

// 4. A pause after the program's last instruction is the end of the program, which that call says.
{
    const mips = core(['main: li $v0, 32', '      li $a0, 1', '      syscall'])
    assert.equal(await mips.simulateWithLimit(1000), StopReason.CLIFF_TERMINATION)
    assert.equal(mips.terminated, true)
    assert.equal(mips.getLastRunInstructionCount(), 3)
}

// 5. A pause is for the call in flight only: one asked for outside a call changes no later call, and
// a handler that does not pause leaves the call running to its limit.
{
    const mips = core(LOOP, () => {})
    mips.pause()
    assert.equal(await mips.simulateWithLimit(12), StopReason.MAX_STEPS)
    assert.equal(mips.getLastRunInstructionCount(), 12, 'the limit, exactly')
    assert.equal(await mips.step(), StopReason.MAX_STEPS)
    assert.equal(mips.getLastRunInstructionCount(), 1, 'a step is one instruction')
}

// 6. The count includes the instruction that exits or fails, is 0 for a call on an exited program,
// and initialize forgets it.
{
    const exit = core(['main: li $t0, 1', '      li $v0, 10', '      syscall', '      li $t0, 2'])
    assert.equal(await exit.simulate(), StopReason.NORMAL_TERMINATION)
    assert.equal(exit.getLastRunInstructionCount(), 3, 'li, li and the exit')
    assert.equal(await exit.simulate(), StopReason.NORMAL_TERMINATION)
    assert.equal(exit.getLastRunInstructionCount(), 0, 'an exited program runs nothing')
    exit.initialize(true)
    assert.equal(exit.getLastRunInstructionCount(), 0)

    const failing = core(['main: li $t0, 1', '      lw $t1, 1($zero)'])
    await assert.rejects(failing.simulate(), (error) => isRuntimeError(error))
    assert.equal(failing.getLastRunInstructionCount(), 2, 'li and the failing lw')
}

// 7. getInstructionsExecuted counts every call's instructions together, failing ones included, and
// only grows: each of them is one undo entry, so a host that splits a run into several calls takes
// the difference across all of them, and undo, initialize and another program leave it alone.
{
    const mips = core(LOOP)
    const start = mips.getInstructionsExecuted()
    assert.equal(await mips.simulateWithLimit(1000), StopReason.PAUSE)
    assert.equal(await mips.simulateWithLimit(3), StopReason.MAX_STEPS)
    assert.equal(mips.getInstructionsExecuted() - start, 8, 'both calls, 5 and then 3')
    assert.equal(mips.getUndoDepth(), 8, 'one undo entry each')
    mips.undo()
    mips.initialize(true)
    assert.equal(mips.getInstructionsExecuted() - start, 8, 'undo and initialize take nothing back')

    const failing = core(['main: li $t0, 1', '      li $t2, 2', '      lw $t1, 1($zero)'])
    const before = failing.getInstructionsExecuted()
    assert.ok(before >= start + 8, 'a new program goes on from the count it found')
    await failing.step()
    await assert.rejects(failing.simulate(), (error) => isRuntimeError(error))
    assert.equal(failing.getInstructionsExecuted() - before, 3, 'the step, then li and the failing lw')
    assert.equal(failing.getUndoDepth(), 3, 'which is one undo entry each, the failing lw included')
}

console.log('pause: ok')
