// Puts the WebAssembly build next to the bundle that loads it: `useWebAssembly` finds it at
// `new URL('./mars.wasm', import.meta.url)`.
import { copyFileSync } from 'node:fs'

copyFileSync(new URL('../src/generated/mars.wasm', import.meta.url), new URL('../dist/mars.wasm', import.meta.url))
