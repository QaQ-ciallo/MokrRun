package com.mokrun.app.xposed;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把引擎算出来的步数喂给应用自己注册的步数监听器。
 *
 * 之前有两个问题会导致步数被放大好几倍：
 *   1. 同一个 listener 如果同时注册了 STEP_COUNTER 和 STEP_DETECTOR，
 *      每步会收到两次事件，步数直接翻倍；
 *   2. 只挂了 registerListener 没挂 unregisterListener，监听器只增不减，
 *      应用每进出一次跑步页就多一份，重复注入越来越严重。
 *
 * 现在用 IdentityHashMap 按 listener 去重，并且补上了注销，每步只投递一次。
 */
public final class SensorHook {

    private static final String TAG = "MokrRun";

    /** listener → 它第一次注册的传感器类型（决定 payload 形态） */
    private static final Map<SensorEventListener, Integer> sListeners =
            Collections.synchronizedMap(new IdentityHashMap<SensorEventListener, Integer>());

    /** 监听器注册的 Sensor 对象，投递事件时需要 */
    private static final Map<SensorEventListener, Sensor> sSensors =
            Collections.synchronizedMap(new IdentityHashMap<SensorEventListener, Sensor>());

    private static boolean sInstalled = false;

    private SensorHook() {}

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        hookRealEventProbe(cl);
        String[] classes = {"android.hardware.SystemSensorManager", "android.hardware.SensorManager"};
        for (int i = 0; i < classes.length; i++) {
            try {
                Class<?> c = XposedHelpers.findClass(classes[i], cl);
                XposedBridge.hookAllMethods(c, "registerListener", new RegHook());
                XposedBridge.hookAllMethods(c, "unregisterListener", new UnregHook());
                XposedBridge.log(TAG + ": sensor hooks installed on " + classes[i]);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": sensor hook failed on " + classes[i] + " (" + t + ")");
            }
        }
    }

    /**
     * 纯观察：统计「真实传感器事件」的投递量。
     *
     * 关键点：RegHook 只记录了 listener，并没有拦掉原始注册，
     * 所以系统仍会把真实硬件事件投递给应用。这里挂在 SensorEventQueue 上，
     * 凡是从系统队列下来的事件都算真实事件（我们合成的事件不经过它）。
     * 用它来判断「步数是不是双重来源」——这是交接文档里尚未验证的第 4 项。
     */
    private static final java.util.Map<Integer, Integer> sRealEvents =
            new java.util.HashMap<Integer, Integer>();
    private static long sLastRealDump = 0L;

    private static void hookRealEventProbe(ClassLoader cl) {
        try {
            Class<?> q = XposedHelpers.findClass(
                    "android.hardware.SystemSensorManager$SensorEventQueue", cl);
            XposedBridge.hookAllMethods(q, "dispatchSensorEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object a0 = param.args[0];
                        if (!(a0 instanceof Integer)) return;
                        Integer handle = (Integer) a0;
                        long now = System.currentTimeMillis();
                        synchronized (sRealEvents) {
                            Integer c = sRealEvents.get(handle);
                            sRealEvents.put(handle, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
                            if (now - sLastRealDump < 10000L) return;
                            sLastRealDump = now;
                            XposedBridge.log(TAG + ": real sensor events -> " + sRealEvents);
                            sRealEvents.clear();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": real sensor event probe installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": real sensor probe skipped (" + t + ")");
        }
    }

    static class RegHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                SensorEventListener l = null;
                Sensor s = null;
                for (Object a : param.args) {
                    if (a instanceof SensorEventListener) l = (SensorEventListener) a;
                    if (a instanceof Sensor) s = (Sensor) a;
                }
                if (l == null || s == null) return;
                int type = s.getType();
                if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return;

                // 同一 listener 只记一次，首次注册的类型决定投递方式
                if (!sListeners.containsKey(l)) {
                    sListeners.put(l, Integer.valueOf(type));
                    sSensors.put(l, s);
                    XposedBridge.log(TAG + ": step listener + type=" + type
                            + " total=" + sListeners.size());
                }
                // 步数双源 A/B 开关：rs=1 时跳过真实注册，
                // 让应用只收到我们合成的事件（真实硬件事件不再叠加）。
                if (MotionEngine.suppressRealSteps()) {
                    param.setResult(null);
                    XposedBridge.log(TAG + ": step register suppressed (rs=1) type=" + type);
                }
                MotionEngine.addListener(null);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": step hook error " + t);
            }
        }
    }

    static class UnregHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                for (Object a : param.args) {
                    if (a instanceof SensorEventListener) {
                        if (sListeners.remove(a) != null) {
                            sSensors.remove(a);
                            XposedBridge.log(TAG + ": step listener - total=" + sListeners.size());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 每产生一步由引擎调用一次；每个 listener 只会收到一个事件 */
    static void pushStep(long value) {
        if (sListeners.isEmpty()) return;

        List<SensorEventListener> ls;
        synchronized (sListeners) {
            ls = new ArrayList<SensorEventListener>(sListeners.keySet());
        }

        try {
            java.lang.reflect.Constructor<SensorEvent> ctor =
                    SensorEvent.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            for (int i = 0; i < ls.size(); i++) {
                SensorEventListener l = ls.get(i);
                Sensor s = sSensors.get(l);
                Integer type = sListeners.get(l);
                if (s == null || type == null) continue;

                SensorEvent ev = ctor.newInstance(Integer.valueOf(1));
                ev.sensor = s;
                ev.timestamp = System.nanoTime();
                ev.accuracy = 0;
                ev.values[0] = (type.intValue() == Sensor.TYPE_STEP_COUNTER) ? (float) value : 1f;
                l.onSensorChanged(ev);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": pushStep failed " + t);
        }
    }
}
