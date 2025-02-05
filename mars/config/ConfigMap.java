package mars.config;

import java.util.HashMap;

public abstract class ConfigMap extends HashMap<String, String>{


    abstract public void reset();

    public int getIntegerValue(String key) {
        return Integer.parseInt(get(key));
    }

    public boolean getBooleanValue(String key) {
        return Boolean.parseBoolean(get(key));
    }

}
