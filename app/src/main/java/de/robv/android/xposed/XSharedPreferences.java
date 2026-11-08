package de.robv.android.xposed;

public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public XSharedPreferences(String packageName, String prefFileName, boolean multiProcess) {}
    public void startSync() {}
    public void stopSync() {}
    public void reload() {}
    public boolean makeWorldReadable() { return false; }
    public String getString(String key, String defValue) { return defValue; }
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
    public long getLong(String key, long defValue) { return defValue; }
    public float getFloat(String key, float defValue) { return defValue; }
    public boolean contains(String key) { return false; }
}
