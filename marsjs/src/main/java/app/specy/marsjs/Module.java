package app.specy.marsjs;
import app.specy.mars.MIPS;
import app.specy.mars.ProcessingException;
import org.teavm.jso.JSExport;

public class Module {

    @JSExport
    public static void foo() {
        System.out.println("foo called");
    }

    @JSExport
    public static void bar(String source) throws ProcessingException {
        MIPS mips = MIPS.fromSource(source);
    }
}
