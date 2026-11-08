package com.mokrun.app.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 高德 / 百度 定位 SDK 的数据对象 hook。
 *
 * 系统 LocationManager 只是定位来源之一，SDK 自己还有网络定位（走自家服务器），
 * 最终结果落在 AMapLocation / BDLocation 这类对象里，地图从这些对象读坐标。
 * 所以直接改 getter，无论上层怎么取都能拿到我们的值。
 */
public final class SDKLocationHook {

    private static final String TAG = "MokrRun";
    private static boolean sAny = false;
    private static long sLastMark = 0L;

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
                    } else {
                        return;
                    }
                    mark(cls, method);
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
