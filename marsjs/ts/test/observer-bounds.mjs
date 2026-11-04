// Memory turns away an access outside every observer's range with two comparisons, against the
// lowest and highest address any observer watches, before walking the observers. These check that
// the bounds never turn away an access an observer watches: at either end of a range, between two
// ranges far apart, after a removal shrinks them and after a registration grows them again, and on
// an instruction fetch, which is checked on its own path.
import assert from 'node:assert/strict'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'midiOut', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr',
    'randomSeed', 'unlink', 'mkdir', 'rmdir', 'rename', 'stat', 'getdents',
]
const LOW = 0x10010000
const DEVICE = 0xffff000c | 0
const TEXT = 0x00400000

const SOURCE = [
    '.data',
    'low:   .word 0',
    'gap:   .space 64',
    '.text',
    'main:  la $t0, low',
    '       li $t1, 7',
    '       sw $t1, 0($t0)',      // the low word: watched
    '       sw $t1, 32($t0)',     // between the two ranges: inside the bounds, watched by neither
    '       lw $t2, 0($t0)',      // a read of the low word
    '       li $t3, 0xffff000c',
    '       sw $t1, 0($t3)',      // the device word: watched
    '       sb $t1, 3($t3)',      // its last byte: watched
    '       sb $t1, 4($t3)',      // the byte after: not watched
    '       sw $t1, -4($sp)',     // the stack, above every observer: outside the bounds
    '       li $v0, 10',
    '       syscall',
].join('\n')

const mips = MIPS.makeMipsFromFiles({ 'main.asm': SOURCE }, 'main.asm')
const result = mips.assemble()
assert.equal(result.hasErrors, false, result.report)
registerHandlers(mips, Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])))

let events = []
const watch = (name) => [
    (address, value) => events.push([name, 'read', address >>> 0, value]),
    (address, value) => events.push([name, 'write', address >>> 0, value]),
]

async function runAll() {
    events = []
    mips.initialize(true)
    let steps = 0
    while (!mips.terminated && steps++ < 1000) await mips.step()
    assert.equal(mips.terminated, true)
    return events
}

// Two observers far apart: the low data word and a device word in the memory map
let low = mips.addMemoryAccessObserver(LOW, ...watch('low'))
const device = mips.addMemoryAccessObserver(DEVICE, ...watch('device'))
assert.deepEqual(await runAll(), [
    ['low', 'write', LOW, 7],
    ['low', 'read', LOW, 7],
    ['device', 'write', DEVICE >>> 0, 7],
    ['device', 'write', (DEVICE >>> 0) + 3, 7],
], 'every watched access is reported, at both ends of a word, and nothing else')

// Removing the low observer shrinks the bounds to the device word, which is still reported
mips.removeMemoryObserver(low)
assert.deepEqual(await runAll(), [
    ['device', 'write', DEVICE >>> 0, 7],
    ['device', 'write', (DEVICE >>> 0) + 3, 7],
], 'after a removal only the remaining observer is reported')

// A registration grows them again
low = mips.addMemoryAccessObserver(LOW, ...watch('low'))
assert.deepEqual((await runAll()).filter(([name]) => name === 'low'), [
    ['low', 'write', LOW, 7],
    ['low', 'read', LOW, 7],
], 'an observer registered again is reported again')

// An instruction fetch is reported to an observer of the text segment, alone or beside the others
const fetch = mips.addMemoryAccessObserver(TEXT, ...watch('fetch'))
const fetches = (await runAll()).filter(([name]) => name === 'fetch')
assert.ok(fetches.length >= 1 && fetches.every(([, kind, address]) => kind === 'read' && address === TEXT),
    `the first instruction's fetch is reported: ${JSON.stringify(fetches)}`)
mips.removeMemoryObserver(low)
mips.removeMemoryObserver(device)
assert.ok((await runAll()).some(([name]) => name === 'fetch'), 'a fetch observer alone is reported')
mips.removeMemoryObserver(fetch)

// With no observer at all, nothing is reported
mips.addMemoryAccessObserver(LOW, ...watch('low'))
mips.removeMemoryObservers()
assert.deepEqual(await runAll(), [], 'no observer, no report')

console.log('observer-bounds: ok')
