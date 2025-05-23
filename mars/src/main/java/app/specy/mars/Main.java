package app.specy.mars;

import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.mips.instructions.SyscallLoader;

public class Main {

    public static void main(String[] args) {
        try {
            MIPS.initializeMIPS();
            MIPS.setIo(null);
            //TODO implement default IO
            MIPS mips = MIPS.fromSource("""
                    li $v0, 5
                    move $t0, $v0
                    move $t0, $v0
                    move $t0, $v0
                    
                    li $t1, 20
                    add $a0, $t0, $t1
                    """);
            mips.assemble();
            mips.initialize(true);
            System.out.println(mips.hasTerminated());
            mips.simulate(1000);
            System.out.println(mips.hasTerminated());
            System.out.println(RegisterFile.getUserRegister("$a0").getValue());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
