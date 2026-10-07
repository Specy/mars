// Floating point literals as the assemblers read them on Java 21. In the MARS dialect `.double`
// stores Double.parseDouble of its token and `.float` that double rounded to a float, out of range
// beyond the largest float; the GNU compiler profile's `.float` and `.double` parse straight to
// their width, as GNU as does. Every golden line of fixtures/java-numbers/GoldenVectors.java that
// the assembler takes as one operand is assembled and its bits compared, and every line Java
// rejects must fail to assemble.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { MIPS } from '../dist/index.mjs'

const vectors = JSON.parse(await readFile(new URL('./fixtures/java-numbers/vectors.json', import.meta.url), 'utf8'))
assert.match(vectors.java, /^21\./, 'the vectors come from Java 21')
const DATA = 0x10010000

/**
 * Whether MARS's tokenizer keeps the line as one token: printable, none of the characters that end
 * a token, and a sign only first (followed by a digit) or in an exponent, as in 1.5e-3.
 */
function oneMarsToken(text) {
    if (!/^[\x21-\x7e]+$/.test(text) || /[,:()"'#]/.test(text)) return false
    for (let i = 0; i < text.length; i++) {
        if (text[i] !== '+' && text[i] !== '-') continue
        const digitNext = /[0-9]/.test(text[i + 1] ?? '')
        if (!digitNext || (i > 0 && text[i - 1] !== 'e' && text[i - 1] !== 'E')) return false
    }
    return true
}

/** Whether the GNU profile reads the line as one argument: printable, with no separator or comment. */
const oneGnuArgument = text => /^[\x20-\x7e]*$/.test(text) && !/[,#;"]/.test(text)

function hexAt(bytes, offset, size) {
    let hex = ''
    for (let i = size - 1; i >= 0; i--) hex += bytes[offset + i].toString(16).padStart(2, '0')
    return hex
}

function check(label, cases, directive, size, options) {
    const valid = cases.filter(([, expected]) => expected !== null)
    const source = '.data\n' + valid.map(([line]) => `${directive} ${line}`).join('\n') + '\n'
    const core = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm', options)
    const result = core.assemble()
    assert.equal(result.hasErrors, false, `${label}: ` + Array.from(result.errors).slice(0, 5)
        .map(error => `${JSON.stringify(valid[error.sourceLine - 2]?.[0])}: ${error.message}`).join('; '))
    const bytes = Array.from(core.readMemoryBytes(DATA, valid.length * size))
    valid.forEach(([line, expected], index) => assert.equal(hexAt(bytes, index * size, size), expected,
        `${label} ${JSON.stringify(line)}`))
    const invalid = cases.filter(([, expected]) => expected === null)
    for (const [line] of invalid) {
        const rejected = MIPS.makeMipsFromFiles({ 'main.asm': `.data\n${directive} ${line}\n` }, 'main.asm', options).assemble()
        assert.equal(rejected.hasErrors, true, `${label} must reject ${JSON.stringify(line)}`)
    }
    assert.ok(valid.length > 1000, `${label}: enough lines are exercised`)
    return valid.length + invalid.length
}

const gnu = { assemblerProfile: 'gnu-compiler-v1' }
const counts = [
    check('MARS .double', vectors.parseDouble.filter(([line]) => oneMarsToken(line)), '.double', 8),
    check('MARS .float', vectors.floatDirective.filter(([line]) => oneMarsToken(line)), '.float', 4),
    check('GNU .double', vectors.parseDouble.filter(([line]) => oneGnuArgument(line)), '.double', 8, gnu),
    check('GNU .float', vectors.parseFloat.filter(([line]) => oneGnuArgument(line)), '.float', 4, gnu),
]

// The edge values TeaVM's parser read wrongly, spelled out.
{
    const core = MIPS.makeMipsFromFiles({ 'main.asm': '.data\n.double 4.9e-324, 1e23, 2.2250738585072011e-308\n.float 1.4e-45\n' }, 'main.asm')
    assert.equal(core.assemble().hasErrors, false)
    const bytes = Array.from(core.readMemoryBytes(DATA, 28))
    assert.deepEqual([hexAt(bytes, 0, 8), hexAt(bytes, 8, 8), hexAt(bytes, 16, 8), hexAt(bytes, 24, 4)],
        ['0000000000000001', '44b52d02c7e14af6', '000fffffffffffff', '00000001'])
}

console.log(`ok - assembler literals: ${counts.join(', ')} lines of MARS .double, MARS .float, GNU .double and GNU .float read as Java 21 reads them`)
