package com.mokrun.app.xposed;

import android.location.Location;
import android.location.LocationListener;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 模拟引擎：位置与步数由同一个 tick 驱动，永远同步。
 *
 * 路线 = 起点（mokr_origin）+ 途经点（mokr_route_b64）。
 * 起点变化时整体重置：位置回起点、距离清零、步数清零。
 *
 * 状态机（mokr_run）：
 *   0 = 未开始，完全不干预，体适能看到真实位置
 *   1 = 运行中，沿路线推进
 *   2 = 就绪/暂停，位置冻结在当前点
 */
public final class MotionEngine {

    private static final String TAG = "MokrRun";
    private static final long TICK_MS = 250L;

    private static volatile float sSpeed = 2.5f;
    private static volatile float sCadence = 2.2f;
    private static volatile float sStride = 1.1f;

    /** 路线：index 0 是起点，后面是途经点 */
    private static volatile double[] sLat = new double[]{39.908700};
    private static volatile double[] sLon = new double[]{116.397500};
    private static volatile String sLastOrigin = "";
    private static volatile int sLastRouteHash = 0;

    private static volatile double sCurLat = 39.908700;
    private static volatile double sCurLon = 116.397500;
    private static volatile double sDistTotal = 0.0;
    private static volatile double sSegProgress = 0.0;
    private static volatile int sSeg = 0;
    private static volatile long sSteps = 0;
    private static volatile double sStepAccum = 0.0;
    private static volatile boolean sConfigLoaded = false;

    private static volatile int sMode = 0;
    /** mode 是否由启动 Intent 指定过（provider 不可用时要靠它维持） */
    private static volatile boolean sIntentMode = false;
    private static long sRunCheckedAt = 0L;

    private static Handler sHandler = null;
    private static long sTicks = 0;
    private static long sLastTick = 0L;
    private static final ArrayList<LocationListener> sListeners = new ArrayList<LocationListener>();
    private static android.content.Context sCtx = null;

    private MotionEngine() {}

    // ---- public accessors for the hooks ------------------------------------

    public static double lat() { return sCurLat; }
    public static double lon() { return sCurLon; }
    public static float speedNow() { return sSpeed; }
    public static long steps() { return sSteps; }

    /** 正在接管位置（运行或冻结） */
    public static boolean isActive() {
        return readMode() != 0;
    }

    /** 只有运行中才推进 */
    public static boolean isRunningNow() {
        return readMode() == 1;
    }

    /**
     * 运行状态只从 ContentProvider 取。
     * 拿不到就意味着「模块没在管理」，此时必须返回 0（不接管位置），
     * 否则体适能会被上一轮的残留状态劫持。
     */
    private static int readMode() {
        long now = System.currentTimeMillis();
        if (now - sRunCheckedAt < 400L) return sMode;
        sRunCheckedAt = now;
        if (sCtx == null) return sMode;
        if (readProvider()) return sMode;
        // provider 不可用（MIUI 拦跨应用 provider）：
        // 若 mode 是 Intent 明确指定的，就保持不变，否则才归零
        if (sIntentMode) return sMode;
        sMode = 0;
        return sMode;
    }


    /** 由广播通道调用：暂停 / 继续 / 结束 */
    public static void setMode(int mode) {
        sMode = mode;
        sIntentMode = true;      // 一旦被明确设置，就由它主导，不再回落
        sRunCheckedAt = System.currentTimeMillis();
        XposedBridge.log(TAG + ": mode set to " + mode + " (via command)");
    }

    public static Location current() {
        Location l = new Location("gps");
        l.setLatitude(sCurLat);
        l.setLongitude(sCurLon);
        l.setSpeed(sSpeed);
        l.setAccuracy(3.0f);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(System.nanoTime());
        return l;
    }

    // ---- lifecycle ---------------------------------------------------------

    public static synchronized void configure(android.content.Context ctx) {
        try {
            sCtx = ctx.getApplicationContext();
        } catch (Throwable ignored) {
            sCtx = ctx;
        }
        tryRead();
    }

    public static synchronized void start() {
        if (sHandler != null) return;
        sHandler = new Handler(Looper.getMainLooper());
        sHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": tick error " + t);
                }
                if (sHandler != null) sHandler.postDelayed(this, TICK_MS);
            }
        });
        XposedBridge.log(TAG + ": motion engine started, tick=" + TICK_MS + "ms");
    }

    public static void addListener(LocationListener l) {
        if (l != null) {
            synchronized (sListeners) {
                if (!sListeners.contains(l)) sListeners.add(l);
            }
        }
        start();
    }

    // ---- config ------------------------------------------------------------

    /**
     * 主通道：模块 App 的 ContentProvider。
     * App 有常驻前台服务，provider 一定活着，MIUI 不会拦冷启动。
     * 传的都是 App 本地 prefs 里的值，读写都不需要特殊权限。
     */
    private static boolean readProvider() {
        if (sCtx == null) return false;
        android.database.Cursor c = null;
        try {
            c = sCtx.getContentResolver().query(
                    android.net.Uri.parse("content://com.mokrun.app.config/config"),
                    null, null, null, null);
            if (c == null || !c.moveToFirst()) return false;
            float sp = c.getFloat(0);
            float cd = c.getFloat(1);
            float st = c.getFloat(2);
            String origin = c.getString(4);
            String route = c.getString(5);
            int run = c.getInt(6);
            applyOriginRoute(origin, route);
            applyConfigInner(sp, cd, st, route);
            sMode = run;
            sRunCheckedAt = System.currentTimeMillis();
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": provider read failed " + t);
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 只从 ContentProvider 读配置，不再回退 Settings.System。
     * 回退曾把调试残留的测试坐标当成有效配置，导致体适能一启动就偏到几百米外。
     */
    private static synchronized boolean tryRead() {
        if (sCtx == null) return false;
        if (readProvider()) {
            sConfigLoaded = true;
            return true;
        }
        return false;
    }


    /**
     * 从模块 App 的启动 Intent 里应用配置（零权限通道）。
     * cfg 格式："speed=3.0;cadence=2.4;stride=1.25;anti=1"
     */
    public static void applyFromIntent(String cfg, String origin, String waypoints, int run) {
        float sp = -1f, cd = -1f, st = -1f;
        if (cfg != null) {
            String[] parts = cfg.split(";");
            for (int i = 0; i < parts.length; i++) {
                String kv = parts[i].trim();
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                try {
                    if ("speed".equals(k)) sp = Float.parseFloat(v);
                    else if ("cadence".equals(k)) cd = Float.parseFloat(v);
                    else if ("stride".equals(k)) st = Float.parseFloat(v);
                } catch (Throwable ignored) {
                }
            }
        }
        applyOriginRoute(origin, waypoints);
        applyConfigInner(sp, cd, st, waypoints);
        sMode = run;
        sIntentMode = true;
        sRunCheckedAt = System.currentTimeMillis();
        sConfigLoaded = true;
        XposedBridge.log(TAG + ": applied from intent speed=" + sSpeed
                + " cadence=" + sCadence + " stride=" + sStride
                + " mode=" + run + " points=" + sLat.length);
    }

    /** 只更新参数，不动路线（路线由 applyOriginRoute 负责） */
    private static void applyConfigInner(float speed, float cadence, float stride, String route) {
        if (speed > 0.1f) sSpeed = speed;
        if (cadence > 0.1f) sCadence = cadence;
        if (stride > 0.2f) sStride = stride;
    }

    /** 起点 + 途经点 → 路线数组；起点变了就重置到起点 */
    private static void applyOriginRoute(String originStr, String waypointsJson) {
        String os = originStr == null ? "" : originStr.trim();
        int wpsHash = waypointsJson == null ? 0 : waypointsJson.hashCode();
        boolean originChanged = !os.equals(sLastOrigin);
        boolean routeChanged = wpsHash != sLastRouteHash;
        if (!originChanged && !routeChanged) return;

        sLastOrigin = os;
        sLastRouteHash = wpsHash;

        double oLat = 0, oLon = 0;
        boolean hasOrigin = false;
        if (os.contains(",")) {
            try {
                String[] p = os.split(",");
                oLat = Double.parseDouble(p[0].trim());
                oLon = Double.parseDouble(p[1].trim());
                hasOrigin = true;
            } catch (Throwable ignored) {
            }
        }

        ArrayList<double[]> wps = new ArrayList<double[]>();
        if (waypointsJson != null && waypointsJson.length() > 4) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(waypointsJson);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    wps.add(new double[]{o.getDouble("lat"), o.getDouble("lng")});
                }
            } catch (Throwable ignored) {
            }
        }

        if (!hasOrigin && wps.isEmpty()) {
            XposedBridge.log(TAG + ": origin/route cleared");
            return;
        }

        int n = wps.size() + (hasOrigin ? 1 : 0);
        double[] lat = new double[n];
        double[] lon = new double[n];
        int k = 0;
        if (hasOrigin) {
            lat[k] = oLat;
            lon[k] = oLon;
            k++;
        }
        for (int i = 0; i < wps.size(); i++) {
            lat[k] = wps.get(i)[0];
            lon[k] = wps.get(i)[1];
            k++;
        }
        sLat = lat;
        sLon = lon;

        if (originChanged || routeChanged) {
            resetToStart();
            XposedBridge.log(TAG + ": route applied, " + n + " points, start="
                    + lat[0] + "," + lon[0]);
        }
    }

    private static void resetToStart() {
        sSeg = 0;
        sSegProgress = 0;
        sCurLat = sLat[0];
        sCurLon = sLon[0];
        sDistTotal = 0;
        sSteps = 0;
        sStepAccum = 0;
        sTicks = 0;
        sLastTick = 0;
    }

    // ---- tick --------------------------------------------------------------

    private static void tick() {
        if (!sConfigLoaded && sTicks % 8 == 0) {
            tryRead();
        }
        if (!isRunningNow()) {
            // 空闲时也要偶尔吭一声，否则 mode 不对的时候完全没有日志可查
            if (sTicks % 80 == 0) {
                XposedBridge.log(TAG + ": idle mode=" + sMode
                        + " intentMode=" + sIntentMode
                        + " sConfigLoaded=" + sConfigLoaded);
            }
            sTicks++;
            return;
        }

        long now = System.currentTimeMillis();
        double dt = sLastTick == 0L ? TICK_MS / 1000.0 : (now - sLastTick) / 1000.0;
        sLastTick = now;
        if (dt <= 0 || dt > 120.0) {
            dt = TICK_MS / 1000.0;
        }

        double stepDist = sSpeed * dt;
        sDistTotal += stepDist;
        sSegProgress += stepDist;

        // 只有一个点（只有起点）时位置不动
        if (sLat.length > 1) {
            while (sSeg < sLat.length - 1) {
                double segLen = dist(sLat[sSeg], sLon[sSeg], sLat[sSeg + 1], sLon[sSeg + 1]);
                if (segLen < 0.01) {
                    sSeg++;
                    continue;
                }
                if (sSegProgress < segLen) {
                    double f = sSegProgress / segLen;
                    sCurLat = sLat[sSeg] + (sLat[sSeg + 1] - sLat[sSeg]) * f;
                    sCurLon = sLon[sSeg] + (sLon[sSeg + 1] - sLon[sSeg]) * f;
                    break;
                }
                sSegProgress -= segLen;
                sSeg++;
                if (sSeg >= sLat.length - 1) {
                    sSeg = 0;
                    sSegProgress = 0;
                }
            }
        }

        sStepAccum += sCadence * dt;
        while (sStepAccum >= 1.0) {
            sStepAccum -= 1.0;
            sSteps++;
            SensorHook.pushStep(sSteps);
        }

        emitLocation();

        sTicks++;
        if (sTicks % 20 == 1) {
            XposedBridge.log(TAG + ": tick dist=" + String.format(Locale.US, "%.1f", sDistTotal)
                    + "m steps=" + sSteps
                    + String.format(Locale.US, " pos=%.6f,%.6f", sCurLat, sCurLon)
                    + " seg=" + sSeg + "/" + Math.max(0, sLat.length - 1));
        }
    }

    private static void emitLocation() {
        ArrayList<LocationListener> ls;
        synchronized (sListeners) {
            if (sListeners.isEmpty()) return;
            ls = new ArrayList<LocationListener>(sListeners);
        }
        for (int i = 0; i < ls.size(); i++) {
            try {
                ls.get(i).onLocationChanged(current());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": emit failed " + t);
            }
        }
    }

    private static double dist(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }
}
