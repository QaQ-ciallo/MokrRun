package com.mokrun.app.xposed;

import android.location.Location;
import android.location.LocationListener;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

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

    // ---- 参数合法区间 ------------------------------------------------------
    // 任何来源（Intent / 广播 / ContentProvider / 设置页）的数值都必须过硬校验：
    // Float.parseFloat 对超长数字返回 Infinity，而 Infinity 一旦进入累加器，
    // 「减去 1 仍然是 Infinity」会让每个 tick 的补步循环永远退不出去，主线程直接卡死。
    private static final float SPEED_MIN = 0.1f;
    private static final float SPEED_HARD_MAX = 20f;
    private static final float CADENCE_MIN = 0.1f;
    private static final float CADENCE_MAX = 10f;
    private static final float STRIDE_MIN = 0.2f;
    private static final float STRIDE_MAX = 5f;
    private static final int SAT_MIN = 1;
    private static final int SAT_MAX = 64;
    private static final int QR_MIN = 0;
    private static final int QR_MAX = 2;
    private static final float ALT_MIN = -500f;
    private static final float ALT_MAX = 9000f;
    private static final int ACC_MIN = -1;
    private static final int ACC_MAX = 3;
    private static final int MODE_MIN = 0;
    private static final int MODE_MAX = 2;
    /** 单 tick 最多补多少步：即使配置/时钟异常，主线程也只被占用有限次迭代 */
    private static final int STEP_MAX_PER_TICK = 64;
    /** 步数累计使用的 dt 上限：长时间卡顿恢复时不能一次性补几百步 */
    private static final double STEP_DT_MAX = 2.0;

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
    /** 默认只投递合成步数；rs=0 仅用于诊断双源，正常运行必须保持 rs=1。 */
    private static volatile boolean sSuppressRealSteps = true;
    /** 跨场次累计的步数基数：保证 TYPE_STEP_COUNTER 单调递增，不会「变小」 */
    private static volatile long sStepBase = 0;
    /** 伪装海拔（米）。真实 Location 里没设 altitude，取出来是 0.0，本身就很可疑 */
    private static volatile float sAlt = 25f;
    /** AMapLocation.getGpsAccuracyStatus() 的伪装值；真实值 -1，默认 0（“精度正常”） */
    private static volatile int sGpsAccStatus = 0;
    /** 人脸识别期间冻结状态 */
    private static volatile boolean sFaceFrozen = false;
    /** 冻结前的模式；冻结期间若 App 侧改了意图（provider 的 run 变化），这里会被更新 */
    private static volatile int sPrevMode = 1;
    private static Handler sFaceHandler = null;
    /** 人脸识别冻结最长 3 分钟，超时自动恢复（防止卡死在暂停态） */
    private static final long FACE_FREEZE_MS = 180000L;
    /** provider 最近一次读到的 run 值，用于区分「App 改了状态」和「App prefs 是旧的」 */
    private static final int PROVIDER_MODE_UNKNOWN = Integer.MIN_VALUE;
    private static volatile int sProviderMode = PROVIDER_MODE_UNKNOWN;

    /** 路线：index 0 是起点，后面是途经点。空数组 = 没有有效路线 */
    private static volatile double[] sLat = new double[0];
    private static volatile double[] sLon = new double[0];
    private static volatile String sLastOrigin = "";
    private static volatile int sLastRouteHash = 0;

    /** 没有有效路线时不接管位置，也不给目标应用任何默认坐标 */
    private static volatile double sCurLat = 0.0;
    private static volatile double sCurLon = 0.0;
    private static volatile double sDistTotal = 0.0;
    private static volatile double sSegProgress = 0.0;
    private static volatile int sSeg = 0;
    /**
     * 推进方向：+1 沿数组向后，-1 折返。
     * 路线是用户画的开放折线，没有任何「末点回到起点」的闭合腿；
     * 到端点后沿原路折返，既不瞬移，也不凭空补出用户没画过的几何。
     */
    private static volatile int sDir = 1;
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
    /** 定位监听器：value = true 表示单次定位（requestSingleUpdate），投递一次即解除 */
    private static final Map<LocationListener, Boolean> sListeners =
            Collections.synchronizedMap(new IdentityHashMap<LocationListener, Boolean>());
    private static android.content.Context sCtx = null;

    private MotionEngine() {}

    /** 只接受「有限且落在业务区间内」的浮点数；越界/NaN/Infinity 一律拒绝 */
    private static boolean inRange(float v, float min, float max) {
        return !Float.isNaN(v) && !Float.isInfinite(v) && v >= min && v <= max;
    }

    private static boolean inRange(int v, int min, int max) {
        return v >= min && v <= max;
    }

    // ---- public accessors for the hooks ------------------------------------

    public static double lat() { return sCurLat; }
    public static double lon() { return sCurLon; }

    /**
     * 当前运动速度。配置速度只用于推进计算：暂停/未开始时人是不动的，
     * 这里必须回 0，否则目标应用会看到「位置冻结但速度为 4.8 m/s」的矛盾数据。
     */
    public static float speedNow() { return isRunningNow() ? sSpeed : 0f; }

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
        // 恢复目标 = 冻结前的模式；未开始时保持未开始，暂停时保持暂停
        sPrevMode = sMode;
        applyInternalMode(2);
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

    /** 识别做完后恢复冻结前的模式。不会把「未开始」猜成「运行」 */
    public static void resumeAfterFace() {
        if (!sFaceFrozen) return;
        sFaceFrozen = false;
        int back = sPrevMode;
        applyInternalMode(back);
        XposedBridge.log(TAG + ": face check done -> resume mode=" + back);
    }

    public static boolean faceFrozen() { return sFaceFrozen; }

    /**
     * 撤销冻结但不改变当前模式。
     * 用户按下暂停/结束/继续，或者冻结超时任务被替换时使用——
     * 之后再来的旧弹窗关闭事件、旧超时任务都不能把状态改回去。
     */
    private static void cancelFaceFreeze(String reason) {
        if (!sFaceFrozen) return;
        sFaceFrozen = false;
        Handler h = sFaceHandler;
        if (h != null) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": face freeze cancelled (" + reason + ")");
    }

    /** 引擎自己的状态切换（冻结/恢复），不走「显式命令」的取消冻结逻辑 */
    private static void applyInternalMode(int mode) {
        sMode = inRange(mode, MODE_MIN, MODE_MAX) ? mode : 0;
        sIntentMode = true;
        sRunCheckedAt = System.currentTimeMillis();
    }

    public static boolean hasRoute() { return sLat != null && sLat.length > 1; }

    /** 正在接管位置（运行或冻结）。没有路线时不接管——否则会把人丢到默认坐标上 */
    public static boolean isActive() {
        return readMode() != 0 && hasRoute();
    }

    /** 只有「运行中」并且有可推进的路线时才推进：没有路线时位置不许动、里程与步数也不许涨 */
    public static boolean isRunningNow() {
        return readMode() == 1 && hasRoute();
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
        Integer run = readProvider();
        if (run != null) {
            applyProviderMode(run.intValue());
            return sMode;
        }
        // provider 不可用（MIUI 拦跨应用 provider）：
        // 若 mode 是 Intent 明确指定的，就保持不变，否则才归零
        if (sIntentMode) return sMode;
        sMode = 0;
        return sMode;
    }

    /**
     * provider 里的 run 是「App 侧的用户意图」，但只有它**发生变化**时
     * 才代表一次新的用户操作。值没变说明 App 的 prefs 还是旧的
     * （例如用户用 adb 广播暂停、或引擎自己为了人脸识别冻结），
     * 此时循环读取不能把引擎里更新的状态覆盖回去。
     */
    private static void applyProviderMode(int run) {
        if (!inRange(run, MODE_MIN, MODE_MAX)) return;
        boolean first = sProviderMode == PROVIDER_MODE_UNKNOWN;
        boolean changed = !first && run != sProviderMode;
        sProviderMode = run;
        if (sFaceFrozen) {
            // 冻结期间不解除冻结，但把用户的最新意图记成恢复目标
            if (!first && changed) sPrevMode = run;
            return;
        }
        if (first) {
            if (!sIntentMode) sMode = run;
            return;
        }
        if (changed) sMode = run;
    }


    /** 由广播通道调用：暂停 / 继续 / 结束 */
    public static void setMode(int mode) {
        if (!inRange(mode, MODE_MIN, MODE_MAX)) {
            XposedBridge.log(TAG + ": mode rejected (" + mode + ")");
            return;
        }
        // 显式命令优先于内部冻结：用户已经用暂停/继续/结束表达了新意图，
        // 不能再等 3 分钟超时或某次旧弹窗关闭把状态改回冻结前的值。
        cancelFaceFreeze("command mode=" + mode);
        sMode = mode;
        sIntentMode = true;      // 一旦被明确设置，就由它主导，不再回落
        sRunCheckedAt = System.currentTimeMillis();
        XposedBridge.log(TAG + ": mode set to " + mode + " (via command)");
    }

    public static Location current() {
        Location l = new Location("gps");
        l.setLatitude(sCurLat);
        l.setLongitude(sCurLon);
        l.setSpeed(speedNow());
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
        addListener(l, false);
    }

    /** requestSingleUpdate 的监听器：只投递一次，投递后自动解除 */
    public static void addSingleShotListener(LocationListener l) {
        addListener(l, true);
    }

    private static void addListener(LocationListener l, boolean oneShot) {
        if (l != null) {
            synchronized (sListeners) {
                Boolean previous = sListeners.get(l);
                // 连续注册不会被单次注册降级；单次注册也不会把连续注册改成单次
                if (previous == null || (previous.booleanValue() && !oneShot)) {
                    sListeners.put(l, Boolean.valueOf(oneShot));
                }
            }
        }
        start();
    }

    /**
     * 框架调用 removeUpdates(listener) 时同步解除引擎引用。
     * 之前没有任何移除路径：应用注销后引擎每个 tick 仍在回调它，
     * 目标应用会收到「已经注销却还在更新」的定位，属于明显异常。
     */
    public static void removeListener(LocationListener l) {
        if (l == null) return;
        boolean removed;
        synchronized (sListeners) {
            removed = sListeners.remove(l) != null;
        }
        if (removed) XposedBridge.log(TAG + ": location listener removed");
    }

    // ---- config ------------------------------------------------------------

    /**
     * 主通道：模块 App 的 ContentProvider。
     * App 有常驻前台服务，provider 一定活着，MIUI 不会拦冷启动。
     * 传的都是 App 本地 prefs 里的值，读写都不需要特殊权限。
     */
    private static Integer readProvider() {
        if (sCtx == null) return null;
        android.database.Cursor c = null;
        try {
            c = sCtx.getContentResolver().query(
                    android.net.Uri.parse("content://com.mokrun.app.config/config"),
                    null, null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            float sp = c.getFloat(0);
            float cd = c.getFloat(1);
            float st = c.getFloat(2);
            String origin = c.getString(4);
            String route = c.getString(5);
            int run = c.getInt(6);
            applyOriginRoute(origin, route);
            applyConfigInner(sp, cd, st);
            sRunCheckedAt = System.currentTimeMillis();
            return Integer.valueOf(run);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": provider read failed " + t);
            return null;
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
        Integer run = readProvider();
        if (run == null) return false;
        applyProviderMode(run.intValue());
        sConfigLoaded = true;
        return true;
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
        if (sat > 0) sSat = inRange(sat, SAT_MIN, SAT_MAX) ? sat : sSat;
        if (qr >= 0) sQrStatus = inRange(qr, QR_MIN, QR_MAX) ? qr : sQrStatus;
        if (spdmax > 0.1f) sSpeedMax = inRange(spdmax, SPEED_MIN, SPEED_HARD_MAX) ? spdmax : sSpeedMax;
        if (rs == 0 || rs == 1) sSuppressRealSteps = (rs == 1);
        if (alt > ALT_MIN) sAlt = inRange(alt, ALT_MIN, ALT_MAX) ? alt : sAlt;
        // acc<0 是「不伪装、保留真实值」的显式约定（见 SDKLocationHook），因此接受 -1
        if (acc >= ACC_MIN) sGpsAccStatus = inRange(acc, ACC_MIN, ACC_MAX) ? acc : sGpsAccStatus;
        applyOriginRoute(origin, waypoints);
        applyConfigInner(sp, cd, st);
        if (inRange(run, MODE_MIN, MODE_MAX)) {
            sMode = run;
        } else {
            XposedBridge.log(TAG + ": run mode rejected (" + run + "), keep mode=" + sMode);
        }
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
        if (inRange(speed, SPEED_MIN, SPEED_HARD_MAX)) {
            float eff = speed;
            if (eff > sSpeedMax) {
                eff = sSpeedMax;
                XposedBridge.log(TAG + ": speed " + speed + " clamped to " + sSpeedMax
                        + " (target drops points above ~5.0 m/s)");
            }
            sSpeed = eff;
        } else if (speed > 0f) {
            // 默认值是 -1（未提供），只有显式提供的非法值才需要告警
            XposedBridge.log(TAG + ": speed rejected (" + speed + ")");
        }
        if (inRange(cadence, CADENCE_MIN, CADENCE_MAX)) {
            sCadence = cadence;
        } else if (cadence > 0f) {
            XposedBridge.log(TAG + ": cadence rejected (" + cadence + ")");
        }
        if (inRange(stride, STRIDE_MIN, STRIDE_MAX)) {
            sStride = stride;
        } else if (stride > 0f) {
            XposedBridge.log(TAG + ": stride rejected (" + stride + ")");
        }
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
            clearRoute();
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

    /**
     * 空配置 = 用户清空了路线。
     * 必须把「有效路线」一起清掉：只更新去重用的 sLastOrigin/sLastRouteHash 会让
     * sLat/sLon 保留上一次的坐标，hasRoute() 继续为 true，引擎接着在旧路线上跑。
     */
    private static void clearRoute() {
        sLat = new double[0];
        sLon = new double[0];
        sSeg = 0;
        sSegProgress = 0;
        sDir = 1;
        resetCounters();
        XposedBridge.log(TAG + ": origin/route cleared");
    }

    private static void resetToStart() {
        sSeg = 0;
        sSegProgress = 0;
        sDir = 1;
        if (sLat.length > 0) {
            sCurLat = sLat[0];
            sCurLon = sLon[0];
        }
        resetCounters();
    }

    /** 里程/步数/计时归零；步数不能回退，把本场计入基数 */
    private static void resetCounters() {
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
        advanceAlongRoute(stepDist);

        sStepAccum += sCadence * Math.min(dt, STEP_DT_MAX);
        if (Double.isNaN(sStepAccum) || sStepAccum < 0) sStepAccum = 0;
        if (sStepAccum > STEP_MAX_PER_TICK) {
            XposedBridge.log(TAG + ": step backlog dropped (" + sStepAccum + ")");
            sStepAccum = STEP_MAX_PER_TICK;
        }
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
            ls = new ArrayList<LocationListener>(sListeners.keySet());
        }
        for (int i = 0; i < ls.size(); i++) {
            LocationListener l = ls.get(i);
            try {
                l.onLocationChanged(current());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": emit failed " + t);
            }
            // 单次定位：无论回调是否抛异常，都只投递一次
            boolean oneShot;
            synchronized (sListeners) {
                Boolean flag = sListeners.get(l);
                oneShot = flag != null && flag.booleanValue();
                if (oneShot) sListeners.remove(l);
            }
            if (oneShot) XposedBridge.log(TAG + ": single-shot location listener released");
        }
    }

    /**
     * 沿折线推进 distance 米，到端点后折返。
     *
     * sSegProgress 是「当前段内、沿 sDir 方向已经走过的距离」：
     *   sSeg 与 sSeg+sDir 构成本段，0 <= sSegProgress <= 该段长度。
     * 原实现把末段的剩余距离直接丢弃并 sSeg = 0，等于从终点瞬移回起点
     * （一次 tick 可移动上百米），而引擎里程只加了 1.2 米。
     */
    private static void advanceAlongRoute(double distance) {
        int n = sLat.length;
        if (n < 2 || distance <= 0) return;
        double remaining = distance;
        int guard = 0;
        while (remaining > 1e-9 && guard++ < 4096) {
            int other = sSeg + sDir;
            if (other < 0 || other >= n) {
                // 已经站在端点上：掉头，从同一端点重新出发
                sDir = -sDir;
                sSegProgress = 0;
                continue;
            }
            double segLen = dist(sLat[sSeg], sLon[sSeg], sLat[other], sLon[other]);
            if (segLen < 0.01) {
                // 退化段（重复点）：直接跳过，避免除零
                sSeg = other;
                sSegProgress = 0;
                continue;
            }
            double space = segLen - sSegProgress;
            if (remaining < space) {
                sSegProgress += remaining;
                remaining = 0;
                break;
            }
            remaining -= space;
            sSeg = other;
            sSegProgress = 0;
        }

        int other = sSeg + sDir;
        if (other < 0 || other >= n) other = sSeg;
        double segLen = dist(sLat[sSeg], sLon[sSeg], sLat[other], sLon[other]);
        double f = segLen < 0.01 ? 0.0 : sSegProgress / segLen;
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        sCurLat = sLat[sSeg] + (sLat[other] - sLat[sSeg]) * f;
        sCurLon = sLon[sSeg] + (sLon[other] - sLon[sSeg]) * f;
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
