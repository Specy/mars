package app.specy.marsjs.Main;

import app.specy.mars.MIPS;
import app.specy.mars.mips.hardware.RegisterFile;

public class Main {
    public static void main(String[] args) {
        try {
            MIPS.initializeMIPS();
            MIPS mips = MIPS.fromSource("""
                    main:
                    li $t0, 30
                    li $t1, 20
                    add $a0, $t0, $t1
                    """);
            mips.assemble();
            mips.initialize(true);
            mips.simulate(1000);
            mips.getSimulator();
            System.out.println(RegisterFile.getUserRegister("$a0").getValue());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
