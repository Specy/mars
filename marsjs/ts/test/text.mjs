// Text is UTF-8 throughout, a documented deviation from MARS, which stores literals and reads and
// writes strings one byte per character: string literals are stored by code point, and print
// string, read string, the dialogs and open read and write UTF-8 the way Java 21 decodes and
// encodes it (golden vectors from fixtures/java-numbers/GoldenVectors.java). Print char and read
// char work as MARS and RARS do.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { MIPS, registerHandlers, unimplementedHandler } from '../dist/index.mjs'

const vectors = JSON.parse(await readFile(new URL('./fixtures/java-numbers/vectors.json', import.meta.url), 'utf8'))
assert.match(vectors.java, /^21\./, 'the vectors come from Java 21')

const HANDLER_NAMES = [
    'openFile', 'closeFile', 'writeFile', 'readFile', 'confirm', 'inputDialog',
    'outputDialog', 'readDouble', 'readFloat', 'readInt', 'readString', 'readChar',
    'printString', 'sleep', 'time', 'stdIn', 'seekFile', 'stdOut', 'stdErr', 'randomSeed',
]

function assemble(source) {
    const mips = MIPS.makeMipsFromFiles({ 'main.asm': source }, 'main.asm')
    const result = mips.assemble()
    assert.equal(result.hasErrors, false, result.errors.map(e => `${e.sourceLine}: ${e.message}`).join('\n'))
    return mips
}

/** Runs a program with only `handlers` implemented; any other handler call fails the run. */
async function run(source, handlers = {}) {
    const mips = assemble(source)
    registerHandlers(mips, {
        ...Object.fromEntries(HANDLER_NAMES.map(name => [name, unimplementedHandler(name)])),
        ...handlers,
    })
    mips.initialize(true)
    while (!mips.terminated) await mips.simulateWithLimit(100_000)
    return mips
}

const bytesAt = (mips, label, length) => Array.from(mips.readMemoryBytes(mips.getAddressOfLabel(label), length))
const utf8 = text => Array.from(Buffer.from(text, 'utf8'))
const TEXT = 'é€😀'
const TEXT_BYTES = [0xc3, 0xa9, 0xe2, 0x82, 0xac, 0xf0, 0x9f, 0x98, 0x80]

// Literals are stored by code point: the characters as written, a \u escape naming one UTF-16 unit,
// and two escapes that make a surrogate pair naming one character.
{
    const mips = assemble(`
        .data
written: .asciiz "${TEXT}"
escaped: .asciiz "\\u00e9\\u20AC\\ud83d\\ude00"
listed:  .ascii "é", "€"
         .ascii "😀"
         .byte 0
signed:  .asciiz "\\u+041\\uff41"
lone:    .asciiz "a\\ud83dz"
        .text
main:   li $v0, 10
        syscall
`)
    assert.deepEqual(bytesAt(mips, 'written', 10), [...TEXT_BYTES, 0])
    assert.deepEqual(bytesAt(mips, 'escaped', 10), [...TEXT_BYTES, 0])
    assert.deepEqual(bytesAt(mips, 'listed', 10), [...TEXT_BYTES, 0], 'a list and a continuation line')
    // Integer.parseInt reads the four digits, a sign included, as RARS does.
    assert.deepEqual(bytesAt(mips, 'signed', 5), [0x41, 0xef, 0xbd, 0x81, 0])
    // An unpaired surrogate is encoded as Java encodes it: a question mark.
    assert.deepEqual(bytesAt(mips, 'lone', 4), [0x61, 0x3f, 0x7a, 0])
    assert.equal(mips.getAddressOfLabel('escaped'), mips.getAddressOfLabel('written') + 10, 'labels follow the bytes stored')
}
for (const [literal, message] of [
    ['"\\u12"', /unicode escape "\\u12" is incomplete\. Only escapes with 4 digits are valid\./],
    ['"\\u12g4"', /illegal unicode escape: "\\u12g4"/],
    ['"\\u-001"', /illegal unicode escape: "\\u-001"/],
]) {
    const result = MIPS.makeMipsFromFiles({ 'main.asm': `.data\n.asciiz ${literal}\n` }, 'main.asm').assemble()
    assert.equal(result.hasErrors, true, literal)
    assert.match(result.errors.map(e => e.message).join('\n'), message, literal)
}

// A round trip: print string, read string and a file path all carry the literal's text.
{
    const printed = []
    const opened = []
    const mips = await run(`
        .data
text:   .asciiz "${TEXT}"
path:   .asciiz "données/${TEXT}.txt"
buffer: .space 32
cut:    .space 8
        .text
main:   la   $a0, text
        li   $v0, 4
        syscall
        la   $a0, buffer
        li   $a1, 32
        li   $v0, 8
        syscall
        la   $a0, buffer
        li   $v0, 4
        syscall
        la   $a0, cut
        li   $a1, 4
        li   $v0, 8
        syscall
        la   $a0, cut
        li   $v0, 4
        syscall
        la   $a0, path
        li   $a1, 0
        li   $v0, 13
        syscall
        li   $v0, 10
        syscall
`, {
        printString: text => printed.push(text),
        readString: () => TEXT,
        openFile: (path, flags, append) => { opened.push([path, flags, append]); return 3 },
    })
    assert.deepEqual(bytesAt(mips, 'buffer', 11), [...TEXT_BYTES, 0x0a, 0], 'read string stores UTF-8, then the newline')
    // A buffer of 4 takes 3 characters, then 3 of their bytes: the second character is cut short.
    assert.deepEqual(bytesAt(mips, 'cut', 4), [0xc3, 0xa9, 0xe2, 0])
    assert.deepEqual(printed, [TEXT, `${TEXT}\n`, 'é\ufffd'])
    assert.deepEqual(opened, [[`données/${TEXT}.txt`, 0, false]])
}

// Print char prints the character numbered by the low byte of $a0, and read char answers the first
// UTF-16 unit of what was typed, both as MARS and RARS do.
{
    const printed = []
    const answers = ['é', '€', '😀', 'A']
    const mips = await run(`
        .text
main:   li   $a0, 0x41
        li   $v0, 11
        syscall
        li   $a0, 0xe9
        syscall
        li   $a0, 0xc3
        syscall
        li   $a0, 0x20ac
        syscall
        li   $a0, 0x1f600
        syscall
        li   $v0, 12
        syscall
        move $s0, $v0
        li   $v0, 12
        syscall
        move $s1, $v0
        li   $v0, 12
        syscall
        move $s2, $v0
        li   $v0, 12
        syscall
        move $s3, $v0
        li   $v0, 10
        syscall
`, { printString: text => printed.push(text), readChar: () => answers.shift() })
    assert.deepEqual(printed, ['A', 'é', 'Ã', '¬', '\u0000'])
    assert.deepEqual(['$s0', '$s1', '$s2', '$s3'].map(name => mips.getRegisterValue(name)), [0xe9, 0x20ac, 0xd83d, 0x41])
}

// The dialogs read their message as UTF-8, and input dialog string (54) stores its answer as UTF-8,
// measured in bytes.
{
    const messages = []
    const answers = ['é€', 'é']
    const mips = await run(`
        .data
ask:    .asciiz "${TEXT}?"
tail:   .asciiz " ok"
small:  .space 4
large:  .space 8
        .text
main:   la   $a0, ask
        li   $v0, 50
        syscall
        la   $a0, ask
        la   $a1, small
        li   $a2, 4
        li   $v0, 54
        syscall
        move $s0, $a1
        la   $a0, ask
        la   $a1, large
        li   $a2, 8
        li   $v0, 54
        syscall
        move $s1, $a1
        la   $a0, ask
        li   $a1, 1
        li   $v0, 55
        syscall
        la   $a0, ask
        la   $a1, tail
        li   $v0, 59
        syscall
        li   $v0, 10
        syscall
`, {
        confirm: message => { messages.push(['confirm', message]); return 0 },
        inputDialog: message => { messages.push(['input', message]); return answers.shift() },
        outputDialog: (message, type) => { messages.push(['output', message, type]) },
    })
    assert.deepEqual(messages, [
        ['confirm', `${TEXT}?`], ['input', `${TEXT}?`], ['input', `${TEXT}?`],
        ['output', `${TEXT}?`, 1], ['output', `${TEXT}? ok`, 1],
    ])
    assert.deepEqual(bytesAt(mips, 'small', 4), [0xc3, 0xa9, 0xe2, 0], 'three bytes of "é€", cut, then the NUL')
    assert.equal(mips.getRegisterValue('$s0'), -4, 'the answer did not fit')
    assert.deepEqual(bytesAt(mips, 'large', 4), [0xc3, 0xa9, 0x0a, 0])
    assert.equal(mips.getRegisterValue('$s1'), 0)
}

// Print string prints up to the NUL byte whatever the length, as MARS 4.5 does. A string that runs
// into memory it cannot read stops the program with the address error, after printing what it read:
// this one fills the last 16 bytes of the data segment, which ends at 0x10400000.
{
    const long = 'é'.repeat(35000)
    const printed = []
    await run(`.data\ntext: .asciiz "${long}"\n.text\nmain: la $a0, text\nli $v0, 4\nsyscall\nli $v0, 10\nsyscall\n`,
        { printString: text => printed.push(text) })
    assert.deepEqual(printed, [long], '70,000 bytes print whole')
    printed.length = 0
    await assert.rejects(() => run(`.data 0x103ffff0\nend: .ascii "0123456789abcdé"\n.text\nmain: la $a0, end\nli $v0, 4\nsyscall\n`,
        { printString: text => printed.push(text) }),
        /Runtime exception at 0x0040000c: address out of range 0x10400000/)
    assert.deepEqual(printed, ['0123456789abcdé'], 'the text read before the address error prints')
}

// Every Java 21 decoding of the golden vectors through print string, malformed bytes included.
{
    const decodings = vectors.utf8Decode
    const lines = ['.data', `table: .word ${decodings.map((_, index) => `str${index}`).join(', ')}`]
    decodings.forEach(([hex], index) => {
        lines.push(`str${index}: .byte ${[...Buffer.from(hex, 'hex')].join(', ')}, 0`)
    })
    lines.push('.text', 'main: la $s0, table', `li $s1, ${decodings.length}`,
        'loop: lw $a0, 0($s0)', 'li $v0, 4', 'syscall', 'addiu $s0, $s0, 4', 'addiu $s1, $s1, -1', 'bnez $s1, loop',
        'li $v0, 10', 'syscall')
    const printed = []
    await run(lines.join('\n'), { printString: text => printed.push(text) })
    assert.equal(printed.length, decodings.length)
    decodings.forEach(([hex, text], index) => assert.equal(printed[index], text, `bytes ${hex}`))
}

// Every Java 21 encoding of the golden vectors through read string, unpaired surrogates included.
{
    const encodings = vectors.utf8Encode
    const answers = encodings.map(([text]) => text)
    const mips = await run([
        '.data', `buffers: .space ${64 * encodings.length}`, '.text', 'main: la $s0, buffers', `li $s1, ${encodings.length}`,
        'loop: move $a0, $s0', 'li $a1, 64', 'li $v0, 8', 'syscall', 'addiu $s0, $s0, 64', 'addiu $s1, $s1, -1',
        'bnez $s1, loop', 'li $v0, 10', 'syscall',
    ].join('\n'), { readString: () => answers.shift() })
    encodings.forEach(([text, hex], index) => {
        const expected = [...Buffer.from(hex, 'hex'), 0x0a, 0]
        const address = mips.getAddressOfLabel('buffers') + 64 * index
        assert.deepEqual(Array.from(mips.readMemoryBytes(address, expected.length)), expected, JSON.stringify(text))
    })
}

assert.deepEqual(utf8(TEXT), TEXT_BYTES)
console.log(`ok - text as UTF-8: literals, print and read string, open, dialogs, print and read char, a string of any length, ${vectors.utf8Decode.length} Java decodings and ${vectors.utf8Encode.length} encodings`)
