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

    /**
     * 取一份**只属于我们自己的** extras 副本。
     *
     * 关键：绝不能对宿主 Activity 的 Intent 调 setExtrasClassLoader —— 该 Intent 会被应用
     * 复用/复制（例如 getIntent() 再 startActivity），模块的 InMemoryDexFile 类加载器
     * 一旦留在上面，应用之后读自己的 Parcelable 就会 ClassNotFoundException
     * （实机 1.0.153 复现：FaceLiveV3Activity 启动即崩，PointData 反序列化失败）。
     */
    private static android.os.Bundle probeExtras(Intent it) {
        try {
            android.os.Bundle raw = it.getExtras();
            if (raw == null) return null;
            boolean ours;
            try {
                ours = raw.containsKey(EX_OP) || raw.containsKey(EX_CFG)
                        || raw.containsKey(EX_RUN) || raw.containsKey("mokr_rr");
            } catch (Throwable t) {
                return null;
            }
            if (!ours) return null;
            // 拷贝一份，类加载器只设在副本上，原 Bundle 保持宿主默认
            android.os.Bundle probe = new android.os.Bundle(raw);
            probe.setClassLoader(MotionEngine.class.getClassLoader());
            return probe;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": probe extras failed " + t);
            return null;
        }
    }

    private static void read(Intent it, String from) {
        try {
            if (it == null) return;
            final android.os.Bundle b = probeExtras(it);
            if (b == null) return;                 // 不是我们的 Intent，直接不碰
            String op = b.getString(EX_OP);
            String session = b.getString(EX_SESSION);
            String token = b.getString(EX_TOKEN);
            String cfg = b.getString(EX_CFG);
            String origin = b.getString(EX_ORIGIN);
            String route = b.getString(EX_ROUTE);
            int run = b.getInt(EX_RUN, 0);

            try {
                android.os.Parcelable rr = b.getParcelable("mokr_rr");
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
