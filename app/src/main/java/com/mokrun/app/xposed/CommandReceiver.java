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
 * 运行控制通道：预备 / 确认启动 / 暂停 / 继续 / 结束。
 *
 * 两代协议并存：
 *   旧：只带 mode（0/1/2），走 setMode 或 applyFromIntent；
 *   新：带 op=prepare|confirm 与 session/token 标识：
 *       - prepare：建立预备会话（无路线也可以），目标应用继续看到真实定位；
 *       - confirm：一次下发完整配置并在同一操作里建立路线、基数与时钟，然后 RUNNING。
 * 标识只用于生命周期与幂等，不是安全鉴权。
 *
 * 注册时机：hook Application.attach 太早了，改成在 Activity.onCreate 里注册，只注册一次。
 */
public final class CommandReceiver {

    private static final String TAG = "MokrRun";
    public static final String ACTION = "com.mokrun.app.CMD";
    public static final String EX_OP = "op";
    public static final String EX_SESSION = "session";
    public static final String EX_TOKEN = "token";
    public static final String EX_MODE = "mode";
    public static final String EX_CFG = "cfg";
    public static final String EX_ORIGIN = "origin";
    public static final String EX_ROUTE = "route";

    public static final String OP_PREPARE = "prepare";
    public static final String OP_CONFIRM = "confirm";

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
                if (intent == null) return;
                String op = intent.getStringExtra(EX_OP);
                if (OP_PREPARE.equals(op)) {
                    MotionEngine.prepareSession(intent.getStringExtra(EX_SESSION),
                            intent.getStringExtra(EX_CFG));
                    XposedBridge.log(TAG + ": command op=prepare");
                    return;
                }
                if (OP_CONFIRM.equals(op)) {
                    String err = MotionEngine.applyConfirm(
                            intent.getStringExtra(EX_SESSION),
                            intent.getStringExtra(EX_TOKEN),
                            intent.getStringExtra(EX_CFG),
                            intent.getStringExtra(EX_ORIGIN),
                            intent.getStringExtra(EX_ROUTE));
                    XposedBridge.log(TAG + ": command op=confirm result="
                            + (err == null ? "ok" : err));
                    return;
                }
                int mode = intent.getIntExtra(EX_MODE, -1);
                if (mode < 0) return;

                // 兼容旧通道：配置也可以走广播（不依赖 ContentProvider）
                String cfg = intent.getStringExtra(EX_CFG);
                if (cfg != null && cfg.length() > 4) {
                    String origin = intent.getStringExtra(EX_ORIGIN);
                    String route = intent.getStringExtra(EX_ROUTE);
                    MotionEngine.applyFromIntent(cfg, origin, route, mode);
                    XposedBridge.log(TAG + ": config via broadcast " + cfg
                            + " run=" + mode
                            + " routeLen=" + (route == null ? 0 : route.length()));
                } else {
                    MotionEngine.setMode(mode);
                }
                XposedBridge.log(TAG + ": command mode=" + mode);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": command failed " + t);
            }
        }
    }
}
