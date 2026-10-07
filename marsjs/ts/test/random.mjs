// The random services (40 to 44) draw from java.util.Random exactly, as MARS does on Java 21:
// every sequence fixtures/java-numbers/GoldenVectors.java drew on JDK 21 is drawn again through
// the Core, seeded both by service 40 and by the randomSeed handler. A generator starts from the
// handler's seed on its first use, initialize forgets every generator, and Undo puts a generator
// back, so that Undo then Step draws the same number.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { BackStepAction, MIPS, StopReason, isRuntimeError, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const vectors = JSON.parse(await readFile(new URL('./fixtures/java-numbers/vectors.json', import.meta.url), 'utf8'))
assert.match(vectors.java, /^21\./, 'the vectors come from Java 21')
const { sequences } = vectors.random

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr', 'randomSeed',
]

/**
 * A core for `source` whose only handlers are `handlers`; any other handler call fails the run.
 * Handlers are shared by every core of the page, so leaving randomSeed out registers `undefined`,
 * which removes the one a previous core registered.
 */
function core(source, handlers = {}) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    mips.setUndoSize(10_000)
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.report)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        randomSeed: undefined,
        ...handlers,
    })
    mips.initialize(true)
    return mips
}

const hex = (word, digits = 8) => (word >>> 0).toString(16).padStart(digits, '0')
/** The seed as randomSeed hands it over: the low 48 bits of the Java long, which is all Random keeps. */
const handlerSeed = seed => Number(BigInt.asUintN(48, BigInt(seed)))
const fitsInt = seed => BigInt.asIntN(32, BigInt(seed)) === BigInt(seed)

// 1. Every golden sequence, through a table of syscalls: {service, generator, argument} each, and
// the $a0, $f0 and $f1 every syscall left. Each sequence draws from a generator of its own, seeded
// by service 40 in the first batch and by the randomSeed handler in the second.
const TABLE = `
        .data
        .align 3
ops:    .space 60000
results: .space 60000
        .text
main:   la    $s0, ops
        la    $s1, results
loop:   beqz  $s2, done
        lw    $v0, 0($s0)
        lw    $a0, 4($s0)
        lw    $a1, 8($s0)
        syscall
        sw    $a0, 0($s1)
        swc1  $f0, 4($s1)
        swc1  $f1, 8($s1)
        addiu $s0, $s0, 12
        addiu $s1, $s1, 12
        addiu $s2, $s2, -1
        j     loop
done:   li    $v0, 10
        syscall
`

const words = values => values.flatMap(word => [word & 0xff, (word >>> 8) & 0xff, (word >>> 16) & 0xff, (word >>> 24) & 0xff])
const readWords = (mips, address, count) => {
    const bytes = Array.from(mips.readMemoryBytes(address, count * 4))
    return Array.from({ length: count }, (_, i) => (bytes[4 * i] | (bytes[4 * i + 1] << 8) | (bytes[4 * i + 2] << 16) | (bytes[4 * i + 3] << 24)) | 0)
}

/** The draws of a sequence as [service, bound] pairs, with their expected values. */
function drawsOf(sequence) {
    const draws = sequence.draws ?? sequence.values.map(() => [sequence.service, sequence.bound ?? 0])
    return draws.map(([service, bound], index) => ({ service, bound, expected: sequence.values[index] }))
}

async function checkBatch(name, chosen, seedOp) {
    const seedCalls = []
    const mips = core(TABLE, {
        randomSeed: index => {
            seedCalls.push(index)
            return handlerSeed(chosen[index - 1000].seed)
        },
    })
    const ops = []
    const checks = []
    chosen.forEach((sequence, number) => {
        const generator = 1000 + number
        if (seedOp) ops.push([40, generator, Number(BigInt.asIntN(32, BigInt(sequence.seed)))])
        for (const draw of drawsOf(sequence)) {
            checks.push({ op: ops.length, sequence, ...draw })
            ops.push([draw.service, generator, draw.bound])
        }
    })
    assert.ok(ops.length * 12 <= 60000, `${name}: the table fits`)
    mips.setMemoryBytes(mips.getAddressOfLabel('ops'), words(ops.flat()))
    mips.setRegisterValue('$s2', ops.length)
    assert.equal(await mips.simulate(), StopReason.NORMAL_TERMINATION)
    const results = readWords(mips, mips.getAddressOfLabel('results'), ops.length * 3)
    for (const { op, sequence, service, bound, expected } of checks) {
        const [a0, f0, f1] = results.slice(3 * op, 3 * op + 3)
        const label = `${name}: seed ${sequence.seed}, service ${service}${service === 42 ? ` bound ${bound}` : ''}`
        if (service === 41 || service === 42) assert.equal(a0, expected, label)
        else if (service === 43) assert.equal(hex(f0), expected, label)
        else assert.equal(hex(f1) + hex(f0), expected, label)
    }
    assert.deepEqual(seedCalls, seedOp ? [] : chosen.map((_, number) => 1000 + number),
        `${name}: randomSeed is asked once per generator, on its first use, and never after service 40`)
    return checks.length
}

const drawn = await checkBatch('service 40', sequences.filter(sequence => fitsInt(sequence.seed)), true)
    + await checkBatch('randomSeed', sequences, false)

// 2. Undo then Step draws the same number, whatever the services did: a program mixing every
// service, two generators and a reseed is stepped to its exit, undone to its start and stepped
// again, and every step leaves what it left the first time. The handler is asked once per
// generator: an undone first use keeps the generator, in the state it started from.
const MIXED = `
        .text
main:   li    $a0, 1
        li    $v0, 41
        syscall
        li    $a0, 1
        li    $a1, 1000
        li    $v0, 42
        syscall
        li    $a0, 2
        li    $v0, 43
        syscall
        li    $a0, 1
        li    $v0, 44
        syscall
        li    $a0, 2
        li    $a1, 42
        li    $v0, 40
        syscall
        li    $a0, 2
        li    $v0, 41
        syscall
        li    $a0, 1
        li    $a1, 7
        li    $v0, 40
        syscall
        li    $a0, 1
        li    $a1, 0x40000001
        li    $v0, 42
        syscall
        li    $v0, 10
        syscall
`
{
    const seedCalls = []
    const mips = core(MIXED, { randomSeed: index => { seedCalls.push(index); return 99 } })
    const state = () => [mips.getRegisterValue('$a0'), ...Array.from(mips.getCoprocessor1Values()).slice(0, 2)].join(' ')
    const trace = []
    while (!mips.terminated) {
        await mips.step()
        trace.push(state())
    }
    assert.deepEqual(seedCalls, [1, 2], 'one seed per generator, on its first use')
    let undone = 0
    while (mips.canUndo) {
        mips.undo()
        undone++
    }
    assert.equal(undone, trace.length, 'every step undoes on its own')
    const replay = []
    while (!mips.terminated) {
        await mips.step()
        replay.push(state())
    }
    assert.deepEqual(replay, trace, 'Undo then Step draws the same numbers')
    assert.deepEqual(seedCalls, [1, 2], 'an undone first use keeps the generator it started')

    // Each random service's entry records the generator it changed, newest first, and nothing of
    // its state; the exit's entry records the exit.
    const restores = Array.from(mips.getUndoGroups()).flatMap(group =>
        group.steps.filter(step => step.action === BackStepAction.RANDOM_STREAM_RESTORE))
    assert.deepEqual(restores.map(step => step.param1), [1, 1, 2, 2, 1, 2, 1, 1])
    assert.ok(restores.every(step => step.param2 === 0 && step.newValue === 0), 'the state is not reported')
    assert.deepEqual(Array.from(mips.getUndoGroupsUpTo(1))[0].steps.map(step => step.action), [BackStepAction.EXIT_RESTORE])
}

// 3. Undoing service 40 on a generator that did not exist forgets it: the poke turns the seed call
// into a draw, which then starts the generator from the handler's seed rather than from 42.
{
    const seedCalls = []
    const mips = core(`
        .text
main:   li    $a0, 5
        li    $a1, 42
        li    $v0, 40
        syscall
        li    $v0, 41
        syscall
        li    $v0, 10
        syscall
`, { randomSeed: index => { seedCalls.push(index); return 0 } })
    for (let i = 0; i < 6; i++) await mips.step()
    const seeded = vectors.random.sequences.find(s => s.seed === '42' && s.service === 41).values[0]
    assert.equal(mips.getRegisterValue('$a0'), seeded, 'service 40 seeds as new Random(42)')
    mips.undo() // the draw
    mips.undo() // li $v0, 41
    mips.undo() // the seed
    mips.beginPoke()
    mips.setRegisterValue('$v0', 41)
    mips.endPoke()
    await mips.step()
    const unseeded = vectors.random.sequences.find(s => s.seed === '0' && s.service === 41).values[0]
    assert.equal(mips.getRegisterValue('$a0'), unseeded, 'the generator was forgotten, so it starts from the handler seed')
    assert.deepEqual(seedCalls, [5])
}

// 4. initialize forgets every generator, so a new run asks for seeds again and draws the same
// numbers from the same seeds; without a handler, a generator starts from host randomness.
{
    const PAIR = `
        .text
main:   li    $a0, 0
        li    $v0, 41
        syscall
        move  $s0, $a0
        li    $a0, 0
        li    $v0, 41
        syscall
        move  $s1, $a0
        li    $v0, 10
        syscall
`
    let seedCalls = 0
    const mips = core(PAIR, { randomSeed: () => { seedCalls++; return 0 } })
    const run = async () => {
        mips.initialize(true)
        assert.equal(await mips.simulate(), StopReason.NORMAL_TERMINATION)
        return [mips.getRegisterValue('$s0'), mips.getRegisterValue('$s1')]
    }
    const expected = vectors.random.sequences.find(s => s.seed === '0' && s.service === 41).values.slice(0, 2)
    assert.deepEqual(await run(), expected)
    assert.deepEqual(await run(), expected, 'a new run starts the generator again')
    assert.equal(seedCalls, 2)

    const host = core(PAIR)
    const hostRun = async () => {
        host.initialize(true)
        await host.simulate()
        return [host.getRegisterValue('$s0'), host.getRegisterValue('$s1')]
    }
    assert.notDeepEqual(await hostRun(), await hostRun(), 'without randomSeed, each run starts from host randomness')

    // A handler may answer with a promise, like every other handler.
    const later = core(PAIR, { randomSeed: () => new Promise(resolve => setTimeout(() => resolve(0), 1)) })
    await later.simulate()
    assert.deepEqual([later.getRegisterValue('$s0'), later.getRegisterValue('$s1')], expected)
}

// 5. randomSeed must answer a whole number from 0 to 2^48 - 1; anything else fails the run as the
// host's failure. A bound of 0 or less fails service 42 as MARS words it, before any seed is asked.
{
    const DRAW = `
        .text
main:   li    $a0, 0
        li    $v0, 41
        syscall
        li    $v0, 10
        syscall
`
    for (const answer of [-1, 2 ** 48, 1.5, NaN, Infinity, '5', undefined, null]) {
        await assert.rejects(() => core(DRAW, { randomSeed: () => answer }).simulate(), error => {
            assert.ok(isRuntimeError(error), String(error))
            assert.equal(error.kind, 'handler')
            assert.equal(error.message, 'Handler randomSeed did not return a whole number from 0 to 2^48 - 1')
            return true
        }, `randomSeed answering ${String(answer)}`)
    }
    const largest = core(DRAW, { randomSeed: () => 2 ** 48 - 1 })
    await largest.simulate()
    const minusOne = vectors.random.sequences.find(s => s.seed === '-1' && s.service === 41).values[0]
    assert.equal(largest.getRegisterValue('$a0'), minusOne, '2^48 - 1 is new Random(-1L)')

    for (const bound of [0, -5]) {
        let asked = false
        const bounded = core(`
        .text
main:   li    $a0, 0
        li    $a1, ${bound}
        li    $v0, 42
        syscall
`, { randomSeed: () => { asked = true; return 0 } })
        await assert.rejects(() => bounded.simulate(), error => {
            assert.ok(isRuntimeError(error))
            assert.equal(error.kind, 'syscall')
            assert.equal(error.message, 'Runtime exception at 0x0040000c: Upper bound of range cannot be negative (syscall 42)')
            assert.deepEqual([error.address, error.sourcePath, error.line], [0x0040000c, 'main.asm', 6])
            return true
        })
        assert.equal(asked, false, `a bound of ${bound} fails before the generator starts`)
    }
}

console.log(`ok - random services: ${drawn} java.util.Random draws through the Core, Undo then Step, seeds, initialize, host randomness`)
