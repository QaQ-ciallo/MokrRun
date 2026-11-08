package com.mokrun.app.xposed;

import android.app.Activity;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 配置的零权限通道。
 *
 * 模块 App 点「开始」时，用 startActivity 把配置塞进 Intent 的 extras。
 * 这里在本进程的 Activity 上把 extras 读出来交给引擎——
 * 全程只是一次标准的 Activity 启动，不需要 adb、root 或任何特殊权限。
 *
 * onNewIntent 也要接：体适能已经在前台时加了 SINGLE_TOP 不会重建，
 * 新配置只会从 onNewIntent 进来。
 */
public final class IntentBridge {

    private static final String TAG = "MokrRun";
    private static final String EX_CFG = "mokr_cfg";
    private static final String EX_ORIGIN = "mokr_origin";
    private static final String EX_ROUTE = "mokr_route";
    private static final String EX_RUN = "mokr_run";

    private static boolean sInstalled = false;

    private IntentBridge() {}

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            Class<?> activity = XposedHelpers.findClass("android.app.Activity", cl);
            XposedBridge.hookAllMethods(activity, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    read(param.thisObject, "onCreate");
                }
            });
            XposedBridge.hookAllMethods(activity, "onNewIntent", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    read(param.thisObject, "onNewIntent");
                }
            });
            XposedBridge.log(TAG + ": intent bridge installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": intent bridge failed " + t);
        }
    }

    private static void read(Object obj, String from) {
        try {
            if (!(obj instanceof Activity)) return;
            Intent it = ((Activity) obj).getIntent();
            if (it == null) return;
            String cfg = it.getStringExtra(EX_CFG);
            if (cfg == null || cfg.length() < 5) return;

            String origin = it.getStringExtra(EX_ORIGIN);
            String route = it.getStringExtra(EX_ROUTE);
            int run = it.getIntExtra(EX_RUN, 0);

            MotionEngine.applyFromIntent(cfg, origin, route, run);
            XposedBridge.log(TAG + ": config via Intent (" + from + ") "
                    + cfg + " run=" + run
                    + " routeLen=" + (route == null ? 0 : route.length()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": intent read failed " + t);
        }
    }
}
