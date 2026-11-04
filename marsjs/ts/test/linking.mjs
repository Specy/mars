// A program of several GNU compiler units: the archive the link takes the other units from, which
// reads a unit only once the program needs a global it declares, and the cache that keeps each
// unit's parse by its text.
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { MIPS, StopReason } from '../dist/index.mjs'

const gnu = { assemblerProfile: 'gnu-compiler-v1' }
const exit = 'move $4,$2\nli $2,17\nsyscall\n'

function core(files, entry, options = {}) {
    const mips = MIPS.makeMipsFromFiles(files, entry, { ...gnu, ...options })
    mips.setUndoSize(4096)
    return mips
}
function assemble(files, entry, options) {
    const mips = core(files, entry, options)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.errors.map(e => `${e.sourcePath}:${e.sourceLine} ${e.message}`).join('\n'))
    mips.setUndoEnabled(true)
    return mips
}
function failure(files, entry, options) {
    const result = core(files, entry, options).assemble()
    assert.equal(result.hasErrors, true)
    return result.errors.filter(e => !e.isWarning)
}
async function run(mips, limit = 2000) {
    mips.initialize(true)
    for (let steps = 0; steps < limit && !mips.terminated; steps++) await mips.step()
    assert.equal(mips.terminated, true)
    return mips.exitCode
}
const sourcesOf = mips => [...new Set(mips.getCompiledStatements().map(s => s.sourcePath))]
const lineOf = (text, line) => text.split('\n').findIndex(l => l.trim() === line) + 1

// GCC names its blocks $L2 in every unit and both units have a static counter and a static
// helper, which each unit's code must find as its own. main adds 5 to its counter (100) through its
// helper and calls twice in b, which doubles its argument and adds b's counter (1000) through b's
// helper; b also reads a global `base` main defines and main reads b's global `offset`.
const a = `	.text
	.globl	main
main:
	addiu	$sp,$sp,-24
	sw	$31,20($sp)
	li	$2,5
	jal	helper
	move	$4,$2
	jal	twice
	lui	$3,%hi(offset)
	lw	$3,%lo(offset)($3)
	addu	$2,$2,$3
$L2:
	lw	$31,20($sp)
	addiu	$sp,$sp,24
	${exit.replaceAll('\n', '\n\t')}
helper:
	lui	$3,%hi(counter)
	lw	$3,%lo(counter)($3)
	addu	$2,$2,$3
	jr	$31
	.data
counter:
	.word	100
	.globl	base
base:
	.word	3
`
const b = `	.text
	.globl	twice
twice:
	addiu	$sp,$sp,-24
	sw	$31,20($sp)
	b	$L2
$L2:
	addu	$2,$4,$4
	jal	helper
	lui	$3,%hi(base)
	lw	$3,%lo(base)($3)
	addu	$2,$2,$3
	lw	$31,20($sp)
	addiu	$sp,$sp,24
	jr	$31
helper:
	lui	$3,%hi(counter)
	lw	$3,%lo(counter)($3)
	addu	$2,$2,$3
	j	deep
	.data
counter:
	.word	1000
	.globl	offset
offset:
	.word	20000
`
// Pulled only because b needs it, which a does not know about.
const deep = '\t.text\n\t.globl\tdeep\ndeep:\n\taddiu\t$2,$2,1\n\tjr\t$31\n'
// Nothing refers to these, so they stay out of the program; a's main would clash with this one's.
const unused = '\t.text\n\t.globl\tunused\nunused:\n\tjr\t$31\n\t.globl\tmain\nmain:\n\tjr\t$31\n'
const files = { 'a.c.mips': a, 'b.c.mips': b, 'z/deep.c.mips': deep, 'unused.c.mips': unused }
const units = ['b.c.mips', 'unused.c.mips', 'z/deep.c.mips']
// (5 + 100) * 2 + 1000 + 1 + 3 + 20000
const expected = 21214

let mips = assemble(files, 'a.c.mips', { units })
assert.deepEqual(mips.getLinkedUnits(), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'], 'the entry, then what it needs, in archive order')
assert.deepEqual(sourcesOf(mips), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'], 'the units lie in that order')
assert.deepEqual(mips.getUnitFiles(), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'], 'a unit nothing needs is never read')
assert.equal(await run(mips), expected, 'each unit finds its own $L2, counter and helper')

// Statements, breakpoints, labels and symbols name the unit's own File and line.
const twice = mips.getAddressOfLabel('twice')
assert.equal(mips.getStatementAtAddress(twice).sourcePath, 'b.c.mips')
assert.equal(mips.getStatementAtAddress(twice).sourceLine, lineOf(b, 'addiu\t$sp,$sp,-24'))
const bHelperLine = lineOf(b, 'lui\t$3,%hi(counter)')
const [bHelper] = mips.getStatementsAtSourceLocation('b.c.mips', bHelperLine)
assert.ok(bHelper && bHelper.address > twice, 'b.c.mips has statements of its own')
assert.equal(mips.getLabelAtAddress(bHelper.address), 'helper', "a static function's name, which only its unit knows")
mips.initialize(true)
assert.equal(await mips.simulateWithBreakpoints([bHelper.address]), StopReason.BREAKPOINT)
assert.equal(mips.programCounter, bHelper.address, 'a breakpoint in another unit stops there')
const names = mips.getSymbolNames(), symbolFiles = mips.getSymbolFiles(), values = mips.getSymbolValues()
const counters = names.flatMap((name, i) => name === 'counter' ? [[symbolFiles[i], values[i * 4 + 2], values[i * 4 + 3]]] : [])
assert.deepEqual(counters.sort(), [['a.c.mips', 0, lineOf(a, 'counter:') - 1], ['b.c.mips', 0, lineOf(b, 'counter:') - 1]],
    "both units' counters are listed, in their Files, as the program's own")

// A unit nothing needs is never parsed, so its mistakes, an include of a File that is not there,
// or a whole program in MARS's own dialect, which defines a main of its own, fail nothing.
const exercise = '.eqv EXIT, 10\n.data\nprompt: .asciiz "Number? "\n.text\n.globl main\nmain:\n\tli $v0, EXIT\n\tsyscall\n'
mips = assemble({ ...files, 'unused.c.mips': unused + '\tbogus\t$4\n.include "nowhere.s"\n', 'exercise.asm': exercise }, 'a.c.mips',
    { units: [...units, 'exercise.asm'] })
assert.deepEqual(mips.getLinkedUnits(), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'])
assert.deepEqual(mips.getUnitFiles(), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'])
assert.equal(await run(mips), expected)
// A unit the program needs is parsed as GNU assembly, so one in MARS's dialect fails, its first
// message saying why it is part of the program and what it must be written in, on the line that
// declares the global the program needs.
const marsHelper = '# helpers in MARS syntax\n.eqv STEP, 1\n.text\n.globl deep\ndeep:\n\taddi $v0, $v0, STEP\n\tjr $ra\n'
let errors = failure({ ...files, 'z/deep.c.mips': marsHelper }, 'a.c.mips', { units })
assert.deepEqual({ sourcePath: errors[0].sourcePath, sourceLine: errors[0].sourceLine, sourceColumn: errors[0].sourceColumn, message: errors[0].message }, {
    sourcePath: 'z/deep.c.mips', sourceLine: 4, sourceColumn: 1,
    message: "deep.c.mips is linked into this program because it defines deep, which the program uses. This program is assembled as GNU assembly, the syntax C compiles to, so deep.c.mips must be written in GNU syntax too, not in MARS's.",
})
assert.ok(errors.length > 1 && errors.slice(1).every(e => e.sourcePath === 'z/deep.c.mips'), "the parse's own messages follow")
assert.ok(!errors.some(e => /linked into this program/.test(e.message) && e.sourcePath !== 'z/deep.c.mips'), 'only the unit with mistakes explains itself')
// Need is transitive: a unit pulled only by another pulled unit, which has a mistake, reports it too,
// with the entry's own mistake, all in one assembly.
errors = failure({ ...files, 'a.c.mips': a + '\tbogus\n', 'z/deep.c.mips': deep + '\tbogus\n' }, 'a.c.mips', { units })
assert.deepEqual([...new Set(errors.map(e => e.sourcePath))], ['a.c.mips', 'z/deep.c.mips'])
assert.match(errors.find(e => e.sourcePath === 'z/deep.c.mips').message, /^deep\.c\.mips is linked into this program because it defines deep,/)
// A unit nothing needs that another unit nothing needs includes is read by neither.
mips = assemble({ ...files, 'lone.s': '.include "frag.s"\n', 'frag.s': '.globl twice\ntwice: bogus\n' }, 'a.c.mips',
    { units: [...units, 'frag.s', 'lone.s'] })
assert.deepEqual(mips.getLinkedUnits(), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'], "an included File is no unit, though it declares what b defines")
// Nor is one a unit the program needs includes, which would not assemble on its own: its includer
// is taken for what it declares, though the File sorts first.
mips = assemble({ 'main.s': '.text\n.globl main\nmain:\njal helper\n' + exit, 'a_frag.s': '.globl helper\nhelper: la $2, value\nlw $2, 0($2)\njr $31\n',
    'b_lib.s': '.data\nvalue: .word 40\n.text\n.include "a_frag.s"\n' }, 'main.s', { units: ['a_frag.s', 'b_lib.s'] })
assert.deepEqual(mips.getLinkedUnits(), ['main.s', 'b_lib.s'])
assert.equal(await run(mips), 40)
// A global is found where the parser finds it: after labels and a comment, in a .global, .weak or
// .comm line and through an include; a name only declared, or only defined, pulls nothing.
const declares = {
    'main.s': '.text\n.globl main\nmain:\njal f1\njal f2\nla $3, f3\nlw $2, c4\n' + exit,
    'one.s': '# .globl f2\n.globl f1 # f3\nf1: jr $31\n',
    'two.s': '.text\nx: f2: jr $31 # f2 twice\n.weak f2\n',
    'three.s': '.include "three.inc"\n',
    'three.inc': '.global f3\nf3 = 0x40\n',
    'four.s': '.globl c4\n.comm c4, 4\n',
    'decoy.s': '.globl f1, f2, f3\nlabel: jr $31\nc4: .word 0\n',
}
mips = assemble(declares, 'main.s', { units: ['decoy.s', 'four.s', 'one.s', 'three.s', 'three.inc', 'two.s'] })
assert.deepEqual(mips.getLinkedUnits(), ['main.s', 'four.s', 'one.s', 'three.s', 'two.s'])
// A linked unit's undefined name, and a global two linked units define, name their Files.
errors = failure({ ...files, 'z/deep.c.mips': deep.replace('jr\t$31', 'j\tnowhere') }, 'a.c.mips', { units })
assert.deepEqual([...new Set(errors.map(e => `${e.sourcePath}: ${e.message}`))], ['z/deep.c.mips: Unresolved symbol: nowhere'])
errors = failure({ ...files, 'z/deep.c.mips': deep + '\t.globl\toffset\noffset:\n\tjr\t$31\n' }, 'a.c.mips', { units })
assert.equal(errors.length, 1)
assert.equal(errors[0].sourcePath, 'z/deep.c.mips')
assert.match(errors[0].message, /^Multiple definition of offset, first defined in b\.c\.mips$/)
// Without the units, b's twice is nowhere.
assert.match(failure(files, 'a.c.mips').map(e => e.message).join('\n'), /Unresolved symbol: twice/)

// A unit the entry includes is part of the entry's unit, not a member of its own.
mips = assemble({ 'main.s': '.include "lib.s"\n.text\n.globl main\nmain:\njal seven\n' + exit, 'lib.s': '.text\n.globl seven\nseven:\nli $2,7\njr $31\n' },
    'main.s', { units: ['lib.s'] })
assert.deepEqual(mips.getLinkedUnits(), ['main.s'])
assert.equal(await run(mips), 7)

// The program's own units are searched before the library, except for the entry symbol, which
// the library's start code supplies even though a unit defines a _start too.
const library = {
    members: { '@runtime/v1/crt0.s': '.text\n.globl _start\n_start:\njal main\n' + exit, '@runtime/v1/seven.s': '.text\n.globl seven\nseven:\nli $2,7\njr $31\n' },
    index: { _start: '@runtime/v1/crt0.s', seven: '@runtime/v1/seven.s' },
}
const project = {
    'main.c.mips': '.text\n.globl main\nmain:\naddiu $sp,$sp,-8\nsw $31,4($sp)\njal seven\nlw $31,4($sp)\naddiu $sp,$sp,8\njr $31\n',
    'mine.c.mips': '.text\n.globl seven\nseven:\nli $2,8\njr $31\n',
    'start.c.mips': '.text\n.globl _start\n_start:\nli $2,99\n' + exit,
}
mips = assemble(project, 'main.c.mips', { units: ['mine.c.mips', 'start.c.mips'], libraries: [library], entrySymbol: '_start' })
assert.deepEqual(mips.getLinkedUnits(), ['main.c.mips', 'mine.c.mips'])
assert.deepEqual(sourcesOf(mips), ['main.c.mips', 'mine.c.mips', '@runtime/v1/crt0.s'], "the program's units, then the library's")
assert.equal(await run(mips), 8, "the unit's seven, not the library's, and the library's _start")

// What a core refuses.
assert.throws(() => core({ 'main.s': 'main:\n' }, 'main.s', { assemblerProfile: 'mars', units: ['main.s'] }), /gnu-compiler-v1/)
assert.throws(() => core(files, 'a.c.mips', { units: ['missing.s'] }), /not present/)
assert.throws(() => core(files, 'a.c.mips', { units: ['a.c.mips'] }), /entry file/)
assert.throws(() => core(files, 'a.c.mips', { units: ['b.c.mips', 'b.c.mips'] }), /Duplicate unit/)
assert.throws(() => core(files, 'a.c.mips', { units: 'b.c.mips' }), /array of paths/)

// The cache: a unit is parsed once for its text, and a Build parses again only the units that changed.
MIPS.clearAssemblyCache()
mips = assemble(files, 'a.c.mips', { units })
assert.deepEqual(mips.getAssembledUnits(), ['a.c.mips', 'b.c.mips', 'z/deep.c.mips'], 'a unit nothing needs is not parsed')
mips = assemble(files, 'a.c.mips', { units })
assert.deepEqual(mips.getAssembledUnits(), [], 'nothing changed, nothing is parsed')
assert.equal(await run(mips), expected)

// b changes: only b is parsed, and the program runs b's new text, never the parse of its old one.
const changed = { ...files, 'b.c.mips': b.replace('.word\t1000', '.word\t1100').replace('addu\t$2,$4,$4', 'addu\t$2,$4,$4\n\taddiu\t$2,$2,2') }
mips = assemble(changed, 'a.c.mips', { units })
assert.deepEqual(mips.getAssembledUnits(), ['b.c.mips'])
assert.equal(await run(mips), 21214 + 100 + 2, "b's new counter and instruction")
const shifted = mips.getAddressOfLabel('deep')
// Back to the old text: parsed again, as the cache keeps one text per File, and right again.
mips = assemble(files, 'a.c.mips', { units })
assert.deepEqual(mips.getAssembledUnits(), ['b.c.mips'])
assert.equal(await run(mips), expected)
assert.equal(mips.getAddressOfLabel('deep'), shifted - 4, 'an unchanged unit after it is laid out again')
// The same text at another path is another unit.
mips = assemble({ 'a.c.mips': a, 'b2.c.mips': b, 'z/deep.c.mips': deep }, 'a.c.mips', { units: ['b2.c.mips', 'z/deep.c.mips'] })
assert.deepEqual(mips.getAssembledUnits(), ['b2.c.mips'])
assert.equal(await run(mips), expected)

// A unit whose parse reports something is parsed every time, so its message is never lost.
const broken = { ...files, 'b.c.mips': b + '\tbogus\n' }
for (let i = 0; i < 2; i++) assert.deepEqual(failure(broken, 'a.c.mips', { units }).map(e => [e.sourcePath, /^b\.c\.mips is linked/.test(e.message)]),
    [['b.c.mips', true], ['b.c.mips', false]])
// A unit that includes a File is parsed every time, since the File can change on its own.
const including = { 'main.s': '.include "seven.s"\n.text\n.globl main\nmain:\njal seven\n' + exit, 'seven.s': '.text\n.globl seven\nseven:\nli $2,7\njr $31\n' }
mips = assemble(including, 'main.s')
mips = assemble({ ...including, 'seven.s': including['seven.s'].replace('li $2,7', 'li $2,9') }, 'main.s')
assert.deepEqual(mips.getAssembledUnits(), ['main.s'])
assert.equal(await run(mips), 9)

// A far branch is relaxed by parsing the units again with the branch long, which is kept too.
const far = `.text\n.globl main\nmain: li $2,7\nbnez $2,far\nli $2,1\n.p2align 17\nnop\nnop\nfar: li $2,42\n${exit}`
mips = assemble({ 'main.s': far }, 'main.s')
assert.deepEqual(mips.getAssembledUnits(), ['main.s'])
mips = assemble({ 'main.s': far }, 'main.s')
assert.deepEqual(mips.getAssembledUnits(), [])
assert.equal(await run(mips), 42)

// Library members are units too.
const sevens = { members: { '@runtime/v1/seven.s': '.text\n.globl seven\nseven:\nli $2,7\njr $31\n' }, index: { seven: '@runtime/v1/seven.s' } }
const program = { 'main.s': '.text\n.globl main\nmain:\njal seven\n' + exit }
mips = assemble(program, 'main.s', { libraries: [sevens] })
assert.deepEqual(mips.getAssembledUnits(), ['main.s', '@runtime/v1/seven.s'])
mips = assemble(program, 'main.s', { libraries: [sevens] })
assert.deepEqual(mips.getAssembledUnits(), [])
assert.equal(await run(mips), 7)
// Cleared, everything is parsed again.
MIPS.clearAssemblyCache()
mips = assemble(program, 'main.s', { libraries: [sevens] })
assert.deepEqual(mips.getAssembledUnits(), ['main.s', '@runtime/v1/seven.s'])

// A statement's encoding is kept by its text: compiler output assembled with nothing kept, then
// again with every unit's parse and every encoding kept, gives the same statements, branches and
// jumps included, whose encodings depend on where they are and are never kept.
const fixtures = new URL('./fixtures/gnu-compiler/', import.meta.url)
const statements = core => core.getCompiledStatements().map(s => `${s.address} ${s.binaryStatement} ${s.assemblyStatement} ${s.sourceLine}`)
const programs = []
for (const name of (await readdir(fixtures)).filter(name => /^gcc-c-.*\.json$/.test(name)).sort()) {
    const fixture = JSON.parse(await readFile(new URL(name, fixtures)))
    if (fixture.expectedDiagnostic) continue
    programs.push(fixture.response.asm.map(line => line.text).join('\n') + '\n')
}
assert.ok(programs.length >= 3)
// each its own File, as the cache keeps one text a File
const cold = programs.map((text, index) => {
    MIPS.clearAssemblyCache()
    return statements(assemble({ [`p${index}.s`]: text }, `p${index}.s`))
})
programs.forEach((text, index) => assemble({ [`p${index}.s`]: text }, `p${index}.s`))
programs.forEach((text, index) => {
    const core = assemble({ [`p${index}.s`]: text }, `p${index}.s`)
    assert.deepEqual(core.getAssembledUnits(), [])
    assert.deepEqual(statements(core), cold[index])
})

// A unit whose globals a macro, a repetition or a conditional may make is not read for certain by
// the scan: nothing between such a line and its end counts, and once the rounds leave a name
// undefined each such unit is parsed on its own and taken only if it parses and defines the name.
// This profile parses none of them, so none is ever taken, and none of its mistakes is reported.
{
    const caller = { 'main.s': '.text\n.globl main\nmain:\njal helper\n' + exit }
    const byMacro = '\t.macro FUNC name\n\t.globl \\name\n\\name:\n\t.endm\n\t.text\n\tFUNC helper\n\tjr $31\n'
    const conditional = '\t.if 1\n\t.globl helper\nhelper:\tjr $31\n\t.endif\n'
    const falseConditional = '\t.if 0\n\t.globl helper\nhelper:\tjr $31\n\t.endif\n'
    for (const [name, text] of [['a macro', byMacro], ['a conditional', conditional], ['a false conditional', falseConditional]]) {
        const errors = failure({ ...caller, 'h.s': text }, 'main.s', { units: ['h.s'] })
        assert.deepEqual(errors.map(e => `${e.sourcePath}:${e.sourceLine} ${e.message}`), ['main.s:4 Unresolved symbol: helper'],
            `a global defined through ${name} pulls nothing`)
    }
    // a unit with a macro and a definition outside it is needed for that definition, and says why
    // it must be GNU syntax when it does not parse
    const outside = '\t.macro NOTHING\n\t.endm\n\t.text\n\t.globl helper\nhelper:\tjr $31\n'
    const errors = failure({ ...caller, 'h.s': outside }, 'main.s', { units: ['h.s'] })
    assert.match(errors[0].message, /^h\.s is linked into this program because it defines helper/)
    assert.ok(errors.some(e => /Unsupported GNU directive: \.macro/.test(e.message)))
    // MARS's own .macro ... .end_macro ends its body too, so what follows it is read
    const marsMacro = '.macro done\nli $v0, 10\nsyscall\n.end_macro\n\t.text\n\t.globl helper\nhelper:\tjr $31\n'
    assert.match(failure({ ...caller, 'h.s': marsMacro }, 'main.s', { units: ['h.s'] })[0].message, /because it defines helper/)
    // a name the program declares global and does not define needs a unit, as ld pulls a member for
    // an undefined global: how a C program asks for a unit it never calls
    const asks = { 'main.s': '.globl wanted\n.text\n.globl main\nmain:\nli $2,3\n' + exit }
    const mips = assemble({ ...asks, 'w.s': '.data\n.globl wanted\nwanted: .word 9\n' }, 'main.s', { units: ['w.s'] })
    assert.deepEqual(mips.getLinkedUnits(), ['main.s', 'w.s'])
    assert.equal(await run(mips), 3)
    // .extern declares nothing GNU as keeps, so it needs nothing, as for ld
    const declares = { 'main.s': '.extern wanted\n.text\n.globl main\nmain:\nli $2,3\n' + exit }
    assert.deepEqual(assemble({ ...declares, 'w.s': '.data\n.globl wanted\nwanted: .word 9\n' }, 'main.s', { units: ['w.s'] }).getLinkedUnits(), ['main.s'])
}

console.log('ok - linking: units taken by need, a .globl of an undefined name a need, macro- and conditional-made globals never pulled, found from their declarations before parsing, with their own local names, File identity, never read when unused, a needed one in MARS dialect explained, user units before the library; the cache by text and long branches, encodings by text')
