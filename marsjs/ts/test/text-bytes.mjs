// The host reads text as the encodings of the statements it holds, little endian as MARS stores
// words, while the program's own loads from text still fault as they do in MARS.
import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'

const make = (files, main, options) => {
    const core = MIPS.makeMipsFromFiles(files, main, options)
    const result = core.assemble()
    assert.equal(result.hasErrors, false, JSON.stringify(result.errors))
    core.initialize(true)
    return core
}
const littleEndian = words => words.flatMap(word => [0, 8, 16, 24].map(shift => (word >>> shift) & 0xff))

{
    const core = make({ 'main.asm': '.text\n.globl main\nmain:\naddi $t0,$zero,5\nli $v0,10\nsyscall' }, 'main.asm')
    const statements = core.getCompiledStatements()
    const start = statements[0].address
    const expected = littleEndian(statements.map(statement => statement.binaryStatement))
    assert.deepEqual(Array.from(core.readMemoryBytes(start, expected.length)), expected)
    assert.deepEqual(Array.from(core.readMemoryBytes(start + 1, 6)), expected.slice(1, 7), 'reads need not be aligned')
    const [textStart, textEnd] = core.getTextSegments()
    assert(textStart >>> 0 <= start && start + expected.length <= textEnd >>> 0, 'the text segment holds the program')
    const end = start + expected.length
    assert.deepEqual(Array.from(core.readMemoryBytes(end, 8)), new Array(8).fill(0), 'text without a statement reads as zero')
}

{
    const core = make({ 'main.asm': '.text\n.globl main\nmain:\nla $t0,main\nlw $t1,0($t0)\nli $v0,10\nsyscall' }, 'main.asm')
    await assert.rejects(async () => { while (!core.terminated) await core.step() }, /text segment/, 'the program still cannot load from text')
}

{
    const core = make({ 'main.asm': '.text\n.globl main\nmain:\nli $v0,10\nsyscall\n.ktext 0x80000180\neret' }, 'main.asm')
    const handler = core.getCompiledStatements().find(statement => statement.address >>> 0 === 0x80000180)
    assert.deepEqual(Array.from(core.readMemoryBytes(0x80000180, 4)), littleEndian([handler.binaryStatement]), 'kernel text reads too')
}

{
    const core = make({ 'main.s': '.text\n.globl main\nmain:\naddiu $t0,$zero,1\njr $ra' }, 'main.s', { assemblerProfile: 'gnu-compiler-v1' })
    const statements = core.getCompiledStatements()
    const expected = littleEndian(statements.map(statement => statement.binaryStatement))
    assert.deepEqual(Array.from(core.readMemoryBytes(statements[0].address, expected.length)), expected, 'a GNU-profile build reads as well')
}
console.log('text bytes passed')
