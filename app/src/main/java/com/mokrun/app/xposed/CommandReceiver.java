package com.mokrun.app.xposed;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 运行控制通道：暂停 / 继续 / 结束。
 *
 * 这三个动作不需要传配置，只需要改一个数字，用显式广播就够了
 * （setPackage 定向发送，不触发 Android 8+ 的隐式广播限制），
 * 这样按暂停时不会把目标应用切到前台。
 *
 * 注册时机：hook Application.attach 太早了——我们注入的时候它早就调用过，
 * 回调永远不触发。改成在 Activity.onCreate 里拿 Context 注册，只注册一次。
 */
public final class CommandReceiver {

    private static final String TAG = "MokrRun";
    public static final String ACTION = "com.mokrun.app.CMD";
    public static final String EX_MODE = "mode";

    private static boolean sInstalled = false;
    private static volatile boolean sRegistered = false;

    private CommandReceiver() {}

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            Class<?> activity = XposedHelpers.findClass("android.app.Activity", cl);
            XposedBridge.hookAllMethods(activity, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (sRegistered) return;
                    if (!(param.thisObject instanceof Activity)) return;
                    register(((Activity) param.thisObject).getApplicationContext());
                }
            });
            XposedBridge.log(TAG + ": command receiver wired");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": command receiver install failed " + t);
        }
    }

    private static synchronized void register(Context ctx) {
        if (ctx == null || sRegistered) return;
        try {
            IntentFilter f = new IntentFilter(ACTION);
            Receiver r = new Receiver();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            sRegistered = true;
            XposedBridge.log(TAG + ": command receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": register failed " + t);
        }
    }

    static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                int mode = intent.getIntExtra(EX_MODE, -1);
                if (mode < 0) return;
                MotionEngine.setMode(mode);
                XposedBridge.log(TAG + ": command mode=" + mode);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": command failed " + t);
            }
        }
    }
}
