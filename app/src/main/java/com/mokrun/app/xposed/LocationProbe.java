package com.mokrun.app.xposed;

import android.location.Location;
import android.location.LocationListener;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Redirects location reads to the MotionEngine.
 *
 * Crucially: when the simulation is NOT started, every call is left untouched,
 * so the app sees the real position and nothing looks suspicious.
 */
public final class LocationProbe {

    private static final String TAG = "MokrRun";
    private static boolean sAllMethodsLogged = false;

    private LocationProbe() {}

    public static void install(ClassLoader cl) {
        MotionEngine.start();
        hookGetLastKnown(cl);
        hookGetCurrent(cl);
        hookRequestUpdates(cl);
        hookSingleUpdate(cl);
        hookRemoveUpdates(cl);
        hookSystemCallbacks(cl);
        hookPendingIntent(cl);
        logAllCalls(cl);
    }

    /**
     * 接管期间的「单一来源」兜底。
     *
     * 连续注册（requestLocationUpdates）故意保留真实注册：如果在这里拦掉，
     * 「结束」之后应用就拿不回真实定位了。代价是系统仍会回调真实位置，
     * 所以在系统回调进入应用的入口拦一次：只在 isActive() 时丢弃，
     * 结束/暂停后真实回调照常放行。
     *
     * 真实类名与签名随 API 变化（已按 AOSP 源码逐版本核对）：
     *   Android 11~16：android.location.LocationManager$LocationListenerTransport
     *                   onLocationChanged(List<Location> locations, IRemoteCallback onComplete)
     *   Android 10 及更早：android.location.LocationManager$ListenerTransport
     *                   onLocationChanged(Location location)
     * 因此按候选类名逐个尝试，并对 onLocationChanged 的**所有重载**挂钩
     * （不假设参数个数/类型）；一个载体都没找到时只记日志，
     * 行为回落到「真实事件可能混入合成流」。
     */
    private static final String[] CALLBACK_TRANSPORTS = {
            "android.location.LocationManager$LocationListenerTransport",
            "android.location.LocationManager$ListenerTransport",
    };

    private static void hookSystemCallbacks(ClassLoader cl) {
        int hooked = 0;
        for (int i = 0; i < CALLBACK_TRANSPORTS.length; i++) {
            final String name = CALLBACK_TRANSPORTS[i];
            try {
                Class<?> transport = XposedHelpers.findClass(name, cl);
                XposedBridge.hookAllMethods(transport, "onLocationChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!MotionEngine.isActive()) return;
                        completeSystemCallback(param);
                        param.setResult(null);
                    }
                });
                hooked++;
                XposedBridge.log(TAG + ": system location callbacks suppressed via " + name);
            } catch (Throwable t) {
                // 该 API 上不存在这个类名属正常情况：候选列表覆盖多个版本
                XposedBridge.log(TAG + ": callback guard not found on " + name);
            }
        }
        if (hooked == 0) {
            XposedBridge.log(TAG + ": no location callback transport found,"
                    + " real location events may mix into the synthetic stream");
        }
    }

    /**
     * 系统在 onLocationChanged(List, IRemoteCallback) 里等待应用侧投递完成的确认回调。
     * 跳过投递时必须补一次 sendResult(null)，否则系统侧的投递记账永远等不到完成。
     */
    private static void completeSystemCallback(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 2) return;
        Object callback = param.args[1];
        if (callback == null) return;
        try {
            java.lang.reflect.Method send = callback.getClass()
                    .getMethod("sendResult", android.os.Bundle.class);
            send.invoke(callback, new Object[]{null});
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": onComplete notify skipped (" + t + ")");
        }
    }

    /**
     * 定位监听器的注销必须同步到引擎。
     * 之前只接了 requestLocationUpdates，没有接 removeUpdates：
     * 应用注销后引擎每个 tick 仍在回调该 listener，目标应用会看到
     * 「已经注销却还在更新」的定位流。
     */
    private static void hookRemoveUpdates(ClassLoader cl) {
        try {
            Class<?> lm = XposedHelpers.findClass("android.location.LocationManager", cl);
            XposedBridge.hookAllMethods(lm, "removeUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        for (Object a : param.args) {
                            if (a instanceof LocationListener) {
                                MotionEngine.removeListener((LocationListener) a);
                                XposedBridge.log(TAG + ": removeUpdates captured "
                                        + a.getClass().getName());
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": removeUpdates hook error " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked removeUpdates");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": removeUpdates hook skipped (" + t + ")");
        }
    }

    private static void hookGetLastKnown(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.location.LocationManager", cl,
                    "getLastKnownLocation", String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!MotionEngine.isActive()) return;
                            param.setResult(MotionEngine.current());
                        }
                    });
            XposedBridge.log(TAG + ": hooked getLastKnownLocation");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getLastKnownLocation failed " + t);
        }
    }

    private static void hookGetCurrent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.location.LocationManager", cl,
                    "getCurrentLocation", String.class,
                    "android.os.CancellationSignal",
                    "java.util.concurrent.Executor",
                    "java.util.function.Consumer",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!MotionEngine.isActive()) return;
                            try {
                                Object consumer = param.args[3];
                                Method accept = consumer.getClass().getMethod("accept", Object.class);
                                accept.invoke(consumer, MotionEngine.current());
                                param.setResult(null);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": getCurrentLocation fake failed " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked getCurrentLocation");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getCurrentLocation skipped (" + t + ")");
        }
    }

    private static void hookRequestUpdates(ClassLoader cl) {
        hookUpdates(cl, new Class[]{String.class, long.class, float.class, LocationListener.class});
        hookUpdates(cl, new Class[]{String.class, long.class, float.class, LocationListener.class, android.os.Looper.class});
    }

    private static void hookUpdates(ClassLoader cl, Class<?>[] sig) {
        try {
            Object[] args = new Object[sig.length + 1];
            System.arraycopy(sig, 0, args, 0, sig.length);
            args[sig.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!MotionEngine.isActive()) return;
                    try {
                        for (Object a : param.args) {
                            if (a instanceof LocationListener) {
                                MotionEngine.addListener((LocationListener) a);
                                XposedBridge.log(TAG + ": requestLocationUpdates captured "
                                        + a.getClass().getName());
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": requestUpdates hook error " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.location.LocationManager", cl,
                    "requestLocationUpdates", args);
            XposedBridge.log(TAG + ": hooked requestLocationUpdates " + sig.length);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": requestLocationUpdates sig" + sig.length + " skipped (" + t + ")");
        }
    }

    /** requestSingleUpdate has two variants, both take a LocationListener */
    private static void hookSingleUpdate(ClassLoader cl) {
        Class<?>[][] sigs = new Class<?>[][]{
                {String.class, LocationListener.class, android.os.Looper.class},
                {String.class, LocationListener.class},
        };
        for (int i = 0; i < sigs.length; i++) {
            try {
                Object[] args = new Object[sigs[i].length + 1];
                System.arraycopy(sigs[i], 0, args, 0, sigs[i].length);
                args[sigs[i].length] = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!MotionEngine.isActive()) return;
                        try {
                            for (Object a : param.args) {
                                if (a instanceof LocationListener) {
                                    // 单次定位：投递一次就解除，不等应用来注销
                                    MotionEngine.addSingleShotListener((LocationListener) a);
                                    XposedBridge.log(TAG + ": requestSingleUpdate captured");
                                }
                            }
                            // 接管期间单次请求只由合成流服务：必须短路原调用，
                            // 否则系统稍后仍会回调一次真实位置，同一个 listener 会收到
                            // 两次事件（一次合成、一次真实，且后者可能带真实坐标）。
                            // 该方法返回 void，setResult(null) 即跳过原实现。
                            param.setResult(null);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": requestSingleUpdate hook error " + t);
                        }
                    }
                };
                XposedHelpers.findAndHookMethod("android.location.LocationManager", cl,
                        "requestSingleUpdate", args);
                XposedBridge.log(TAG + ": hooked requestSingleUpdate #" + (i + 1));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": requestSingleUpdate #" + (i + 1) + " skipped (" + t + ")");
            }
        }
    }

    /** the PendingIntent flavour polled by some SDKs */
    private static void hookPendingIntent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.location.LocationManager", cl,
                    "requestLocationUpdates", String.class, long.class, float.class,
                    android.app.PendingIntent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": requestLocationUpdates(PendingIntent) called"
                                    + " running=" + MotionEngine.isActive());
                        }
                    });
            XposedBridge.log(TAG + ": hooked requestLocationUpdates(PendingIntent)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PendingIntent variant skipped (" + t + ")");
        }
    }

    /**
     * Temporarily logs every public LocationManager call so we can see exactly
     * which API the run page uses. Remove once the path is confirmed.
     */
    private static void logAllCalls(ClassLoader cl) {
        if (sAllMethodsLogged) return;
        sAllMethodsLogged = true;
        try {
            Class<?> c = XposedHelpers.findClass("android.location.LocationManager", cl);
            XposedBridge.hookAllMethods(c, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + ": getLastLocation() called running="
                            + MotionEngine.isActive());
                    if (MotionEngine.isActive()) param.setResult(MotionEngine.current());
                }
            });
            XposedBridge.log(TAG + ": hooked getLastLocation (fused client)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getLastLocation hook skipped (" + t + ")");
        }
    }
}
