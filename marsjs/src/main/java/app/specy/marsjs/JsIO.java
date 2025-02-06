package app.specy.marsjs;

import app.specy.mars.mips.io.MIPSIO;
import app.specy.mars.mips.io.MIPSIOError;

public class JsIO extends MIPSIO {
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
    public int askInt(String message) {
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
        return 0;
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
