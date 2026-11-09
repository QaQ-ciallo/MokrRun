package com.mokrun.app.xposed;

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
 * 这三个动作不需要传配置，只需要改一个数字，所以用显式广播就够了
 * （setPackage 定向发送，不触发 Android 8+ 的隐式广播限制）。
 * 这样悬浮窗按暂停时不会把目标应用切到前台。
 */
public final class CommandReceiver {

    private static final String TAG = "MokrRun";
    public static final String ACTION = "com.mokrun.app.CMD";
    public static final String EX_MODE = "mode";

    private static boolean sInstalled = false;

    private CommandReceiver() {}

    public static void install(final ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl,
                    "attach", Context.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            register(ctx);
                        }
                    });
            XposedBridge.log(TAG + ": command receiver wired");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": command receiver install failed " + t);
        }
    }

    private static void register(Context ctx) {
        try {
            IntentFilter f = new IntentFilter(ACTION);
            Receiver r = new Receiver();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            XposedBridge.log(TAG + ": command receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": register agent failed " + t);
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
