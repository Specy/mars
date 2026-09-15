// Smoke test for the published artifact: assembles and runs a small MIPS
// program through dist/, so it covers the whole Java -> TeaVM -> TypeScript
// chain rather than just type-checking. Run `npm run build` first.
import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const dist = new URL('../dist/index.mjs', import.meta.url)
if (!existsSync(fileURLToPath(dist))) {
    console.error('dist/index.mjs is missing - run `npm run build` (or `npm run build:all`) first.')
    process.exit(1)
}

const packageExports = await import(dist)
const { MIPS, MIPS_COPROCESSOR0_REGISTER_NUMBERS, makeMipsFromFiles, registerHandlers, unimplementedHandler } = packageExports

const makeSingleFileMips = source => makeMipsFromFiles({ 'main.asm': source }, 'main.asm')

assert.equal('makeMipsFromSource' in packageExports, false, 'the v2 single-source export must be removed')
assert.equal('makeMipsFromSource' in MIPS, false, 'the v2 single-source static factory must be removed')

const SOURCE = `
    .data
msg:    .asciiz "sum = "

    .text
    .globl main
main:
    li   $t0, 0             # accumulator
    li   $t1, 1             # counter
loop:
    add  $t0, $t0, $t1
    addi $t1, $t1, 1
    ble  $t1, 10, loop      # sum 1..10 == 55

    li   $v0, 4             # print_string
    la   $a0, msg
    syscall

    li   $v0, 1             # print_int
    move $a0, $t0
    syscall

    li   $v0, 10            # exit
    syscall
`

const WARNINGS_ONLY_SOURCE = `
    .data
val: .byte 300
    .text
main:
    li $v0, 10
    syscall
`

const REAL_ERROR_SOURCE = `
    .text
main:
    bogus_instruction
`

// Every handler must be registered; the ones this program cannot reach throw
// so an unexpected syscall fails the test instead of silently doing nothing.
const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'askDouble', 'askFloat', 'askInt', 'askString', 'readDouble',
    'readFloat', 'readInt', 'readString', 'readChar', 'logLine', 'log', 'printChar',
    'printDouble', 'printFloat', 'printInt', 'printString', 'sleep', 'time', 'stdIn', 'stdOut',
    'stdErr',
]

const warningProgram = makeSingleFileMips(WARNINGS_ONLY_SOURCE)
const warningsOnly = warningProgram.assemble()
assert.equal(warningsOnly.hasErrors, false, `warnings-only assembly failed: ${warningsOnly.report}`)
assert.equal(warningsOnly.hasWarnings, true, 'warnings-only assembly should report warnings')
assert.ok(warningsOnly.errors.length >= 1, 'warnings-only assembly should include at least one diagnostic')
assert.equal(warningsOnly.errors.every(error => error.isWarning === true), true, 'every warnings-only diagnostic should expose isWarning: true')

warningProgram.initialize(true)
let warningSteps = 0
while (!warningProgram.terminated && warningSteps < 10) {
    await warningProgram.step()
    warningSteps++
}
assert.ok(warningProgram.terminated, 'warnings-only program should remain runnable')

const realErrorProgram = makeSingleFileMips(REAL_ERROR_SOURCE)
const realError = realErrorProgram.assemble()
assert.equal(realError.hasErrors, true, 'invalid assembly should report an error')
assert.ok(realError.errors.some(error => error.isWarning === false), 'invalid assembly should expose isWarning: false')
assert.ok(realErrorProgram.getTokenizedLines().length > 0, 'tokens should remain available after completed tokenization')
assert.throws(() => realErrorProgram.getCompiledStatements(), /not been assembled successfully/)
assert.throws(() => realErrorProgram.initialize(true), /not been assembled successfully/)

const output = []
const mips = makeSingleFileMips(SOURCE)

registerHandlers(mips, {
    ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
    printInt: value => output.push(String(value)),
    printString: value => output.push(value),
    printChar: value => output.push(value),
})

const assembled = mips.assemble()
assert.equal(assembled.hasErrors, false, `assembly failed: ${assembled.report}`)
assert.equal(assembled.hasWarnings, false, `clean assembly produced warnings: ${assembled.report}`)

mips.initialize(true)

// step() resolves on a microtask, and settles later still if an IO handler
// returned a promise, so the loop has to await each instruction.
let steps = 0
while (!mips.terminated && steps < 10_000) {
    await mips.step()
    steps++
}

assert.ok(mips.terminated, `program did not terminate within ${steps} steps`)
assert.equal(output.join(''), 'sum = 55')
assert.equal(mips.getRegisterValue('$t0'), 55)
assert.equal(mips.getRegisterValue('$t1'), 11)
assert.ok(mips.getUndoStack().length > 0, 'undo stack should record executed steps')
assert.ok(MIPS.getInstructionSet().length > 0, 'instruction set should not be empty')

// Multi-file construction: one immutable virtual source tree, rooted at the entry file.
const projectFiles = {
    'src/main.asm': [
        '.include "../shared/macros.asm"',
        '.eqv EXIT_CODE 10',
        '.text',
        '.globl main',
        'main:',
        '    load_magic($t0)',
        '    jal helper',
        '    li $v0, EXIT_CODE',
        '    syscall',
        '.include "./helper.asm"',
        '.include "/shared/padding.asm"',
        '.include "../shared/padding.asm"',
    ].join('\n'),
    'src/helper.asm': [
        '.text',
        'helper:',
        '    addiu $t0, $t0, 1',
        '    jr $ra',
        '    nop',
    ].join('\n'),
    'shared/macros.asm': [
        '.macro load_magic(%register)',
        '    li %register, 0x12345678',
        '.end_macro',
    ].join('\n'),
    'shared/padding.asm': [
        '.text',
        '    nop',
    ].join('\n'),
    'unused.asm': 'bogus_instruction',
}

const project = MIPS.makeMipsFromFiles(projectFiles, 'src/main.asm')
assert.throws(() => project.getTokenizedLines(), /not been assembled/, 'tokens should be unavailable before assembly')

// Construction snapshots the caller's object; edits after this point cannot affect the program.
projectFiles['src/main.asm'] = 'bogus_instruction'
projectFiles['new.asm'] = 'bogus_instruction'

const projectAssembly = project.assemble()
assert.equal(projectAssembly.hasErrors, false, `multi-file assembly failed: ${projectAssembly.report}`)

const tokenizedLines = Array.from(project.getTokenizedLines())
assert.equal(tokenizedLines.some(line => line.source.includes('.include')), false, 'include directives should be replaced')
assert.equal(tokenizedLines.some(line => line.sourcePath === 'unused.asm'), false, 'unused source files should be ignored')

const substitutedLine = tokenizedLines.find(line => line.sourcePath === 'src/main.asm' && line.sourceLine === 8)
assert.equal(substitutedLine.source.trim(), 'li $v0, EXIT_CODE')
assert.equal(substitutedLine.processedSource.trim(), 'li $v0, 10')
assert.ok(substitutedLine.tokens.every(token => !('sourceLine' in token) && !('originalSourceLine' in token)))
assert.ok(substitutedLine.tokens.every(token => Number.isInteger(token.sourceColumn) && token.sourceColumn >= 1))

const macroStatements = Array.from(project.getStatementsAtSourceLocation('src/main.asm', 6))
assert.ok(macroStatements.length >= 2, 'the large li in the macro should expand to multiple machine statements')
assert.ok(macroStatements.every(statement => statement.sourcePath === 'src/main.asm'))
assert.ok(macroStatements.every(statement => statement.sourceLine === 6))
assert.ok(macroStatements.every(statement => statement.source.trim() === 'load_magic($t0)'))
assert.deepEqual(
    macroStatements.map(statement => statement.address),
    [...macroStatements].map(statement => statement.address).sort((a, b) => a - b),
    'source lookup should return machine statements in address order',
)

const repeatedStatements = Array.from(project.getStatementsAtSourceLocation('shared/padding.asm', 2))
assert.equal(repeatedStatements.length, 2, 'each textual inclusion should produce its own machine statement')
assert.deepEqual(Array.from(project.getStatementsAtSourceLocation('missing.asm', 1)), [])
assert.throws(() => project.getStatementsAtSourceLocation('./src/main.asm', 1), /canonical|root-relative/)
assert.throws(() => project.getStatementsAtSourceLocation('src/main.asm', 0), /positive integer/)
assert.throws(() => project.getStatementsAtSourceLocation('src/main.asm', 1.5), /positive integer/)
assert.ok(project.getCompiledStatements().length > macroStatements.length, 'the complete machine program should remain available')
assert.ok(project.getParsedStatements().every(statement => typeof statement.sourcePath === 'string'))

project.initialize(true)
assert.equal(project.getNextStatement().sourcePath, 'src/main.asm')

// A call stack frame names the label it jumped to, wherever that label was declared. A .globl
// label - which is how one file calls into another - is moved out of the local symbol table and
// into the global one at assembly, so a local-only lookup would miss it.
const callStackProgram = makeMipsFromFiles({
    'main.asm': [
        '.include "library.asm"',
        '.text',
        '.globl main',
        'main:',
        '    jal shared',
        '    jal private',
        '    li $v0, 10',
        '    syscall',
        'private:',
        '    jr $ra',
    ].join('\n'),
    'library.asm': [
        '.text',
        '.globl shared',
        'shared:',
        '    jr $ra',
    ].join('\n'),
}, 'main.asm')
assert.equal(callStackProgram.assemble().hasErrors, false)
callStackProgram.initialize(true)

const visitedFrameLabels = []
let callStackSteps = 0
while (!callStackProgram.terminated && callStackSteps < 100) {
    await callStackProgram.step()
    callStackSteps++
    for (const frame of callStackProgram.getCallStack()) {
        const label = callStackProgram.getLabelAtAddress(frame.toAddress)
        if (!visitedFrameLabels.includes(label)) visitedFrameLabels.push(label)
    }
}
assert.deepEqual(visitedFrameLabels, ['shared', 'private'], 'both global and local callees should resolve to a name')
assert.equal(callStackProgram.getLabelAtAddress(0x12345678), null, 'an address with no label is not an error')

assert.throws(
    () => makeMipsFromFiles({ './main.asm': SOURCE }, './main.asm'),
    /canonical|root-relative/,
    'noncanonical source keys should fail construction',
)
assert.throws(
    () => makeMipsFromFiles({ 'main.asm': SOURCE }, 'missing.asm'),
    /not present/,
    'the entry file must exist',
)

const opaquePathAssembly = makeMipsFromFiles({
    'directory with spaces/π.library.asm': '.text\nnop',
}, 'directory with spaces/π.library.asm').assemble()
assert.equal(opaquePathAssembly.hasErrors, false, 'valid source path segments should remain opaque')

const missingIncludeProgram = makeMipsFromFiles({
    'main.asm': '.include "missing.asm"',
}, 'main.asm')
const missingInclude = missingIncludeProgram.assemble()
assert.equal(missingInclude.hasErrors, true)
assert.equal(missingInclude.errors[0].sourcePath, 'main.asm')
assert.equal(missingInclude.errors[0].sourceLine, 1)
assert.ok(missingInclude.errors[0].sourceColumn >= 1)
assert.equal('filename' in missingInclude.errors[0], false)
assert.throws(() => missingIncludeProgram.getTokenizedLines(), /tokenization did not complete/)

const escapingInclude = makeMipsFromFiles({
    'main.asm': '.include "../outside.asm"',
}, 'main.asm').assemble()
assert.equal(escapingInclude.hasErrors, true)
assert.match(escapingInclude.report, /escapes the virtual root/)

const includeCycle = makeMipsFromFiles({
    'entry.asm': '.include "a.asm"',
    'a.asm': '.include "dir/b.asm"',
    'dir/b.asm': '.include "../a.asm"',
}, 'entry.asm').assemble()
assert.equal(includeCycle.hasErrors, true)
assert.match(includeCycle.report, /entry\.asm -> a\.asm -> dir\/b\.asm -> a\.asm/)
assert.equal(includeCycle.errors[0].sourcePath, 'dir/b.asm')

const macroError = makeMipsFromFiles({
    'main.asm': [
        '.include "macros.asm"',
        '.text',
        '.globl main',
        'main:',
        '    bad()',
    ].join('\n'),
    'macros.asm': [
        '.macro bad()',
        '    bogus_instruction',
        '.end_macro',
    ].join('\n'),
}, 'main.asm').assemble()
assert.equal(macroError.hasErrors, true)
const expandedDiagnostic = macroError.errors.find(error => error.macroExpansionTrace.length > 0)
assert.ok(expandedDiagnostic, 'macro diagnostics should expose a structured expansion trace')
assert.equal(expandedDiagnostic.sourcePath, 'macros.asm')
assert.deepEqual(Array.from(expandedDiagnostic.macroExpansionTrace).map(location => ({
    sourcePath: location.sourcePath,
    sourceLine: location.sourceLine,
})), [{ sourcePath: 'main.asm', sourceLine: 5 }])

// Peripherals: the framebuffer range, a memory-mapped register and program time. The program
// stores three words into static data, reads the register word back, sleeps and asks for the time.
const FRAMEBUFFER = 0x10010000
// Deliberately the unsigned form: a memory-mapped address does not fit a positive int, and the
// wrapper has to accept it anyway or an observer registered from it could never match an access.
const REGISTER = 0xffff0000
// What the guest holds, and so what an observer is handed.
const SIGNED_REGISTER = 0xffff0000 | 0

const PERIPHERAL_SOURCE = `
    .text
    .globl main
main:
    li   $t0, 0x10010000
    li   $t1, 0x00ff0012
    sw   $t1, 0($t0)
    sw   $t1, 4($t0)
    sb   $t1, 8($t0)        # a byte store must report length 1

    lui  $t2, 0xffff
    lw   $t3, 0($t2)        # read of the observed register
    li   $t4, 7
    sw   $t4, 0($t2)        # write of the observed register

    li   $v0, 32            # sleep
    li   $a0, 25
    syscall

    li   $v0, 30            # time
    syscall
    move $s0, $a0

    li   $v0, 10
    syscall
`

const writes = []
const registerReads = []
const registerWrites = []
const slept = []
let clock = 1000

const peripherals = makeSingleFileMips(PERIPHERAL_SOURCE)
registerHandlers(peripherals, {
    ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
    // A virtual clock: sleeping advances it instead of waiting, the scripted-run shape.
    sleep: milliseconds => {
        slept.push(milliseconds)
        clock += milliseconds
    },
    time: () => clock,
})

// Registering before assembling is allowed; notifications start once a program is assembled.
const framebufferHandle = peripherals.addMemoryWriteObserver(
    FRAMEBUFFER,
    FRAMEBUFFER + 8,
    (address, length, value) => writes.push([address, length, value])
)
const registerHandle = peripherals.addMemoryAccessObserver(
    REGISTER,
    (address, value) => registerReads.push([address, value]),
    (address, value) => registerWrites.push([address, value])
)
assert.equal(peripherals.countMemoryObservers(), 2)

const peripheralAssembly = peripherals.assemble()
assert.equal(peripheralAssembly.hasErrors, false, `peripheral assembly failed: ${peripheralAssembly.report}`)

// A peripheral preloads its register the way a keyboard would; that write must stay invisible.
peripherals.setPeripheralWord(REGISTER, 0x41)
assert.equal(registerWrites.length, 0, 'setPeripheralWord must not notify the observer')

peripherals.initialize(true)
let peripheralSteps = 0
while (!peripherals.terminated && peripheralSteps < 10_000) {
    await peripherals.step()
    peripheralSteps++
}
assert.ok(peripherals.terminated, 'peripheral program did not terminate')

assert.deepEqual(writes, [
    [FRAMEBUFFER, 4, 0x00ff0012],
    [FRAMEBUFFER + 4, 4, 0x00ff0012],
    // A byte store reports only the byte it wrote, not the whole register.
    [FRAMEBUFFER + 8, 1, 0x12],
], 'framebuffer writes should be reported in order, with the width of each store')
assert.deepEqual(registerReads, [[SIGNED_REGISTER, 0x41]], 'the register read should report the preloaded value')
assert.deepEqual(registerWrites, [[SIGNED_REGISTER, 7]], 'the register write should report the stored value')

// readMemoryBytes is host inspection, so it must not look like a program read.
assert.deepEqual(Array.from(peripherals.readMemoryBytes(REGISTER, 1)), [7])
assert.equal(registerReads.length, 1, 'readMemoryBytes must not notify a read observer')

assert.deepEqual(slept, [25], 'syscall 32 should reach the sleep handler with $a0')
assert.equal(peripherals.getRegisterValue('$s0'), 1025, 'syscall 30 should report the handler clock')

// Undo replays the memory restores through the same stores, so observers hear them: an adapter
// that follows the notifications alone still ends up with the rolled-back image.
const writesBeforeUndo = writes.length
while (peripherals.canUndo) {
    peripherals.undo()
}
assert.deepEqual(writes.slice(writesBeforeUndo), [
    [FRAMEBUFFER + 8, 1, 0],
    [FRAMEBUFFER + 4, 4, 0],
    [FRAMEBUFFER, 4, 0],
], 'undo should report each restored word, newest first, as an ordinary write')

// Assembling clears memory but not the registrations, so a rebuilt program is still observed.
const writesBeforeRebuild = writes.length
const reassembled = peripherals.assemble()
assert.equal(reassembled.hasErrors, false, `reassembly failed: ${reassembled.report}`)
peripherals.setPeripheralWord(REGISTER, 0x42)
peripherals.initialize(true)
let rebuiltSteps = 0
while (!peripherals.terminated && rebuiltSteps < 10_000) {
    await peripherals.step()
    rebuiltSteps++
}
assert.deepEqual(writes.slice(writesBeforeRebuild, writesBeforeRebuild + 3), [
    [FRAMEBUFFER, 4, 0x00ff0012],
    [FRAMEBUFFER + 4, 4, 0x00ff0012],
    [FRAMEBUFFER + 8, 1, 0x12],
], 'observers should survive assemble() and initialize()')

peripherals.removeMemoryObserver(framebufferHandle)
assert.equal(peripherals.countMemoryObservers(), 1)
peripherals.removeMemoryObserver(registerHandle)
assert.equal(peripherals.countMemoryObservers(), 0)

// Observers are shared by every instance, so a leftover registration would fire for the next
// program; removing them all is what a fresh build should do.
peripherals.removeMemoryObservers()


// Register files: the FPU (coprocessor 1) registers with their condition flags, and the four
// coprocessor 0 registers. MARS has no li.s/li.d pseudo-instruction, so the constants are loaded
// from .float/.double data with l.s/l.d, which is the same register write.
const FPU_SOURCE = `
    .data
one_half:   .float  1.5
two_fifths: .double 0.4
    .text
    .globl main
main:
    l.s  $f2, one_half      # $f2 = 1.5f
    l.d  $f4, two_fifths    # $f4/$f5 = 0.4 as a double, low word in $f4
    li   $t0, 0x40400000    # 3.0f as a bit pattern
    mtc1 $t0, $f6           # $f6 = 3.0f
    add.s $f8, $f2, $f6     # $f8 = 4.5f
    li   $t1, 0x11223344
    mtc1 $t1, $f11          # a pattern the next instruction overwrites
    cvt.d.s $f10, $f2       # $f10/$f11 = 1.5 as a double
    c.lt.s $f2, $f6         # 1.5 < 3.0, so condition flag 0 becomes 1
    c.lt.s 3, $f6, $f2      # 3.0 < 1.5 is false, so flag 3 becomes 0
    c.lt.s 5, $f2, $f6      # and flag 5 becomes 1
    li   $v0, 10
    syscall
`

const FLOAT_BITS = new DataView(new ArrayBuffer(8))
const singleBits = value => {
    FLOAT_BITS.setFloat32(0, value)
    return FLOAT_BITS.getInt32(0)
}
const doubleWords = value => {
    FLOAT_BITS.setFloat64(0, value)
    // low word first, the order the even/odd register pair holds
    return [FLOAT_BITS.getInt32(4), FLOAT_BITS.getInt32(0)]
}

const fpu = makeSingleFileMips(FPU_SOURCE)
registerHandlers(fpu, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))

const fpuAssembly = fpu.assemble()
assert.equal(fpuAssembly.hasErrors, false, `FPU assembly failed: ${fpuAssembly.report}`)

// Before running, every FPU register reads zero and coprocessor 0 holds its reset values.
fpu.initialize(true)
const initialCoprocessor1 = Array.from(fpu.getCoprocessor1Values())
assert.equal(initialCoprocessor1.length, 32, 'the FPU file is $f0..$f31')
assert.ok(initialCoprocessor1.every(value => value === 0), 'the FPU registers start cleared')

const DEFAULT_STATUS_VALUE = 0x0000ff11
assert.deepEqual(Array.from(MIPS_COPROCESSOR0_REGISTER_NUMBERS), [8, 12, 13, 14])
const initialCoprocessor0 = Array.from(fpu.getCoprocessor0Values())
assert.equal(initialCoprocessor0.length, 4, 'coprocessor 0 implements four registers')
assert.deepEqual(initialCoprocessor0, [0, DEFAULT_STATUS_VALUE, 0, 0], 'status reads its default before any exception')
assert.deepEqual(Array.from(fpu.getConditionFlags()), [0, 0, 0, 0, 0, 0, 0, 0])

// Flag 3 is preset to 1 so that the false compare below has something to clear: 0 is also the
// flag's initial value, so without the preset the post-run expectation could not tell a compare
// that wrote 0 from one that never ran.
fpu.setConditionFlag(3, true)
assert.equal(fpu.getConditionFlags()[3], 1, 'a preset flag reads back before the program runs')

let fpuSteps = 0
while (!fpu.terminated && fpuSteps < 10_000) {
    await fpu.step()
    fpuSteps++
}
assert.ok(fpu.terminated, 'FPU program did not terminate')

const coprocessor1 = Array.from(fpu.getCoprocessor1Values())
assert.equal(coprocessor1[2], 0x3fc00000 | 0, 'l.s of 1.5 gives its single precision bit pattern in $f2')
assert.deepEqual([coprocessor1[4], coprocessor1[5]], doubleWords(0.4), 'l.d fills the even/odd pair, low word first')
assert.equal(coprocessor1[6], singleBits(3.0), 'mtc1 copies the integer register bit pattern into $f6')
assert.equal(coprocessor1[8], singleBits(4.5), 'add.s sums the two single precision registers')
assert.deepEqual([coprocessor1[10], coprocessor1[11]], doubleWords(1.5), 'cvt.d.s widens $f2 into the $f10/$f11 pair')
assert.notEqual(coprocessor1[11], 0x11223344, 'cvt.d.s overwrites the high word of the pair')
assert.deepEqual(Array.from(fpu.getConditionFlags()), [1, 0, 0, 0, 0, 1, 0, 0],
    'each compare wrote the flag it named, the false one clearing the flag preset to 1')

// The Core's own undo restores a coprocessor 1 write: the backstepper records
// COPROC1_REGISTER_RESTORE for every FPU register an instruction touches, and
// COPROC1_CONDITION_SET/CLEAR for every condition flag a compare writes.
const undoUntil = (predicate, message) => {
    let undos = 0
    while (fpu.canUndo && !predicate() && undos < 100) {
        fpu.undo()
        undos++
    }
    assert.ok(predicate(), message)
}

undoUntil(() => fpu.getConditionFlags()[5] === 0, 'undo should roll back the flag the last compare set')
assert.deepEqual(Array.from(fpu.getConditionFlags()), [1, 0, 0, 0, 0, 0, 0, 0], 'the earlier compare result survives')

undoUntil(() => fpu.getConditionFlags()[0] === 0, 'undo should roll back the first compare too')
assert.deepEqual(Array.from(fpu.getConditionFlags()), [0, 0, 0, 1, 0, 0, 0, 0],
    'undoing the false compare puts flag 3 back to the 1 it was preset to')

const beforeUndo = Array.from(fpu.getCoprocessor1Values())
assert.deepEqual([beforeUndo[10], beforeUndo[11]], doubleWords(1.5), 'the widened pair is still there')
undoUntil(() => fpu.getCoprocessor1Values()[11] !== beforeUndo[11], 'undo should restore the pair cvt.d.s wrote')
const afterUndo = Array.from(fpu.getCoprocessor1Values())
assert.deepEqual([afterUndo[10], afterUndo[11]], [0, 0x11223344],
    'undo restores the previous bit pattern of both registers of the pair')
assert.deepEqual(afterUndo.slice(0, 10), beforeUndo.slice(0, 10), 'undo leaves the registers that instruction did not write')

// The setters write the register itself, as a host presetting state, so they round trip through
// the getters and add nothing to the undo stack.
const undoStackBeforeSetters = fpu.getUndoStack().length
assert.ok(undoStackBeforeSetters > 0, 'the executed program should have filled the undo stack')

fpu.setCoprocessor1Value(0, 0x7fffffff | 0)
fpu.setCoprocessor1Value(31, -1)
fpu.setCoprocessor1Value(12, singleBits(-2.25))
const afterSetters = Array.from(fpu.getCoprocessor1Values())
assert.equal(afterSetters[0], 0x7fffffff | 0)
assert.equal(afterSetters[31], -1)
assert.equal(afterSetters[12], singleBits(-2.25))

fpu.setCoprocessor0Value(8, 0x00400020)
fpu.setCoprocessor0Value(12, 0)
fpu.setCoprocessor0Value(13, 0x18)
fpu.setCoprocessor0Value(14, 0x00400000)
assert.deepEqual(Array.from(fpu.getCoprocessor0Values()), [0x00400020, 0, 0x18, 0x00400000])

fpu.setConditionFlag(7, true)
fpu.setConditionFlag(0, true)
fpu.setConditionFlag(0, false)
assert.deepEqual(Array.from(fpu.getConditionFlags()), [0, 0, 0, 1, 0, 0, 0, 1],
    'a flag set directly reads back, and flag 3 still holds what undo restored')

assert.equal(fpu.getUndoStack().length, undoStackBeforeSetters, 'a preset value must not become an undo entry')

assert.throws(() => fpu.setCoprocessor1Value(32, 0), /FPU register index/)
assert.throws(() => fpu.setCoprocessor1Value(-1, 0), /FPU register index/)
assert.throws(() => fpu.setCoprocessor0Value(9, 0), /8, 12, 13 or 14/)
assert.throws(() => fpu.setConditionFlag(8, true), /Condition flag/)
// A fractional index is not an index: TeaVM hands the JS number through untouched, so the range
// check rejects it rather than letting the array access fail with a raw TypeError.
assert.throws(() => fpu.setCoprocessor1Value(1.7, 0), /FPU register index/)
assert.throws(() => fpu.setCoprocessor1Value(Number.NaN, 0), /FPU register index/)

// MARS holds the register files statically, so the next program's initialize() clears them:
// remember the flags now, while they still belong to this run.
const presetFlags = Array.from(fpu.getConditionFlags()).join('')


// Undo restores a coprocessor 0 write too: an address error writes vaddr, status, cause and epc,
// and the backstepper's COPROC0_REGISTER_RESTORE entries put all four back.
const EXCEPTION_SOURCE = `
    .text
    .globl main
main:
    lw $t0, 3($zero)        # unaligned and unmapped, so MARS raises an address error
    li $v0, 10
    syscall
`

const trapping = makeSingleFileMips(EXCEPTION_SOURCE)
registerHandlers(trapping, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
const trappingAssembly = trapping.assemble()
assert.equal(trappingAssembly.hasErrors, false, `exception program assembly failed: ${trappingAssembly.report}`)
trapping.initialize(true)
assert.deepEqual(Array.from(trapping.getCoprocessor0Values()), [0, DEFAULT_STATUS_VALUE, 0, 0])

// With no exception handler installed, MARS reports the address error out of step() itself.
await assert.rejects(() => trapping.step(), /not aligned/, 'the unaligned load should raise an address error')

const trapped = Array.from(trapping.getCoprocessor0Values())
assert.equal(trapped[0], 3, 'vaddr holds the address that faulted')
assert.notEqual(trapped[2], 0, 'cause records the exception')
assert.notEqual(trapped[3], 0, 'epc records the faulting instruction')

assert.ok(trapping.canUndo, 'the exception should leave undo entries behind')
while (trapping.canUndo) {
    trapping.undo()
}
assert.deepEqual(Array.from(trapping.getCoprocessor0Values()), [0, DEFAULT_STATUS_VALUE, 0, 0],
    'undo rolls every coprocessor 0 register back to its pre-exception value')


// Pokes: a register or memory value the host changes between two instructions, recorded in this
// same history as a step of its own and undone by the Core itself. The contract is beginPoke() /
// endPoke() around the setters the package already has; outside a transaction they stay direct,
// which is what presetting a testcase needs.
const POKE_DATA = 0x10010000
const POKE_SOURCE = `
    .data
cell:   .word 0x0a0b0c0d
    .text
    .globl main
main:
    li   $t0, 1
    li   $t1, 2
    jal  helper             # one instruction, two back steps: $ra and the program counter
    li   $v0, 10
    syscall
helper:
    addi $t2, $zero, 7
    jr   $ra
`

const poking = makeSingleFileMips(POKE_SOURCE)
registerHandlers(poking, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
const pokeAssembly = poking.assemble()
assert.equal(pokeAssembly.hasErrors, false, `poke program assembly failed: ${pokeAssembly.report}`)
poking.initialize(true)

const pokeWrites = []
const pokeObserver = poking.addMemoryWriteObserver(POKE_DATA, POKE_DATA,
    (address, length, value) => pokeWrites.push([address, length, value]))

const groupKinds = mips => Array.from(mips.getUndoGroups(), group => group.kind)

await poking.step() // li $t0, 1
await poking.step() // li $t1, 2
await poking.step() // jal helper

// The grouped history is what undo() pops: the jal is one entry, not the two back steps it records.
const afterJal = poking.getUndoGroups()
assert.equal(afterJal.length, 3, 'three executed instructions are three entries')
assert.deepEqual(groupKinds(poking), ['instruction', 'instruction', 'instruction'])
assert.equal(afterJal[0].steps.length, 2, 'jal is one entry made of the $ra restore and the pc restore')
assert.equal(afterJal[0].pc, 0x00400008, 'an instruction entry reports its own address')
assert.deepEqual(Array.from(afterJal[0].writes), [], 'only a poke reports writes')
assert.equal(poking.getUndoStack().length, 4, 'getUndoStack still reports one element per back step')

// 2. Outside a transaction every setter is direct and records nothing, so a preset never becomes
// an entry, and the host write is not reverted with the instruction that ran before it.
const stackBeforePresets = poking.getUndoStack().length
const groupsBeforePresets = poking.getUndoGroups().length
poking.setRegisterValue('$s0', 0x1111)
poking.setCoprocessor1Value(4, 0x2222)
poking.setCoprocessor0Value(13, 0x3333)
poking.setConditionFlag(2, true)
poking.setMemoryBytes(POKE_DATA, [9, 9, 9, 9])
assert.equal(poking.getUndoStack().length, stackBeforePresets, 'a preset must not push a back step')
assert.equal(poking.getUndoGroups().length, groupsBeforePresets, 'a preset must not become an entry')
assert.deepEqual(pokeWrites, [[POKE_DATA, 1, 9], [POKE_DATA + 1, 1, 9], [POKE_DATA + 2, 1, 9], [POKE_DATA + 3, 1, 9]],
    'a direct memory write still notifies observers, which is how a memory mapped display repaints')
poking.undo() // the jal
assert.deepEqual(Array.from(poking.readMemoryBytes(POKE_DATA, 4)), [9, 9, 9, 9],
    'undoing the instruction before it must not revert a host memory write')
assert.equal(poking.getRegisterValue('$s0'), 0x1111, 'nor a host register write')
assert.equal(poking.getUndoGroups().length, 2, 'the jal was one undo, so one entry is gone')
pokeWrites.length = 0

// 1. The transaction API.
assert.equal(poking.pokeOpen(), false)
poking.beginPoke()
assert.equal(poking.pokeOpen(), true)
assert.throws(() => poking.beginPoke(), /already open/, 'a second beginPoke inside a poke throws')
assert.equal(poking.endPoke(), false, 'a poke that wrote nothing records no entry')
assert.equal(poking.pokeOpen(), false)
assert.throws(() => poking.endPoke(), /No poke is open/, 'endPoke outside a poke throws')

// 3. A write that leaves the value unchanged journals nothing.
const groupsBeforeNoop = poking.getUndoGroups().length
poking.beginPoke()
poking.setRegisterValue('$t0', poking.getRegisterValue('$t0'))
poking.setMemoryBytes(POKE_DATA, [9, 9, 9, 9])
poking.setConditionFlag(2, true)
assert.equal(poking.endPoke(), false, 'writing the values already there records nothing')
assert.equal(poking.getUndoGroups().length, groupsBeforeNoop, 'and leaves the history as it was')
assert.deepEqual(pokeWrites, [], 'a byte already holding the value is not even written')

// 4, 5 and 7. One transaction is one entry, at its place in the history, with its own identity:
// never the address of an instruction, so that a host journal keyed by one is not popped by a poke.
const pcBeforePoke = poking.programCounter
const callStackBeforePoke = poking.getCallStack().length
assert.ok(callStackBeforePoke > 0, 'the jal left a frame to check the poke does not disturb')
const t2BeforePoke = poking.getRegisterValue('$t2')

const stackBeforePoke = poking.getUndoStack().length
poking.beginPoke()
poking.setRegisterValue('$t0', 0x1234)
poking.setRegisterValue('$t0', 0x5678) // the same register twice: one write, the first old value
poking.setMemoryBytes(POKE_DATA, [1, 2, 3, 4])
poking.setCoprocessor1Value(2, 0x7f800000 | 0)
poking.setCoprocessor0Value(13, 0x18)
poking.setConditionFlag(6, true)
assert.equal(poking.endPoke(), true, 'a poke that changed something records one entry')

const pokeGroups = poking.getUndoGroups()
assert.equal(pokeGroups.length, groupsBeforeNoop + 1, 'however many writes, a poke is one entry')
assert.equal(poking.getUndoStack().length, stackBeforePoke + 1,
    'and one back step: eight values written cost one slot of the history, not eight')
assert.deepEqual(groupKinds(poking), ['poke', 'instruction', 'instruction'],
    'the entry sits at its place in the same history, newest first')
const pokeGroup = pokeGroups[0]
assert.equal(pokeGroup.kind, 'poke')
assert.equal(pokeGroup.pc, -1, 'a poke belongs to no instruction, so it has no address')
assert.equal(pokeGroup.steps.length, 1,
    'a poke is one back step holding all of its writes, so it takes one slot of the history')
assert.equal(pokeGroup.steps[0].pc, -1, 'which carries no instruction address')
assert.equal(pokeGroup.steps[0].isPoke, true, 'and says it is a poke')
assert.equal(poking.getUndoStack()[0].isPoke, true,
    'the raw stack reports the same discriminator: a poke and a pre-run host write share pc -1')
assert.equal(poking.getUndoStack()[1].isPoke, false, 'an instruction step does not')
assert.notEqual(pokeGroups[1].pc, -1, 'the instruction below it still reports its address')
assert.deepEqual(pokeGroup.writes, [
    // Old values are what the simulator held when each write happened, new values what it held at
    // endPoke: $t0 was written twice and reports the first old value and the last new one.
    { type: 'register', name: '$t0', old: 1, new: 0x5678 },
    { type: 'register', name: '$f2', old: 0, new: 0x7f800000 | 0 },
    { type: 'register', name: '$13 (cause)', old: 0x3333, new: 0x18 },
    { type: 'register', name: 'flag 6', old: 0, new: 1 },
    { type: 'memory', address: POKE_DATA, old: [9, 9, 9, 9], new: [1, 2, 3, 4] },
], 'the entry reports every value it changed, old and new')
// The entries, their back steps and their writes are ordinary objects with own properties, not
// accessors on a class, so a host can clone or serialize a history without mapping it first.
assert.deepEqual(Object.keys(pokeGroup), ['kind', 'pc', 'steps', 'writes'])
assert.deepEqual(Object.keys(pokeGroup.steps[0]), ['action', 'pc', 'param1', 'param2', 'isPoke'])
assert.deepEqual(structuredClone(pokeGroup.writes)[4],
    { type: 'memory', address: POKE_DATA, old: [9, 9, 9, 9], new: [1, 2, 3, 4] },
    'a writes list survives structuredClone with the documented shape')
assert.deepEqual(JSON.parse(JSON.stringify(pokeGroup)).writes[0],
    { type: 'register', name: '$t0', old: 1, new: 0x5678 },
    'and JSON.stringify')
assert.deepEqual(pokeWrites, [[POKE_DATA, 1, 1], [POKE_DATA + 1, 1, 2], [POKE_DATA + 2, 1, 3], [POKE_DATA + 3, 1, 4]],
    'a poked byte is written the way the program writes it, so a display repaints')
assert.equal(poking.canUndo, true, 'a poke on top is something to undo')
pokeWrites.length = 0

// 6. Undoing a poke restores every write and touches nothing else.
poking.undo()
assert.equal(poking.getRegisterValue('$t0'), 1)
assert.deepEqual(Array.from(poking.readMemoryBytes(POKE_DATA, 4)), [9, 9, 9, 9])
assert.equal(poking.getCoprocessor1Values()[2], 0)
assert.equal(poking.getCoprocessor0Values()[2], 0x3333)
assert.equal(poking.getConditionFlags()[6], 0)
assert.equal(poking.programCounter, pcBeforePoke, 'undoing a poke leaves the program counter alone')
assert.equal(poking.getCallStack().length, callStackBeforePoke, 'and the call stack')
assert.equal(poking.getRegisterValue('$t2'), t2BeforePoke, 'and every register it did not write')
assert.deepEqual(pokeWrites, [[POKE_DATA + 3, 1, 9], [POKE_DATA + 2, 1, 9], [POKE_DATA + 1, 1, 9], [POKE_DATA, 1, 9]],
    'the restored bytes are reported like any write, so a display repaints back')
assert.deepEqual(groupKinds(poking), ['instruction', 'instruction'], 'and the entry is gone')
pokeWrites.length = 0

// 8. Two consecutive pokes are two entries, and an instruction between pokes undoes in order.
poking.beginPoke()
poking.setRegisterValue('$t0', 11)
assert.equal(poking.endPoke(), true)
poking.beginPoke()
poking.setRegisterValue('$t0', 12)
assert.equal(poking.endPoke(), true)
assert.deepEqual(groupKinds(poking), ['poke', 'poke', 'instruction', 'instruction'],
    'two pokes are two entries, not one merged group')
poking.undo()
assert.equal(poking.getRegisterValue('$t0'), 11, 'the newer poke alone is reverted')
poking.undo()
assert.equal(poking.getRegisterValue('$t0'), 1, 'and then the older one')

const beforeSequence = {
    t0: poking.getRegisterValue('$t0'),
    t1: poking.getRegisterValue('$t1'),
    pc: poking.programCounter,
    memory: Array.from(poking.readMemoryBytes(POKE_DATA, 4)),
}
poking.beginPoke()
poking.setRegisterValue('$t1', 0x4242)
poking.setMemoryBytes(POKE_DATA + 1, [0xff])
assert.equal(poking.endPoke(), true)
await poking.step() // the jal again, on top of the poke
assert.deepEqual(groupKinds(poking), ['instruction', 'poke', 'instruction', 'instruction'])
poking.undo()
assert.equal(poking.getRegisterValue('$t1'), 0x4242, 'the instruction is reverted first')
assert.equal(poking.programCounter, beforeSequence.pc, 'back to where the poke was made')
poking.undo()
assert.deepEqual({
    t0: poking.getRegisterValue('$t0'),
    t1: poking.getRegisterValue('$t1'),
    pc: poking.programCounter,
    memory: Array.from(poking.readMemoryBytes(POKE_DATA, 4)),
}, beforeSequence, 'and then the poke, leaving exactly the state from before it')

// Addresses that are not adjacent are reported as one write per run, by ascending address.
poking.beginPoke()
poking.setMemoryBytes(POKE_DATA + 3, [0xaa])
poking.setMemoryBytes(POKE_DATA, [0xbb, 0xcc])
assert.equal(poking.endPoke(), true)
assert.deepEqual(poking.getUndoGroups()[0].writes, [
    { type: 'memory', address: POKE_DATA, old: [9, 9], new: [0xbb, 0xcc] },
    { type: 'memory', address: POKE_DATA + 3, old: [9], new: [0xaa] },
], 'each run of consecutive addresses is one write')
poking.undo()
assert.deepEqual(Array.from(poking.readMemoryBytes(POKE_DATA, 4)), [9, 9, 9, 9])

// $zero holds no value, so a poke of it is left alone rather than recorded as something undo
// could not put back.
poking.beginPoke()
poking.setRegisterValue('$zero', 5)
assert.equal(poking.endPoke(), false, 'poking $zero records nothing')
assert.equal(poking.getRegisterValue('$zero'), 0, 'and changes nothing')

// With recording off the writes still stand; they simply cannot be undone, exactly as an
// instruction executed with recording off cannot.
const groupsBeforeDisabled = poking.getUndoGroups().length
poking.setUndoEnabled(false)
poking.beginPoke()
poking.setRegisterValue('$t0', 0x999)
assert.equal(poking.endPoke(), false, 'with undo disabled a poke records no entry')
poking.setUndoEnabled(true)
assert.equal(poking.getRegisterValue('$t0'), 0x999, 'but the write stands')
assert.equal(poking.getUndoGroups().length, groupsBeforeDisabled, 'and the history is untouched')

// A poke is refused while an instruction is executing: step() resolves on a microtask, and the
// simulator's state is half written until it does.
const pendingStep = poking.step()
assert.throws(() => poking.beginPoke(), /while an instruction is executing/,
    'a poke may not open while a step is in flight')
await pendingStep
poking.beginPoke()
poking.endPoke()

poking.removeMemoryObserver(pokeObserver)
poking.removeMemoryObservers()

// 5 and 6, at the edge the rest of this file never reaches: the undo size is a Setting the user
// may set to 3, so a poke has to cost one slot of the ring whatever it wrote. A poke recording one
// slot per value would evict the instructions before it and then be evicted in part itself, which
// leaves an entry that reports writes `undo()` can no longer put back.
const CAPACITY_DATA = 0x10010000
const CAPACITY_SOURCE = `
    .data
cell:   .space 16
    .text
    .globl main
main:
    li   $t0, 1
    li   $t1, 2
    li   $t2, 3
`

const withUndoSize = size => {
    const mips = makeSingleFileMips(CAPACITY_SOURCE)
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    // the ring is allocated by assemble(), so the size has to be set before it
    mips.setUndoSize(size)
    const assembly = mips.assemble()
    assert.equal(assembly.hasErrors, false, `capacity program assembly failed: ${assembly.report}`)
    mips.initialize(true)
    return mips
}

const sized = withUndoSize(8)
await sized.step() // li $t0, 1
await sized.step() // li $t1, 2
sized.beginPoke()
sized.setMemoryBytes(CAPACITY_DATA, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
assert.equal(sized.endPoke(), true)
const sizedSlots = sized.getUndoStack().length
assert.equal(sizedSlots, 3,
    'a twelve byte poke into an eight slot history is one slot, so both instructions survive')
assert.deepEqual(groupKinds(sized), ['poke', 'instruction', 'instruction'])
assert.deepEqual(sized.getUndoGroups()[0].writes, [
    { type: 'memory', address: CAPACITY_DATA, old: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], new: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] },
])
sized.undo()
assert.deepEqual(Array.from(sized.readMemoryBytes(CAPACITY_DATA, 12)), new Array(12).fill(0),
    'and undoing it puts back every byte it reported, not just the ones that fitted')
assert.deepEqual(groupKinds(sized), ['instruction', 'instruction'])
sized.undo()
assert.equal(sized.getRegisterValue('$t1'), 0, 'the instruction under the poke is still undoable')

const tiny = withUndoSize(3)
await tiny.step()
await tiny.step()
tiny.beginPoke()
tiny.setMemoryBytes(CAPACITY_DATA, [1, 2, 3, 4])
assert.equal(tiny.endPoke(), true)
assert.equal(tiny.getUndoStack().length, 3)
assert.deepEqual(groupKinds(tiny), ['poke', 'instruction', 'instruction'])
tiny.undo()
assert.deepEqual(Array.from(tiny.readMemoryBytes(CAPACITY_DATA, 4)), [0, 0, 0, 0])

// A history of one: the poke fits, and pushes exactly one entry off the bottom, as an instruction
// would. What falls off the ring is always a whole entry.
const single = withUndoSize(1)
await single.step()
single.beginPoke()
single.setRegisterValue('$t1', 0x77)
single.setMemoryBytes(CAPACITY_DATA, [1, 2, 3, 4])
assert.equal(single.endPoke(), true)
assert.equal(single.getUndoStack().length, 1)
assert.deepEqual(groupKinds(single), ['poke'])
single.undo()
assert.equal(single.getRegisterValue('$t1'), 0, 'the poke is undone whole')
assert.deepEqual(Array.from(single.readMemoryBytes(CAPACITY_DATA, 4)), [0, 0, 0, 0])
assert.equal(single.canUndo, false, 'and the instruction it evicted is gone, as it would be')

// The in flight guard is this core's own: a step on one core must not refuse a poke on another,
// because a host assembles throwaway cores (the editor checks a source that way).
const stepping = withUndoSize(8)
const other = withUndoSize(8)
const pendingOnOther = other.step()
assert.throws(() => other.beginPoke(), /while an instruction is executing/,
    'the core that is stepping refuses the poke')
stepping.beginPoke()
assert.equal(stepping.endPoke(), false, 'while another core does not')
await pendingOnOther
other.beginPoke()
other.endPoke()

console.log(`ok - ran ${steps} instructions, printed "${output.join('')}"`)
console.log(`ok - peripherals: ${writes.length} observed writes, slept ${slept.join(',')}ms, clock ${clock}`)
console.log(`ok - register files: FPU $f2 ${coprocessor1[2].toString(16)}, flags ${presetFlags}`)
console.log(`ok - pokes: ${groupKinds(poking).length} history entries left, ${pokeWrites.length} observed poke writes`)
console.log(`ok - poke capacity: two instructions and a 12 byte poke fill ${sizedSlots} of 8 slots`)
