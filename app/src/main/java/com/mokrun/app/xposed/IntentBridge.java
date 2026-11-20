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
     * 读我们的 extras：**读写都在宿主默认加载器之上，读完立刻还原**。
     *
     * 不能用 setExtrasClassLoader 长期留在宿主 Intent 上（1.0.153 闪退根因：
     * FaceLiveV3Activity 读自己的 PointData 时用了模块的 InMemoryDexFile 加载器）；
     * 也不能只拷一份 Bundle 去读（LazyValue 仍绑定原 Bundle，回执句柄反序列化失败，
     * 表现为「模块一直未启用」）。
     */
    /** 是否携带模块自己的 key：不是我们的 Intent 一律不碰 */
    private static boolean isOurs(android.os.Bundle raw) {
        try {
            return raw.containsKey(EX_OP) || raw.containsKey(EX_CFG)
                    || raw.containsKey(EX_RUN) || raw.containsKey("mokr_rr");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void read(Intent it, String from) {
        if (it == null) return;
        android.os.Bundle raw = null;
        ClassLoader prev = null;
        boolean touched = false;
        try {
            raw = it.getExtras();
            if (raw == null || !isOurs(raw)) return;   // 不是我们的 Intent，直接不碰

            // 读完必须还原：否则模块的 InMemoryDexFile 加载器会留在宿主 Intent 上，
            // 应用之后读自己的 Parcelable 就 ClassNotFound（1.0.153 闪退根因）。
            prev = raw.getClassLoader();
            raw.setClassLoader(MotionEngine.class.getClassLoader());
            touched = true;

            final android.os.Bundle b = raw;
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
        } finally {
            if (touched) {
                try {
                    raw.setClassLoader(prev);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
