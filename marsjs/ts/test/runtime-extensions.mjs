import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'
const make = source => {
    const core = MIPS.makeMipsFromFiles({'main.asm': source}, 'main.asm')
    core.setUndoSize(100)
    assert.equal(core.assemble().hasErrors, false, source)
    core.initialize(true)
    core.setUndoEnabled(true)
    return core
}
// Recoverable failure, followed by a successful allocation; reference service 9 remains unchanged.
{
    const core = make(`.text
main:
li $a0, 2147483647
li $v0, 1100
syscall
move $s0, $v0
li $a0, 16
li $v0, 1100
syscall
li $v0, 10
syscall
`.replaceAll('move ', 'move '))
    await core.simulateWithLimit(100)
    assert.equal(core.getRegisterValue('$s0'), -1)
    assert.notEqual(core.getRegisterValue('$v0'), -1)
}
// Extended open flags reach the host intact.
{
    const seen = []
    const core = make(`.data
path: .asciiz "file.txt"
.text
main:
` + [2,3,10].map(flag => `la $a0, path
li $a1, ${flag}
li $v0, 13
syscall
`).join('') + `li $v0, 10
syscall`)
    core.registerHandler('openFile', (path, flags, append) => { seen.push([flags, append]); return 3 })
    await core.simulateWithLimit(100)
    assert.deepEqual(seen, [[2,false],[3,false],[10,true]])
}
// CPU counter follows Undo, rather than the serial allocator.
{
    const core = make('.text\nmain:\nli $v0, 1101\nsyscall\nnop\n')
    await core.simulateWithLimit(2)
    const count = core.getRegisterValue('$a0')
    assert.equal(count, 1)
    core.undo()
    await core.simulateWithLimit(1)
    assert.equal(core.getRegisterValue('$a0'), count)
}
