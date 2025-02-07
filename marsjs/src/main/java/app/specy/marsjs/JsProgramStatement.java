package app.specy.marsjs;

import app.specy.mars.ProgramStatement;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSProperty;

@JSClass
public class JsProgramStatement {

    private int sourceLine;
    private int address;
    private int binaryStatement;


    private String source;
    private String machineStatement;


    public JsProgramStatement(ProgramStatement programStatement) {
        this.sourceLine = programStatement.getSourceLine();
        this.address = programStatement.getAddress();
        this.binaryStatement = programStatement.getBinaryStatement();
        this.source = programStatement.getSource();
        this.machineStatement = programStatement.getMachineStatement();
    }


    @JSProperty
    public int getSourceLine() {
        return sourceLine;
    }

    @JSProperty
    public int getAddress() {
        return address;
    }

    @JSProperty
    public int getBinaryStatement() {
        return binaryStatement;
    }

    @JSProperty
    public String getSource() {
        return source;
    }

    @JSProperty
    public String getMachineStatement() {
        return machineStatement;
    }
}
