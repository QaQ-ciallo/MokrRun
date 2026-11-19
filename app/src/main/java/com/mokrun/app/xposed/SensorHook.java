package com.mokrun.app.xposed;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把引擎算出来的步数喂给应用自己注册的步数监听器。
 *
 * 历史问题（都已修）：
 *   1. 同一个 listener 同时注册 STEP_COUNTER 和 STEP_DETECTOR 时每步收到两次事件；
 *   2. 只挂了 registerListener 没挂 unregisterListener，监听器只增不减；
 *   3. unregisterListener(listener, sensor) 不区分传感器，直接把整个 listener 删掉——
 *      注销加速度计会把计步一起注销，注销 counter 会连带废掉仍有效的 detector；
 *   4. 单个 listener 抛异常会中断这一步对其他 listener 的投递。
 *
 * 现在按「listener → 有效计步类型集合」记录注册，每步对每个 listener 只投递一次，
 * 注销按 Sensor 精确移除，异常按 listener 隔离。
 */
public final class SensorHook {

    private static final String TAG = "MokrRun";

    /** listener → 它当前有效的计步传感器注册（按 type 精确记录） */
    private static final Map<SensorEventListener, Registration> sListeners =
            Collections.synchronizedMap(new IdentityHashMap<SensorEventListener, Registration>());

    /**
     * 一个 listener 的计步注册集合。
     *
     * 按 Sensor **实例**记录，而不是按 type：同一个 listener 可以同时持有
     * 同类型的两个不同 Sensor（例如 counter 的唤醒/非唤醒变体），
     * 注销其中一个不能影响另一个。身份比较用 ==，与
     * SensorManager.unregisterListener(listener, sensor) 的语义一致。
     */
    static final class Registration {
        final List<Sensor> sensors = new ArrayList<Sensor>();

        boolean add(Sensor s) {
            if (s == null || indexOf(s) >= 0) return false;
            sensors.add(s);
            return true;
        }

        /** 精确按实例移除；返回 false 表示这个实例本来就不在注册表里 */
        boolean remove(Sensor s) {
            int i = indexOf(s);
            if (i < 0) return false;
            sensors.remove(i);
            return true;
        }

        boolean isEmpty() { return sensors.isEmpty(); }

        int size() { return sensors.size(); }

        int countOfType(int type) {
            int n = 0;
            for (int i = 0; i < sensors.size(); i++) {
                if (sensors.get(i).getType() == type) n++;
            }
            return n;
        }

        Sensor firstOfType(int type) {
            for (int i = 0; i < sensors.size(); i++) {
                Sensor s = sensors.get(i);
                if (s.getType() == type) return s;
            }
            return null;
        }

        /** 投递形态：注册了 counter 就喂累计值，否则喂 detector 的单步 1 */
        Sensor payload() {
            Sensor counter = firstOfType(Sensor.TYPE_STEP_COUNTER);
            if (counter != null) return counter;
            return firstOfType(Sensor.TYPE_STEP_DETECTOR);
        }

        private int indexOf(Sensor s) {
            for (int i = 0; i < sensors.size(); i++) {
                if (sensors.get(i) == s) return i;
            }
            return -1;
        }
    }

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
     * 系统队列下来的事件均为真实硬件事件，合成事件直接调用 listener。
     * 除注册时阻止硬件计步外，这里也拦截已注册/缓冲的计步事件，
     * 防止延迟到达的开机累计值混进合成流。其他类型的传感器照常投递。
     */
    private static final java.util.Map<Integer, Integer> sRealEvents =
            new java.util.HashMap<Integer, Integer>();
    private static long sLastRealDump = 0L;

    private static void hookRealEventProbe(ClassLoader cl) {
        try {
            Class<?> q = XposedHelpers.findClass(
                    "android.hardware.SystemSensorManager$SensorEventQueue", cl);
            XposedBridge.hookAllMethods(q, "dispatchSensorEvent", new RealEventHook());
            XposedBridge.log(TAG + ": real sensor event guard installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": real sensor guard skipped (" + t + ")");
        }
    }

    static class RealEventHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object a0 = param.args[0];
                if (!(a0 instanceof Integer)) return;
                Integer handle = (Integer) a0;
                if (MotionEngine.suppressRealSteps()) {
                    // 与系统 dispatch 使用同一 handle → Sensor 映射；首个事件的
                    // SensorEvent.sensor 尚未赋值，不能用它来判断传感器类型。
                    Object manager = XposedHelpers.getObjectField(param.thisObject, "mManager");
                    Map<?, ?> sensors = (Map<?, ?>) XposedHelpers.getObjectField(
                            manager, "mHandleToSensor");
                    Sensor sensor = (Sensor) sensors.get(handle);
                    if (sensor != null) {
                        int type = sensor.getType();
                        if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                            param.setResult(null); // dispatchSensorEvent 返回 void
                            float[] values = (float[]) param.args[1];
                            XposedBridge.log(TAG + ": real step blocked type=" + type
                                    + " value=" + (values.length == 0 ? "empty" : values[0]));
                            return;
                        }
                    }
                }
                long now = System.currentTimeMillis();
                synchronized (sRealEvents) {
                    Integer c = sRealEvents.get(handle);
                    sRealEvents.put(handle, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
                    if (now - sLastRealDump < 10000L) return;
                    sLastRealDump = now;
                    XposedBridge.log(TAG + ": real sensor events -> " + sRealEvents);
                    sRealEvents.clear();
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": real sensor guard failed " + t);
            }
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

                // 同一 listener 的每个 Sensor 实例各记一条，投递时 counter 优先去重
                synchronized (sListeners) {
                    Registration reg = sListeners.get(l);
                    if (reg == null) {
                        reg = new Registration();
                        sListeners.put(l, reg);
                    }
                    if (reg.add(s)) {
                        XposedBridge.log(TAG + ": step listener + type=" + type
                                + " regs=" + reg.size()
                                + " total=" + sListeners.size());
                    }
                }
                // 步数双源 A/B 开关：rs=1 时跳过真实注册，
                // 让应用只收到我们合成的事件（真实硬件事件不再叠加）。
                if (MotionEngine.suppressRealSteps()) {
                    param.setResult(Boolean.TRUE); // registerListener 返回 boolean，不能用 null
                    XposedBridge.log(TAG + ": step register suppressed (rs=1) type=" + type);
                }
                MotionEngine.addListener(null);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": step hook error " + t);
            }
        }
    }

    /**
     * 注销：带 Sensor 时只移除这一个实例，不带 Sensor 时移除该 listener 的全部计步注册。
     *
     * Android 的 unregisterListener(listener, sensor) 只注销传入的那个 Sensor；
     * 同类型的不同 Sensor 实例必须各自独立。应用若传入了「等价但不同实例」的 Sensor，
     * 只有该类型在表中只剩一条时才等价移除，避免在同类型多条时误删。
     */
    static class UnregHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                SensorEventListener l = null;
                Sensor s = null;
                for (Object a : param.args) {
                    if (a instanceof SensorEventListener) l = (SensorEventListener) a;
                    if (a instanceof Sensor) s = (Sensor) a;
                }
                if (l == null) return;
                synchronized (sListeners) {
                    Registration reg = sListeners.get(l);
                    if (reg == null) return;
                    if (s != null) {
                        int type = s.getType();
                        boolean removed = reg.remove(s);
                        if (!removed) {
                            int ofType = reg.countOfType(type);
                            if (ofType == 0) return;   // 这个类型没有注册
                            if (ofType > 1) return;    // 同类型多条且实例不匹配：保守不动
                            removed = reg.remove(reg.firstOfType(type));
                        }
                        if (!removed) return;
                        XposedBridge.log(TAG + ": step listener - type=" + type
                                + " regs=" + reg.size());
                    } else {
                        reg.sensors.clear();
                    }
                    if (reg.isEmpty()) {
                        sListeners.remove(l);
                        XposedBridge.log(TAG + ": step listener - total=" + sListeners.size());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 每产生一步由引擎调用一次；每个 listener 只会收到一个事件，异常按 listener 隔离 */
    static void pushStep(long value) {
        if (sListeners.isEmpty()) return;

        List<SensorEventListener> ls;
        synchronized (sListeners) {
            ls = new ArrayList<SensorEventListener>(sListeners.keySet());
        }

        java.lang.reflect.Constructor<SensorEvent> ctor;
        try {
            ctor = SensorEvent.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": step event ctor unavailable " + t);
            return;
        }

        for (int i = 0; i < ls.size(); i++) {
            SensorEventListener l = ls.get(i);
            // try 必须在循环内：一个 listener 抛异常不能让后面的 listener 丢掉这一步
            try {
                Registration reg;
                synchronized (sListeners) {
                    reg = sListeners.get(l);
                }
                if (reg == null) continue;
                Sensor s = reg.payload();
                if (s == null) continue;
                int type = s.getType();

                SensorEvent ev = ctor.newInstance(Integer.valueOf(1));
                ev.sensor = s;
                ev.timestamp = System.nanoTime();
                ev.accuracy = 0;
                ev.values[0] = (type == Sensor.TYPE_STEP_COUNTER) ? (float) value : 1f;
                l.onSensorChanged(ev);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": step delivery to " + l.getClass().getName()
                        + " threw " + t);
            }
        }
    }
}
