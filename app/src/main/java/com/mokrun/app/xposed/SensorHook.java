package com.mokrun.app.xposed;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Feeds the app's step listeners with the counter maintained by {@link MotionEngine}.
 */
public final class SensorHook {

    private static final String TAG = "MokrRun";

    private static final ArrayList<SensorEventListener> sListeners =
            new ArrayList<SensorEventListener>();
    private static final ArrayList<Sensor> sSensors = new ArrayList<Sensor>();
    private static boolean sInstalled = false;

    private SensorHook() {}

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        String[] classes = {"android.hardware.SensorManager", "android.hardware.SystemSensorManager"};
        for (int i = 0; i < classes.length; i++) {
            try {
                Class<?> c = XposedHelpers.findClass(classes[i], cl);
                XposedBridge.hookAllMethods(c, "registerListener", new RegHook());
                XposedBridge.log(TAG + ": sensor registerListener hooked on " + classes[i]);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": sensor hook failed on " + classes[i] + " (" + t + ")");
            }
        }
    }

    static class RegHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object l = null;
                Object s = null;
                for (Object a : param.args) {
                    if (a instanceof SensorEventListener) l = a;
                    if (a instanceof Sensor) s = a;
                }
                if (l == null || s == null) return;
                int type = ((Sensor) s).getType();
                if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return;
                synchronized (sListeners) {
                    sListeners.add((SensorEventListener) l);
                    sSensors.add((Sensor) s);
                }
                XposedBridge.log(TAG + ": step listener registered type=" + type
                        + " total=" + sListeners.size());
                MotionEngine.addListener(null);  // make sure the engine is running
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": step hook error " + t);
            }
        }
    }

    /** called from MotionEngine every time a step is produced */
    static void pushStep(long value) {
        ArrayList<SensorEventListener> ls;
        ArrayList<Sensor> ss;
        synchronized (sListeners) {
            if (sListeners.isEmpty()) return;
            ls = new ArrayList<SensorEventListener>(sListeners);
            ss = new ArrayList<Sensor>(sSensors);
        }
        try {
            java.lang.reflect.Constructor<SensorEvent> ctor =
                    SensorEvent.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            for (int i = 0; i < ls.size(); i++) {
                Sensor s = ss.get(i);
                SensorEvent ev = ctor.newInstance(Integer.valueOf(1));
                ev.sensor = s;
                ev.timestamp = System.nanoTime();
                ev.accuracy = 0;
                ev.values[0] = (s.getType() == Sensor.TYPE_STEP_COUNTER) ? (float) value : 1f;
                ls.get(i).onSensorChanged(ev);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": pushStep failed " + t);
        }
    }
}
