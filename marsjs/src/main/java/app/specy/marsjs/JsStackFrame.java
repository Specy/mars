package app.specy.marsjs;

import app.specy.mars.mips.hardware.StackFrame;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSProperty;

public class JsStackFrame {
    public int pc;
    public int callSite;
    public int toAddress;
    public int returnAddress;
    public int sp;
    public int fp;
    public String kind;
    public int[] registers;

    public JsStackFrame(StackFrame frame){
        this.pc = frame.getPC();
        this.callSite = frame.getCallSite();
        this.toAddress = frame.getToAddress();
        this.returnAddress = frame.getReturnAddress();
        this.sp = frame.getSP();
        this.fp = frame.getFP();
        this.kind = frame.getKind() == StackFrame.KIND_EXCEPTION ? "exception"
                : frame.getKind() == StackFrame.KIND_INTERRUPT ? "interrupt" : "call";
        this.registers = frame.getRegisters();
    }

    @JSExport
    @JSProperty
    public int getPc() {
        return pc;
    }

    @JSExport
    @JSProperty
    public int getCallSite() {
        return callSite;
    }

    @JSExport
    @JSProperty
    public int getToAddress() {
        return toAddress;
    }

    @JSExport
    @JSProperty
    public int getReturnAddress() {
        return returnAddress;
    }

    @JSExport
    @JSProperty
    public String getKind() {
        return kind;
    }

    @JSExport
    @JSProperty
    public int getSp() {
        return sp;
    }

    @JSExport
    @JSProperty
    public int getFp() {
        return fp;
    }

    @JSExport
    @JSProperty
    public int[] getRegisters() {
        return registers;
    }
}
