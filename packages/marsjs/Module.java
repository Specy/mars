package marsjs;
import org.teavm.jso.JSExport;


public class Module {


    @JSExport
    public static void foo() {
        try{
        }catch(Exception e){
            System.out.println("Exception: " + e);
        }
        System.out.println("foo called");
    }
    
    @JSExport
    public static String someTestMethod(int a, int b) {
        return "bar: " + (a + b); 
    }
}
