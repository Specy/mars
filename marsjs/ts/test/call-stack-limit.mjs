// The call stack keeps the newest 131,072 frames and goes on counting past them. A `jal` used as a
// jump in a loop adds a frame on every pass and moves no stack, so nothing else bounds it: the
// depth keeps counting, the frames held stay the newest, memory stops growing, and Undo stays
// exact across the limit, putting back the oldest frame a push took the slot of and counting again
// a frame popped past the limit.
import assert from 'node:assert/strict'
import v8 from 'node:v8'
import vm from 'node:vm'
import { MIPS, StopReason, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const LIMIT = 131072
const T0 = 8
/** The instructions before the loop: two loads of a label, two instructions each. */
const PREFIX = 4

/**
 * P - 1 passes of a loop whose `jal loop` is a jump, a frame each, all returning to `ret`; then Q
 * returns: one on arriving at `ret`, and Q - 1 by `jr $ra`.
 */
function core(P, Q, undo = true) {
    const mips = MIPS.makeMipsFromFiles({
        'main.asm': [
            '       .data',
            'p:     .word ' + P,
            'q:     .word ' + Q,
            '       .text',
            'main:  lw $t0, p',
            '       lw $t1, q',
            'loop:  addiu $t0, $t0, -1',
            '       beq $t0, $zero, ret',
            '       jal loop',
            'ret:   addiu $t1, $t1, -1',
            '       beq $t1, $zero, done',
            '       jr $ra',
            'done:  li $v0, 10',
            '       syscall',
        ].join('\n'),
    }, 'main.asm')
    mips.setUndoSize(2000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.setUndoEnabled(undo)
    mips.initialize(true)
    return mips
}

/**
 * What Undo has to put back: the depth and which passes the newest two frames are; with `ends`,
 * the frames held and which pass the oldest is too; with `every`, which pass every frame is.
 * Reading every frame copies 131,072 of them, so the walks below do it only now and then.
 */
function state(mips, ends = true, every = false) {
    const newestTwo = Array.from(mips.getCallStack(2), frame => frame.registers[T0])
    if (!ends) return JSON.stringify({ depth: mips.getCallDepth(), newestTwo })
    const frames = mips.getCallStack()
    return JSON.stringify({
        depth: mips.getCallDepth(),
        held: frames.length,
        oldest: frames[0]?.registers[T0],
        passes: every ? frames.map(frame => frame.registers[T0]).join() : undefined,
        newestTwo,
    })
}

// 1. Past the limit: the depth counts on, the frames held are the newest, and Undo across pushes
// that each took the oldest frame's slot gives each one back.
{
    const P = 3 * LIMIT
    const mips = core(P, 1)
    const passes = LIMIT + 1000
    assert.equal(await mips.run({ limit: PREFIX + 3 * passes }), StopReason.MAX_STEPS)
    assert.equal(mips.getCallDepth(), passes)
    const frames = mips.getCallStack()
    assert.equal(frames.length, LIMIT, 'the newest 131,072')
    assert.equal(frames.at(-1).registers[T0], P - passes, 'the newest pass')
    assert.equal(frames[0].registers[T0], P - passes + LIMIT - 1, 'and the oldest held')
    assert.equal(mips.getCallStack(10).length, 10)

    // 20 more passes, each pushing the oldest frame out; every frame compared at both ends
    const STEPS = 60
    const ends = i => i % 30 === 0
    const every = i => i === 0 || i === STEPS
    const states = [state(mips, true, true)]
    for (let i = 1; i <= STEPS; i++) {
        await mips.step()
        states.push(state(mips, ends(i), every(i)))
    }
    assert.equal(JSON.parse(states.at(-1)).depth, passes + STEPS / 3)
    assert.equal(JSON.parse(states.at(-1)).held, LIMIT)
    assert.equal(JSON.parse(states.at(-1)).oldest, P - passes + LIMIT - 1 - STEPS / 3)
    for (let i = STEPS; i > 0; i--) {
        mips.undo()
        assert.equal(state(mips, ends(i - 1), every(i - 1)), states[i - 1], `undoing step ${i}`)
    }
    // and forward again
    for (let i = 1; i <= STEPS; i++) {
        await mips.step()
        assert.equal(state(mips, ends(i), every(i)), states[i], `stepping ${i} again`)
    }
}

// 2. Popping past the limit: once the frames held are gone, a return takes only the depth down,
// and Undo counts it back, then puts back the frames held.
{
    const P = LIMIT + 6
    const mips = core(P, LIMIT + 2)
    // the pushes, the arrival at ret and LIMIT - 4 returns
    const pushed = P - 1
    const toLast = PREFIX + 3 * (P - 1) + 2 + 3 * (LIMIT - 4)
    assert.equal(await mips.run({ limit: toLast }), StopReason.MAX_STEPS)
    assert.equal(mips.getCallDepth(), pushed - (LIMIT - 3))
    assert.equal(mips.getCallStack().length, 3)
    const states = [state(mips)]
    while (!mips.terminated) {
        await mips.step()
        states.push(state(mips))
    }
    assert.deepEqual(JSON.parse(states.at(-1)), { depth: pushed - (LIMIT + 2), held: 0, newestTwo: [] },
        'every frame held popped, then two past the limit')
    for (let i = states.length - 1; i > 0; i--) {
        mips.undo()
        assert.equal(state(mips), states[i - 1], `undoing step ${i}`)
    }
}

// 3. Memory stops growing at the limit, with Undo on and off, and the depth counts on without Undo.
v8.setFlagsFromString('--expose-gc')
const gc = vm.runInNewContext('gc')
for (const undo of [true, false]) {
    const mips = core(3 * LIMIT, 1, undo)
    assert.equal(await mips.run({ limit: PREFIX + 3 * (LIMIT + 2000) }), StopReason.MAX_STEPS)
    gc()
    const before = process.memoryUsage().heapUsed
    assert.equal(await mips.run({ limit: 3 * LIMIT }), StopReason.MAX_STEPS)
    gc()
    const grown = process.memoryUsage().heapUsed - before
    assert.equal(mips.getCallDepth(), 2 * LIMIT + 2000)
    assert.equal(mips.getCallStack().length, LIMIT)
    // a frame is some 200 bytes: 131,072 more of them would be over 25 MB
    assert.ok(grown < 8 * 1024 * 1024, `undo ${undo}: ${Math.round(grown / 1024)} KB more after 131,072 more frames`)
}

console.log('call-stack-limit: ok')
