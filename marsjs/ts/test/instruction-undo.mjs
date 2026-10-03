import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'

const source = `.text
.globl main
main:
    jal caller
    li $v0, 10
    syscall
helper:
    addiu $a0, $a0, 1
    jr $ra
caller:
    addiu $sp, $sp, -16
    sw $ra, 12($sp)
    li $a0, 5
    jal helper
    li $t0, 2
loop:
    addiu $t0, $t0, -1
    bgtz $t0, loop
    lw $ra, 12($sp)
    addiu $sp, $sp, 16
    jr $ra
`

const RealDate = Date
let now = 1_700_000_000_000
globalThis.Date = class extends RealDate {
    constructor(...args) {
        super(...(args.length ? args : [now]))
    }
    static now() {
        return now
    }
}

try {
    const core = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    core.setUndoSize(200)
    assert.equal(core.assemble().hasErrors, false)
    core.setUndoEnabled(true)
    core.initialize(true)
    const snapshot = () => ({
        pc: core.programCounter,
        line: core.getNextStatement().sourceLine,
        registers: Array.from(core.getRegistersValues()),
    })
    const snapshots = []
    while (!core.terminated && snapshots.length < 50) {
        const before = snapshot()
        snapshots.push(before)
        now += 2
        await core.step()
        const groups = core.getUndoGroups()
        assert.equal(groups.length, snapshots.length, 'one history group per executed instruction')
        assert.equal(groups[0].pc, before.pc, 'the newest group names the executed instruction')
        assert.equal(groups[0].steps.every(step => step.pc === before.pc), true,
            'every restore belongs to the executed instruction')
    }
    assert.ok(core.terminated, 'the program must complete its calls, returns, and branch loop')
    for (const before of snapshots.reverse()) {
        core.undo()
        assert.deepEqual(snapshot(), before, 'one Undo restores the PC, source line, and registers')
    }
    assert.equal(core.canUndo, false)
    console.log('ok - MIPS: calls, returns, and branches undo correctly as the clock advances')
} finally {
    globalThis.Date = RealDate
}
