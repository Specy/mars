// Library members, the entry symbol, MARS-dialect programs linked against GNU members, and the
// unit analysis a library index is built from.
import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'

const members = {
    '@runtime/v1/twice.s': '.text\n.globl twice\ntwice:\nmove $10,$31\njal helper\nsll $2,$2,1\njr $10\n',
    '@runtime/v1/helper.s': '.text\n.globl helper\nhelper:\naddiu $2,$4,1\njr $31\n',
    '@runtime/v1/unused.s': '.text\n.globl unused\nunused:\njr $31\n',
    '@runtime/v1/maybe.s': '.data\n.globl maybe\nmaybe:\n.word 7\n',
    '@runtime/v1/crt0.s': '.text\n.globl _start\n_start:\njal main\nmove $4,$2\nli $2,17\nsyscall\n',
    '@runtime/v1/both.s': '.text\n.globl other\n.globl dup\nother:\njr $31\ndup:\njr $31\n',
}
const library = {
    members,
    index: {
        twice: '@runtime/v1/twice.s', helper: '@runtime/v1/helper.s', unused: '@runtime/v1/unused.s',
        maybe: '@runtime/v1/maybe.s', _start: '@runtime/v1/crt0.s', other: '@runtime/v1/both.s', dup: '@runtime/v1/both.s',
    },
}
const gnu = { assemblerProfile: 'gnu-compiler-v1' }

function core(source, options = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.s': source }, 'main.s', options)
    mips.setUndoSize(4096)
    return mips
}
function assemble(source, options) {
    const mips = core(source, options)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.errors.map(e => `${e.sourcePath}:${e.sourceLine} ${e.message}`).join('\n'))
    mips.setUndoEnabled(true)
    return mips
}
function errors(source, options) {
    const result = core(source, options).assemble()
    assert.equal(result.hasErrors, true, source)
    return result.errors.map(e => e.message).join('\n')
}
/** Runs to the exit syscall and answers the exit value, which syscall 17 takes from $a0. */
async function run(mips, limit = 500) {
    mips.initialize(true)
    for (let steps = 0; steps < limit && !mips.terminated; steps++) await mips.step()
    assert.equal(mips.terminated, true)
    return mips.getRegistersValues()[4]
}
const sourcesOf = mips => new Set(mips.getCompiledStatements().map(s => s.sourcePath))

// A compiled program: _start pulls the startup member, which calls the program's main; main
// pulls twice, which pulls helper in turn. Nothing pulls unused.
const program = '.text\n.globl main\nmain:\nli $4,20\nmove $17,$31\njal twice\nmove $31,$17\njr $31\n'
let mips = assemble(program, { ...gnu, libraries: [library], entrySymbol: '_start' })
assert.deepEqual([...sourcesOf(mips)].sort(), ['@runtime/v1/crt0.s', '@runtime/v1/helper.s', '@runtime/v1/twice.s', 'main.s'])
assert.equal(mips.getAddressOfLabel('main'), 0x00400000, 'user units come first')
assert.ok(mips.getAddressOfLabel('_start') > mips.getAddressOfLabel('main'))
assert.equal(await run(mips), 42, 'execution starts at the entry symbol')

// Without an entry symbol the global main starts, and nothing pulls the startup member.
mips = assemble(program + 'li $2,10\nsyscall\n', { ...gnu, libraries: [library] })
assert.equal(sourcesOf(mips).has('@runtime/v1/crt0.s'), false)

// A user definition comes first; a weak reference pulls nothing and is zero.
mips = assemble('.text\n.globl main\nmain:\nli $4,1\njal helper\nmove $4,$2\nli $2,17\nsyscall\n.globl helper\nhelper:\naddiu $2,$4,41\njr $31\n.data\n.weak maybe\n.word maybe\n',
    { ...gnu, libraries: [library] })
assert.deepEqual([...sourcesOf(mips)], ['main.s'])
assert.deepEqual(Array.from(mips.readMemoryBytes(0x10010000, 4)), [0, 0, 0, 0])
assert.equal(await run(mips), 42)

// A weak reference pulls nothing, unless the library says it always supplies the symbol.
const weakly = '.text\n.globl main\nmain:\njr $31\n.data\n.weak maybe\n.word maybe\n'
mips = assemble(weakly, { ...gnu, libraries: [library] })
assert.deepEqual(Array.from(mips.readMemoryBytes(0x10010000, 4)), [0, 0, 0, 0])
mips = assemble(weakly, { ...gnu, libraries: [{ ...library, resolveWeak: ['maybe', 'notindexed'] }] })
const maybe = mips.getAddressOfLabel('maybe')
assert.ok(maybe > 0x10010000, 'the data member was pulled')
assert.deepEqual(Array.from(mips.readMemoryBytes(0x10010000, 4)), [maybe & 255, (maybe >> 8) & 255, (maybe >> 16) & 255, maybe >>> 24])
assert.throws(() => core('', { libraries: [{ ...library, resolveWeak: [1] }] }), /resolveWeak/)

assert.match(errors('.text\n.globl main\nmain:\njal other\n.globl dup\ndup:\njr $31\n', { ...gnu, libraries: [library] }),
    /Multiple definition of dup, first defined in main\.s/)
assert.match(errors('.text\n.globl main\nmain:\njal nowhere\n', { ...gnu, libraries: [library] }), /Unresolved symbol: nowhere/)
assert.match(errors('.text\n.globl main\nmain:\njr $31\n', { ...gnu, entrySymbol: 'missing' }), /Undefined entry symbol: missing/)
assert.throws(() => core('', { libraries: [{ members: {}, index: { x: 'nope.s' } }] }), /missing member/)
assert.throws(() => core('', { entrySymbol: '1bad' }), /Invalid entry symbol/)
assert.throws(() => core('', { libraries: {} }), /Libraries must be an array/)

// A MARS-dialect program calling the library keeps its own addresses; the members follow.
const manual = '.data\nptr: .word helper\n.text\nmain:\nli $a0, 20\njal twice\nmove $a0, $v0\nli $v0, 17\nsyscall\n'
mips = assemble(manual, { libraries: [library] })
assert.equal(mips.getAddressOfLabel('main'), 0x00400000)
assert.equal(mips.getAddressOfLabel('ptr'), 0x10010000)
const helper = mips.getAddressOfLabel('helper')
assert.ok(helper >= 0x00400014 && mips.getAddressOfLabel('twice') >= 0x00400014, 'members follow the program text')
assert.deepEqual(Array.from(mips.readMemoryBytes(0x10010000, 4)), [helper & 255, (helper >> 8) & 255, (helper >> 16) & 255, helper >>> 24])
assert.equal(await run(mips), 42)

// A program that needs no member assembles exactly as it does without the library.
const plain = '.data\nvalue: .word 5\n.text\nmain:\nli $a0, 1\nla $t0, value\nli $v0, 10\nsyscall\n'
const words = mips => mips.getCompiledStatements().map(s => [s.address, s.binaryStatement])
assert.deepEqual(words(assemble(plain, { libraries: [library] })), words(assemble(plain)))
// A local label of the program satisfies its own reference before any member.
mips = assemble('.text\nmain:\nli $a0, 5\njal helper\nmove $a0, $v0\nli $v0, 17\nsyscall\nhelper:\naddiu $v0, $a0, 37\njr $ra\n', { libraries: [library] })
assert.deepEqual([...sourcesOf(mips)], ['main.s'])
assert.equal(await run(mips), 42)
// A global the program defines is never pulled, and the members that use it get the program's.
mips = assemble('.text\nmain:\nli $a0, 20\njal twice\nmove $a0, $v0\nli $v0, 17\nsyscall\n.globl helper\nhelper:\naddiu $v0, $a0, 2\njr $ra\n', { libraries: [library] })
assert.deepEqual([...sourcesOf(mips)].sort(), ['@runtime/v1/twice.s', 'main.s'])
assert.equal(await run(mips), 44)
// The entry symbol applies to a MARS-dialect program as well: it must be defined, and pulls its member.
mips = assemble('.text\n.globl main\nmain:\nli $v0, 42\njr $ra\n', { libraries: [library], entrySymbol: '_start' })
assert.equal(await run(mips), 42)
assert.match(errors('.text\nmain:\nli $v0, 10\nsyscall\n', { entrySymbol: 'start' }), /Undefined entry symbol: start/)

// Index building: what a unit defines and what it needs from elsewhere. Numbers are not names.
const symbols = MIPS.analyzeGnuUnit('u.s', '.text\n.globl f\n.weak g\nf:\njal h\nla $4,$LC0\nxori $2,$2,0x55\nlw $3,%lo(k+4)($3)\ng:\njr $31\n.data\n$LC0: .word k\n.weak w\n.word w\n')
assert.deepEqual(symbols, { defined: ['f', 'g'], weak: ['g'], references: ['h', 'k'], errors: [] })
assert.match(MIPS.analyzeGnuUnit('u.s', '.bogus\n').errors[0], /^u\.s:1: Unsupported GNU directive/)

console.log('ok - MIPS: library members, weak references the library resolves, entry symbol, MARS-dialect programs, user definitions first, analysis')
