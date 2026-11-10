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
                + ";acc=0";
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
        if (mode == 1) return "运行中";
        if (mode == 2) return "就绪 · 已暂停";
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
