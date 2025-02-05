package app.specy.mars.mips.fs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.specy.mars.MIPSFile;

public class MemoryFileSystem extends MIPSFileSystem {
    private Map<String, String> files = new HashMap<String, String>();

    public String read(String path) {
        String f = files.get(path);
        if (f == null) {
            throw new RuntimeException("File not found: " + path);
        }
        return f;
    }

    public void write(String path, String content) {
        files.put(path, content);
    }

    public void delete(String path) {
        files.remove(path);
    }

    public void create(String path) {
        this.write(path, "");
    }

    public MemoryFileSystem addFile(String path, String content) {
        files.put(path, content);
        return this;
    }
    
    public List<MIPSFile> getFiles() {
        return files.entrySet().stream().map(e -> new MIPSFile(e.getKey(), e.getValue())).toList();
    }

}
