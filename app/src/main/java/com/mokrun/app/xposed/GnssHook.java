package com.mokrun.app.xposed;

import android.location.GnssStatus;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 合成 GNSS 卫星状态。
 *
 * 为什么需要它：
 *   实测系统侧真实定位是 `Location[gps ... {satellites=3, maxCn0=13}]`——
 *   室内只有 3 颗星。目标应用右上角那个「GPS ✕ / 信号格」如果读的是系统
 *   GnssStatus（很常见，addGpsStatusListener / registerGnssStatusCallback），
 *   那么只改 AMapLocation 的 getter 永远不会让它变绿，而它恰好又是
 *   「GPS 轨迹异常」和「有效距离不累加」的闸门。
 *
 * 做法：
 *   拦 registerGnssStatusCallback，记下回调，然后每秒用 GnssStatus.Builder
 *   造一份「12~16 颗星、全部参与定位、CN0 28~34」的状态推给它。
 *   只在模拟运行中推送（MotionEngine.isActive()），未开始时不干预。
 */
public final class GnssHook {

    private static final String TAG = "MokrRun";

    private static final List<GnssStatus.Callback> sCallbacks =
            new ArrayList<GnssStatus.Callback>();
    private static Handler sHandler = null;
    private static boolean sInstalled = false;
    private static boolean sRunning = false;

    private GnssHook() {}

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            Class<?> lm = XposedHelpers.findClass("android.location.LocationManager", cl);
            XposedBridge.hookAllMethods(lm, "registerGnssStatusCallback", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        Object a = param.args[i];
                        if (a instanceof GnssStatus.Callback) {
                            add((GnssStatus.Callback) a);
                            XposedBridge.log(TAG + ": gnss callback captured -> "
                                    + a.getClass().getName());
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": gnss hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": gnss hook failed " + t);
        }
    }

    private static void add(GnssStatus.Callback cb) {
        if (cb == null) return;
        synchronized (sCallbacks) {
            if (!sCallbacks.contains(cb)) sCallbacks.add(cb);
        }
        start();
    }

    private static synchronized void start() {
        if (sRunning) return;
        sRunning = true;
        sHandler = new Handler(Looper.getMainLooper());
        sHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    emit();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": gnss emit error " + t);
                }
                if (sHandler != null) sHandler.postDelayed(this, 1000L);
            }
        });
    }

    private static void emit() {
        if (!MotionEngine.isActive()) return;
        GnssStatus st = build();
        if (st == null) return;
        List<GnssStatus.Callback> ls;
        synchronized (sCallbacks) {
            if (sCallbacks.isEmpty()) return;
            ls = new ArrayList<GnssStatus.Callback>(sCallbacks);
        }
        for (int i = 0; i < ls.size(); i++) {
            try {
                ls.get(i).onSatelliteStatusChanged(st);
            } catch (Throwable ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (now - sLastEmitLog > 10000L) {
            sLastEmitLog = now;
            XposedBridge.log(TAG + ": gnss emit -> count=" + st.getSatelliteCount()
                    + " usedInFix=" + st.usedInFix(0)
                    + " targets=" + ls.size());
        }
    }

    private static long sLastEmitLog = 0L;

    /** GPS L1 载频，单位 Hz。之前按 cn0-2 传，量级差了 8 个数量级 */
    private static final float CARRIER_HZ = 1575.42e6f;
    /** 12 参版本（API 30+）的参数个数 */
    private static final int ARITY_12 = 12;

    private static boolean sDumpedBuilder = false;
    private static Method sAdd = null;
    private static Class<?>[] sAddParams = null;

    private static GnssStatus build() {
        try {
            Class<?> builderCls = Class.forName("android.location.GnssStatus$Builder");
            if (!sDumpedBuilder) {
                sDumpedBuilder = true;
                StringBuilder sb = new StringBuilder();
                java.lang.reflect.Method[] ms = builderCls.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    sb.append(ms[i].getName()).append('(');
                    Class<?>[] ps = ms[i].getParameterTypes();
                    for (int k = 0; k < ps.length; k++) {
                        sb.append(ps[k].getSimpleName());
                        if (k < ps.length - 1) sb.append(',');
                    }
                    sb.append(") ");
                }
                XposedBridge.log(TAG + ": GnssStatus.Builder methods -> " + sb);
            }
            if (sAdd == null) {
                java.lang.reflect.Method[] ms = builderCls.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    if (!"addSatellite".equals(ms[i].getName())) continue;
                    int p = ms[i].getParameterTypes().length;
                    // 优先用 12 参版本（API 30 起）；更高版本新增的重载只在没有 12 参时兜底
                    if (sAdd == null
                            || (p == ARITY_12 && sAddParams.length != ARITY_12)
                            || (p > sAddParams.length && sAddParams.length != ARITY_12)) {
                        sAdd = ms[i];
                        sAddParams = ms[i].getParameterTypes();
                    }
                }
                if (sAdd == null) throw new NoSuchMethodException("addSatellite");
                XposedBridge.log(TAG + ": gnss addSatellite arity=" + sAddParams.length);
            }
            Object b = builderCls.getDeclaredConstructor().newInstance();

            long t = System.currentTimeMillis() / 1000L;
            int n = 12 + (int) (t % 5);          // 12~16 颗，随时间抖动
            for (int i = 0; i < n; i++) {
                int svid = i + 1;
                float cn0 = 28f + (i % 7);        // 28~34 dBHz
                float elev = 20f + (i * 7) % 65;
                float azim = (i * 41) % 360;
                addOne(b, svid, cn0, elev, azim);
            }
            Method buildM = builderCls.getMethod("build");
            return (GnssStatus) buildM.invoke(b);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": gnss build failed " + t);
            return null;
        }
    }

    /**
     * 按运行时实际签名填参数。
     *
     * 官方 12 参签名（API 30~36，javap 与文档一致）：
     *   addSatellite(int constellationType, int svid, float cn0DbHz,
     *                float elevationDegrees, float azimuthDegrees,
     *                boolean hasEphemerisData, boolean hasAlmanacData, boolean usedInFix,
     *                boolean hasCarrierFrequency, float carrierFrequencyHz,
     *                boolean hasBasebandCn0DbHz, float basebandCn0DbHz)
     *
     * 旧实现把 svid 放在第一位，并在载频位置传 cn0-2、在 basebandCn0 位置传 1575.42，
     * 与公共 API 合同不符：星座类型变成了 svid，载频小 8 个数量级。
     */
    private static void addOne(Object b, int svid, float cn0, float elev, float azim)
            throws Exception {
        int p = sAddParams == null ? 0 : sAddParams.length;
        if (p == ARITY_12) {
            sAdd.invoke(b, args12(svid, cn0, elev, azim));
        } else if (p > ARITY_12) {
            // 更高版本追加了参数（例如 API 37 的 18 参重载）：前 12 个语义相同，
            // 其余按参数类型填「不可用」的默认值。该分支未在实机验证。
            Class<?>[] types = sAddParams;
            Object[] head = args12(svid, cn0, elev, azim);
            Object[] argv = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (i < head.length) {
                    argv[i] = head[i];
                } else if (types[i] == boolean.class) {
                    argv[i] = Boolean.FALSE;
                } else if (types[i] == long.class) {
                    argv[i] = Long.valueOf(0L);
                } else if (types[i] == double.class) {
                    argv[i] = Double.valueOf(0.0);
                } else if (types[i] == float.class) {
                    argv[i] = Float.valueOf(0f);
                } else if (types[i] == int.class) {
                    argv[i] = Integer.valueOf(0);
                } else {
                    argv[i] = null;
                }
            }
            XposedBridge.log(TAG + ": addSatellite arity=" + types.length
                    + " (12 参语义 + 默认尾参)");
            sAdd.invoke(b, argv);
        } else if (p == 6) {
            // addSatellite(svid, cn0DbHz, elevation, azimuth, carrierFrequencyHz, constellationType)
            sAdd.invoke(b, Integer.valueOf(svid), Float.valueOf(cn0),
                    Float.valueOf(elev), Float.valueOf(azim), Float.valueOf(CARRIER_HZ),
                    Integer.valueOf(GnssStatus.CONSTELLATION_GPS));
        } else if (p == 5) {
            // addSatellite(svid, cn0DbHz, elevation, azimuth, carrierFrequencyHz)
            sAdd.invoke(b, Integer.valueOf(svid), Float.valueOf(cn0),
                    Float.valueOf(elev), Float.valueOf(azim), Float.valueOf(CARRIER_HZ));
        } else {
            throw new NoSuchMethodException("unexpected addSatellite arity " + p);
        }
    }

    /** 12 参版本的实参：星座类型与 svid 各就其位，载频用 Hz，basebandCn0 在 0~63 dB-Hz */
    private static Object[] args12(int svid, float cn0, float elev, float azim) {
        float baseband = cn0 - 2f;
        if (baseband < 0f) baseband = 0f;
        if (baseband > 63f) baseband = 63f;
        return new Object[]{
                Integer.valueOf(GnssStatus.CONSTELLATION_GPS),
                Integer.valueOf(svid),
                Float.valueOf(cn0),
                Float.valueOf(elev),
                Float.valueOf(azim),
                Boolean.TRUE,       // hasEphemerisData
                Boolean.TRUE,       // hasAlmanacData
                Boolean.TRUE,       // usedInFix
                Boolean.TRUE,       // hasCarrierFrequency
                Float.valueOf(CARRIER_HZ),
                Boolean.TRUE,       // hasBasebandCn0DbHz
                Float.valueOf(baseband)
        };
    }
}
