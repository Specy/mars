// The call stack reuses its frames from call to call, so that a call allocates nothing. What a host
// is given must still be its own: frames it holds keep what they said when a later call reuses
// their slot, each frame keeps the registers of its own call, the stack grows past the frames it
// has made so far, and initialize empties it.
import assert from 'node:assert/strict'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const S0 = 16
const DEPTH = 40

const SOURCE = [
    '.text',
    'main:  li $s0, 5',
    '       jal a',
    '       li $s0, 8',
    '       jal b',
    `       li $a0, ${DEPTH}`,
    '       jal r',
    '       li $v0, 10',
    '       syscall',
    'a:     li $s0, 6',
    '       addiu $sp, $sp, -4',
    '       sw $ra, 0($sp)',
    '       jal b',
    '       lw $ra, 0($sp)',
    '       addiu $sp, $sp, 4',
    '       jr $ra',
    'b:     li $s0, 7',
    '       jr $ra',
    'r:     addiu $sp, $sp, -8',
    '       sw $ra, 4($sp)',
    '       beq $a0, $zero, rdone',
    '       addiu $a0, $a0, -1',
    '       jal r',
    'rdone: lw $ra, 4($sp)',
    '       addiu $sp, $sp, 8',
    '       jr $ra',
].join('\n')

const mips = MIPS.makeMipsFromFiles({ 'main.asm': SOURCE }, 'main.asm')
const result = mips.assemble()
assert.equal(result.hasErrors, false, result.report)
registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
mips.initialize(true)
const A = mips.getAddressOfLabel('a')
const B = mips.getAddressOfLabel('b')
const R = mips.getAddressOfLabel('r')
const plain = frames => Array.from(frames, frame => ({ to: frame.toAddress, s0: frame.registers[S0], sp: frame.sp }))

await mips.step() // li $s0, 5
await mips.step() // jal a
const first = mips.getCallStack()
assert.deepEqual(plain(first).map(({ to, s0 }) => ({ to, s0 })), [{ to: A, s0: 5 }], 'the call to a, with the registers at the call')
const firstSp = first[0].sp

for (let i = 0; i < 4; i++) await mips.step() // li, addiu, sw, jal b
const nested = plain(mips.getCallStack())
assert.deepEqual(nested.map(({ to, s0 }) => ({ to, s0 })), [{ to: A, s0: 5 }, { to: B, s0: 6 }], 'a nested call is a second frame')
assert.equal(nested[1].sp, firstSp - 4, 'with the stack pointer at its own call')

while (mips.getCallStack().length > 0) await mips.step() // back out of b and a
await mips.step() // li $s0, 8
await mips.step() // jal b: the first slot again
assert.deepEqual(plain(mips.getCallStack()).map(({ to, s0 }) => ({ to, s0 })), [{ to: B, s0: 8 }])
assert.deepEqual(plain(first).map(({ to, s0 }) => ({ to, s0 })), [{ to: A, s0: 5 }],
    'a frame the host holds is not changed when a later call reuses its slot')

// A recursion deeper than the frames made so far grows the stack, and every return pops one
let deepest = 0
let steps = 0
while (!mips.terminated && steps++ < 10000) {
    await mips.step()
    const frames = mips.getCallStack()
    if (frames.length > deepest) {
        deepest = frames.length
        assert.ok(Array.from(frames).slice(1).every(frame => frame.toAddress === R), 'every frame below the first is a call to r')
    }
}
assert.equal(deepest, DEPTH + 1, 'one frame per call of the recursion')
assert.equal(mips.getCallStack().length, 0, 'every return popped its frame')

// initialize empties it
mips.initialize(true)
await mips.step()
await mips.step()
assert.equal(mips.getCallStack().length, 1)
mips.initialize(true)
assert.equal(mips.getCallStack().length, 0, 'initialize empties the call stack')

console.log('call-stack-frames: ok')
