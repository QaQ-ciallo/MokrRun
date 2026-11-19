package com.mokrun.app.data;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 配置通道：Settings.System
 *
 * 选它而不是 Settings.Global，是因为写入所需的权限级别不同：
 *   WRITE_SECURE_SETTINGS  只能 adb / root 授予，普通用户几乎不可能做到
 *   WRITE_SETTINGS         用户可以在系统设置里手动允许，App 也能直接跳转过去
 * 而两者**读取都不需要任何权限**，hook 侧可以随便读。
 *
 * 四个键：
 *   mokr_config      参数  speed=..;cadence=..;stride=..;anti=..
 *   mokr_origin      起点  "lat,lon"
 *   mokr_route_b64   途经点 base64(JSON 数组，不含起点)
 *   mokr_run         状态  0 停止 / 1 运行 / 2 就绪·暂停
 */
public final class ConfigSync {

    private static final String TAG = "MokrRunCfg";
    public static final String KEY_CFG = "mokr_config";
    public static final String KEY_ORIGIN = "mokr_origin";
    public static final String KEY_ROUTE = "mokr_route_b64";
    public static final String KEY_RUN = "mokr_run";

    private ConfigSync() {}

    // ---- write side --------------------------------------------------------

    /** 是否已获得「修改系统设置」权限 */
    public static boolean canWrite(Context ctx) {
        try {
            return Settings.System.canWrite(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳到系统页面让用户手动允许（这是唯一需要用户操作的权限） */
    public static void requestWrite(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "cannot open write settings page", t);
        }
    }

    /**
     * "speed=4.8;cadence=3.1;stride=1.55;anti=1;spdmax=5.0;sat=14;qr=2;acc=0"
     *
     * 后四个是模拟侧的固定配方（实测结论，见交接校验报告 §11）：
     *   spdmax=5.0  目标应用的速度上限，超过只认一半里程
     *   sat=14      AMapLocation.getSatellites / 质量报告的卫星数
     *   qr=2        AMapLocationQualityReport.getGPSStatus（0 = GPS 无效，会弹轨迹异常）
     *   acc=0       AMapLocation.getGpsAccuracyStatus（真实值 -1）
     */
    public static String buildLine(Context ctx) {
        return "speed=" + Store.speed(ctx)
                + ";cadence=" + Store.cadence(ctx)
                + ";stride=" + Store.stride(ctx)
                + ";anti=" + (Store.anti(ctx) ? "1" : "0")
                + ";spdmax=5.0"
                + ";sat=14"
                + ";qr=2"
                + ";acc=0"
                + ";rs=1";
    }

    /**
     * 下发配置。
     *
     * 两条轨道：
     *   A. 有 WRITE_SECURE_SETTINGS（一次 adb 授予）→ 写 Settings.Global，最可靠
     *   B. 没有 → 不动设置，hook 通过 ContentProvider 直接读本地 prefs
     *      这条路在原生 Android 上可用；MIUI 会拦跨应用 provider，需要 A。
     *
     * @return null 表示至少有一条轨道可用
     */
    public static String push(Context ctx) {
        boolean globalOk = canWriteSecure(ctx);
        if (globalOk) {
            try {
                putGlobal(ctx, KEY_CFG, buildLine(ctx));
                double[] o = Store.origin(ctx);
                putGlobal(ctx, KEY_ORIGIN, o == null ? "" : (o[0] + "," + o[1]));
                String route = waypointsJson(ctx);
                putGlobal(ctx, KEY_ROUTE, route.isEmpty() ? ""
                        : Base64.encodeToString(route.getBytes("UTF-8"), Base64.NO_WRAP));
                Log.i(TAG, "pushed via Settings.Global");
                return null;
            } catch (Throwable t) {
                Log.e(TAG, "global push failed", t);
            }
        }
        // 轨道 B：配置已经在 prefs 里，provider 会带出去
        Log.i(TAG, "prefs updated, relying on ContentProvider (no WRITE_SECURE_SETTINGS)");
        return null;
    }

    /** 是否具备写 Settings.Global 的权限（只能 adb / root 授予） */
    public static boolean canWriteSecure(Context ctx) {
        try {
            return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void putGlobal(Context ctx, String key, String value) {
        Settings.Global.putString(ctx.getContentResolver(), key, value);
    }

    /** 运行状态存本地 prefs，由 ContentProvider 暴露；有权限时同时写 Global 兜底 */
    public static void setMode(Context ctx, int mode) {
        Store.setRunMode(ctx, mode);
        if (canWriteSecure(ctx)) {
            try {
                putGlobal(ctx, KEY_RUN, String.valueOf(mode));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- 会话通道：预备 / 确认启动 ----------------------------------------
    //
    // 目标进程可能已在前台（广播/onNewIntent），也可能没打开（需要拉起 + onCreate）。
    // 两条路径携带同一套 extras，确认 token 由引擎做幂等，重复送达只应用一次。

    public static final String CMD_ACTION = "com.mokrun.app.CMD";
    public static final String ACK_ACTION = "com.mokrun.app.ACK";
    public static final String OP_PREPARE = "prepare";
    public static final String OP_CONFIRM = "confirm";

    public static final int MODE_OFF = 0;
    public static final int MODE_RUNNING = 1;
    public static final int MODE_PAUSED = 2;
    public static final int MODE_PREPARED = 3;

    public static final String TARGET_PKG = "com.bxkj.student";
    public static final String TARGET_ACTIVITY = "com.bxkj.student.splash.SplashActivity";

    /** 建立预备会话：目标进程已在前台时走显式广播 */
    public static boolean sendPrepare(Context ctx, String session) {
        try {
            Intent i = new Intent(CMD_ACTION);
            i.setPackage(TARGET_PKG);
            i.putExtra("op", OP_PREPARE);
            i.putExtra("session", session == null ? "" : session);
            i.putExtra("cfg", buildLine(ctx));
            ctx.sendBroadcast(i);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "sendPrepare failed", t);
            return false;
        }
    }

    /** 建立预备会话并携带 ResultReceiver（binder 回调，最小验证用） */
    public static boolean launchPrepared(Context ctx, String session, android.os.Parcelable rr) {
        Intent i = baseTargetIntent(ctx);
        i.putExtra("mokr_op", OP_PREPARE);
        i.putExtra("mokr_session", session == null ? "" : session);
        i.putExtra("mokr_cfg", buildLine(ctx));
        if (rr != null) i.putExtra("mokr_rr", rr);
        return startTarget(ctx, i);
    }

    /** 建立预备会话：目标进程没打开时拉起（配置随启动 Intent 送达） */
    public static boolean launchPrepared(Context ctx, String session) {
        Intent i = baseTargetIntent(ctx);
        i.putExtra("mokr_op", OP_PREPARE);
        i.putExtra("mokr_session", session == null ? "" : session);
        i.putExtra("mokr_cfg", buildLine(ctx));
        return startTarget(ctx, i);
    }

    /**
     * 确认启动：一次下发完整配置。
     * 已在前台 → 显式广播；没打开 → 拉起并在 onCreate 应用同一份配置。
     */
    public static boolean sendConfirm(Context ctx, String session, String token,
                                      String origin, String route) {
        try {
            Intent i = new Intent(CMD_ACTION);
            i.setPackage(TARGET_PKG);
            i.putExtra("op", OP_CONFIRM);
            i.putExtra("session", session == null ? "" : session);
            i.putExtra("token", token == null ? "" : token);
            i.putExtra("cfg", buildLine(ctx));
            i.putExtra("origin", origin == null ? "" : origin);
            i.putExtra("route", route == null ? "" : route);
            ctx.sendBroadcast(i);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "sendConfirm failed", t);
            return false;
        }
    }

    public static boolean launchConfirm(Context ctx, String session, String token,
                                        String origin, String route) {
        Intent i = baseTargetIntent(ctx);
        i.putExtra("mokr_op", OP_CONFIRM);
        i.putExtra("mokr_session", session == null ? "" : session);
        i.putExtra("mokr_token", token == null ? "" : token);
        i.putExtra("mokr_cfg", buildLine(ctx));
        i.putExtra("mokr_origin", origin == null ? "" : origin);
        i.putExtra("mokr_route", route == null ? "" : route);
        return startTarget(ctx, i);
    }

    private static Intent baseTargetIntent(Context ctx) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        // 不用 getLaunchIntentForPackage：HMA 之类的隐藏模块会把它挡掉
        i.setClassName(TARGET_PKG, TARGET_ACTIVITY);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return i;
    }

    private static boolean startTarget(Context ctx, Intent i) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "launch target failed", t);
            return false;
        }
    }

    /** 显式清除当前活动路线（用户主动操作；与「配置快照为空」严格区分） */
    public static boolean sendClear(Context ctx, String session) {
        try {
            Intent i = new Intent(CMD_ACTION);
            i.setPackage(TARGET_PKG);
            i.putExtra("op", "clear");
            i.putExtra("session", session == null ? "" : session);
            ctx.sendBroadcast(i);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "sendClear failed", t);
            return false;
        }
    }

    /** 把目标应用切到前台（不携带任何配置，不改变状态） */
    public static boolean bringTargetToFront(Context ctx) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(TARGET_PKG, TARGET_ACTIVITY);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return startTarget(ctx, i);
    }
    /** 目标进程是否在运行：用于选择「广播」还是「拉起」通道 */
    public static boolean isTargetRunning(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps =
                    am.getRunningAppProcesses();
            if (ps == null) return false;
            for (int i = 0; i < ps.size(); i++) {
                if (TARGET_PKG.equals(ps.get(i).processName)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
    // ---- read side（hook 也走这里，不需要权限）------------------------------

    public static String get(Context ctx, String key) {
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), key);
            if (v != null) return v;
        } catch (Throwable ignored) {
        }
        try {
            return Settings.System.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int getMode(Context ctx) {
        return Store.runMode(ctx);
    }

    public static String modeName(int mode) {
        if (mode == MODE_RUNNING) return "运行中";
        if (mode == MODE_PAUSED) return "已暂停";
        if (mode == MODE_PREPARED) return "已启用 · 待配置";
        return "未开始";
    }

    /** 只取途经点（起点单独走 mokr_origin） */
    public static String waypointsJson(Context ctx) {
        JSONObject r = Store.activeRoute(ctx);
        if (r == null) return "";
        JSONArray pts = r.optJSONArray("points");
        return pts == null ? "" : pts.toString();
    }

    public static String readBack(Context ctx) {
        try {
            return "参数: " + orEmpty(get(ctx, KEY_CFG))
                    + "\n起点: " + orEmpty(get(ctx, KEY_ORIGIN))
                    + "\n途经点: " + orEmpty(get(ctx, KEY_ROUTE))
                    + "\n状态: " + modeName(getMode(ctx));
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    private static String orEmpty(String s) {
        return s == null || s.isEmpty() ? "(空)" : s;
    }
}
