// A `jr $ra` pops down to the frame returning where it jumps, or the innermost frame when none
// does. Which held frames return where is counted as they come and go, so a return that matches
// no frame pops one without looking through all of them, and one that matches an outer frame
// looks only that far. These check that the count keeps up with every push, pop, eviction and
// Undo, and that a return matching nothing costs the same at any depth.
import assert from 'node:assert/strict'
import { MIPS, StopReason, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]

function core(lines, undo = true) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(1000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.setUndoEnabled(undo)
    mips.initialize(true)
    return mips
}

const label = (mips, name) => mips.getAddressOfLabel(name)

/** Steps until the program counter is `address`. */
async function stepTo(mips, address, limit = 1000) {
    for (let i = 0; i < limit && mips.programCounter !== address; i++) await mips.step()
    assert.equal(mips.programCounter, address)
}

// 1. 100,000 frames, then 100,000 returns that match none of them: each pops one frame, and the
// whole run takes about as long as 100,000 returns that match. Looking through every frame held
// for each made it some five billion comparisons.
{
    const DEPTH = 100_000
    const mips = core([
        '        .data',
        'p:      .word ' + (DEPTH + 1),
        'q:      .word ' + DEPTH,
        '        .text',
        'main:   lw $t0, p',
        'loop:   addiu $t0, $t0, -1',
        '        beq $t0, $zero, spin',
        '        jal loop',              // a frame a pass, every one returning to the nop
        '        nop',
        'spin:   lw $t1, q',
        'again:  la $ra, back',          // no frame returns to back
        '        jr $ra',
        'back:   addiu $t1, $t1, -1',
        '        bne $t1, $zero, again',
        '        li $v0, 10',
        '        syscall',
    ], false)
    assert.equal(await mips.run({ breakpoints: [label(mips, 'spin')], limit: 10 * DEPTH }), StopReason.BREAKPOINT)
    assert.equal(mips.getCallDepth(), DEPTH, 'a frame a pass')
    const started = performance.now()
    assert.equal(await mips.run({ limit: 10 * DEPTH }), StopReason.NORMAL_TERMINATION)
    const took = performance.now() - started
    assert.equal(mips.getCallDepth(), 0, 'each return that matched no frame popped one')
    assert.ok(took < 10_000, `100,000 unmatched returns took ${Math.round(took)} ms`)
}

// 2. A return that matches an outer frame pops through to it, and after Undo puts the frames back
// the count does too: the same return pops through them again.
{
    const mips = core([
        'main:   jal a',
        'm1:     li $v0, 10',
        '        syscall',
        'a:      move $s0, $ra',
        '        jal b',
        '        nop',
        'b:      jal c',
        '        nop',
        'c:      move $ra, $s0',         // m1, which only a's frame returns to
        'cret:   jr $ra',
    ])
    await stepTo(mips, label(mips, 'cret'))
    assert.equal(mips.getCallDepth(), 3)
    await mips.step()
    assert.equal(mips.getCallDepth(), 0, 'popped c, b and a')
    mips.undo()
    assert.equal(mips.getCallDepth(), 3, 'Undo puts the three back')
    await mips.step()
    assert.equal(mips.getCallDepth(), 0, 'and the same return pops them again')
    // with every frame popped, a return to m1 matches nothing: one more Undo and a step that
    // matches nothing leave the count as it was
    mips.undo()
    mips.undo()
    assert.equal(mips.programCounter, label(mips, 'c'))
    await mips.step()
    await mips.step()
    assert.equal(mips.getCallDepth(), 0)
}

// 3. A frame under a handler's frame is not returned to: a call inside the handler whose return
// matches only a frame outside it pops the handler's call alone.
{
    const mips = core([
        '        .text',
        'main:   jal work',
        'm1:     li $v0, 10',
        '        syscall',
        'work:   move $s0, $ra',         // m1
        '        li $t1, 1',
        '        teq $t1, $t1',          // an exception: the handler's frame
        '        jr $s0',
        '        .ktext 0x80000180',
        '        jal h',
        '        eret',
        'h:      move $ra, $s0',
        'hret:   jr $ra',                // to m1, which only work's frame, under the handler's, returns to
    ])
    await stepTo(mips, label(mips, 'hret'))
    assert.deepEqual(Array.from(mips.getCallStack(), frame => frame.kind), ['call', 'exception', 'call'])
    await mips.step()
    assert.deepEqual(Array.from(mips.getCallStack(), frame => frame.kind), ['call', 'exception'],
        'the handler\'s call alone is popped')
}

console.log('return-search: ok')
