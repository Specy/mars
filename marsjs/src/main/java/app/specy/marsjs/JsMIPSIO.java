package app.specy.marsjs;

import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.mips.io.MIPSIOError;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

import java.util.HashMap;
import java.util.Map;

public class JsMIPSIO extends MIPSIO {

    private final Map<String, IOHandler> handlers = new HashMap<>();

    @JSExport
    public JsMIPSIO() {
        super();
    }

    public interface IOHandler extends JSObject {
        Object invoke(Object... args);
    }


    @Override
    public int askInt(String message) {
        Object result = callHandler("askInt", message);
        if (result instanceof Number) {
            return ((Number) result).intValue();
        } else {
            throw new IllegalArgumentException("Invalid result type");
        }
    }


    // Method to register a handler
    public void registerHandler(String name, IOHandler handler) {
        handlers.put(name, handler);
    }

    // Helper method to safely invoke handlers
    private Object callHandler(String name, Object... args) {
        IOHandler handler = handlers.get(name);
        if (handler == null) throw new IllegalArgumentException("No handler registered for " + name);
        return handler.invoke(args);
    }

    @Override
    public int openFile(String filename, int flags, boolean append) throws MIPSIOError {
        return 0;
    }

    @Override
    public void closeFile(int fileDescriptor) throws MIPSIOError {

    }

    @Override
    public void writeFile(int fileDescriptor, byte[] buffer) throws MIPSIOError {

    }

    @Override
    public int readFile(int fileDescriptor, byte[] destination, int length) throws MIPSIOError {
        return 0;
    }

    @Override
    public int confirm(String message) {
        return 0;
    }

    @Override
    public String inputDialog(String message) {
        return "";
    }

    @Override
    public void outputDialog(String message, int type) {

    }

    @Override
    public double askDouble(String message) {
        return 0;
    }

    @Override
    public float askFloat(String message) {
        return 0;
    }



    @Override
    public String askString(String message) {
        return "";
    }

    @Override
    public double readDouble() {
        return 0;
    }

    @Override
    public float readFloat() {
        return 0;
    }

    @Override
    public int readInt() {
        Object result = callHandler("readInt");
        if (result instanceof Number) {
            return ((Number) result).intValue();
        } else {
            throw new IllegalArgumentException("Invalid result type");
        }
    }

    @Override
    public String readString() {
        return "";
    }

    @Override
    public char readChar() {
        return 0;
    }

    @Override
    public void logLine(String message) {

    }

    @Override
    public void log(String message) {

    }

    @Override
    public void printChar(char c) {

    }

    @Override
    public void printDouble(double d) {

    }

    @Override
    public void printFloat(float f) {

    }

    @Override
    public void printInt(int i) {

    }

    @Override
    public void printString(String l) {

    }

    @Override
    public double randomDouble() {
        return 0;
    }

    @Override
    public float randomFloat() {
        return 0;
    }

    @Override
    public int randomInt() {
        return 0;
    }

    @Override
    public int randomIntWithRange(int max) {
        return 0;
    }

    @Override
    public void setRandomSeed(int seed) {

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
