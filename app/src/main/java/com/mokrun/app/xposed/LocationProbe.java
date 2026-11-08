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
        hookPendingIntent(cl);
        logAllCalls(cl);
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
                                    MotionEngine.addListener((LocationListener) a);
                                    XposedBridge.log(TAG + ": requestSingleUpdate captured");
                                }
                            }
                        } catch (Throwable ignored) {
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
