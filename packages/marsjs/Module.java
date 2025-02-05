package mips.marsjs;

import org.teavm.jso.JSExport;
import mips.mars.*;

public class Module {


    @JSExport
    public static void foo() {
        System.out.println("foo called");
    }
    
    @JSExport
    public static String someTestMethod(int a, int b) {
        return "bar: " + (a + b); 
    }
}
