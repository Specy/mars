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
                backStep.getParam2(), backStep.getParam3(), backStep.isPoke());
    }

    /*
     * `isPoke` is the discriminator this stack needs: a poke entry keeps pc == -1, which a host
     * write made before anything ran also has, so the pc alone cannot tell the two apart.
     *
     * `newValue` is the other half of what the step restores: the value the write put there, taken
     * by the setter at the moment of the write, beside the `param2` it replaced. It is a signed 32
     * bit int like `param2`; a host that wants the unsigned form takes `newValue >>> 0`.
     */
    @JSBody(params = { "action", "pc", "param1", "param2", "newValue", "isPoke" },
            script = "return { action: action, pc: pc, param1: param1, param2: param2, newValue: newValue, isPoke: isPoke };")
    private static native JSObject create(int action, int pc, int param1, int param2, int newValue,
            boolean isPoke);
}
