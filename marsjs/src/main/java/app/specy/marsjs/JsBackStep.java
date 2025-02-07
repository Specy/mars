package app.specy.marsjs;

import app.specy.mars.simulator.BackStepper;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSProperty;


@JSClass
public class JsBackStep {

    int action;
    int pc;

    public JsBackStep(BackStepper.BackStep backStep) {
        this.action = backStep.getAction();
        this.pc = backStep.getPc();
    }


    @JSProperty
    public int getAction() {
        return action;
    }

    @JSProperty
    public int getPc() {
        return pc;
    }

}
