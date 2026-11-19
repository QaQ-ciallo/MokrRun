package com.mokrun.app.xposed;

import android.app.Activity;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 配置的零权限通道（Intent 侧）。
 *
 * 与 CommandReceiver 共用一套 extras：op=prepare|confirm、session、token、
 * cfg、origin、route、mode。这样「目标应用没打开」时也能一次把会话/确认送进去，
 * 并且确认只在目标进程内应用一次（幂等由 MotionEngine 的 token 保证）。
 */
public final class IntentBridge {

    private static final String TAG = "MokrRun";
    private static final String EX_OP = "mokr_op";
    private static final String EX_SESSION = "mokr_session";
    private static final String EX_TOKEN = "mokr_token";
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
                    Object obj = param.thisObject;
                    if (!(obj instanceof Activity)) return;
                    // onCreate 没有 Intent 参数：启动 Intent 只能从 Activity.getIntent() 取
                    read(((Activity) obj).getIntent(), "onCreate");
                }
            });
            XposedBridge.hookAllMethods(activity, "onNewIntent", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // Android 官方文档：onNewIntent 期间 getIntent() 仍返回**旧的**启动 Intent，
                    // 除非应用自己调用了 setIntent(newIntent)。
                    Intent incoming = null;
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] instanceof Intent) {
                            incoming = (Intent) param.args[i];
                            break;
                        }
                    }
                    if (incoming == null) return;
                    read(incoming, "onNewIntent");
                }
            });
            XposedBridge.log(TAG + ": intent bridge installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": intent bridge failed " + t);
        }
    }

    private static void read(Intent it, String from) {
        try {
            if (it == null) return;
            String op = it.getStringExtra(EX_OP);
            String session = it.getStringExtra(EX_SESSION);
            String token = it.getStringExtra(EX_TOKEN);
            String cfg = it.getStringExtra(EX_CFG);
            String origin = it.getStringExtra(EX_ORIGIN);
            String route = it.getStringExtra(EX_ROUTE);
            int run = it.getIntExtra(EX_RUN, 0);

            // 模块把 ResultReceiver 句柄放在 extras 里：拿到后引擎在状态切换完成时直接回调
            try {
                // extras 里是模块 APK 里的类（ResultReceiver 子类）：目标进程默认用体适能的
                // classloader 反序列化会抛 BadParcelableException，必须换成模块的 classloader
                it.setExtrasClassLoader(MotionEngine.class.getClassLoader());
                android.os.Parcelable rr = it.getParcelableExtra("mokr_rr");
                if (rr != null) MotionEngine.setAckReceiver(rr);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": read result receiver failed " + t);
            }

            if (MotionEngine.OP_PREPARE.equals(op)) {
                MotionEngine.prepareSession(session, cfg);
                XposedBridge.log(TAG + ": config via Intent (" + from + ") op=prepare session="
                        + session);
                return;
            }
            if (MotionEngine.OP_CLEAR.equals(op)) {
                String err = MotionEngine.clearExplicit(session);
                XposedBridge.log(TAG + ": config via Intent (" + from + ") op=clear result="
                        + (err == null ? "ok" : err));
                return;
            }
            if (MotionEngine.OP_CONFIRM.equals(op)) {
                String err = MotionEngine.applyConfirm(session, token, cfg, origin, route);
                XposedBridge.log(TAG + ": config via Intent (" + from + ") op=confirm result="
                        + (err == null ? "ok" : err)
                        + " routeLen=" + (route == null ? 0 : route.length()));
                return;
            }
            if (cfg == null || cfg.length() < 5) return;
            MotionEngine.applyFromIntent(cfg, origin, route, run);
            XposedBridge.log(TAG + ": config via Intent (" + from + ") "
                    + cfg + " run=" + run
                    + " routeLen=" + (route == null ? 0 : route.length()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": intent read failed " + t);
        }
    }
}
