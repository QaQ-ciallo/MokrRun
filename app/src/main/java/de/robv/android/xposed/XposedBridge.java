package de.robv.android.xposed;
public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
