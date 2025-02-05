package mars;

public class MIPSFile {
    String source;
    String name;

    public MIPSFile(String name, String source) {
        this.source = source;
        this.name = name;
    }

    public String getSource() {
        return source;
    }

    public String getName() {
        return name;
    }
}