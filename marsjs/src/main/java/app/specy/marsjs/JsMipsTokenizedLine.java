package app.specy.marsjs;

import org.teavm.jso.JSExport;
import org.teavm.jso.JSProperty;

public class JsMipsTokenizedLine {
    String sourcePath;
    int sourceLine;
    String source;
    String processedSource;
    JsMipsToken[] tokens;

    public JsMipsTokenizedLine(String sourcePath, int sourceLine, String source,
            String processedSource, JsMipsToken[] tokens) {
        this.sourcePath = sourcePath;
        this.sourceLine = sourceLine;
        this.source = source;
        this.processedSource = processedSource;
        this.tokens = tokens;
    }

    @JSExport
    @JSProperty
    public String getSourcePath() {
        return sourcePath;
    }

    @JSExport
    @JSProperty
    public int getSourceLine() {
        return sourceLine;
    }

    @JSExport
    @JSProperty
    public String getSource() {
        return source;
    }

    @JSExport
    @JSProperty
    public String getProcessedSource() {
        return processedSource;
    }

    @JSExport
    @JSProperty
    public JsMipsToken[] getTokens() {
        return tokens;
    }
}
