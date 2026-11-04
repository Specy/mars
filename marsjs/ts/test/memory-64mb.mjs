// Static data and the heap share 64 MB from the data segment's base, 0x10000000 to 0x14000000,
// where MARS has 4 MB: a documented superset. Addresses do not move, so a program that fits in
// 4 MB runs as before; one that needs more keeps going, up to the 64 MB, and sbrk past them still
// ends the program. .bss reserves without being emitted, so it is not counted towards the
// assembler's 16 MiB output limit.
import assert from 'node:assert/strict'
import { MIPS, isRuntimeError, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const HEAP = 0x10040000
const MB = 1 << 20

function core(source, options = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.s': source }, 'main.s', options)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.errors.map(error => `${error.sourceLine}: ${error.message}`).join('\n'))
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.initialize(true)
    return mips
}
const errorsOf = (source, options = {}) => MIPS.makeMipsFromFiles({ 'main.s': source }, 'main.s', options)
    .assemble().errors.filter(error => !error.isWarning).map(error => error.message)
const register = (mips, name) => mips.getRegisterValue(name) >>> 0

// 1. 40 MB from sbrk, ten blocks of 4 MB, each written at both ends and read back
{
    const mips = core([
        '.text',
        'main:   li $s0, 10',
        '        li $s2, 0',
        'block:  li $a0, 4194304',
        '        li $v0, 9',
        '        syscall',
        '        move $s1, $v0',
        '        sw $s0, 0($s1)',
        '        li $t0, 4194300',
        '        addu $t1, $s1, $t0',
        '        sw $s0, 0($t1)',
        '        lw $t2, 0($s1)',
        '        lw $t3, 0($t1)',
        '        addu $s2, $s2, $t2',
        '        addu $s2, $s2, $t3',
        '        addiu $s0, $s0, -1',
        '        bnez $s0, block',
        '        move $a0, $s2',
        '        li $v0, 17',
        '        syscall',
    ].join('\n'))
    await mips.simulateWithLimit(1000)
    assert.equal(mips.terminated, true)
    assert.equal(mips.exitCode, 110, 'every block held both of its words: 2 * (10 + 9 + ... + 1)')
    assert.equal(register(mips, '$s1'), HEAP + 36 * MB, 'the tenth block starts 36 MB above the heap base')
}

// 2. sbrk past the 64 MB still ends the program
{
    const mips = core([
        '.text',
        'main:   li $a0, 62914560',     // 60 MB: fits
        '        li $v0, 9',
        '        syscall',
        '        move $s0, $v0',
        '        li $a0, 8388608',      // 8 MB more: past 0x14000000
        '        li $v0, 9',
        '        syscall',
        '        li $v0, 10',
        '        syscall',
    ].join('\n'))
    let failure
    try {
        await mips.simulateWithLimit(100)
    } catch (error) {
        failure = error
    }
    assert.ok(failure && isRuntimeError(failure), `the second sbrk fails: ${failure}`)
    assert.match(failure.message, /exceeds available heap storage/)
    assert.equal(register(mips, '$s0'), HEAP, 'after the first one succeeded')
}

// 3. 20 MB of .bss in the GNU profile: placed, used, the heap after it, and not counted towards
// the 16 MiB output limit; 17 MB of .data, which is emitted, is
{
    const gnu = { assemblerProfile: 'gnu-compiler-v1' }
    const program = (bss) => [
        '\t.text', '\t.globl\tmain', 'main:',
        '\tlui\t$8,%hi(big)', '\taddiu\t$8,$8,%lo(big)',
        '\tli\t$9,20971516', '\taddu\t$9,$8,$9',
        '\tli\t$10,77', '\tsw\t$10,0($9)',
        '\tlw\t$4,0($9)', '\tli\t$2,17', '\tsyscall',
        ...bss,
    ].join('\n')
    for (const bss of [
        ['\t.bss', '\t.globl\tbig', '\t.p2align\t2', 'big:', '\t.space\t20971520'],
        ['\t.comm\tbig,20971520,4'],
    ]) {
        const mips = core(program(bss), gnu)
        const big = mips.getAddressOfLabel('big')
        assert.ok(mips.getHeapStart() >= big + 20 * MB && mips.getHeapStart() % 4096 === 0, 'the heap starts on the page after it')
        await mips.simulateWithLimit(100)
        assert.equal(mips.exitCode, 77, 'its last word holds what was stored')
    }
    assert.match(errorsOf('\t.data\n\t.space\t17825792\n', gnu).join('\n'), /Section exceeds 16 MiB limit/)
    assert.deepEqual(errorsOf('\t.data\n\t.space\t12582912\n\t.bss\n\t.space\t41943040\n', gnu), [],
        '12 MB emitted and 40 MB reserved fit')
    assert.match(errorsOf('\t.bss\n\t.space\t67043329\n', gnu).join('\n'), /past the end of the data segment at 0x14000000/)
}

// 4. MARS's own dialect reaches the 64 MB too, and a program that fits in 4 MB keeps its addresses
{
    const mips = core([
        '.data',
        'first: .word 1',
        'big:   .space 20971520',
        'last:  .word 5',
        '.text',
        'main:  li $a0, 1048576',
        '       li $v0, 9',
        '       syscall',
        '       move $s0, $v0',
        '       li $a0, 16',
        '       li $v0, 9',
        '       syscall',
        '       move $s1, $v0',
        '       lw $s2, last',
        '       li $v0, 10',
        '       syscall',
    ].join('\n'))
    assert.equal(mips.getAddressOfLabel('first'), 0x10010000, 'static data starts where it did')
    assert.equal(mips.getAddressOfLabel('last'), 0x10010004 + 20 * MB)
    await mips.simulateWithLimit(100)
    assert.equal(register(mips, '$s2'), 5, 'a word past 4 MB holds its value')
    assert.deepEqual([register(mips, '$s0'), register(mips, '$s1')], [HEAP, HEAP + MB],
        "sbrk starts at MARS's heap base, as before")
}

// 5. The data segment ends at 0x14000000: its last word is memory, the next address is not
{
    const mips = core([
        '.text',
        'main:  li $t0, 0x13fffffc',
        '       li $t1, 9',
        '       sw $t1, 0($t0)',
        '       lw $s0, 0($t0)',
        '       li $t0, 0x14000000',
        '       lw $s1, 0($t0)',
    ].join('\n'))
    await assert.rejects(() => mips.simulateWithLimit(100), /address out of range 0x14000000/)
    assert.equal(register(mips, '$s0'), 9)
}

console.log('memory-64mb: ok')
