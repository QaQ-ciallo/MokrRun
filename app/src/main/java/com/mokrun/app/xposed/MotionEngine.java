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
    /** 伪装用的卫星数：真实 GPS 常见 8~22，这里给 14±2 的抖动，避免恒定值 */
    private static volatile int sSat = 14;
    /** AMapLocationQualityReport.getGPSStatus() 的伪装值，可用 cfg 里的 qr= 覆盖 */
    private static volatile int sQrStatus = 2;
    /**
     * 速度上限（最后更新：2026-11-10 07:56 实测扫点）。
     *
     * 扫点结果（GPS 质量伪装全开，同一场次热切换，窗长 40~62 s）：
     *   2.8 m/s → 比值 1.01    3.5 → 0.96    5.0 → 0.95
     *   5.5 m/s → 0.47    6.2 → 0.50        ← 过阈值直接掉一半
     * 所以目标应用的上限在 5.0~5.5 m/s（18~20 km/h）之间，默认夹到 5.0。
     *
     * 注意：早前「3 m/s 上限」的结论是错的——那次同时叠加了 GPS 质量闸门，
     * 速度与质量两个变量没分开。质量伪装（qr/sat/acc）生效后，5.0 m/s 是正常的。
     */
    private static volatile float sSpeedMax = 5.0f;
    /** 是否屏蔽真实传感器注册（步数双源 A/B 开关，cfg 里 rs=1 打开） */
    private static volatile boolean sSuppressRealSteps = false;
    /** 跨场次累计的步数基数：保证 TYPE_STEP_COUNTER 单调递增，不会「变小」 */
    private static volatile long sStepBase = 0;
    /** 伪装海拔（米）。真实 Location 里没设 altitude，取出来是 0.0，本身就很可疑 */
    private static volatile float sAlt = 25f;
    /** AMapLocation.getGpsAccuracyStatus() 的伪装值；真实值 -1，默认 0（“精度正常”） */
    private static volatile int sGpsAccStatus = 0;
    /** 人脸识别期间冻结状态 */
    private static volatile boolean sFaceFrozen = false;
    private static volatile int sPrevMode = 1;
    private static Handler sFaceHandler = null;
    /** 人脸识别冻结最长 3 分钟，超时自动恢复（防止卡死在暂停态） */
    private static final long FACE_FREEZE_MS = 180000L;

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

    /** 伪装卫星数：14 上下 ±2 抖动（真实 GPS 不会恒定） */
    public static int satellites() {
        long t = sTicks;
        int jitter = (int) (t % 5) - 2;
        return sSat + jitter;
    }

    public static String provider() { return "gps"; }

    public static int qrStatus() { return sQrStatus; }

    public static boolean suppressRealSteps() { return sSuppressRealSteps; }

    /** 伪装海拔：在 25m 上下 ±3 抖动（东莞一带地形高度） */
    public static double altitude() {
        return sAlt + ((sTicks % 7) - 3) * 0.5;
    }

    public static int gpsAccStatus() { return sGpsAccStatus; }

    /**
     * 中途人脸识别 → 立刻冻结。
     *
     * 实测（07:36 弹窗）：「中途第2次人脸识别超出150米允许识别范围，本次跑步无效」。
     * 也就是说弹出识别时，模拟位置必须停在原地（150 m 内），
     * 否则识别窗口一过就判无效。真跑步时人本来就会停下做识别，
     * 所以这里等价于「有人脸请求就停表」。
     */
    public static void freezeForFace() {
        if (sFaceFrozen) return;
        sFaceFrozen = true;
        sPrevMode = sMode;
        setMode(2);
        XposedBridge.log(TAG + ": face check detected -> motion frozen (mode=2)");
        try {
            if (sFaceHandler == null) sFaceHandler = new Handler(Looper.getMainLooper());
            sFaceHandler.removeCallbacksAndMessages(null);
            sFaceHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    XposedBridge.log(TAG + ": face freeze timeout -> resuming");
                    resumeAfterFace();
                }
            }, FACE_FREEZE_MS);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": face freeze timer failed " + t);
        }
    }

    /** 识别做完后恢复原来的模式（默认回 1 = 继续跑） */
    public static void resumeAfterFace() {
        if (!sFaceFrozen) return;
        sFaceFrozen = false;
        int back = sPrevMode == 0 ? 1 : sPrevMode;
        setMode(back);
        XposedBridge.log(TAG + ": face check done -> resume mode=" + back);
    }

    public static boolean faceFrozen() { return sFaceFrozen; }

    public static boolean hasRoute() { return sLat != null && sLat.length > 1; }

    /** 正在接管位置（运行或冻结）。没有路线时不接管——否则会把人丢到默认坐标上 */
    public static boolean isActive() {
        return readMode() != 0 && hasRoute();
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
        l.setAltitude(altitude());
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(System.nanoTime());
        // 系统真实 GPS 的 Location 自带 satellites/maxCn0/meanCn0 三个 extras
        // （dumpsys location 可见），不少应用直接从这里读卫星数画信号格。
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putInt("satellites", satellites());
            b.putInt("maxCn0", 33);
            b.putInt("meanCn0", 29);
            l.setExtras(b);
        } catch (Throwable ignored) {
        }
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
            applyConfigInner(sp, cd, st);
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
        int sat = -1;
        int qr = -1;
        float spdmax = -1f;
        int rs = -1;
        float alt = -1f;
        int acc = -2;
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
                    else if ("sat".equals(k)) sat = Integer.parseInt(v);
                    else if ("qr".equals(k)) qr = Integer.parseInt(v);
                    else if ("spdmax".equals(k)) spdmax = Float.parseFloat(v);
                    else if ("rs".equals(k)) rs = Integer.parseInt(v);
                    else if ("alt".equals(k)) alt = Float.parseFloat(v);
                    else if ("acc".equals(k)) acc = Integer.parseInt(v);
                } catch (Throwable ignored) {
                }
            }
        }
        if (sat > 0) sSat = sat;
        if (qr >= 0) sQrStatus = qr;
        if (spdmax > 0.1f) sSpeedMax = spdmax;
        if (rs >= 0) sSuppressRealSteps = (rs == 1);
        if (alt > 0f) sAlt = alt;
        if (acc >= 0) sGpsAccStatus = acc;
        applyOriginRoute(origin, waypoints);
        applyConfigInner(sp, cd, st);
        sMode = run;
        sIntentMode = true;
        sRunCheckedAt = System.currentTimeMillis();
        sConfigLoaded = true;
        XposedBridge.log(TAG + ": applied from intent speed=" + sSpeed
                + " cadence=" + sCadence + " stride=" + sStride
                + " sat=" + sSat
                + " mode=" + run + " points=" + sLat.length);
    }

    /** 只更新参数，不动路线（路线由 applyOriginRoute 负责） */
    private static void applyConfigInner(float speed, float cadence, float stride) {
        if (speed > 0.1f) {
            float eff = speed;
            if (speed > sSpeedMax) {
                eff = sSpeedMax;
                XposedBridge.log(TAG + ": speed " + speed + " clamped to " + sSpeedMax
                        + " (target drops points above ~3.0 m/s)");
            }
            sSpeed = eff;
        }
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
        // 步数不能回退：TYPE_STEP_COUNTER 的语义是「开机以来累计」，
        // 一旦变小就是很明显的伪造特征。所以本场清零时把基数累加过去。
        sStepBase += sSteps;
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
            // 关键：暂停期间也要刷新时间基准。
            // 否则恢复时 dt = 整个暂停时长，引擎会把这段时间的距离一次性补上，
            // 表现为位置瞬移、里程和步数突然跳一大截。
            sLastTick = System.currentTimeMillis();
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
            SensorHook.pushStep(sStepBase + sSteps);
        }

        SDKLocationHook.dumpStats();

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
