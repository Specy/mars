package app.specy.marsjs;

import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.mips.io.MIPSIOError;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.*;
import org.teavm.jso.impl.JS;

import java.util.HashMap;
import java.util.Map;

public class JsMIPSIO extends MIPSIO {

    private final Map<String, JSFunction> handlers = new HashMap<>();

    @JSExport
    public JsMIPSIO() {
        super();
    }

    public void registerHandler(String name, JSFunction handler) {
        handlers.put(name, handler);
    }

    private JSObject callHandler(String name, JSObject... args) {
        JSFunction handler = handlers.get(name);
        if (handler == null) throw new IllegalArgumentException("No handler registered for " + name);
        if(args.length == 0) return (JSObject) handler.call(null);
        if(args.length == 1) return (JSObject) handler.call(null, args[0]);
        if(args.length == 2) return (JSObject) handler.call(null, args[0], args[1]);
        if(args.length == 3) return (JSObject) handler.call(null, args[0], args[1], args[2]);
        if(args.length == 4) return (JSObject) handler.call(null, args[0], args[1], args[2], args[3]);
        if(args.length == 5) return (JSObject) handler.call(null, args[0], args[1], args[2], args[3], args[4]);
        if(args.length == 6) return (JSObject) handler.call(null, args[0], args[1], args[2], args[3], args[4], args[5]);
        if(args.length == 7) return (JSObject) handler.call(null, args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
        if(args.length == 8) return (JSObject) handler.call(null, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
        throw new IllegalArgumentException("Too many arguments, max 8");
    }

    private int callIntHandler(String name, JSObject... args) {
        Object result = callHandler(name, args);
        if (result instanceof JSNumber) {
            return ((JSNumber) result).intValue();
        }
        throw new IllegalArgumentException("Handler " + name + " did not return an integer");
    }

    private String callStringHandler(String name, JSObject... args) {
        Object result = callHandler(name, args);
        if (result instanceof JSString) {
            return ((JSString) result).stringValue();
        }
        throw new IllegalArgumentException("Handler " + name + " did not return a string");
    }

    private char callCharHandler(String name, JSObject... args) {
        String result = callStringHandler(name, args);
        if (result.length() == 1) {
            return result.charAt(0);
        }
        throw new IllegalArgumentException("Handler " + name + " did not return a char");
    }

    private float callFloatHandler(String name, JSObject... args) {
        Object result = callHandler(name, args);
        if (result instanceof JSNumber) {
            return ((JSNumber) result).floatValue();
        }
        throw new IllegalArgumentException("Handler " + name + " did not return a float");
    }

    private double callDoubleHandler(String name, JSObject... args) {
        Object result = callHandler(name, args);
        if (result instanceof JSNumber) {
            return ((JSNumber) result).doubleValue();
        }
        throw new IllegalArgumentException("Handler " + name + " did not return a double");
    }


    @Override
    public int openFile(String filename, int flags, boolean append) throws MIPSIOError {
        return callIntHandler("openFile", JSString.valueOf(filename), JSNumber.valueOf(flags), JSBoolean.valueOf(append));
    }

    @Override
    public void closeFile(int fileDescriptor) throws MIPSIOError {
        callHandler("closeFile", JSNumber.valueOf(fileDescriptor));
    }

    @Override
    public void writeFile(int fileDescriptor, byte[] buffer) throws MIPSIOError {
        //TODO
        callHandler("writeFile", JSNumber.valueOf(fileDescriptor), JSArray.of(buffer));
    }

    @Override
    public int readFile(int fileDescriptor, byte[] destination, int length) throws MIPSIOError {
        return callIntHandler("readFile", JSNumber.valueOf(fileDescriptor), JSArray.of(destination), JSNumber.valueOf(length));
    }

    @Override
    public int confirm(String message) {
        return callIntHandler("confirm", JSString.valueOf(message));
    }

    @Override
    public String inputDialog(String message) {
        return callStringHandler("inputDialog", JSString.valueOf(message));
    }

    @Override
    public void outputDialog(String message, int type) {
        callHandler("outputDialog", JSString.valueOf(message), JSNumber.valueOf(type));
    }

    @Override
    public int askInt(String message) {
        return callIntHandler("askInt", JSString.valueOf(message));
    }


    @Override
    public double askDouble(String message) {
        return callDoubleHandler("askDouble", JSString.valueOf(message));
    }

    @Override
    public float askFloat(String message) {
        return callFloatHandler("askFloat", JSString.valueOf(message));
    }


    @Override
    public String askString(String message) {
        return callStringHandler("askString", JSString.valueOf(message));
    }

    @Override
    public int readInt() {
        return callIntHandler("readInt");
    }

    @Override
    public double readDouble() {
        return callDoubleHandler("readDouble");
    }

    @Override
    public float readFloat() {
        return callFloatHandler("readFloat");
    }


    @Override
    public String readString() {
        return callStringHandler("readString");
    }

    @Override
    public char readChar() {
        return callCharHandler("readChar");
    }

    @Override
    public void logLine(String message) {
        callHandler("logLine", JSString.valueOf(message));
    }

    @Override
    public void log(String message) {
        callHandler("log", JSString.valueOf(message));
    }

    @Override
    public void printChar(char c) {
        callHandler("printChar", JSString.valueOf(String.valueOf(c)));
    }

    @Override
    public void printDouble(double d) {
        callHandler("printDouble", JSNumber.valueOf(d));
    }

    @Override
    public void printFloat(float f) {
        callHandler("printFloat", JSNumber.valueOf(f));
    }

    @Override
    public void printInt(int i) {
        callHandler("printInt", JSNumber.valueOf(i));
    }

    @Override
    public void printString(String l) {
        callHandler("printString", JSString.valueOf(l));
    }

    @Override
    public double randomDouble() {
        return callDoubleHandler("randomDouble");
    }

    @Override
    public float randomFloat() {
        return callFloatHandler("randomFloat");
    }

    @Override
    public int randomInt() {
        return callIntHandler("randomInt");
    }

    @Override
    public int randomIntWithRange(int max) {
        return callIntHandler("randomIntWithRange", JSNumber.valueOf(max));
    }

    @Override
    public void sleep(int milliseconds) {

    }

    @Override
    public void stdIn(byte[] buffer, int length) {

    }

    @Override
    public void stdOut(byte[] buffer) {

    }

    @Override
    public void stdErr(byte[] buffer) {

    }
}
