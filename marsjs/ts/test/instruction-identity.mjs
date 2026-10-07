import assert from 'node:assert/strict'
import { MIPS, registerHandlers, BackStepAction } from '../dist/index.mjs'

const make = (source, capacity = 200) => {
    const core = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    core.setUndoSize(capacity)
    assert.equal(core.assemble().hasErrors, false, source)
    core.setUndoEnabled(true)
    core.initialize(true)
    return core
}
const reg = (core, name, value) => core.setRegisterValue(name, value)
const text = body => `.text\n.globl main\nmain:\n${body}\n`
const jump = 'j main'
const dispatchSource = `.data
path: .asciiz "probe.txt"
.text
.globl main
main:
la $a0, path
li $a1, 1
li $a2, 0
li $v0, 13
jal dispatch
li $v0, 10
jal dispatch
dispatch:
syscall
jr $ra
`
const faultSource = text(`li $v0, 31
syscall`)
const randomSource = text(`li $a0, 0
li $v0, 41
syscall
li $v0, 10
syscall`)
const printSource = text(`li $a0, 7
li $v0, 1
syscall
li $v0, 10
syscall`)
const top = core => core.getUndoGroupsUpTo(1)[0]
const serial = group => {
    assert.match(group.serial, /^[1-9][0-9]*$/)
    for (const step of group.steps) assert.equal(step.serial, group.serial)
    return BigInt(group.serial)
}

// Adjacent dynamic executions of exactly the same static jump are separate instructions.
{
    const core = make(text(jump))
    assert.equal(core.getCurrentInstructionSerial(), null)
    const pc = core.programCounter
    await core.simulateWithLimit(5)
    const groups = core.getUndoGroups()
    assert.equal(groups.length, 5)
    assert.ok(groups.every(g => g.pc === pc))
    const ids = groups.map(serial)
    assert.equal(new Set(ids).size, 5)
    assert.ok(ids.every((id, i) => i === 0 || id < ids[i - 1]))
    core.undo()
    assert.equal(core.getUndoDepth(), 4)
    await core.step()
    assert.ok(serial(top(core)) > ids[0], 'Undo and reexecution never alias')
    const last = serial(top(core))
    core.initialize(true)
    assert.equal(core.getUndoDepth(), 0, 'initialize clears old restores')
    assert.equal(core.getCurrentInstructionSerial(), null)
    await core.step()
    assert.ok(serial(top(core)) > last, 'initialize does not reset serials')
    assert.deepEqual(core.getUndoGroupsRange(0, 1), core.getUndoGroupsUpTo(1))
    const preAssembly = serial(top(core))
    assert.equal(core.assemble().hasErrors, false)
    core.initialize(true)
    await core.step()
    assert.ok(serial(top(core)) > preAssembly, 'reassembly does not alias identity')
}

// Handler identity is exactly its final group, and survives asynchronous waits.
{
    const core = make(printSource)
    let during
    registerHandlers(core, { printString: async () => {
        during = core.getCurrentInstructionSerial()
        assert.ok(during)
        assert.throws(() => core.initialize(true))
        assert.throws(() => core.beginPoke())
        assert.throws(() => core.undo())
        assert.throws(() => core.setUndoEnabled(false))
        await Promise.resolve()
        assert.equal(core.getCurrentInstructionSerial(), during)
    } })
    await core.simulateWithLimit(10)
    const group = core.getUndoGroups().find(g => g.serial === during)
    assert.ok(group)
    serial(group)
    assert.equal(core.getCurrentInstructionSerial(), null)
}

// Host file service and Core-only exit at one dispatch PC must never share an identity.
{
    const core = make(dispatchSource)
    let fileSerial
    registerHandlers(core, { openFile: () => {
        fileSerial = core.getCurrentInstructionSerial()
        return 3
    } })
    await core.simulateWithLimit(100)
    assert.equal(core.exitCode, 0)
    const exit = top(core)
    assert.ok(exit.steps.some(s => s.action === BackStepAction.EXIT_RESTORE))
    const file = core.getUndoGroups().find(g => g.serial === fileSerial)
    assert.ok(file)
    assert.equal(file.pc, exit.pc)
    assert.notEqual(file.serial, exit.serial)
    const before = core.getUndoDepth()
    core.undo()
    assert.equal(core.getUndoDepth(), before - 1)
    assert.ok(core.getUndoGroups().some(g => g.serial === fileSerial), 'Undo exit leaves file frame live')
}

// Every poke is a separate identity, even at the same paused PC.
{
    const core = make(text(jump))
    await core.step()
    let prev = serial(top(core))
    for (let i = 1; i <= 3; i++) {
        core.beginPoke()
        reg(core, '$t0', i)
        assert.equal(core.getCurrentInstructionSerial(), null)
        assert.equal(core.endPoke(), true)
        assert.equal(top(core).kind, 'poke')
        assert.ok(serial(top(core)) > prev)
        prev = serial(top(core))
    }
    core.undo()
    assert.equal(core.getUndoDepth(), 3)
    await core.step()
    assert.equal(top(core).kind, 'instruction')
    assert.ok(serial(top(core)) > prev)
}

// Capacity still counts raw restore slots, but never exposes half an instruction.
{
    const core = make(text(jump), 5)
    await core.simulateWithLimit(20)
    const groups = core.getUndoGroups()
    assert.equal(groups.length, 5, 'capacity retains only whole dynamic instructions')
    assert.ok(core.getUndoStack().length <= 5)
    groups.forEach(serial)
    const ids = groups.map(g => g.serial)
    for (const id of ids) {
        assert.equal(top(core).serial, id)
        core.undo()
    }
    assert.equal(core.getUndoDepth(), 0)
    const tiny = make(printSource, 1)
    registerHandlers(tiny, { printString: () => {} })
    await tiny.simulateWithLimit(10)
    assert.ok(tiny.getUndoStack().length <= 1)
    for (const group of tiny.getUndoGroups()) serial(group)
}

{
    const core = make(text(`mult $zero, $zero`), 1)
    await core.step()
    assert.equal(core.getUndoDepth(), 0, 'an instruction larger than capacity is discarded whole')
    const zero = make(text(jump), 0)
    await zero.simulateWithLimit(3)
    assert.equal(zero.getUndoDepth(), 0)
    assert.throws(() => zero.setUndoSize(-1))
    zero.beginPoke()
    reg(zero, '$t0', 4)
    assert.equal(zero.endPoke(), false)
}

// Failing handlers and instructions leave a complete dynamic group, including write-free faults.
{
    const core = make(printSource)
    let failed
    registerHandlers(core, { printString: () => {
        failed = core.getCurrentInstructionSerial()
        throw new Error('identity probe')
    } })
    await assert.rejects(core.simulateWithLimit(10))
    assert.equal(top(core).serial, failed)
    assert.equal(core.getCurrentInstructionSerial(), null)
    core.undo()
    registerHandlers(core, { printString: () => {} })
    await core.step()
    assert.ok(serial(top(core)) > BigInt(failed))
    const fault = make(faultSource)
    await assert.rejects(fault.simulateWithLimit(10))
    const failedGroup = top(fault)
    assert.equal(failedGroup.pc, fault.getStopReason() === -1 ? -1 : fault.programCounter - 4)
    serial(failedGroup)
    const depth = fault.getUndoDepth()
    fault.undo()
    assert.equal(fault.getUndoDepth(), depth - 1)
    assert.equal(fault.programCounter, failedGroup.pc)
}

// Random's first-seed host hook agrees with the draw's restore group.
{
    const core = make(randomSource)
    let seedSerial
    registerHandlers(core, { randomSeed: () => {
        seedSerial = core.getCurrentInstructionSerial()
        return 42
    } })
    await core.simulateWithLimit(10)
    const group = core.getUndoGroups().find(g => g.serial === seedSerial)
    assert.ok(group.steps.some(s => s.action === BackStepAction.RANDOM_STREAM_RESTORE))
    serial(group)
}

// Disabled recording allocates identity for transport but retains no Undo through the gap.
{
    const core = make(printSource)
    await core.step()
    assert.ok(core.getUndoDepth() > 0)
    core.setUndoEnabled(false)
    let unrecorded
    registerHandlers(core, { printString: () => { unrecorded = core.getCurrentInstructionSerial() } })
    await core.simulateWithLimit(10)
    assert.ok(unrecorded)
    assert.equal(core.getUndoDepth(), 0)
    assert.equal(core.getCurrentInstructionSerial(), null)
    core.setUndoEnabled(true)
    core.initialize(true)
    await core.step()
    assert.ok(serial(top(core)) > BigInt(unrecorded))
}


console.log('ok - dynamic instruction serials: handlers, shared-PC exits, pokes, failures, eviction, reset and no history')
