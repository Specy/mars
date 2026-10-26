package app.specy.marsjs;

import app.specy.mars.simulator.BackStepper;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

/**
 * One entry of the raw back step stack, as `getUndoStack()` reports it.
 *
 * It is handed over as an ordinary JS object rather than as a Java object with accessors, so that a
 * host can clone it, `JSON.stringify` it or compare it against a plain object literal; reading its
 * properties is unchanged.
 */
public final class JsBackStep {

    private JsBackStep() {
    }

    static JSObject of(BackStepper.BackStep backStep) {
        return create(backStep.getAction(), backStep.getPc(), backStep.getParam1(),
                Integer.toString(backStep.getOldValue()), Integer.toString(backStep.getNewValue()),
                backStep.isPoke(), Long.toString(backStep.getSerial()));
    }

    /*
     * `isPoke` is the discriminator this stack needs: a poke entry keeps pc == -1, which a host
     * write made before anything ran also has, so the pc alone cannot tell the two apart.
     *
     * `oldValue` and `newValue` are the two sides of the write, taken by the setter at the moment
     * of the write. They cross as signed decimal strings, the shape @specy/risc-v reports its 64 bit
     * values in, so that one reading serves the back steps of both. `param1` crosses unsigned, so
     * an address at or above 0x80000000 (the memory-mapped devices, kernel data) reads as the
     * address it is; a register number or an index is never negative and reads as before.
     */
    @JSBody(params = { "action", "pc", "param1", "oldValue", "newValue", "isPoke", "serial" },
            script = "return { action: action, pc: pc, param1: param1 >>> 0, oldValue: oldValue,"
                    + " newValue: newValue, isPoke: isPoke, serial: serial };")
    private static native JSObject create(int action, int pc, int param1, String oldValue,
            String newValue, boolean isPoke, String serial);
}
