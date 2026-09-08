package app.specy.mars;

import app.specy.mars.mips.hardware.RegisterFile;
import app.specy.mars.mips.instructions.SyscallLoader;
import app.specy.mars.mips.fs.MemoryFileSystem;

public class Main {

    public static void main(String[] args) {
        try {
            MIPS.initializeMIPS();
            MIPS.setIo(null);
            //TODO implement default IO
            MemoryFileSystem files = new MemoryFileSystem();
            files.write("main.asm", """
                li $v0, 5
                move $t0, $v0
                move $t0, $v0
                move $t0, $v0
                    
                li $t1, 20
                add $a0, $t0, $t1
                    """);
            MIPS mips = MIPS.fromFs("main.asm", files);
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
