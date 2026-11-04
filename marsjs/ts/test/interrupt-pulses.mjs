// Pulses: a device's edge-triggered interrupt request, as MARS's display transmitter makes one each
// time it becomes ready. A pulse is pending until an interrupt takes it, whatever Status says
// meanwhile, and taking it spends it, so eret does not bring it back; the handler finds the
// device's bit in Cause until the next exception or interrupt, or until it writes it as 0. Pulses
// are part of the Undo history: one sent during an instruction (from a write observer, as the
// editor's transmitter sends it) goes back with that instruction, and spending one goes back with
// the interrupt's entry. Last, Undo then Step forward reproduces every state of a run that mixes
// pulses, the timer and calls.
import assert from 'node:assert/strict'
import { MIPS, MIPS_COPROCESSOR0_REGISTER_NUMBERS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const HANDLER = 0x80000180 | 0
const DISPLAY = 0x200, KEYBOARD = 0x100
// the display transmitter's data register, which the program writes a character to
const TRANSMITTER_DATA = 0xffff000c | 0

function core(lines, undoSize = 1000) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': lines.join('\n') }, 'main.asm')
    mips.setUndoSize(undoSize)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report ?? result.errors?.map(e => e.message).join('\n'))
    registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))
    mips.setUndoEnabled(true)
    mips.initialize(true)
    return mips
}
const cp0 = (mips, number) => mips.getCoprocessor0Values()[MIPS_COPROCESSOR0_REGISTER_NUMBERS.indexOf(number)]
const taken = (mips) => Array.from(mips.getInterruptTaken())
const reg = (mips, name) => mips.getRegisterValue(name)
/** The display's pulse, sent from the write observer on its data register, inside the store. */
function transmitter(mips) {
    return mips.addMemoryWriteObserver(TRANSMITTER_DATA, TRANSMITTER_DATA + 4, () => mips.pulseExternalInterrupt(0x80))
}

// A program writing two characters, interrupts enabled, and a handler that counts its visits in
// $s1, keeps the Cause it found in $s2 and returns without clearing anything, as MARS labs do.
const WRITER = [
    '        .text',
    'main:   li $t0, 0xffff000c',
    '        li $t1, 65',
    '        sw $t1, 0($t0)',
    'spin:   addi $s0, $s0, 1',
    '        blt $s0, 6, spin',
    '        sw $t1, 0($t0)',
    'spin2:  addi $s0, $s0, 1',
    '        blt $s0, 12, spin2',
    '        li $v0, 10',
    '        syscall',
    '        .ktext 0x80000180',
    'handler: addi $s1, $s1, 1',
    '        mfc0 $s2, $13',
    '        eret',
]

// 1. One request per pulse: the store's pulse is taken once, before the next instruction, and the
// handler's eret does not bring it back; the second store's pulse is taken once more.
{
    const mips = core(WRITER)
    transmitter(mips)
    let entries = 0
    while (!mips.terminated) {
        await mips.step()
        if (taken(mips).length) {
            entries++
            assert.equal(mips.programCounter, HANDLER)
            assert.equal(taken(mips)[1], DISPLAY, 'the display bit is what was pending')
        }
    }
    assert.equal(entries, 2, 'one interrupt for each of the two characters')
    assert.equal(reg(mips, '$s1'), 2)
    assert.equal(reg(mips, '$s2') & 0x7f7c, DISPLAY, 'the handler found the display bit in Cause, code 0')
    assert.equal(cp0(mips, 13) & DISPLAY, DISPLAY, 'Cause still holds the bit after eret, as in MARS')
}

// 2. A pulse waits while Status keeps it out (interrupts disabled), then is taken once.
{
    const mips = core([
        '        .text',
        'main:   mfc0 $t2, $12',
        '        andi $t3, $t2, 0xfffe',
        '        mtc0 $t3, $12',
        '        addi $s0, $s0, 1',
        '        addi $s0, $s0, 1',
        '        mtc0 $t2, $12',
        'loop:   addi $s0, $s0, 1',
        '        j loop',
        '        .ktext 0x80000180',
        'handler: addi $s1, $s1, 1',
        '        eret',
    ])
    await mips.step(); await mips.step(); await mips.step() // interrupts off
    mips.pulseExternalInterrupt(0x80)
    mips.pulseExternalInterrupt(0x80) // the same request
    await mips.step(); await mips.step()
    assert.deepEqual(taken(mips), [], 'not taken while interrupts are disabled')
    assert.equal(cp0(mips, 13) & DISPLAY, DISPLAY, 'but pending, in Cause')
    await mips.step() // interrupts on again
    await mips.step()
    assert.equal(mips.programCounter, HANDLER, 'taken as soon as Status lets it through')
    await mips.simulateWithLimit(20)
    assert.equal(reg(mips, '$s1'), 1, 'once: two pulses before it was taken are one request')
}

// 3. The bit Cause holds for the handler: cleared by an mtc0 that writes it as 0, and replaced at
// the next interrupt's entry; it asks for nothing itself. clearExternalInterrupt withdraws a
// pending pulse, and initialize clears them.
{
    const mips = core([
        '        .text',
        'main:   addi $s0, $s0, 1',
        '        j main',
        '        .ktext 0x80000180',
        'handler: addi $s1, $s1, 1',
        '        mfc0 $k0, $13',
        '        beq $s1, 1, keep',
        '        mtc0 $zero, $13',
        'keep:   eret',
    ])
    mips.pulseExternalInterrupt(0x80)
    await mips.simulateWithLimit(10)
    assert.equal(reg(mips, '$s1'), 1)
    assert.equal(cp0(mips, 13) & DISPLAY, DISPLAY, 'held after the first visit')
    mips.pulseExternalInterrupt(0x40)
    await mips.simulateWithLimit(10)
    assert.equal(reg(mips, '$s1'), 2)
    assert.equal(cp0(mips, 13) & (DISPLAY | KEYBOARD), 0, 'the second entry held the keyboard bit, which its mtc0 then cleared')
    mips.pulseExternalInterrupt(0x80)
    mips.clearExternalInterrupt(0x80)
    await mips.simulateWithLimit(10)
    assert.equal(reg(mips, '$s1'), 2, 'a withdrawn pulse is never taken')
    mips.pulseExternalInterrupt(0x80)
    mips.initialize(true)
    assert.equal(cp0(mips, 13), 0)
    await mips.simulateWithLimit(5)
    assert.deepEqual(taken(mips), [], 'initialize clears pulses')
}

// 4. Undo: the store that sent the pulse takes it back, and the entry that spent it gives it back,
// so Step forward again takes it at the same place. A pulse sent between two instructions records
// nothing.
{
    const mips = core(WRITER)
    transmitter(mips)
    for (let i = 0; i < 10 && !(cp0(mips, 13) & DISPLAY); i++) await mips.step() // to the sw that sends it
    assert.equal(cp0(mips, 13) & DISPLAY, DISPLAY)
    mips.undo() // the sw
    assert.equal(cp0(mips, 13) & DISPLAY, 0, 'undoing the store withdraws its pulse')
    await mips.step() // the sw again
    await mips.step() // the entry
    assert.equal(mips.programCounter, HANDLER)
    const atHandler = cp0(mips, 13)
    mips.undo() // the entry
    assert.equal(cp0(mips, 13) & DISPLAY, DISPLAY, 'undoing the entry makes the pulse pending again')
    assert.notEqual(mips.programCounter, HANDLER)
    await mips.step()
    assert.equal(mips.programCounter, HANDLER, 'and Step takes it again')
    assert.equal(cp0(mips, 13), atHandler)

    const between = core(WRITER)
    await between.step()
    between.pulseExternalInterrupt(0x80)
    between.undo()
    assert.equal(between.programCounter, between.getAddressOfLabel('main'), 'one undo took back the instruction, not the pulse')
}

// 5. Undo then Step forward reproduces every state of a run mixing pulses from a store, the timer
// re-armed by its handler, nested calls and Count read into memory, with a large history and with
// one so small it evicts all the time.
const MIXED = [
    '        .text',
    'main:   li $t0, 15',
    '        mtc0 $t0, $11',
    '        li $t5, 0xffff000c',
    'loop:   addi $s0, $s0, 1',
    '        jal f',
    '        mfc0 $s3, $9',
    '        sw $s3, buf',
    '        andi $t6, $s0, 7',
    '        bnez $t6, loop',
    '        sw $s0, 0($t5)',
    '        j loop',
    'f:      addiu $sp, $sp, -4',
    '        sw $ra, 0($sp)',
    '        jal g',
    '        lw $ra, 0($sp)',
    '        addiu $sp, $sp, 4',
    '        jr $ra',
    'g:      addi $s2, $s2, 3',
    '        jr $ra',
    '        .data',
    'buf:    .word 0',
    '        .ktext 0x80000180',
    'handler: addi $s1, $s1, 1',
    '        mfc0 $k1, $13',
    '        andi $k1, $k1, 0x8000',
    '        beqz $k1, done',
    '        mfc0 $k0, $9',
    '        addi $k0, $k0, 13',
    '        mtc0 $k0, $11',
    'done:   eret',
]
for (const undoSize of [100000, 37]) {
    const mips = core(MIXED, undoSize)
    transmitter(mips)
    const snap = () => JSON.stringify({
        pc: mips.programCounter,
        regs: Array.from(mips.getRegistersValues()),
        cp0: Array.from(mips.getCoprocessor0Values()),
        stack: mips.getCallStack().map(f => [f.pc, f.toAddress, f.sp, Array.from(f.registers).join()]),
        mem: Array.from(mips.readMemoryBytes(mips.getAddressOfLabel('buf'), 4)),
    })
    const states = [snap()]
    let pulses = 0, timers = 0
    for (let i = 0; i < 3000; i++) {
        await mips.step()
        const entry = taken(mips)
        if (entry.length) {
            if (entry[1] & DISPLAY) pulses++
            if (entry[1] & 0x8000) timers++
        }
        states.push(snap())
    }
    assert.ok(pulses > 10 && timers > 20, `both kinds were taken: ${pulses} pulses, ${timers} timer interrupts`)
    let undone = 0
    for (let i = states.length - 2; i >= 0 && mips.canUndo; i--) {
        mips.undo()
        undone++
        assert.equal(snap(), states[i], `undo ${undone} (history ${undoSize}) restores state ${i}`)
    }
    assert.ok(undoSize < 3000 ? undone > 10 : undone === 3000, `undid ${undone}`)
    for (let i = states.length - 1 - undone + 1; i < states.length; i++) {
        await mips.step()
        assert.equal(snap(), states[i], `step forward to state ${i} (history ${undoSize})`)
    }
}

console.log('interrupt-pulses: ok')
