// GNU compiler v1 for MIPS32: directives, little-endian data, %hi/%lo and branch fixups, delay
// slots, diagnostics, and real GCC 14.2 output checked against GNU as and executed.
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { MIPS } from '../dist/index.mjs'

const directory = new URL('./fixtures/gnu-compiler/', import.meta.url)
const gnu = { assemblerProfile: 'gnu-compiler-v1' }
const TEXT = 0x00400000, DATA = 0x10010000
/** Leaves $v0 in $a0 and exits through syscall 17, whose exit value is $a0. */
const exit = 'move $4,$2\nli $2,17\nsyscall\n'

function coreFor(source, extra = {}, options = gnu) {
    const core = MIPS.makeMipsFromFiles({ 'main.s': source, ...extra }, 'main.s', options)
    core.setUndoSize(4096)
    return core
}
function assemble(source, extra, options) {
    const core = coreFor(source, extra, options)
    const result = core.assemble()
    assert.equal(result.hasErrors, false, result.errors.map(e => `${e.sourcePath}:${e.sourceLine} ${e.message}`).join('\n'))
    core.setUndoEnabled(true)
    return core
}
function reject(source, pattern) {
    const result = coreFor(source).assemble()
    assert.equal(result.hasErrors, true, source)
    assert.match(result.errors.map(e => e.message).join('\n'), pattern, source)
}
const word = (core, address) => (core.getStatementAtAddress(address)?.binaryStatement ?? 0) >>> 0
const words = (core, address, count) => Array.from({ length: count }, (_, index) => word(core, address + 4 * index))
const bytes = (core, address, length) => Array.from(core.readMemoryBytes(address, length))
async function run(core, seeds = {}, limit = 200000) {
    core.initialize(true)
    for (const [name, value] of Object.entries(seeds)) core.setRegisterValue(name, value)
    for (let chunks = 0; chunks < limit / 100 && !core.terminated; chunks++) await core.simulateWithLimit(100)
    assert.equal(core.terminated, true, 'program terminates')
    return core.getRegistersValues()
}

for (const invalid of [undefined, null, '', 'gnu-compiler-v2', 'rars', 1]) {
    assert.throws(() => coreFor('', {}, { assemblerProfile: invalid }), /Unsupported assembler profile/)
}
assert.throws(() => coreFor('', {}, null), /Assembly options/)
assert.deepEqual(MIPS.assemblerProfiles, ['mars', 'gnu-compiler-v1'])

// Layout against GNU as: data directives align themselves, pulling the labels right before them
// along, as .align does; .p2align leaves them, .align 0 turns alignment off until the next section.
{
    const layout = await readFile(new URL('layout.s', directory), 'utf8')
    const oracle = JSON.parse(await readFile(new URL('layout.json', directory), 'utf8'))
    const core = assemble(layout)
    for (const [name, offset] of Object.entries(oracle.dataOffsets)) assert.equal(core.getAddressOfLabel(name), DATA + offset, name)
    assert.equal(core.getAddressOfLabel('common'), DATA + 72, 'common storage follows the data')
    // Each la is GNU as's lui/addiu pair: %hi, then the signed %lo.
    for (const [index, name] of ['a', 'b', 'end', 'common', 'moved', 'kept', 'unaligned', 'half', 'after', 'dbl'].entries()) {
        const address = core.getAddressOfLabel(name)
        assert.deepEqual(words(core, TEXT + 8 * index, 2), [(0x3c020000 | ((address + 0x8000) >>> 16)) >>> 0, (0x24420000 | (address & 0xffff)) >>> 0], name)
    }
    assert.deepEqual(bytes(core, DATA, 9), [1, 0x14, 0, 1, 0x10, 0, 0, 0, 0], 'a: .8byte b+4, unaligned and little-endian')
    assert.deepEqual(bytes(core, DATA + 16, 8), Array(8).fill(255))
    assert.deepEqual(bytes(core, DATA + 24, 8), [0xc3, 0xa9, 0x80, 0xff, 0x22, 0x5c, 0, 0])
    assert.deepEqual(bytes(core, DATA + 32, 4), [16, 0, 0, 0], 'end-b, after end moved down to the word')
    assert.deepEqual(bytes(core, DATA + 49, 4), [6, 0, 0, 0])
    assert.deepEqual(bytes(core, DATA + 53, 4), [7, 8, 0, 9], '.half pads one byte after .byte 7')
    assert.deepEqual(bytes(core, DATA + 64, 8), [0, 0, 0, 0, 0, 0, 0xf8, 0x3f])
    await run(core)
}

// GNU as aligns .word as MARS does; the legacy profile is the default and is unchanged.
{
    const source = '.data\n.byte 1\nword: .word 2\n'
    const core = assemble(source)
    const legacy = assemble(source, {}, {})
    const explicitLegacy = assemble(source, {}, { assemblerProfile: 'mars' })
    assert.equal(core.getAddressOfLabel('word'), DATA + 4)
    assert.equal(legacy.getAddressOfLabel('word'), DATA + 4)
    assert.deepEqual(bytes(core, DATA, 8), [1, 0, 0, 0, 2, 0, 0, 0])
    assert.deepEqual(bytes(legacy, DATA, 8), bytes(explicitLegacy, DATA, 8))
    assert.equal(assemble('.data\n.align 0\n.byte 1\nword: .word 2\n').getAddressOfLabel('word'), DATA + 1)
    // The MARS dialect's register pair macros expand under TeaVM, where a null dereference is no
    // NullPointerException for the template code to catch.
    assert.deepEqual(words(assemble('.text\nmain: mfc1.d $t0,$f2\n', {}, {}), TEXT, 2), [0x44081000, 0x44091800])
}

// Little-endian data, as MARS memory is.
{
    const core = assemble('.data\nh: .half 0x1234, -2\nw: .word 0x11223344\nd: .dword 0x0102030405060708\nf: .float 1.0\ng: .double -2.5\ns: .asciiz "hi"\n.byte 255\n.4byte w\n')
    assert.deepEqual(bytes(core, DATA, 40), [
        0x34, 0x12, 0xfe, 0xff, 0x44, 0x33, 0x22, 0x11, 8, 7, 6, 5, 4, 3, 2, 1,
        0, 0, 0x80, 0x3f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0xc0, 0x68, 0x69, 0, 255, 4, 0, 1, 0x10])
    assert.equal(core.getAddressOfLabel('g'), DATA + 24, '.double aligns to eight bytes')
}

// %hi rounds up for a negative %lo, which the signed immediate subtracts again; zero-extending
// immediates take the low half unsigned.
for (const value of [0x7fff, 0x8000, 0xffff, 0x12345678, 0x1234ffff, -32768, -32769, -1]) {
    const registers = await run(assemble(`.text\n.globl main\nmain: lui $2,%hi(${value})\naddiu $2,$2,%lo(${value})\n${exit}`))
    assert.equal(registers[4], value | 0, String(value))
}
{
    const core = assemble(`.text\n.globl main\nmain: ori $8,$0,%lo(0x18001)\naddiu $9,$0,%lo(0x18001)\naddu $2,$8,$9\n${exit}`)
    assert.deepEqual(words(core, TEXT, 2), [0x34088001, 0x24098001])
    assert.equal((await run(core))[4], 2)
}

// Loads and stores through %lo, la, a bare address reached through $at, and symbol addends.
{
    const core = assemble(`.text\n.globl main\nmain:\nlui $8,%hi(values)\nlw $9,%lo(values+4)($8)\naddiu $9,$9,1\nsw $9,%lo(values)($8)\nla $10,values\nlw $11,0($10)\nlw $12,values\naddu $2,$11,$12\n${exit}.data\nvalues: .word 1, 20\n`)
    assert.equal((await run(core))[4], 42)
    assert.deepEqual(bytes(core, DATA, 4), [21, 0, 0, 0])
}

// Encodings GNU as writes for GCC's macros and the forms MARS spells differently (captured from
// an object disassembly of mips gcc 14.2.0 on Compiler Explorer). The lui of la carries the data
// address an object leaves to the linker; teq loses its code field, which MARS cannot encode.
{
    const core = assemble([
        '.text', '.globl main', 'main:', '.set noreorder',
        'move $2,$4', 'b 1f', 'nop', 'li $3,-2', 'li $3,40000', 'li $3,0x12340000', 'li $3,0x12345678', 'la $5,datum',
        'slt $2,$4,2', 'sltu $2,$4,7', 'div $0,$4,$5', 'teq $5,$0,7', 'neg $2,$3', 'negu $2,$3', 'not $2,$3',
        'c.lt.d $fcc1,$f2,$f6', 'bc1f $fcc1,1f', 'nop', 'jalr $25', 'nop', '1: jr $31', 'nop', '.set reorder',
        '.data', '.byte 1', 'datum: .word 2',
    ].join('\n'))
    const gas = ['00801025', '10000014', '00000000', '2403fffe', '34039c40', '3c031234', '3c031234', '34635678', '3c050000', '24a50004',
        '28820002', '2c820007', '0085001a', '00a001f4', '00031022', '00031023', '00601027', '4626113c', '45040003', '00000000',
        '0320f809', '00000000', '03e00008', '00000000'].map(hex => parseInt(hex, 16))
    gas[8] = 0x3c051001
    gas[13] = 0x00a00034
    assert.deepEqual(words(core, TEXT, gas.length), gas)
    assert.deepEqual(words(assemble('.text\nsll $9,$9,$2\nsrl $8,$8,$3\nsra $2,$4,$7\nsll $2,$4,3\n'), TEXT, 4), [0x00494804, 0x00684006, 0x00e41007, 0x000410c0])
}

// Delay slots: MARS runs without delayed branching, so in noreorder code each slot must hold a
// nop, and a call returns to it; in reorder code the instruction after a branch is no slot at all.
{
    let core = assemble(`.text\n.globl main\n.set noreorder\nmain:\njal f\nnop\n${exit}f:\nmove $5,$31\nli $2,42\njr $31\nnop\n`)
    let registers = await run(core)
    assert.equal(registers[4], 42)
    assert.equal(registers[5], TEXT + 4, 'a link saves the address of the delay slot')
    core = assemble(`.text\n.globl main\nmain: li $2,0\nli $8,3\nloop: addiu $2,$2,7\naddiu $8,$8,-1\nbnez $8,loop\naddiu $2,$2,21\n${exit}`)
    assert.equal((await run(core))[4], 42)
    reject('.text\n.set noreorder\nmain: beq $2,$0,main\naddiu $2,$2,1\n', /Filled branch delay slots are unsupported: addiu/)
    reject('.text\n.set noreorder\nmain: jal main\nmove $4,$2\n', /Filled branch delay slots/)
}

// What GCC writes around its code: options, ABI declarations, function and frame metadata,
// debug sections, which are discarded, and $L aliases, which never hide a function's name.
{
    const core = assemble([
        '.section .mdebug.abi32', '.previous', '.nan legacy', '.module fp=32', '.module oddspreg', '.module arch=mips32',
        '.gnu_attribute 4, 1', '.option pic0', '.text', '$Ltext0:', '.cfi_sections .debug_frame', '.file 1 "main.c"',
        '.set push', '.set noreorder', '.set nomacro', '.set noat', '.set nomips16', '.set nomicromips', '.set arch=mips32', '.set pop',
        '.align 2', '.globl main', '$LFB0 = .', '.loc 1 2 3', '.cfi_startproc', '.ent main', '.type main, @function', 'main:',
        '.frame $sp,0,$31 # vars= 0', '.mask 0x00000000,0', '.fmask 0x00000000,0', 'li $2,42', exit, '.end main', '.cfi_endproc',
        '$LFE0:', '.size main, .-main', '.rdata', '.align 2', 'table: .word main', '.section .rodata.cst8,"aM",@progbits,8', '.align 3',
        '$LC0: .word 0', '.word 1072693248', '.weak shared', '.section .data.shared,"awG",@progbits,shared,comdat', '.align 2',
        '.type shared, @gnu_unique_object', 'shared: .word 3', '.section .debug_info,"",@progbits', '.4byte $LFB0', '.uleb128 0x1', '.ascii "x\\000"',
        '.ident "GCC"', '.section .note.GNU-stack,"",@progbits',
    ].join('\n'))
    assert.equal(core.getLabelAtAddress(TEXT), 'main')
    assert.equal(core.getAddressOfLabel('$LFB0'), TEXT)
    assert.deepEqual(bytes(core, DATA, 4), [0, 0, 0x40, 0])
    assert.equal(core.getAddressOfLabel('$LC0'), DATA + 8)
    assert.equal((await run(core))[4], 42)
}

for (const [source, message] of [
    ['.text\naddiu $2,$0,42; syscall', /Multiple GNU statements/],
    ['.word 42', /data section/],
    ['.data\n.word missing+4', /Unresolved symbol: missing/],
    ['.data\n.word 1<<8', /Unsupported expression/],
    ['.set x,y\n.set y,x', /Cyclic/],
    ['.set x,1\n.set x,2', /Duplicate/],
    ['.data\n.p2align missing', /Unresolved symbol/],
    ['.data\nlabel: .zero label', /Layout expression/],
    ['.set pop', /underflow/],
    ['.set mips16', /Unsupported GNU option/],
    ['.set micromips', /Unsupported GNU option/],
    ['.set mips32r6', /Unsupported GNU option/],
    ['.module fp=64', /floating point register mode/],
    ['.module fp=xx', /floating point register mode/],
    ['.module arch=mips64', /Unsupported module option/],
    ['.gnu_attribute 4, 6', /Unsupported GNU attribute/],
    ['.nan ieee', /Unsupported NaN encoding/],
    ['.option pic2', /Unsupported GNU option/],
    ['.abicalls', /Position-independent code/],
    ['.text\n.cpload $25', /Position-independent code/],
    ['.section .tdata,"awT",@progbits', /Unsupported section/],
    ['.section .init_array.first,"aw"', /Unsupported section/],
    ['.section .text.foo,"axG",@progbits,foo', /Only COMDAT/],
    ['.section .text.foo,"axG",@progbits', /flags/],
    ['.bogus 0', /Unsupported GNU directive: \.bogus/],
    ['.text\nlw $2,%got(x)($28)\n.data\nx: .word 0', /Unsupported address modifier: %got/],
    ['.text\naddiu $2,$2,%hi(x)\n.data\nx: .word 0', /wrong operand format/],
    ['.text\nlui $2,%lo(x)\n.data\nx: .word 0', /wrong operand format/],
    ['.text\nlw $2,x($3)\n.data\nx: .word 0', /Memory offset does not fit 16 bits/],
    ['.text\ndiv $2,$4,$5', /Unsupported division macro/],
    ['.text\n.set noat\nlw $2,x\n.data\nx: .word 0', /\$at/],
    ['.text\nmain: jal value\n.data\nvalue: .word 0', /not an aligned executable label/],
    ['.text\nmain: beq $2,$0,main+2', /not an aligned executable label/],
    ['.text\nmain: jal missing', /Unresolved symbol: missing/],
    ['.text\nmain: beql $2,$0,main', /Unsupported instruction: beql/],
    ['.text\nmthc1 $2,$f0', /Unsupported instruction: mthc1/],
    ['.text\nmove $2,5', /move requires two registers/],
    ['.text\nli $2,0x100000000', /li value exceeds/],
    ['.text\nla $2,8($sp)', /Unsupported address pseudo/],
    ['.data\n.ascii "\\x"', /Empty hex/],
    ['.data\n.ascii "\\400"', /exceeds one byte/],
    ['.bss\n.word 1', /Nonzero initializer/],
    ['.section .debug_info,"",@progbits\ndebug: .byte 1\n.data\n.word debug', /discarded section/],
    ['.comm a,4\n.comm a,8', /Competing common/],
    ['.comm 123,4', /Invalid common symbol/],
    ['.section .rodata.foo,"aM",@progbits,4\n.section .rodata.foo,"aM",@progbits,8', /Conflicting repeated/],
    ['.data\na:.word 0\n.text\nb:nop\n.data\n.word b-a', /same section/],
    ['.data\n.byte target\ntarget:.byte 0', /overflows/],
    ['.data\nvalue:.word 0\n.dword value-0x10010000', /outside mapped memory/],
]) reject(source, message)

// Separated high and low users, an include with its own numeric labels, source identities, and
// every instruction undone back to the start.
{
    const core = assemble(`.text\n.globl main\nmain:\n.include "inc.s"\n${exit}.data\nvalue: .word 41\n`, {
        'inc.s': '1: lui $8,%hi(value)\nnop\naddiu $9,$8,%lo(value)\nlw $2,%lo(value)($8)\naddiu $2,$2,1\nsw $2,%lo(value)($8)\nb 2f\nli $2,0\n2: nop\n'
    })
    assert.ok(core.getCompiledStatements().some(s => s.sourcePath === 'inc.s' && s.sourceLine === 1))
    core.initialize(true)
    const states = []
    while (!core.terminated && states.length < 30) {
        states.push({ pc: core.programCounter, registers: Array.from(core.getRegistersValues()), memory: bytes(core, DATA, 4) })
        await core.step()
    }
    assert.equal(core.getRegistersValues()[4], 42)
    for (const before of states.reverse()) {
        core.undo()
        assert.equal(core.programCounter, before.pc)
        assert.deepEqual(Array.from(core.getRegistersValues()), before.registers)
        assert.deepEqual(bytes(core, DATA, 4), before.memory)
    }
}

// Code padding is nops that keep the directive's line; an explicit fill is the word it spells,
// which executes as hardware would: zero is a nop, all ones is no instruction.
{
    let core = assemble(`.text\n.globl main\nmain: nop\n.p2align 4\nli $2,42\n${exit}`)
    assert.equal(core.getCompiledStatements().filter(s => s.sourceLine === 4).length, 3, 'nop padding retains directive identity')
    assert.equal((await run(core))[4], 42)
    core = assemble(`.text\n.globl main\nmain: li $2,42\n.p2align 3,0\n${exit}`)
    assert.equal(core.getStatementAtAddress(TEXT + 4).sourceLine, 4)
    assert.equal((await run(core))[4], 42)
    core = assemble('.text\nmain: nop\n.p2align 3,255\n')
    assert.equal(word(core, TEXT + 4), 0xffffffff)
    core.initialize(true)
    await core.step()
    await assert.rejects(() => core.step(), /undefined instruction/)
}

// Global labels reach the Core's symbol table, so starting at main does not need main first.
assert.equal((await run(assemble(`.text\nhelper: li $2,1\njr $31\n.globl main\nmain: li $2,42\n${exit}`)))[4], 42)

// C++ shapes: a constructor in .init_array run through the ld-provided bounds, a weak inline
// function in a COMDAT group with a weak .set alias, and an undefined weak reference (as a
// vtable's __cxa_pure_virtual slot) resolving to zero, started at the entry symbol _start.
{
    const core = assemble([
        '.text', '.globl _start', '_start:',
        'la $16,__init_array_start', 'la $17,__init_array_end',
        '1: beq $16,$17,2f', 'lw $25,0($16)', 'jalr $25', 'addiu $16,$16,4', 'b 1b',
        '2: jal program', exit,
        '.section .text._Z3getv,"axG",@progbits,_Z3getv,comdat', '.weak _Z3getv', '_Z3getv:',
        'la $2,counter', 'lw $2,0($2)', 'jr $31',
        '.set _Z5aliasv,_Z3getv', '.weak _Z5aliasv',
        '.text', '.globl program', 'program:', 'addiu $sp,$sp,-24', 'sw $31,20($sp)',
        'jal _Z5aliasv', 'la $8,slot', 'lw $9,0($8)', 'addu $2,$2,$9',
        'lw $31,20($sp)', 'addiu $sp,$sp,24', 'jr $31',
        'init:', 'la $8,counter', 'li $9,42', 'sw $9,0($8)', 'jr $31',
        '.section .init_array,"aw"', '.align 2', '.word init',
        '.data', '.align 2', 'counter: .word 0', 'slot: .word __cxa_pure_virtual',
        '.weak __cxa_pure_virtual',
    ].join('\n'), {}, { ...gnu, entrySymbol: '_start' })
    assert.equal(core.getAddressOfLabel('_Z5aliasv'), core.getAddressOfLabel('_Z3getv'))
    assert.equal(core.getAddressOfLabel('__init_array_start'), -1, 'ld-provided bounds are not labels')
    assert.equal((await run(core))[4], 42)
}

// Constructors run in priority order, as ld sorts .init_array.NNNNN before the plain section.
{
    const core = assemble([
        '.text', '.globl _start', '_start:', 'li $18,0',
        'la $16,__init_array_start', 'la $17,__init_array_end',
        '1: beq $16,$17,2f', 'lw $25,0($16)', 'jalr $25', 'addiu $16,$16,4', 'b 1b',
        '2: move $4,$18', 'li $2,17', 'syscall',
        'last: li $8,10', 'mul $18,$18,$8', 'addiu $18,$18,3', 'jr $31',
        'first: li $8,10', 'mul $18,$18,$8', 'addiu $18,$18,1', 'jr $31',
        'middle: li $8,10', 'mul $18,$18,$8', 'addiu $18,$18,2', 'jr $31',
        '.section .init_array,"aw"', '.align 2', '.word last',
        '.section .init_array.00300,"aw"', '.align 2', '.word middle',
        '.section .init_array.00200,"aw"', '.align 2', '.word first',
    ].join('\n'), {}, { ...gnu, entrySymbol: '_start' })
    assert.equal((await run(core))[4], 123, 'priority 200, then 300, then the plain section')
}

// A conditional branch beyond its +-128 KiB reach becomes the inverted branch over a j, with
// everything after it laid out again.
{
    const core = assemble(`.text\n.globl main\nmain: li $2,7\nbnez $2,far\nli $2,1\n.p2align 17\nnop\nnop\nfar: li $2,42\n${exit}`)
    assert.equal(core.getAddressOfLabel('far'), TEXT + 0x20008)
    assert.deepEqual(words(core, TEXT + 4, 2), [0x10400001, (0x08000000 | ((TEXT + 0x20008) >>> 2)) >>> 0], 'beq $2,$0 over j far')
    assert.equal((await run(core))[4], 42)
    const alias = assemble('.text\nmain: beq $0,$0,far\nfar = main + 131076')
    assert.deepEqual(words(alias, TEXT, 2), [0x14000001, (0x08000000 | ((TEXT + 131076) >>> 2)) >>> 0], 'bne $0,$0 over j far')
}

// Register-only macros take MARS's fixed templates, a label operand as written; one that needs
// $at is refused after .set noat, and one that needs a constant at expansion is not offered.
{
    const core = assemble([
        '.text', '.globl main', 'main:',
        'li $8,-5', 'abs $9,$8', 'seq $10,$9,$9', 'sge $11,$8,$9', 'li $12,0x80000001', 'rol $13,$12,$10',
        'li $2,0', 'bge $9,$10,1f', 'li $2,100', '1: addu $2,$2,$9', 'addu $2,$2,$10', 'addu $2,$2,$11', 'addu $2,$2,$13',
        'la $14,value', 'ulw $15,($14)', 'addu $2,$2,$15', 'li $8,7', 'mtc1 $8,$f2', 'li $8,9', 'mtc1 $8,$f3', 'mfc1.d $6,$f2',
        exit, '.data', '.byte 0', 'value: .byte 33,0,0,0',
    ].join('\n'))
    const registers = await run(core)
    assert.equal(registers[4], 42)
    assert.deepEqual([registers[6], registers[7]], [7, 9])
    reject('.text\n.set noat\nabs $2,$3', /abs needs \$at/)
    reject('.text\nsne $2,$3,5', /Unsupported pseudo instruction: sne/)
}

// Spaces inside an operand are not significant to GNU as.
assert.equal((await run(assemble(`.text\n.globl main\nmain: addiu $sp,$sp,-16\nli $8,42\nsw $8, 8 ( $sp )\nlw $2, % lo ( 8 ) ( $sp )\naddiu $sp,$sp,16\n${exit}`)))[4], 42)

assert.equal((await run(assemble(`.text\n.globl main\nmain: li $2,N\n${exit}.equ N,M+2\n.set M,40\n`)))[4], 42)
// GNU as's li: addiu, ori or lui alone where they suffice, lui then ori otherwise.
for (const [value, count] of [['-2147483648', 1], ['2147483647', 2], ['0xffffffff', 1], ['0x8000', 1], ['-32768', 1], ['65535', 1], ['0x10000', 1], ['0x12345678', 2]]) {
    const core = assemble(`.text\n.globl main\nmain: li $2,${value}\n${exit}`)
    assert.equal(core.getCompiledStatements().filter(s => s.sourceLine === 3).length, count, value)
    assert.equal((await run(core))[4], Number(BigInt.asIntN(32, BigInt(value))), value)
}

// Real GCC 14.2 output (cmipsg1420/mipsg1420, -EL) with the debug sections Compiler Explorer
// returns, started by a small _start that runs .init_array, calls main and exits with syscall 17.
// GCC's helpers such as __divdi3 are not part of it and remain unresolved.
const startup = {
    members: {
        'runtime/crt0.s': [
            '.text', '.globl _start', '_start:', 'li $8,-8', 'and $sp,$sp,$8', 'addiu $sp,$sp,-24',
            'la $8,__init_array_start', 'sw $8,16($sp)',
            '1: lw $8,16($sp)', 'la $9,__init_array_end', 'beq $8,$9,2f', 'lw $25,0($8)', 'addiu $8,$8,4', 'sw $8,16($sp)', 'jalr $25', 'b 1b',
            '2: move $4,$0', 'move $5,$0', 'jal main', exit,
        ].join('\n'),
        'runtime/delete.s': '.text\n.globl _ZdlPvj\n.globl _ZdlPv\n.globl _ZdaPv\n_ZdlPvj:\n_ZdlPv:\n_ZdaPv:\njr $31\n',
        // operator new and new[]: a bump allocator over a static heap.
        'runtime/new.s': [
            '.text', '.globl _Znwj', '.globl _Znaj', '_Znwj:', '_Znaj:', 'la $8,next', 'lw $2,0($8)', 'addiu $4,$4,7', 'li $9,-8',
            'and $4,$4,$9', 'addu $9,$2,$4', 'sw $9,0($8)', 'jr $31', '.data', '.align 2', 'next: .word heap', '.bss', '.align 3', 'heap: .space 4096',
        ].join('\n'),
    },
    index: { _start: 'runtime/crt0.s', _ZdlPvj: 'runtime/delete.s', _ZdlPv: 'runtime/delete.s', _ZdaPv: 'runtime/delete.s', _Znwj: 'runtime/new.s', _Znaj: 'runtime/new.s' },
}
const fixtures = (await readdir(directory)).filter(name => /^gcc-.*\.json$/.test(name)).sort()
assert.ok(fixtures.length >= 8)
for (const filename of fixtures) {
    const fixture = JSON.parse(await readFile(new URL(filename, directory), 'utf8'))
    const source = fixture.response.asm.map(line => line.text).join('\n') + '\n'
    const options = { ...gnu, entrySymbol: '_start', libraries: [startup] }
    if (fixture.expectedDiagnostic) {
        const result = coreFor(source, {}, options).assemble()
        assert.equal(result.hasErrors, true, filename)
        const lines = source.split('\n')
        assert.ok(result.errors.some(e => e.message === fixture.expectedDiagnostic && e.sourcePath === 'main.s' &&
            lines[e.sourceLine - 1].includes('__divdi3')), `${filename}: ${result.errors.map(e => e.message).join('; ')}`)
        continue
    }
    const core = assemble(source, {}, options)
    // GNU as encodes every instruction of every function identically, apart from the fields a
    // relocation fills, which an object leaves to the linker.
    assert.ok(fixture.oracle.functions.length > 0)
    for (const { symbol, words: expected, relocations } of fixture.oracle.functions) {
        const address = core.getAddressOfLabel(symbol)
        assert.ok(address > 0, `${filename}: ${symbol} is defined`)
        const actual = words(core, address, expected.length)
        expected.forEach((hex, index) => {
            const gas = parseInt(hex, 16)
            const relocation = relocations[index]
            let mask = 0xffffffff
            if (relocation?.startsWith('R_MIPS_26')) {
                mask = 0xfc000000
                const target = core.getAddressOfLabel(relocation.split(' ')[1])
                if (target > 0) assert.equal(actual[index] & 0x03ffffff, (target >>> 2) & 0x03ffffff, `${filename}: ${symbol}+${index * 4} ${relocation}`)
            } else if (relocation) mask = 0xffff0000
            // MARS encodes conditional traps without their code field.
            if ((gas >>> 26) === 0 && (gas & 0x3f) >= 0x30 && (gas & 0x3f) <= 0x36) mask &= ~0xffc0
            assert.equal((actual[index] & mask) >>> 0, (gas & mask) >>> 0, `${filename}: ${symbol}+${index * 4}: ${hex}`)
        })
    }
    const saved = [16, 17, 18, 19, 20, 21, 22, 23, 30]
    const registers = await run(core, Object.fromEntries(saved.map((number, index) => [`$${number}`, 100 + index])))
    assert.equal(registers[4], fixture.expected, filename)
    // The startup aligns $sp and reserves the argument area; every function restores both $sp
    // and the callee-saved registers.
    assert.equal(registers[29] >>> 0, 0x7fffefe0, filename)
    for (const [index, number] of saved.entries()) assert.equal(registers[number], 100 + index, `${filename}: $${number}`)
    assert.ok(core.getCompiledStatements().some(s => s.sourcePath === 'runtime/crt0.s'))
    assert.equal(core.getLabelAtAddress(core.getAddressOfLabel('main')), 'main', filename)
}
console.log(`ok - GNU compiler v1 MIPS32: GNU as layout and encodings, little-endian data, %hi/%lo, delay slots, diagnostics, ${fixtures.length} GCC fixtures`)
