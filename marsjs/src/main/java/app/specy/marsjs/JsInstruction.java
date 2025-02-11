package app.specy.marsjs;

import app.specy.mars.assembler.Token;
import app.specy.mars.mips.instructions.Instruction;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSProperty;

public class JsInstruction {

    class JsInstructionToken {
        int sourceLine;
        int sourceColumn;
        int originalSourceLine;
        String value;
        String type;

        public JsInstructionToken(Token token) {
            this.sourceLine = token.getSourceLine();
            this.sourceColumn = token.getStartPos();
            this.originalSourceLine = token.getOriginalSourceLine();
            this.value = token.getValue();
            this.type = token.getType().toString();
        }

        @JSExport
        @JSProperty
        public int getSourceLine() {
            return sourceLine;
        }

        @JSExport
        @JSProperty
        public int getSourceColumn() {
            return sourceColumn;
        }

        @JSExport
        @JSProperty
        public int getOriginalSourceLine() {
            return originalSourceLine;
        }

        @JSExport
        @JSProperty
        public String getValue() {
            return value;
        }

        @JSExport
        @JSProperty
        public String getType() {
            return type;
        }
    }

    String name;
    String example;
    String description;
    JsInstructionToken[] tokens;

    public JsInstruction(Instruction ins) {
        this.name = ins.getName();
        this.example = ins.getExampleFormat();
        this.description = ins.getDescription();
        this.tokens = new JsInstructionToken[ins.getTokenList().size()];
        for(int i = 0; i < ins.getTokenList().size(); i++) {
            this.tokens[i] = new JsInstructionToken(ins.getTokenList().get(i));
        }
    }

    @JSExport
    @JSProperty
    public String getName() {
        return name;
    }

    @JSExport
    @JSProperty
    public String getExample() {
        return example;
    }

    @JSExport
    @JSProperty
    public String getDescription() {
        return description;
    }

    @JSExport
    @JSProperty
    public JsInstructionToken[] getTokens() {
        return tokens;
    }

}
