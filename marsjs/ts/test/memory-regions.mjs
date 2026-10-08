import assert from 'node:assert/strict'
import { MIPS } from '../dist/index.mjs'
const make = source => {
    const core = MIPS.makeMipsFromFiles({'main.asm': source}, 'main.asm')
    core.setUndoSize(200)
    assert.equal(core.assemble().hasErrors, false, source)
    core.initialize(true)
    core.setUndoEnabled(true)
    return core
}
{
    const core = make(".data\nvalue: .word 7\nroom: .space 20\n.text\n.globl main\nmain:\nlui $sp,0x1002\naddiu $sp,$sp,-16\nli $v0,10\nsyscall")
    const items = core.getLayoutItems()
    assert(items.length > 0 && items.length % 5 === 0)
    assert(core.getSymbolNames().includes('value'))
    assert(core.getSectionNames().includes('.data'))
    assert([...items].some((value, i) => i % 5 === 2 && value === 2), 'space is reserved')
    const initial = core.getStackTop()
    await core.step()
    assert.equal(core.getStackTop(), 0x10020000)
    await core.step()
    assert.equal(core.getStackTop(), 0x10020000)
    core.undo()
    assert.equal(core.getStackTop(), 0x10020000)
    core.undo()
    assert.equal(core.getStackTop(), initial)
    await core.step()
    assert.equal(core.getStackTop(), 0x10020000, 'redo rebases the stack again')
}
{
    const core = make(".text\n.globl main\nmain:\nli $a0,64\nli $v0,9\nsyscall\nli $v0,10\nsyscall")
    const start = core.getHeapStart()
    await core.step(); await core.step(); await core.step()
    assert.equal(core.getHeapBreak(), start + 64)
    core.undo()
    assert.equal(core.getHeapBreak(), start, 'undo restores the heap break')
    await core.step()
    assert.equal(core.getHeapBreak(), start + 64, 'redo does not leak an allocation')
}
console.log('memory layout, stack and heap Undo passed')

{
    const core = MIPS.makeMipsFromFiles({'main.s': '.include "generated.s"', 'generated.s': '.section .data\nuserData: .word libraryData\n.text\n.globl main\nmain: nop'}, 'main.s', {
        assemblerProfile: 'gnu-compiler-v1', libraries: [{ members: {
            '@runtime/data.s': '.section .data\n.globl libraryData\nlibraryData: .word 3\nlocalData: .word 4\n.section .bss\nlocalRoom: .space 8'
        }, index: { libraryData: '@runtime/data.s' } }]
    })
    const result = core.assemble()
    assert.equal(result.hasErrors, false, JSON.stringify(result.errors))
    const names = core.getSymbolNames(), files = core.getSymbolFiles(), values = core.getSymbolValues()
    for (const name of ['libraryData', 'localData', 'localRoom']) {
        const i = names.indexOf(name)
        assert(i >= 0, `${name} is exported even from a data-only library`)
        assert.equal(values[i * 3 + 2], 1, `${name} belongs to the library`)
    }
    const user = names.indexOf('userData')
    assert.equal(values[user * 3 + 2], 0)
    assert.equal(files[user], 'generated.s', 'symbols preserve their included source file')
}
