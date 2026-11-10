package com.mokrun.app.xposed;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 高德 / 百度 定位 SDK 的数据对象 hook。
 *
 * 系统 LocationManager 只是定位来源之一，SDK 自己还有网络定位（走自家服务器），
 * 最终结果落在 AMapLocation / BDLocation 这类对象里，地图从这些对象读坐标。
 * 所以直接改 getter，无论上层怎么取都能拿到我们的值。
 *
 * 这一版额外加了纯观察探针（不改变任何返回值）：
 *   - 一次性打印 AMapLocation 的全部方法名，确认还有哪些取数口子没覆盖；
 *   - 记录 latitude/longitude 取值的调用者（com.bxkj.* 那一层），
 *     用来定位「距离到底在哪个类里累加」；
 *   - 每 10 秒汇总质量类 getter 的取值（卫星数、定位质量、GPS 精度状态…）。
 */
public final class SDKLocationHook {

    private static final String TAG = "MokrRun";
    private static boolean sAny = false;
    private static long sLastMark = 0L;
    private static boolean sDumped = false;

    private SDKLocationHook() {}

    public static void install(ClassLoader cl) {
        hookAmap(cl);
        hookBaidu(cl);
        if (!sAny) {
            XposedBridge.log(TAG + ": 未找到高德/百度定位类，仅依赖系统 Location hook");
        }
    }

    private static void hookAmap(ClassLoader cl) {
        String cls = "com.amap.api.location.AMapLocation";
        hookGetter(cl, cls, "getLatitude");
        hookGetter(cl, cls, "getLongitude");
        hookGetter(cl, cls, "getSpeed");
        hookGetter(cl, cls, "getBearing");
        hookGetter(cl, cls, "getAccuracy");
        // 定位类型必须伪装成 GPS：SDK 默认会给成「前次定位结果」(2)，
        // 目标应用会把这类点当成无效样本丢弃，导致累计距离只有实际的几分之一。
        hookGetter(cl, cls, "getLocationType");
        hookGetter(cl, cls, "getErrorCode");
        // 质量口径：实测真实取值是 satellites=0 / provider=lbs / gpsAccuracyStatus=-1，
        // 目标应用的 GPS 状态灯与「GPS 轨迹异常」判定几乎一定读这些字段。
        hookGetter(cl, cls, "getSatellites");
        hookGetter(cl, cls, "getProvider");
        hookGetter(cl, cls, "isMock");
        hookGetter(cl, cls, "isFixLastLocation");
        // 真实值：altitude=0.0（Location 里没设海拔）、gpsAccuracyStatus=-1，
        // 这两个都可能被当成「定位质量差」的证据，一并伪装。
        hookGetter(cl, cls, "getAltitude");
        hookGetter(cl, cls, "getGpsAccuracyStatus");
        // 只观察不修改，留作诊断用
        hookProbe(cl, cls, "getCoordType");
        hookProbe(cl, cls, "getTrustedLevel");
        hookProbe(cl, cls, "getLocationDetail");
        hookProbe(cl, cls, "getErrorInfo");
        hookQualityReport(cl, cls);
        dumpMethods(cl, cls);
    }

    /** 一次性把 AMapLocation 的公开方法名打出来（分段，避免被 logcat 截断） */
    private static void dumpMethods(ClassLoader cl, String cls) {
        if (sDumped) return;
        sDumped = true;
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            Method[] ms = c.getDeclaredMethods();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < ms.length; i++) {
                sb.append(ms[i].getName()).append('/');
                n++;
                if (sb.length() > 900) {
                    XposedBridge.log(TAG + ": AMapLocation methods [" + n + "] " + sb);
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) {
                XposedBridge.log(TAG + ": AMapLocation methods [" + n + "] " + sb);
            }
            XposedBridge.log(TAG + ": AMapLocation total methods=" + ms.length);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dumpMethods failed " + t);
        }
    }

    /** 只记录调用情况，不改变返回值 */
    private static void hookProbe(ClassLoader cl, final String cls, final String method) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    String v = (r == null) ? "null" : String.valueOf(r);
                    probe(method, v);
                }
            });
            XposedBridge.log(TAG + ": probe " + cls.substring(cls.lastIndexOf('.') + 1)
                    + "." + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": probe " + method + " skipped (" + t + ")");
        }
    }

    /** 每 10 秒汇总一次各 getter 的调用次数与最近取值 */
    private static final java.util.Map<String, Integer> sCounts =
            new java.util.HashMap<String, Integer>();
    private static final java.util.Map<String, String> sLastValue =
            new java.util.HashMap<String, String>();
    private static final java.util.Map<String, Long> sCallerSampleAt =
            new java.util.HashMap<String, Long>();
    private static long sLastDump = 0L;

    private static void probe(String method, String value) {
        synchronized (sCounts) {
            Integer c = sCounts.get(method);
            sCounts.put(method, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
            sLastValue.put(method, value);
        }
    }

    /**
     * 记录「谁在读坐标」。目标应用的业务代码在 360 加固里，静态看不到，
     * 但运行时的调用栈是明文——第一个非 SDK / 非框架帧就是它自己的类。
     * 每个方法每秒最多采样一次，避免高频 getter 把性能拖垮。
     */
    private static void captureCaller(String method) {
        long now = System.currentTimeMillis();
        synchronized (sCallerSampleAt) {
            Long last = sCallerSampleAt.get(method);
            if (last != null && now - last.longValue() < 1000L) return;
            sCallerSampleAt.put(method, Long.valueOf(now));
        }
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            String sdkFrame = null;
            String appFrame = null;
            for (int i = 0; i < st.length; i++) {
                String cn = st[i].getClassName();
                if (cn.equals("com.mokrun.app.xposed.SDKLocationHook")) continue;
                if (appFrame == null && cn.startsWith("com.bxkj")) {
                    appFrame = cn + "#" + st[i].getMethodName() + ":" + st[i].getLineNumber();
                }
                if (sdkFrame == null && (cn.startsWith("com.autonavi") || cn.startsWith("com.amap")
                        || cn.startsWith("com.loc"))) {
                    sdkFrame = cn + "#" + st[i].getMethodName() + ":" + st[i].getLineNumber();
                }
            }
            if (appFrame != null) probe("caller:" + method, appFrame);
            else if (sdkFrame != null) probe("caller:" + method, "sdk:" + sdkFrame);
        } catch (Throwable ignored) {
        }
    }

    /** 由 MotionEngine 的 tick 驱动，定期把统计打出来 */
    public static void dumpStats() {
        long now = System.currentTimeMillis();
        if (now - sLastDump < 10000L) return;
        sLastDump = now;
        StringBuilder sb = new StringBuilder();
        synchronized (sCounts) {
            for (java.util.Map.Entry<String, Integer> e : sCounts.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue())
                        .append("(last ").append(sLastValue.get(e.getKey())).append(") ");
            }
            sCounts.clear();
            sLastValue.clear();
        }
        if (sb.length() > 0) {
            XposedBridge.log(TAG + ": getter stats -> " + sb.toString().trim());
        }
    }

    /**
     * 质量报告：AMap v10 把「GPS 好不好」单独放进 AMapLocationQualityReport，
     * 主类 getter 全伪装了也未必有用——所以先把它返回的对象抓出来，
     * 把它的所有 getter 都挂上只读探针，看应用到底读哪几个字段。
     */
    private static boolean sReportHooked = false;
    private static long sLastQrLog = 0L;

    /** 10 秒一条：确认「伪装值真的回给了应用」，而不是只挂了探针 */
    private static void qrFakeLog(String what) {
        long now = System.currentTimeMillis();
        if (now - sLastQrLog < 10000L) return;
        sLastQrLog = now;
        XposedBridge.log(TAG + ": qr fake applied -> " + what);
    }

    private static void hookQualityReport(ClassLoader cl, String cls) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "getLocationQualityReport",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            probe("getLocationQualityReport",
                                    r == null ? "null" : r.getClass().getName());
                            if (r != null && !sReportHooked) {
                                sReportHooked = true;
                                hookReportGetters(r.getClass());
                            }
                        }
                    });
            XposedBridge.log(TAG + ": probe AMapLocation.getLocationQualityReport");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getLocationQualityReport skipped (" + t + ")");
        }
    }

    private static void hookReportGetters(Class<?> rc) {
        try {
            // 先把类里的常量打出来（GPS_STATUS_* 之类），便于确定「好」的取值
            java.lang.reflect.Field[] fs = rc.getDeclaredFields();
            StringBuilder cs = new StringBuilder();
            for (int i = 0; i < fs.length; i++) {
                try {
                    int mod = fs[i].getModifiers();
                    if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                    if (fs[i].getType() != int.class) continue;
                    cs.append(fs[i].getName()).append('=').append(fs[i].getInt(null)).append(' ');
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": quality report constants: " + cs);

            java.lang.reflect.Method[] ms = rc.getDeclaredMethods();
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < ms.length; i++) {
                final java.lang.reflect.Method m = ms[i];
                final String n = m.getName();
                names.append(n).append('/');
                if (m.getParameterTypes().length != 0) continue;
                if (!(n.startsWith("get") || n.startsWith("is"))) continue;
                try {
                    XposedHelpers.findAndHookMethod(rc, n, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            probe("qr:" + n, r == null ? "null" : String.valueOf(r));
                            if (!MotionEngine.isActive()) return;
                            // 实测真实值：getGPSStatus=0、getGPSSatellites=0、isInstalledHighDangerMockApp=false
                            // 「GPS ✕」与「轨迹异常」就是这两个 0 判出来的，这里给成健康值。
                            if ("getGPSStatus".equals(n)) {
                                p.setResult(Integer.valueOf(MotionEngine.qrStatus()));
                                qrFakeLog("status=" + MotionEngine.qrStatus());
                            } else if ("getGPSSatellites".equals(n)) {
                                p.setResult(Integer.valueOf(MotionEngine.satellites()));
                                qrFakeLog("sat=" + MotionEngine.satellites());
                            } else if ("isInstalledHighDangerMockApp".equals(n)) {
                                p.setResult(Boolean.FALSE);
                            } else if ("isWifiAble".equals(n)) {
                                p.setResult(Boolean.TRUE);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": quality report class=" + rc.getName()
                    + " methods=" + names);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookReportGetters failed " + t);
        }
    }

    private static void hookBaidu(ClassLoader cl) {
        String cls = "com.baidu.location.BDLocation";
        hookGetter(cl, cls, "getLatitude");
        hookGetter(cl, cls, "getLongitude");
        hookGetter(cl, cls, "getSpeed");
    }

    private static void hookGetter(ClassLoader cl, final String cls, final String method) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!MotionEngine.isActive()) return;
                    if ("getLatitude".equals(method)) {
                        param.setResult(Double.valueOf(MotionEngine.lat()));
                    } else if ("getLongitude".equals(method)) {
                        param.setResult(Double.valueOf(MotionEngine.lon()));
                    } else if ("getSpeed".equals(method)) {
                        param.setResult(Float.valueOf(MotionEngine.speedNow()));
                    } else if ("getBearing".equals(method)) {
                        param.setResult(Float.valueOf(0f));
                    } else if ("getAccuracy".equals(method)) {
                        param.setResult(Float.valueOf(3f));
                    } else if ("getLocationType".equals(method)) {
                        param.setResult(Integer.valueOf(1));   // 1 = GPS 定位结果
                    } else if ("getErrorCode".equals(method)) {
                        param.setResult(Integer.valueOf(0));   // 0 = 成功
                    } else if ("getSatellites".equals(method)) {
                        param.setResult(Integer.valueOf(MotionEngine.satellites()));
                    } else if ("getProvider".equals(method)) {
                        param.setResult(MotionEngine.provider());
                    } else if ("isMock".equals(method)) {
                        param.setResult(Boolean.FALSE);
                    } else if ("isFixLastLocation".equals(method)) {
                        param.setResult(Boolean.FALSE);        // 不是「上一次缓存的定位」
                    } else if ("getAltitude".equals(method)) {
                        param.setResult(Double.valueOf(MotionEngine.altitude()));
                    } else if ("getGpsAccuracyStatus".equals(method)) {
                        int acc = MotionEngine.gpsAccStatus();
                        if (acc < 0) return;                   // 未配置则保留真实值
                        param.setResult(Integer.valueOf(acc));
                    } else {
                        return;
                    }
                    mark(cls, method);
                    if ("getLatitude".equals(method) || "getLongitude".equals(method)) {
                        captureCaller(method);
                    }
                }
            });
            sAny = true;
            XposedBridge.log(TAG + ": hooked " + cls + "." + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + cls + "." + method + " skipped (" + t + ")");
        }
    }

    /** log at most once every 10s so we can see the fakes being read */
    private static void mark(String cls, String method) {
        long now = System.currentTimeMillis();
        if (now - sLastMark > 10000L) {
            sLastMark = now;
            XposedBridge.log(TAG + ": SDK getter faked -> " + cls.substring(cls.lastIndexOf('.') + 1)
                    + "." + method);
        }
    }
}
