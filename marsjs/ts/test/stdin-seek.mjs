// Standard input by byte count with end of input, lseek (service 62), and reads of a large history.
import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'

async function run(source, handlers = {}, limit = 200) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    mips.setUndoSize(4096)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.errors.map(e => e.message).join('\n'))
    mips.setUndoEnabled(true)
    mips.initialize(true)
    for (const [name, handler] of Object.entries(handlers)) mips.registerHandler(name, handler)
    for (let steps = 0; steps < limit && !mips.terminated; steps++) await mips.step()
    return mips
}

const answers = [[3, [104, 105, 10]], [0, []]]
let mips = await run(`
.data
buf: .space 8
.text
main:
  li $a0, 0
  la $a1, buf
  li $a2, 8
  li $v0, 14
  syscall
  move $s0, $v0
  li $a0, 0
  la $a1, buf
  li $a2, 8
  li $v0, 14
  syscall
  move $s1, $v0
  li $v0, 10
  syscall
`, { stdIn: async (_buffer, length) => { assert.equal(length, 8); return answers.shift() } })
let registers = mips.getRegistersValues()
assert.equal(registers[16], 3)
assert.equal(registers[17], 0)
assert.deepEqual(Array.from(mips.readMemoryBytes(0x10010000, 3)), [104, 105, 10])

const seeks = []
mips = await run(`
.text
main:
  li $a0, 5
  li $a1, -2
  li $a2, 2
  li $v0, 62
  syscall
  move $s0, $v0
  li $a0, 1
  li $a1, 0
  li $a2, 0
  li $v0, 62
  syscall
  move $s1, $v0
  li $v0, 10
  syscall
`, { seekFile: (fd, offset, whence) => { seeks.push([fd, offset, whence]); return 98 } })
registers = mips.getRegistersValues()
assert.deepEqual(seeks, [[5, -2, 2]])
assert.equal(registers[16], 98)
assert.equal(registers[17], -1)

const looping = MIPS.makeMipsFromFiles({ 'main.asm': '.text\nmain:\nli $t0, 3000\nloop:\naddi $t0, $t0, -1\nbnez $t0, loop\nli $v0, 10\nsyscall\n' }, 'main.asm')
looping.setUndoSize(2_000_000)
const started = performance.now()
assert.equal(looping.assemble().hasErrors, false)
assert.ok(performance.now() - started < 1000, 'assembly does not allocate the whole history')
looping.setUndoEnabled(true)
looping.initialize(true)
let steps = 0
for (; steps < 10000 && !looping.terminated; steps++) await looping.step()
assert.equal(looping.getUndoDepth(), steps)
const all = looping.getUndoGroups()
assert.deepEqual(looping.getUndoGroupsRange(steps - 3, 2).map(g => g.pc), all.slice(steps - 3, steps - 1).map(g => g.pc))
assert.deepEqual(looping.getUndoGroupsUpTo(4).map(g => g.pc), all.slice(0, 4).map(g => g.pc))
for (let i = 0; i < steps; i++) looping.undo()
assert.equal(looping.getUndoDepth(), 0)
console.log('ok - stdin counts and end of input, lseek, lazy history and ranged history reads')
