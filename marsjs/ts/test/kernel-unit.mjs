// An exception handler sits at 0x80000180, where MIPS enters it. A unit holding .ktext joins the
// link as any other unit does, only when the program needs a global it defines: a C program names
// its handler with a .globl of a name it never defines (what <sim.h>'s SIM_LINK writes), as ld's -u
// would. A unit nothing names is never read, so an exercise holding .ktext beside a program, or two
// of them, changes nothing. Two units the link takes that both hold .ktext are an error.
import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'

const gnu = { assemblerProfile: 'gnu-compiler-v1' }
const HANDLER = 0x80000180

function core(files, units) {
    const mips = MIPS.makeMipsFromFiles(files, 'main.c.mips', { ...gnu, units })
    mips.setUndoSize(1000)
    return mips
}
const errorsOf = mips => mips.assemble().errors.filter(error => !error.isWarning)
async function run(mips) {
    const errors = errorsOf(mips)
    assert.deepEqual(errors.map(e => `${e.sourcePath}:${e.sourceLine} ${e.message}`), [])
    mips.initialize(true)
    for (let steps = 0; steps < 2000 && !mips.terminated; steps++) await mips.step()
    assert.equal(mips.terminated, true)
    return mips.exitCode
}

// main breaks, which only a handler gets past; exit2 then ends the program with $2's value
const body = `	.text
	.globl	main
main:
	li	$2,1
	break
	move	$4,$2
	li	$2,17
	syscall
`
// what SIM_LINK(skip_break) writes in a compiled C unit: a file-scope asm naming the handler
const main = `#APP
	.globl	skip_break
#NO_APP
${body}`
// skips the break, counts the visit in a global another unit defines, and sets $2
const handler = `# an exception handler, which the program names with SIM_LINK
	.globl	skip_break
	.ktext
skip_break:
	mfc0	$26,$14
	addiu	$26,$26,4
	mtc0	$26,$14
	lui	$26,%hi(visits)
	lw	$27,%lo(visits)($26)
	addiu	$27,$27,1
	sw	$27,%lo(visits)($26)
	li	$2,42
	eret
`
const state = '\t.data\n\t.globl\tvisits\nvisits:\n\t.word\t0\n'
const unrelated = '\t.text\n\t.globl\tunrelated\nunrelated:\n\tjr\t$31\n'
// a MARS keyboard lab, in MARS's own dialect, whose handler nothing names
const lab = '# a MARS interrupt lab\n\t.text\nloop:\tj loop\n\t.ktext 0x80000180\n\tmfc0 $k0, $13\n\tlw $t0, 0xffff0004\n\teret\n'

// 1. The handler the program names is linked, the global it uses is pulled for it, and the
//    program gets past its break
{
    const mips = core({ 'main.c.mips': main, 'handler.mips': handler, 'state.mips': state, 'other.mips': unrelated },
        ['handler.mips', 'other.mips', 'state.mips'])
    assert.equal(await run(mips), 42, 'the handler ran and skipped the break')
    assert.deepEqual(mips.getLinkedUnits(), ['main.c.mips', 'handler.mips', 'state.mips'],
        'the handler, what it uses, and not the unit nothing needs')
    assert.ok(mips.getCompiledStatements().some(statement => (statement.address >>> 0) === HANDLER && statement.sourcePath === 'handler.mips'),
        'its first instruction is at 0x80000180')
    assert.deepEqual(Array.from(mips.readMemoryBytes(mips.getAddressOfLabel('visits'), 4)), [1, 0, 0, 0], 'it ran once')
}

// 2. A handler nothing names is not part of the program: the break stops it, as without one
{
    const mips = core({ 'main.c.mips': body, 'handler.mips': handler, 'state.mips': state, 'other.mips': unrelated },
        ['handler.mips', 'other.mips', 'state.mips'])
    assert.deepEqual(errorsOf(mips), [])
    assert.deepEqual(mips.getLinkedUnits(), ['main.c.mips'])
    mips.initialize(true)
    await assert.rejects(async () => { for (let steps = 0; steps < 100 && !mips.terminated; steps++) await mips.step() },
        /break|exception/i, 'no handler: the break stops the program')
}

// 3. MARS-dialect labs holding .ktext beside a C program change nothing, however many, and one
//    whose .ktext sits inside a conditional is no handler either
{
    const conditional = '\t.if 0\n\t.ktext\n\teret\n\t.endif\n\t.text\n\t.globl\thelper\nhelper:\tjr $31\n'
    const mips = core({ 'main.c.mips': body, 'lab.asm': lab, 'lab2.asm': lab, 'c.s': conditional }, ['c.s', 'lab.asm', 'lab2.asm'])
    assert.deepEqual(errorsOf(mips), [])
    assert.deepEqual(mips.getLinkedUnits(), ['main.c.mips'])
    assert.deepEqual(mips.getAssembledUnits().filter(path => path !== 'main.c.mips'), [], 'none of them is even read')
}

// 4. A .section .ktext names the same section
{
    const sectioned = handler.replace('\t.ktext\n', '\t.section\t.ktext,"ax",@progbits\n')
    const mips = core({ 'main.c.mips': main, 'handler.mips': sectioned, 'state.mips': state }, ['handler.mips', 'state.mips'])
    assert.equal(await run(mips), 42, 'linked through its .section line too')
}

// 5. Two units the link takes that both hold .ktext: an error at the second one's first kernel
//    instruction, naming both
{
    const second = '# another handler\n\t.globl\tsecond\n\t.ktext\nsecond:\n\teret\n'
    const named = main.replace('.globl\tskip_break', '.globl\tskip_break, second')
    const errors = errorsOf(core({ 'main.c.mips': named, 'a/handler.mips': handler, 'b/second.mips': second, 'state.mips': state },
        ['a/handler.mips', 'b/second.mips', 'state.mips']))
    const clash = errors.find(error => /both hold \.ktext/.test(error.message))
    assert.ok(clash, `the clash is reported: ${errors.map(e => e.message).join('\n')}`)
    assert.equal(clash.sourcePath, 'b/second.mips')
    assert.equal(clash.sourceLine, 5, "on the second unit's first kernel instruction")
    assert.match(clash.message, /^handler\.mips and second\.mips both hold \.ktext, an exception handler, and a program has room for one, at 0x80000180/)
}

// 6. A handler the program names, written in MARS's own dialect, says why it must be GNU syntax
{
    const dialect = '# MARS\'s own handler\n.eqv SKIP 4\n\t.globl skip_break\n.ktext 0x80000180\nskip_break:\n\tmfc0 $k0, $14\n\taddiu $k0, $k0, SKIP\n\tmtc0 $k0, $14\n\teret\n'
    const errors = errorsOf(core({ 'main.c.mips': main, 'handler.asm': dialect }, ['handler.asm']))
    const why = errors.find(error => /is linked into this program because it defines skip_break/.test(error.message))
    assert.ok(why, `the reason is reported: ${errors.map(e => e.message).join('\n')}`)
    assert.equal(why.sourcePath, 'handler.asm')
    assert.equal(why.sourceLine, 3, 'on its .globl line')
    assert.match(why.message, /must be written in GNU syntax too, not in MARS's/)
}

// 7. A name the program declares global and nothing defines is an undefined symbol, reported, as
//    any such name is, at the unit's first line
{
    const errors = errorsOf(core({ 'main.c.mips': main, 'other.mips': unrelated }, ['other.mips']))
    assert.deepEqual(errors.map(e => `${e.sourcePath}:${e.sourceLine} ${e.message}`), ['main.c.mips:1 Unresolved symbol: skip_break'])
}

console.log('kernel-unit: ok')
