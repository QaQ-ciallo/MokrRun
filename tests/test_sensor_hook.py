"""JVM regression test of the production SensorHook with small Android/Xposed fakes.

Run: python tests/test_sensor_hook.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
The fakes only model the hook boundary; filtering and payloads use production code.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "android/hardware/Sensor.java": """
package android.hardware;
public class Sensor {
    public static final int TYPE_STEP_COUNTER=19, TYPE_STEP_DETECTOR=18;
    private final int type;
    public Sensor(int type) { this.type=type; }
    public int getType() { return type; }
}
""",
    "android/hardware/SensorEvent.java": """
package android.hardware;
public class SensorEvent {
    public Sensor sensor; public long timestamp; public int accuracy;
    public final float[] values;
    SensorEvent(int size) { values=new float[size]; }
}
""",
    "android/hardware/SensorEventListener.java": """
package android.hardware;
public interface SensorEventListener { void onSensorChanged(SensorEvent event); }
""",
    "de/robv/android/xposed/XC_MethodHook.java": """
package de.robv.android.xposed;
public class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam p) {}
    public static class MethodHookParam {
        public Object[] args; public Object thisObject; public boolean stopped;
        private Object result;
        public Object getResult() { return result; }
        public void setResult(Object value) { stopped=true; result=value; }
    }
}
""",
    "de/robv/android/xposed/XposedBridge.java": """
package de.robv.android.xposed;
public class XposedBridge {
    public static void log(String msg) {
        if (msg.contains("failed") || msg.contains("error")) throw new AssertionError(msg);
    }
    public static void hookAllMethods(Class<?> c, String name, XC_MethodHook hook) {}
}
""",
    "de/robv/android/xposed/XposedHelpers.java": """
package de.robv.android.xposed;
public class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader cl) { return Object.class; }
    public static Object getObjectField(Object obj, String name) {
        for (Class<?> c=obj.getClass(); c!=null; c=c.getSuperclass()) {
            try {
                java.lang.reflect.Field f=c.getDeclaredField(name);
                f.setAccessible(true); return f.get(obj);
            } catch (NoSuchFieldException e) { continue; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("Missing field: " + name);
    }
}
""",
    "com/mokrun/app/xposed/MotionEngine.java": """
package com.mokrun.app.xposed;
public class MotionEngine {
    static boolean suppress=true;
    public static boolean suppressRealSteps() { return suppress; }
    public static void addListener(Object listener) {}
}
""",
    "com/mokrun/app/xposed/SensorHookRegression.java": """
package com.mokrun.app.xposed;
import android.hardware.*;
import java.util.*;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
public class SensorHookRegression {
    static class Listener implements SensorEventListener {
        int events; float last;
        public void onSensorChanged(SensorEvent event) { events++; last=event.values[0]; }
    }
    static class Manager { Map<Integer, Sensor> mHandleToSensor=new HashMap<>(); }
    static class BaseQueue { Manager mManager=new Manager(); }
    static class Queue extends BaseQueue {}
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static MethodHookParam param(Object owner, Object... args) {
        MethodHookParam p=new MethodHookParam(); p.thisObject=owner; p.args=args; return p;
    }
    public static void main(String[] args) {
        Sensor counter=new Sensor(19), detector=new Sensor(18), accel=new Sensor(1);
        Listener listener=new Listener();
        SensorHook.RegHook reg=new SensorHook.RegHook();
        MethodHookParam p=param(null, listener, counter, 0);
        reg.beforeHookedMethod(p);
        check(p.stopped && Boolean.TRUE.equals(p.getResult()), "registration must return true");
        reg.beforeHookedMethod(param(null, listener, counter, 0));
        for (int step=1; step<=558; step++) SensorHook.pushStep(step); // 180s * 3.1Hz
        check(listener.events==558 && listener.last==558, "duplicate listener or wrong cadence payload");
        Queue queue=new Queue();
        queue.mManager.mHandleToSensor.put(191, counter);
        queue.mManager.mHandleToSensor.put(181, detector);
        queue.mManager.mHandleToSensor.put(11, accel);
        SensorHook.RealEventHook guard=new SensorHook.RealEventHook();
        p=param(queue, 191, new float[]{50000}, 0, 180000000000L);
        guard.beforeHookedMethod(p);
        if (!p.stopped) listener.last=50000;
        check(p.stopped && listener.last==558, "late hardware 50000 leaked after three minutes");
        p=param(queue, 181, new float[]{1}, 0, 0L); guard.beforeHookedMethod(p);
        check(p.stopped, "hardware detector leaked");
        p=param(queue, 11, new float[]{1,2,3}, 0, 0L); guard.beforeHookedMethod(p);
        check(!p.stopped, "non-step sensor blocked");
        p=param(queue, 999, new float[]{0}, 0, 0L); guard.beforeHookedMethod(p);
        check(!p.stopped, "unknown/disconnected sensor blocked");
        SensorHook.pushStep(559);
        check(listener.events==559 && listener.last==559, "synthetic delivery interrupted");
        MotionEngine.suppress=false;
        p=param(queue, 191, new float[]{50001}, 0, 0L); guard.beforeHookedMethod(p);
        check(!p.stopped, "rs=0 diagnostic mode blocked");
        p=param(null, new Listener(), counter, 0); reg.beforeHookedMethod(p);
        check(!p.stopped, "rs=0 registration blocked");
        MotionEngine.suppress=true;
        Listener detected=new Listener(); reg.beforeHookedMethod(param(null, detected, detector, 0));
        SensorHook.pushStep(560);
        check(detected.last==1, "detector must receive one, not cumulative steps");
        new SensorHook.UnregHook().beforeHookedMethod(param(null, listener));
        int before=listener.events; SensorHook.pushStep(561);
        check(listener.events==before, "unregistered listener still receives steps");
        System.out.println("PASS: late 50000 blocked; synthetic counter/detector, dedup, unregister, rs=0, other sensors");
    }
}
""",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", required=True, type=Path)
    args = parser.parse_args()
    output = ROOT / "build"
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sensor-test-", dir=output) as temp:
        base = Path(temp)
        files = []
        for name, source in SOURCES.items():
            path = base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        files.append(str(ROOT / "app/src/main/java/com/mokrun/app/xposed/SensorHook.java"))
        subprocess.run([str(args.jdk / "bin/javac.exe"), "--release", "8", "-encoding", "UTF-8",
                        "-d", str(base), *files], check=True)
        subprocess.run([str(args.jdk / "bin/java.exe"), "-cp", str(base),
                        "com.mokrun.app.xposed.SensorHookRegression"], check=True)


if __name__ == "__main__":
    main()
