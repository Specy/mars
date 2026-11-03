// A string literal of any length tokenizes: the GNU profile's tokenizer used to match strings with
// a regex group repeated once a character, which TeaVM's regex engine runs as one recursion a
// character, so the `.ascii` a compiler writes for a C byte table of a few hundred bytes exhausted
// the stack. Strings are now scanned by hand.
import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'

const DATA = 0x10010000
const count = 20000
const bytes = Array.from({ length: count }, (_, index) => (index * 7) & 255)
const escaped = bytes.map((byte) => '\\' + byte.toString(8).padStart(3, '0')).join('')
const source = `\t.data\ntable:\n\t.ascii\t"${escaped}"\nafter:\n\t.asciz\t"a, \\"b\\" # c"\n\t.text\n\t.globl main\nmain:\n\tjr $ra\n`
const core = MIPS.makeMipsFromFiles({ 'main.s': source }, 'main.s', { assemblerProfile: 'gnu-compiler-v1' })
const result = core.assemble()
assert.equal(result.hasErrors, false, result.errors.map((error) => error.message).join('\n'))
assert.deepEqual(Array.from(core.readMemoryBytes(DATA, count)), bytes)
assert.equal(new TextDecoder().decode(new Uint8Array(core.readMemoryBytes(DATA + count, 13))), 'a, "b" # c\0\0\0'.slice(0, 13))
console.log(`ok - long strings: a ${escaped.length}-character .ascii and quotes, commas and # inside a string`)
