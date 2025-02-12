package app.specy.mars.mips.hardware;

import java.util.ArrayList;
import java.util.List;

public class Stack {

    static List<Integer> callStack = new ArrayList<Integer>();

    public static void pushCallStack(int pc) {
        callStack.add(pc);
    }

    public static int popCallStack() {
        if (callStack.isEmpty()) {
            return -1;
        }
        return callStack.remove(callStack.size() - 1);
    }

    public static void clearCallStack() {
        callStack.clear();
    }

    public static List<Integer> getCallStack() {
        return callStack;
    }
}
