package mars.mips.fs;

import java.util.List;

import mars.MIPSFile;

public abstract class MIPSFileSystem {
    abstract public String read(String path);
    abstract public void write(String path, String content);
    abstract public void delete(String path);
    abstract public void create(String path);
    abstract public List<MIPSFile> getFiles();
    public void write(MIPSFile file) {
        this.write(file.getName(), file.getSource());
    }
    public void create(MIPSFile file) {
        this.create(file.getName());
    }
    public void readMipsFile(String path) {
        new MIPSFile(path, this.read(path));
    }
}
