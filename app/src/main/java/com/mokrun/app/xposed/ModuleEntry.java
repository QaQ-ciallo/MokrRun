package com.mokrun.app.xposed;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Module entry point.
 *  - com.bxkj.student      : real target, installs the motion simulation
 *  - com.mokrun.app.probe    : test fixture, reports "injected" to its UI
 */
public class ModuleEntry implements IXposedHookLoadPackage {

    public static final String TARGET = "com.bxkj.student";
    public static final String PROBE = "com.mokrun.app.probe";
    public static final String TAG = "MokrRun";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (TARGET.equals(lpparam.packageName)) {
            boolean main = lpparam.processName == null
                    || lpparam.processName.equals(lpparam.packageName);
            XposedBridge.log(TAG + ": attached to " + lpparam.packageName
                    + " process=" + lpparam.processName + " main=" + main);
            if (main) {
                hookAttach(lpparam.classLoader);
            }
            return;
        }
        if (PROBE.equals(lpparam.packageName)) {
            notifyProbe(lpparam);
        }
    }

    /** Application.attach gives us a Context before the app touches any API. */
    private void hookAttach(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl,
                    "attach", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            XposedBridge.log(TAG + ": Application attached, installing hooks");
                            MotionEngine.configure(ctx);
                            LocationProbe.install(cl);
                            SensorHook.install(cl);
                            AntiDetect.install(cl);
                            SDKLocationHook.install(cl);
                            IntentBridge.install(cl);
                            CommandReceiver.install(cl);
                        }
                    });
            XposedBridge.log(TAG + ": hooked Application.attach");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook attach failed " + t + ", falling back to eager install");
            LocationProbe.install(cl);
            SensorHook.install(cl);
        }
    }

    private void notifyProbe(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": attached to probe " + lpparam.processName);
        try {
            Class<?> status = XposedHelpers.findClass(
                    "com.mokrun.app.probe.ProbeStatus", lpparam.classLoader);
            XposedHelpers.callStaticMethod(status, "setInjected", Boolean.TRUE);
            XposedHelpers.callStaticMethod(status, "log", "模块已注入探针进程");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": probe notify failed " + t);
        }
    }
}
