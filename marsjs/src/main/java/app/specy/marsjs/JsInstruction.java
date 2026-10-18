package app.specy.marsjs;

import app.specy.mars.assembler.Token;
import app.specy.mars.mips.instructions.ExtendedInstruction;
import app.specy.mars.mips.instructions.Instruction;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSProperty;

import java.util.List;

public class JsInstruction {

    String name;
    String example;
    String description;
    JsMipsToken[] tokens;
    boolean isPseudo;
    String[] expansion;
    String[] compactExpansion;

    public JsInstruction(Instruction ins) {
        this.name = ins.getName();
        this.example = ins.getExampleFormat();
        this.description = ins.getDescription();
        this.tokens = new JsMipsToken[ins.getTokenList().size()];
        for(int i = 0; i < ins.getTokenList().size(); i++) {
            this.tokens[i] = new JsMipsToken(ins.getTokenList().get(i));
        }
        this.isPseudo = ins instanceof ExtendedInstruction;
        if (ins instanceof ExtendedInstruction) {
            ExtendedInstruction extended = (ExtendedInstruction) ins;
            this.expansion = templates(extended.getBasicIntructionTemplateList());
            this.compactExpansion = templates(extended.getCompactBasicIntructionTemplateList());
        } else {
            this.expansion = new String[0];
            this.compactExpansion = new String[0];
        }
    }

    /*
     * The templates as PseudoOps writes them, trimmed so that none starts or ends with a space; a
     * pseudo-instruction with no compact translation has a null list.
     */
    private static String[] templates(List<?> list) {
        if (list == null) {
            return new String[0];
        }
        String[] templates = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            templates[i] = ((String) list.get(i)).trim();
        }
        return templates;
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
    public JsMipsToken[] getTokens() {
        return tokens;
    }

    /** Whether this form is a pseudo-instruction: `lw $t1, label` is one, `lw $t1, -100($t2)` is not. */
    @JSExport
    @JSProperty("isPseudo")
    public boolean isPseudo() {
        return isPseudo;
    }

    /**
     * The basic instructions the assembler expands this pseudo-instruction into, in order, as
     * templates with MARS's placeholders left in: `lw $t1,100000` gives `lui $1, VH2` and
     * `lw RG1,VL2($1)`. Empty for a basic instruction.
     */
    @JSExport
    @JSProperty
    public String[] getExpansion() {
        return expansion;
    }

    /**
     * The shorter templates MARS uses instead of `expansion` under its compact memory
     * configurations, where every address is below 0x8000: `lw $t1,label` gives `lw RG1, LL2($0)`.
     * Empty when the form has none, which is every basic instruction and most pseudo-instructions.
     * This Core always runs the default memory configuration, so it assembles `expansion`.
     */
    @JSExport
    @JSProperty
    public String[] getCompactExpansion() {
        return compactExpansion;
    }

}
