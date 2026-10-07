// How a program ends and how a run call says so: exit codes for exit, exit2 and running off the
// end, `terminated` read from the program's state (so it is right after Undo), a StopReason from
// every run call, and runtime failures rejected with a typed RuntimeError.
import assert from 'node:assert/strict'
import { MIPS, StopReason, isRuntimeError, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr', 'randomSeed',
]
const TEXT = 0x00400000

/** A core for the source lines, initialized; any handler not in `handlers` fails the run. */
function core(lines, handlers = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(1000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, { ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])), ...handlers })
    mips.initialize(true)
    return mips
}

// 1. Exit codes. exit (10) gives 0, as does running off the end; exit2 (17) gives $a0, signed.
{
    const exit = core(['main: li $t0, 1', '      li $v0, 10', '      syscall', 'after: li $t0, 2'])
    assert.equal(exit.getStopReason(), StopReason.NONE, 'nothing has run yet')
    assert.equal(exit.exitCode, 0)
    assert.equal(await exit.simulate(), StopReason.NORMAL_TERMINATION)
    assert.equal(exit.getStopReason(), StopReason.NORMAL_TERMINATION)
    assert.deepEqual([exit.exitCode, exit.terminated, exit.getNextStatement()], [0, true, null],
        'exit ends the program with code 0, and the statement after the syscall is not next')

    for (const code of [42, -1, 0x7fffffff]) {
        const exit2 = core(['main: li $a0, ' + code, '      li $v0, 17', '      syscall', '      li $t0, 2'])
        assert.equal(await exit2.simulate(), StopReason.NORMAL_TERMINATION)
        assert.deepEqual([exit2.exitCode, exit2.terminated], [code, true], `exit2 with ${code}`)
        exit2.undo()
        assert.deepEqual([exit2.exitCode, exit2.terminated], [0, false], 'undoing exit2 puts back the code and the run')
        assert.equal(exit2.getNextStatement().address, TEXT + (code === 0x7fffffff ? 12 : 8), 'back on the syscall')
        assert.equal(await exit2.step(), StopReason.NORMAL_TERMINATION, 'stepping it exits again')
        assert.equal(exit2.exitCode, code)
        exit2.initialize(true)
        assert.deepEqual([exit2.exitCode, exit2.terminated, exit2.getStopReason()], [0, false, StopReason.NONE],
            'initialize starts a run that has not exited')
    }

    // MARS kept exit2's code for good; a later run that ends with exit reports 0.
    const twice = core(['main: beqz $s0, plain', '      li $a0, 9', '      li $v0, 17', '      syscall',
        'plain: li $v0, 10', '      syscall'])
    twice.setRegisterValue('$s0', 1)
    await twice.simulate()
    assert.equal(twice.exitCode, 9)
    twice.initialize(true)
    await twice.simulate()
    assert.equal(twice.exitCode, 0)

    // Running off the end: the step that runs the last instruction says so, and `terminated` is
    // true at once, without a further step finding nothing to run.
    const cliff = core(['main: li $t0, 1', '      li $t1, 2'])
    assert.equal(await cliff.step(), StopReason.MAX_STEPS)
    assert.equal(cliff.terminated, false)
    assert.equal(await cliff.step(), StopReason.CLIFF_TERMINATION, 'the last instruction ran off the end')
    assert.deepEqual([cliff.terminated, cliff.getNextStatement(), cliff.exitCode], [true, null, 0])
    assert.equal(await cliff.step(), StopReason.CLIFF_TERMINATION, 'there is nothing more to run')
    assert.equal(cliff.getRegisterValue('$t1'), 2)
    cliff.undo()
    assert.equal(cliff.terminated, false, 'undo leaves the program running again')
    assert.equal(cliff.getNextStatement().address, TEXT + 4)
    assert.equal(await cliff.simulateWithLimit(1), StopReason.CLIFF_TERMINATION, 'a limit reached at the end')
    cliff.initialize(true)
    assert.equal(await cliff.simulate(), StopReason.CLIFF_TERMINATION)
}

// 2. A StopReason from every run call, and nothing runs after an exit.
{
    const lines = ['main: li $t0, 1', '      li $t0, 2', 'mark: li $t0, 3', '      li $t0, 4', '      li $v0, 17',
        '      li $a0, 5', '      syscall', '      li $t0, 99']
    const mips = core(lines)
    const mark = mips.getAddressOfLabel('mark')
    assert.equal(await mips.step(), StopReason.MAX_STEPS)
    assert.equal(mips.getStopReason(), StopReason.MAX_STEPS)
    assert.equal(await mips.simulateWithBreakpoints([mark]), StopReason.BREAKPOINT)
    assert.equal(mips.programCounter, mark, 'stopped before the breakpoint instruction')
    assert.equal(await mips.simulateWithLimit(1), StopReason.MAX_STEPS)
    assert.equal(await mips.simulateWithBreakpointsAndLimit([TEXT + 24], 1), StopReason.MAX_STEPS)
    assert.equal(await mips.simulateWithBreakpointsAndLimit([TEXT + 24], 100), StopReason.BREAKPOINT)
    assert.equal(await mips.simulate(), StopReason.NORMAL_TERMINATION)
    assert.deepEqual([mips.exitCode, mips.getRegisterValue('$t0')], [5, 4])
    const depth = mips.getUndoDepth()
    for (const run of [() => mips.step(), () => mips.simulate(), () => mips.simulateWithLimit(10),
        () => mips.simulateWithBreakpoints([TEXT]), () => mips.simulateWithBreakpointsAndLimit([TEXT], 10)]) {
        assert.equal(await run(), StopReason.NORMAL_TERMINATION, 'a program that exited stays exited')
    }
    assert.deepEqual([mips.getRegisterValue('$t0'), mips.getUndoDepth()], [4, depth], 'and nothing after the syscall ran')
}

// 3. Runtime failures reject with a RuntimeError: kind, address, source location, MARS's message.
async function failure(run) {
    try {
        await run()
    } catch (error) {
        return error
    }
    assert.fail('the run should have failed')
}

{
    const exception = core(['main: li $t1, 1', '      lw $t0, 3($zero)', '      li $v0, 10', '      syscall'])
    await exception.step()
    const error = await failure(() => exception.step())
    assert.ok(error instanceof Error && isRuntimeError(error), String(error))
    assert.equal(error.name, 'RuntimeError')
    assert.deepEqual([error.kind, error.address, error.sourcePath, error.line], ['exception', TEXT + 4, 'main.asm', 2])
    assert.match(error.message, /^Runtime exception at 0x00400004: .*not aligned/)
    assert.match(String(error), /^RuntimeError: Runtime exception at 0x00400004/)
    assert.equal(exception.getStopReason(), StopReason.EXCEPTION)
    assert.equal(exception.terminated, false, 'a failure is reported by the rejection, not by terminated')

    const unknown = core(['main: li $v0, 99', '      syscall'])
    const unknownError = await failure(() => unknown.simulate())
    assert.deepEqual([unknownError.kind, unknownError.address, unknownError.line, unknownError.message],
        ['syscall', TEXT + 4, 2, 'Runtime exception at 0x00400004: invalid or unimplemented syscall service: 99'])

    const input = core(['main: li $v0, 5', '      syscall'], { readInt: () => 'abc' })
    const inputError = await failure(() => input.simulate())
    assert.deepEqual([inputError.kind, inputError.message], ['syscall', 'Runtime exception at 0x00400004: invalid integer input (syscall 5)'])

    const contract = core(['main: li $v0, 5', '      syscall'], { readInt: () => 5 })
    const contractError = await failure(() => contract.simulate())
    assert.deepEqual([contractError.kind, contractError.address, contractError.line, contractError.message],
        ['handler', TEXT + 4, 2, 'Handler readInt did not return a string'])
    assert.equal(contractError.cause, undefined)

    const boom = new Error('boom')
    const thrown = core(['main: li $v0, 5', '      syscall'], { readInt: () => { throw boom } })
    const thrownError = await failure(() => thrown.simulate())
    assert.deepEqual([thrownError.kind, thrownError.message], ['handler', 'Handler readInt threw: boom'])
    assert.equal(thrownError.cause, boom, 'the cause is what the handler threw')

    const nope = new Error('nope')
    const rejected = core(['main: li $v0, 5', '      syscall'], { readInt: () => Promise.reject(nope) })
    const rejectedError = await failure(() => rejected.simulate())
    assert.deepEqual([rejectedError.kind, rejectedError.message], ['handler', 'Handler readInt rejected: nope'])
    assert.equal(rejectedError.cause, nope, 'the cause is what the promise rejected with')

    // A memory observer is host code too.
    const observed = core(['main: li $t0, 0x10010000', '      sw $t0, 0($t0)', '      li $v0, 10', '      syscall'])
    const handle = observed.addMemoryWriteObserver(0x10010000, 0x10010000, () => { throw new Error('observer') })
    const observerError = await failure(() => observed.simulate())
    observed.removeMemoryObserver(handle)
    assert.deepEqual([observerError.kind, observerError.address, observerError.message],
        ['handler', TEXT + 8, 'Memory observer threw: observer'])

    // A jump out of the program fails on the fetch, which has no statement.
    const lost = core(['main: jr $zero'])
    assert.equal(await lost.step(), StopReason.MAX_STEPS)
    const lostError = await failure(() => lost.step())
    assert.deepEqual([lostError.kind, lostError.address, lostError.sourcePath, lostError.line],
        ['exception', 0, null, null])
    assert.equal(lostError.message, 'invalid program counter value: 0x00000000')

    // Calling a program that never assembled is not a runtime failure.
    const broken = MIPS.makeMipsFromFiles({ 'main.asm': 'main: bogus' }, 'main.asm')
    broken.assemble()
    const misuse = await failure(() => broken.step())
    assert.equal(isRuntimeError(misuse), false)
    assert.match(String(misuse.message), /not been assembled successfully/)
}

console.log('ok - termination: exit codes, terminated after Undo, a StopReason from every run call, typed runtime errors')
