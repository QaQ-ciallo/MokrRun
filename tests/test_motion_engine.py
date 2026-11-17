"""MotionEngine / SensorHook / LocationProbe / ResourceProbe / IntentBridge / GnssHook 回归测试。

Run: python tests/test_motion_engine.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
     python tests/test_motion_engine.py --jdk ... --case <name>

每个场景在独立 JVM 中编译并执行仓库里的**实际生产 Java 文件**，不是业务逻辑的副本。
时间相关场景使用 Handler 虚拟时钟（tests/jvm_fakes.py），不 sleep、不依赖真实等待。
退出码 1 表示至少一个场景失败；完整输出写入 build/regression-results.txt。

覆盖边界：仅 JVM 逻辑与 hook 合约。真实 Android 行为（OEM 字段、LSPosed 注入时序、
目标应用活动栈、真实传感器/GNSS 回调线程）不在本文件结论范围内。
"""
import argparse
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

PRODUCTION = ["MotionEngine", "SensorHook", "LocationProbe", "ResourceProbe",
              "IntentBridge", "GnssHook", "CommandReceiver"]

# 只有需要编译 CommandReceiver 的用例才补 Context 的广播注册能力；
# 不放进共享替身，外部夹具可以自行补充同名成员而不会重复声明。
RECEIVER_CONTEXT = """
package android.content;
public class Context {
    public static final int RECEIVER_EXPORTED=2;
    public final ContentResolver resolver=new ContentResolver();
    public Context getApplicationContext() { return this; }
    public ContentResolver getContentResolver() { return resolver; }
    public Object registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public Object registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
}
"""

HARNESS = r"""
package com.mokrun.app.xposed;

import android.app.*;
import android.content.*;
import android.database.*;
import android.hardware.*;
import android.location.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;

public class EngineRegression {

    static final String ORIGIN = "30.0,120.0";
    static final String ROUTE = "[{\"lat\":30.001,\"lng\":120.0},{\"lat\":30.001,\"lng\":120.001}]";
    static final String ROUTE_B = "[{\"lat\":30.002,\"lng\":120.0}]";

    // ---- reflection helpers ------------------------------------------------
    static Field field(String name) throws Exception {
        Field f = MotionEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    static void set(String name, Object value) throws Exception { field(name).set(null, value); }
    static Object get(String name) throws Exception { return field(name).get(null); }
    static double d(String name) throws Exception { return ((Double) get(name)).doubleValue(); }
    static long l(String name) throws Exception { return ((Long) get(name)).longValue(); }
    static int i(String name) throws Exception { return ((Integer) get(name)).intValue(); }
    static float f(String name) throws Exception { return ((Float) get(name)).floatValue(); }
    static Object call(String name) throws Exception {
        Method m = MotionEngine.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }
    static int readMode() throws Exception {
        Method m = MotionEngine.class.getDeclaredMethod("readMode");
        m.setAccessible(true);
        return ((Integer) m.invoke(null)).intValue();
    }
    /** 冻结超时时长取自生产常量，测试不硬编码 3 分钟 */
    static long faceFreezeMs() throws Exception { return l("FACE_FREEZE_MS"); }
    static double dist(double lat1, double lon1, double lat2, double lon2) throws Exception {
        Method m = MotionEngine.class.getDeclaredMethod("dist", double.class, double.class,
                double.class, double.class);
        m.setAccessible(true);
        return ((Double) m.invoke(null, lat1, lon1, lat2, lon2)).doubleValue();
    }
    static void check(boolean good, String message) { if (!good) throw new AssertionError(message); }
    static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

    static void configure(int mode) {
        MotionEngine.applyFromIntent("speed=4.8;cadence=3.1", ORIGIN, ROUTE, mode);
    }
    /** 触发一次 tick，并把「上一次 tick」设成 msAgo 毫秒前，dt 因此可控 */
    static void tickAfter(long msAgo) throws Exception {
        set("sLastTick", Long.valueOf(System.currentTimeMillis() - msAgo));
        call("tick");
    }
    /** 直接执行某个 key 下的 before-hook，并保留 param 以便断言是否被短路 */
    static void invokeBefore(String key, MethodHookParam p) throws Exception {
        java.util.List<XposedBridge.Registration> list = XposedBridge.hooks.get(key);
        check(list != null && !list.isEmpty(), "no hook registered for " + key);
        for (XposedBridge.Registration r : list) {
            Method before = r.hook.getClass().getDeclaredMethod("beforeHookedMethod",
                    XC_MethodHook.MethodHookParam.class);
            before.setAccessible(true);
            before.invoke(r.hook, p);
            if (p.stopped) break;   // 已被短路，同名重载不必再跑
        }
    }
    static MethodHookParam param(Object owner, Object... args) {
        MethodHookParam p = new MethodHookParam();
        p.thisObject = owner;
        p.args = args;
        return p;
    }

    // ---- fixtures ----------------------------------------------------------
    static class Listener implements SensorEventListener {
        int count; float last; boolean fail;
        public void onSensorChanged(SensorEvent e) {
            if (fail) throw new IllegalStateException("fixture listener");
            count++; last = e.values[0];
        }
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }
    static class LocListener implements LocationListener {
        int count; boolean fail;
        public void onLocationChanged(Location loc) {
            if (fail) throw new IllegalStateException("fixture location listener");
            count++;
        }
    }
    /** 系统侧的 IRemoteCallback（onComplete）记录器 */
    static class CallbackRecorder implements android.os.IRemoteCallback {
        int completed;
        public void sendResult(android.os.Bundle data) { completed++; }
    }
    static void register(Listener listener, Sensor sensor) {
        new SensorHook.RegHook().beforeHookedMethod(param(null, listener, sensor, Integer.valueOf(0)));
    }
    static void unregister(Listener listener, Sensor sensor) {
        if (sensor == null) new SensorHook.UnregHook().beforeHookedMethod(param(null, listener));
        else new SensorHook.UnregHook().beforeHookedMethod(param(null, listener, sensor));
    }
    static Context provider(float speed, float cadence, float stride, int run, String origin,
                            String route) {
        Context ctx = new Context();
        ctx.resolver.row = new Cursor(Float.valueOf(speed), Float.valueOf(cadence),
                Float.valueOf(stride), Integer.valueOf(1), origin, route, Integer.valueOf(run));
        return ctx;
    }

    /** 悬浮窗 sendCommand 的真实形态：一条广播同时带 cfg / origin / route / mode */
    static Intent command(String cfg, int mode) {
        return new Intent().putExtra("cfg", cfg)
                .putExtra("origin", ORIGIN)
                .putExtra("route", ROUTE)
                .putExtra("mode", mode);
    }

    // ---- cases -------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        String which = args[0];
        Handler.resetClock();

        if (which.equals("cadence_infinity_sanitized")) {
            String huge = "99999999999999999999999999999999999999999999999999";
            MotionEngine.applyFromIntent("speed=4.8;cadence=" + huge + ";stride=" + huge,
                    ORIGIN, ROUTE, 1);
            check(Float.isFinite(f("sCadence")), "cadence accepted a non-finite value");
            check(Float.isFinite(f("sStride")), "stride accepted a non-finite value");
            check(f("sCadence") >= 0.1f && f("sCadence") <= 10f,
                    "cadence out of business range: " + f("sCadence"));
            check(f("sStride") >= 0.2f && f("sStride") <= 5f,
                    "stride out of business range: " + f("sStride"));

        } else if (which.equals("tick_survives_infinite_cadence")) {
            String huge = "99999999999999999999999999999999999999999999999999";
            MotionEngine.applyFromIntent("speed=4.8;cadence=" + huge, ORIGIN, ROUTE, 1);
            check(Float.isFinite(f("sCadence")), "cadence must be sanitized before ticking");
            set("sLastTick", Long.valueOf(System.currentTimeMillis() - 1000L));
            Thread worker = new Thread(new Runnable() {
                public void run() { try { call("tick"); } catch (Exception e) { throw new RuntimeException(e); } }
            });
            worker.setDaemon(true);
            worker.start();
            worker.join(300L);
            check(!worker.isAlive(), "tick did not return in 300ms; steps=" + MotionEngine.steps());
            check(MotionEngine.steps() <= 64, "one tick produced " + MotionEngine.steps() + " steps");

        } else if (which.equals("nonfinite_and_negative_config_rejected")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=3.1", ORIGIN, ROUTE, 0);
            float speed = f("sSpeed"), cadence = f("sCadence"), stride = f("sStride");
            int sat = i("sSat"), qr = i("sQrStatus");
            MotionEngine.applyFromIntent(
                    "speed=NaN;cadence=NaN;stride=NaN;spdmax=Infinity;sat=-99999;qr=-5;alt=NaN;acc=-99",
                    ORIGIN, ROUTE, 0);
            check(f("sSpeed") == speed, "NaN speed overwrote the configured speed");
            check(f("sCadence") == cadence, "NaN cadence overwrote the configured cadence");
            check(f("sStride") == stride, "NaN stride overwrote the configured stride");
            check(f("sSpeedMax") > 0f && Float.isFinite(f("sSpeedMax")),
                    "spdmax became " + f("sSpeedMax"));
            check(i("sSat") == sat, "negative satellites accepted: " + i("sSat"));
            check(i("sQrStatus") == qr, "negative qr accepted: " + i("sQrStatus"));

        } else if (which.equals("tick_bounded_at_max_cadence")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=10000", ORIGIN, ROUTE, 1);
            check(f("sCadence") <= 10f, "cadence not clamped: " + f("sCadence"));
            tickAfter(1000L);
            check(MotionEngine.steps() <= 64, "one tick produced " + MotionEngine.steps() + " steps");
            check(MotionEngine.steps() >= 1, "clamped cadence should still produce steps");

        } else if (which.equals("tick_huge_clock_gap_bounded")) {
            configure(1);
            tickAfter(3600000L);           // 一小时的空档：距离与步数都必须回落到同一个上限窗口
            double moved = dist(30.0, 120.0, MotionEngine.lat(), MotionEngine.lon());
            check(moved <= 4.8 * 2.0 + 0.05, "clock gap moved " + moved + "m > one capped window");
            check(Math.abs(MotionEngine.steps() - moved / 4.8 * 3.1) <= 1.0,
                    "steps and distance diverged after a clock gap: steps=" + MotionEngine.steps()
                            + " dist=" + moved);

        } else if (which.equals("tick_gap_5s_keeps_sources_synced")) {
            configure(1);
            tickAfter(5000L);
            double meters = d("sDistTotal");
            long steps = MotionEngine.steps();
            check(Math.abs(steps - meters / 4.8 * 3.1) <= 1.0,
                    "distance and steps used different elapsed intervals: dist=" + meters
                            + " steps=" + steps + " expected=" + (meters / 4.8 * 3.1));

        } else if (which.equals("tick_gap_60s_keeps_sources_synced")) {
            configure(1);
            tickAfter(60000L);
            double meters = d("sDistTotal");
            long steps = MotionEngine.steps();
            check(meters <= 4.8 * 2.0 + 0.05, "60s gap moved " + meters + "m");
            check(Math.abs(steps - meters / 4.8 * 3.1) <= 1.0,
                    "60s gap: dist=" + meters + " steps=" + steps);

        } else if (which.equals("tick_normal_series_keeps_sources_synced")) {
            configure(1);
            for (int n = 0; n < 40; n++) tickAfter(250L);
            double meters = d("sDistTotal");
            long steps = MotionEngine.steps();
            check(meters > 45.0 && meters < 51.0, "40 ticks should walk ~48m, got " + meters);
            check(Math.abs(steps - meters / 4.8 * 3.1) <= 1.0,
                    "nominal ticks diverged: dist=" + meters + " steps=" + steps);

        } else if (which.equals("freeze_timeout_restores_running")) {
            configure(1);
            MotionEngine.freezeForFace();
            check(MotionEngine.faceFrozen(), "freeze flag not set");
            check(readMode() == 2, "freeze did not pause the engine: mode=" + readMode());
            Handler h = (Handler) get("sFaceHandler");
            check(h != null, "freeze did not schedule a timeout task");
            h.advance(faceFreezeMs() + 1);
            check(!MotionEngine.faceFrozen(), "freeze timeout did not clear the flag");
            check(readMode() == 1, "freeze timeout restored mode=" + readMode());

        } else if (which.equals("freeze_timeout_from_idle_keeps_idle")) {
            configure(0);
            MotionEngine.freezeForFace();
            Handler h = (Handler) get("sFaceHandler");
            h.advance(faceFreezeMs() + 1);
            check(readMode() == 0, "idle process resumed as mode=" + readMode());

        } else if (which.equals("stop_command_cancels_freeze")) {
            configure(1);
            MotionEngine.freezeForFace();
            MotionEngine.setMode(0);
            Handler h = (Handler) get("sFaceHandler");
            check(h == null || h.pending() == 0, "stop left the freeze timeout scheduled");
            h.advance(faceFreezeMs() + 1);
            MotionEngine.resumeAfterFace();
            check(readMode() == 0, "STOP overwritten: mode=" + readMode());
            check(!MotionEngine.faceFrozen(), "stop left the freeze flag set");

        } else if (which.equals("pause_command_cancels_freeze")) {
            configure(1);
            MotionEngine.freezeForFace();
            MotionEngine.setMode(2);
            Handler h = (Handler) get("sFaceHandler");
            h.advance(faceFreezeMs() + 1);
            check(readMode() == 2, "explicit pause was overwritten: mode=" + readMode());
            check(!MotionEngine.faceFrozen(), "explicit pause left the freeze flag set");

        } else if (which.equals("cfg_stop_command_cancels_freeze")) {
            // 真实入口：悬浮窗「结束」走带 cfg/origin/route 的广播 → CommandReceiver
            configure(1);
            MotionEngine.freezeForFace();
            CommandReceiver.Receiver receiver = new CommandReceiver.Receiver();
            receiver.onReceive(new Context(), command("speed=4.8;cadence=3.1", 0));
            Handler h = (Handler) get("sFaceHandler");
            check(h.pending() == 0, "cfg-bearing stop left the freeze timeout scheduled");
            h.advance(faceFreezeMs() + 1);
            check(readMode() == 0, "cfg-bearing STOP overwritten by freeze timeout: mode=" + readMode());
            check(!MotionEngine.faceFrozen(), "cfg-bearing stop left the freeze flag set");

        } else if (which.equals("cfg_pause_command_cancels_freeze")) {
            configure(1);
            MotionEngine.freezeForFace();
            CommandReceiver.Receiver receiver = new CommandReceiver.Receiver();
            receiver.onReceive(new Context(), command("speed=4.8;cadence=3.1", 2));
            Handler h = (Handler) get("sFaceHandler");
            h.advance(faceFreezeMs() + 1);
            check(readMode() == 2, "cfg-bearing PAUSE overwritten by freeze timeout: mode=" + readMode());
            check(!MotionEngine.faceFrozen(), "cfg-bearing pause left the freeze flag set");

        } else if (which.equals("cfg_continue_command_cancels_freeze")) {
            configure(1);
            MotionEngine.freezeForFace();
            CommandReceiver.Receiver receiver = new CommandReceiver.Receiver();
            receiver.onReceive(new Context(), command("speed=4.8;cadence=3.1", 1));
            check(readMode() == 1, "cfg-bearing resume did not apply: mode=" + readMode());
            check(!MotionEngine.faceFrozen(), "cfg-bearing resume left the freeze flag set");

        } else if (which.equals("provider_change_during_freeze_cancels")) {
            Context ctx = provider(4.8f, 3.1f, 1.55f, 1, ORIGIN, ROUTE);
            MotionEngine.configure(ctx);
            MotionEngine.freezeForFace();
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.1f),
                    Float.valueOf(1.55f), Integer.valueOf(1), ORIGIN, ROUTE, Integer.valueOf(0));
            set("sRunCheckedAt", Long.valueOf(0L));
            check(readMode() == 0, "app-side STOP during freeze ignored: mode=" + readMode());
            check(!MotionEngine.faceFrozen(), "app-side STOP left the freeze flag set");

        } else if (which.equals("provider_poll_keeps_freeze")) {
            MotionEngine.configure(provider(4.8f, 3.1f, 1.55f, 1, ORIGIN, ROUTE));
            MotionEngine.freezeForFace();
            set("sRunCheckedAt", Long.valueOf(0L));
            check(readMode() == 2, "provider poll released the freeze: mode=" + readMode());
            check(MotionEngine.faceFrozen(), "provider poll cleared the freeze flag");

        } else if (which.equals("provider_poll_keeps_explicit_pause")) {
            Context ctx = provider(4.8f, 3.1f, 1.55f, 1, ORIGIN, ROUTE);
            MotionEngine.configure(ctx);
            MotionEngine.setMode(2);
            set("sRunCheckedAt", Long.valueOf(0L));
            check(readMode() == 2, "provider overwrote explicit PAUSE: mode=" + readMode());

        } else if (which.equals("provider_change_still_followed")) {
            Context ctx = provider(4.8f, 3.1f, 1.55f, 1, ORIGIN, ROUTE);
            MotionEngine.configure(ctx);
            check(readMode() == 1, "initial provider mode not applied: " + readMode());
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.1f),
                    Float.valueOf(1.55f), Integer.valueOf(1), ORIGIN, ROUTE, Integer.valueOf(2));
            set("sRunCheckedAt", Long.valueOf(0L));
            check(readMode() == 2, "app-side mode change ignored: mode=" + readMode());
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.1f),
                    Float.valueOf(1.55f), Integer.valueOf(1), ORIGIN, ROUTE, Integer.valueOf(1));
            set("sRunCheckedAt", Long.valueOf(0L));
            check(readMode() == 1, "app-side resume ignored: mode=" + readMode());

        } else if (which.equals("route_end_reverses_without_teleport")) {
            configure(1);
            double segLen = dist(30.001, 120.0, 30.001, 120.001);
            set("sSeg", Integer.valueOf(1));
            set("sSegProgress", Double.valueOf(segLen - 0.2));
            double beforeLat = 30.001, beforeLon = 120.001 - (0.2 / segLen) * 0.001;
            set("sCurLat", Double.valueOf(beforeLat));
            set("sCurLon", Double.valueOf(beforeLon));
            tickAfter(250L);
            double moved = dist(beforeLat, beforeLon, MotionEngine.lat(), MotionEngine.lon());
            check(moved < 5.0, "one tick moved " + moved + "m at the route end");
            check(moved > 0.8, "route end did not advance at all: " + moved + "m");
            check(near(MotionEngine.lat(), 30.001, 1e-6), "position left the route: lat=" + MotionEngine.lat());

        } else if (which.equals("route_end_repeat_no_jump")) {
            configure(1);
            double segLen = dist(30.001, 120.0, 30.001, 120.001);
            set("sSeg", Integer.valueOf(1));
            set("sSegProgress", Double.valueOf(segLen - 0.5));
            set("sCurLat", Double.valueOf(30.001));
            set("sCurLon", Double.valueOf(120.001 - (0.5 / segLen) * 0.001));
            double prevLat = MotionEngine.lat(), prevLon = MotionEngine.lon();
            double worst = 0;
            for (int n = 0; n < 80; n++) {
                tickAfter(250L);
                double step = dist(prevLat, prevLon, MotionEngine.lat(), MotionEngine.lon());
                worst = Math.max(worst, step);
                prevLat = MotionEngine.lat(); prevLon = MotionEngine.lon();
                check(MotionEngine.lat() >= 29.9999 && MotionEngine.lat() <= 30.0011,
                        "lat left the route envelope at tick " + n + ": " + MotionEngine.lat());
                check(MotionEngine.lon() >= 119.9999 && MotionEngine.lon() <= 120.0011,
                        "lon left the route envelope at tick " + n + ": " + MotionEngine.lon());
            }
            check(worst < 2.0, "per-tick displacement spiked to " + worst + "m");

        } else if (which.equals("clear_route_clears_points")) {
            configure(1);
            MotionEngine.applyFromIntent("speed=4.8", "", "[]", 0);
            check(!MotionEngine.hasRoute(), "cleared route still has a route");
            check(((double[]) get("sLat")).length == 0,
                    "cleared route retained " + ((double[]) get("sLat")).length + " old points");
            check(d("sDistTotal") == 0.0, "cleared route kept distance " + d("sDistTotal"));
            check(!MotionEngine.isActive(), "cleared route still active");

        } else if (which.equals("origin_only_does_not_advance")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=3.1", ORIGIN, "", 1);
            tickAfter(1000L);
            check(MotionEngine.steps() == 0, "origin-only route produced " + MotionEngine.steps() + " steps");
            check(d("sDistTotal") == 0.0, "origin-only route accumulated " + d("sDistTotal") + "m");
            check(near(MotionEngine.lat(), 30.0, 1e-7) && near(MotionEngine.lon(), 120.0, 1e-7),
                    "origin-only route moved to " + MotionEngine.lat() + "," + MotionEngine.lon());

        } else if (which.equals("no_route_rejects_run")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=3.1", "", "[]", 1);
            tickAfter(1000L);
            check(!MotionEngine.isRunningNow(), "engine claims to run without a route");
            check(MotionEngine.steps() == 0, "no route but steps=" + MotionEngine.steps()
                    + " dist=" + d("sDistTotal"));

        } else if (which.equals("route_switch_resets_progress")) {
            configure(1);
            tickAfter(1000L);
            double walked = d("sDistTotal");
            long steps = MotionEngine.steps();
            check(walked > 4.0 && steps > 0, "fixture failed to advance: " + walked + "m/" + steps + " steps");
            MotionEngine.applyFromIntent("speed=4.8;cadence=3.1", ORIGIN, ROUTE_B, 1);
            check(d("sDistTotal") == 0.0, "route switch kept distance " + d("sDistTotal"));
            check(MotionEngine.steps() == 0, "route switch kept " + MotionEngine.steps() + " steps");
            check(l("sStepBase") >= steps, "step base must stay monotonic across sessions");
            check(near(MotionEngine.lat(), 30.0, 1e-7) && near(MotionEngine.lon(), 120.0, 1e-7),
                    "route switch did not restart at the origin");

        } else if (which.equals("same_route_keeps_progress")) {
            configure(1);
            tickAfter(1000L);
            double walked = d("sDistTotal");
            long steps = MotionEngine.steps();
            MotionEngine.applyFromIntent("speed=4.8;cadence=3.1", ORIGIN, ROUTE, 1);
            check(d("sDistTotal") == walked, "re-sending the same route reset distance");
            check(MotionEngine.steps() == steps, "re-sending the same route reset steps");

        } else if (which.equals("paused_speed_reports_zero")) {
            configure(2);
            check(MotionEngine.current().getSpeed() == 0f,
                    "paused Location speed=" + MotionEngine.current().getSpeed());
            check(MotionEngine.speedNow() == 0f, "paused speedNow=" + MotionEngine.speedNow());
            MotionEngine.setMode(1);
            check(MotionEngine.speedNow() > 0f, "running speedNow must be the configured speed");

        } else if (which.equals("sensor_partial_unregister_keeps_step_types")) {
            Sensor counter = new Sensor(Sensor.TYPE_STEP_COUNTER);
            Sensor detector = new Sensor(Sensor.TYPE_STEP_DETECTOR);
            Sensor accel = new Sensor(Sensor.TYPE_ACCELEROMETER);
            Listener l = new Listener();
            register(l, counter);
            SensorHook.pushStep(1);
            unregister(l, accel);
            SensorHook.pushStep(2);
            check(l.count == 2, "unregistering accelerometer removed the step listener; received=" + l.count);

            Listener dual = new Listener();
            register(dual, counter);
            register(dual, detector);
            SensorHook.pushStep(1);
            unregister(dual, counter);
            SensorHook.pushStep(2);
            check(dual.count == 2, "remaining detector registration lost; received=" + dual.count);
            check(dual.last == 1f, "detector must receive 1, got " + dual.last);

        } else if (which.equals("sensor_dedup_two_step_types")) {
            Sensor counter = new Sensor(Sensor.TYPE_STEP_COUNTER);
            Sensor detector = new Sensor(Sensor.TYPE_STEP_DETECTOR);
            Listener l = new Listener();
            register(l, counter);
            register(l, detector);
            for (int n = 1; n <= 5; n++) SensorHook.pushStep(n);
            check(l.count == 5, "dual-registered listener got " + l.count + " events for 5 steps");
            check(l.last == 5f, "counter payload must be cumulative, got " + l.last);

        } else if (which.equals("sensor_listener_exception_isolated")) {
            Sensor counter = new Sensor(Sensor.TYPE_STEP_COUNTER);
            Listener a = new Listener(), b = new Listener();
            register(a, counter);
            register(b, counter);
            Field f = SensorHook.class.getDeclaredField("sListeners");
            f.setAccessible(true);
            Map<?, ?> listeners = (Map<?, ?>) f.get(null);
            Listener first = (Listener) listeners.keySet().iterator().next();
            first.fail = true;
            Listener healthy = first == a ? b : a;
            SensorHook.pushStep(1);
            check(healthy.count == 1, "one throwing listener prevented the healthy one; received=" + healthy.count);

        } else if (which.equals("sensor_unregister_all_stops_events")) {
            Sensor counter = new Sensor(Sensor.TYPE_STEP_COUNTER);
            Listener l = new Listener();
            register(l, counter);
            SensorHook.pushStep(1);
            unregister(l, null);
            SensorHook.pushStep(2);
            check(l.count == 1, "fully unregistered listener still received=" + l.count);

        } else if (which.equals("sensor_same_type_two_sensors")) {
            // 同一 listener 注册两个同类型（TYPE_STEP_COUNTER）的不同 Sensor 实例
            Sensor a = new Sensor(Sensor.TYPE_STEP_COUNTER);
            Sensor b = new Sensor(Sensor.TYPE_STEP_COUNTER);
            Listener l = new Listener();
            register(l, a);
            register(l, b);
            unregister(l, a);
            SensorHook.pushStep(1);
            check(l.count == 1, "unregistering one Sensor removed a different Sensor of the same type");
            unregister(l, b);
            SensorHook.pushStep(2);
            check(l.count == 1, "both same-type Sensors unregistered but listener still received");

        } else if (which.equals("sensor_equivalent_sensor_instance_unregister")) {
            // 应用传入「同类型但不同实例」的 Sensor 时，只有该类型唯一注册才安全地等价移除
            Sensor registered = new Sensor(Sensor.TYPE_STEP_DETECTOR);
            Listener l = new Listener();
            register(l, registered);
            unregister(l, new Sensor(Sensor.TYPE_STEP_DETECTOR));
            SensorHook.pushStep(1);
            check(l.count == 0, "equivalent Sensor instance did not unregister the only registration");

        } else if (which.equals("location_single_update_suppresses_system_request")) {
            configure(1);
            LocationProbe.install(EngineRegression.class.getClassLoader());
            LocListener sink = new LocListener();
            XC_MethodHook.MethodHookParam p = param(null, "gps", sink, Looper.getMainLooper());
            invokeBefore("android.location.LocationManager.requestSingleUpdate", p);
            check(p.stopped, "single-shot system request was not short-circuited during takeover");
            call("emitLocation");
            check(sink.count == 1, "synthetic one-shot delivery failed: " + sink.count);
            // 原系统请求已被短路，框架不可能再回调一次真实位置
            call("emitLocation");
            check(sink.count == 1, "single-shot listener received " + sink.count + " updates");

        } else if (which.equals("system_callback_suppressed_during_takeover")) {
            // 设备（API 36）上的真实形态：LocationManager$LocationListenerTransport
            // 的 onLocationChanged(List<Location>, IRemoteCallback)
            configure(1);
            LocationProbe.install(EngineRegression.class.getClassLoader());
            check(XposedBridge.hookCount(
                    "android.location.LocationManager$LocationListenerTransport.onLocationChanged") > 0,
                    "callback guard not installed on the device transport class");
            android.location.LocationManager$LocationListenerTransport transport =
                    new android.location.LocationManager$LocationListenerTransport();
            CallbackRecorder cb = new CallbackRecorder();
            List<Location> batch = new ArrayList<Location>();
            batch.add(new Location("gps"));
            MethodHookParam p = param(transport, batch, cb);
            invokeBefore("android.location.LocationManager$LocationListenerTransport.onLocationChanged", p);
            if (!p.stopped) transport.onLocationChanged(batch, cb);      // 框架原实现
            check(transport.delivered == 0, "real system callback delivered during takeover");
            check(p.stopped, "real system callback was not short-circuited during takeover");
            check(cb.completed == 1, "system onComplete callback was not acknowledged");

            MotionEngine.setMode(0);                                     // 结束：交还真实定位
            p = param(transport, batch, cb);
            invokeBefore("android.location.LocationManager$LocationListenerTransport.onLocationChanged", p);
            if (!p.stopped) transport.onLocationChanged(batch, cb);
            check(transport.delivered == 1, "real system callback blocked after takeover ended");

        } else if (which.equals("legacy_system_callback_suppressed")) {
            // Android 10 及更早的旧类名/旧签名：候选类名回退路径
            configure(1);
            LocationProbe.install(EngineRegression.class.getClassLoader());
            check(XposedBridge.hookCount(
                    "android.location.LocationManager$ListenerTransport.onLocationChanged") > 0,
                    "callback guard not installed on the legacy transport class");
            android.location.LocationManager$ListenerTransport transport =
                    new android.location.LocationManager$ListenerTransport();
            MethodHookParam p = param(transport, new Location("gps"));
            invokeBefore("android.location.LocationManager$ListenerTransport.onLocationChanged", p);
            if (!p.stopped) transport.onLocationChanged(new Location("gps"));
            check(transport.delivered == 0, "legacy transport delivered during takeover");
            MotionEngine.setMode(0);
            p = param(transport, new Location("gps"));
            invokeBefore("android.location.LocationManager$ListenerTransport.onLocationChanged", p);
            if (!p.stopped) transport.onLocationChanged(new Location("gps"));
            check(transport.delivered == 1, "legacy transport blocked after takeover ended");

        } else if (which.equals("location_remove_updates_stops_delivery")) {
            configure(1);
            LocationProbe.install(EngineRegression.class.getClassLoader());
            LocListener l = new LocListener();
            XposedBridge.invoke("android.location.LocationManager.requestLocationUpdates",
                    true, null, "gps", Long.valueOf(1000L), Float.valueOf(0f), l);
            call("emitLocation");
            check(l.count == 1, "fixture failed to capture the location listener");
            XposedBridge.invoke("android.location.LocationManager.removeUpdates", true, null,
                    (Object) l);
            call("emitLocation");
            call("emitLocation");
            check(l.count == 1, "removed location listener still received " + (l.count - 1)
                    + " extra synthetic update(s)");

        } else if (which.equals("location_single_shot_delivered_once")) {
            configure(1);
            LocationProbe.install(EngineRegression.class.getClassLoader());
            LocListener l = new LocListener();
            XposedBridge.invoke("android.location.LocationManager.requestSingleUpdate", true, null,
                    "gps", l, (Object) Looper.getMainLooper());
            call("emitLocation");
            call("emitLocation");
            check(l.count == 1, "single-shot listener received " + l.count + " updates");

        } else if (which.equals("location_listener_exception_isolated")) {
            configure(1);
            LocationProbe.install(EngineRegression.class.getClassLoader());
            LocListener bad = new LocListener(), good = new LocListener();
            bad.fail = true;
            XposedBridge.invoke("android.location.LocationManager.requestLocationUpdates",
                    true, null, "gps", Long.valueOf(1000L), Float.valueOf(0f), bad);
            XposedBridge.invoke("android.location.LocationManager.requestLocationUpdates",
                    true, null, "gps", Long.valueOf(1000L), Float.valueOf(0f), good);
            call("emitLocation");
            check(good.count == 1, "throwing location listener blocked the healthy one; received=" + good.count);

        } else if (which.equals("related_dialog_dismiss_resumes")) {
            configure(1);
            ResourceProbe.install(EngineRegression.class.getClassLoader());
            Dialog face = new Dialog();
            reportText(face, "本次跑步记录无效｜系统检测到您中途第2次人脸识别超出150米允许识别范围");
            check(MotionEngine.faceFrozen(), "face text did not freeze the engine");
            XposedBridge.invoke("android.app.Dialog.dismiss", false, face);
            check(!MotionEngine.faceFrozen(), "dismissing the face dialog kept the freeze");
            check(readMode() == 1, "resume after face dialog restored mode=" + readMode());

        } else if (which.equals("unrelated_dialog_dismiss_keeps_freeze")) {
            configure(1);
            ResourceProbe.install(EngineRegression.class.getClassLoader());
            Dialog face = new Dialog();
            reportText(face, "请进行人脸识别");
            Dialog other = new Dialog();
            XposedBridge.invoke("android.app.Dialog.dismiss", false, other);
            check(MotionEngine.faceFrozen(), "unrelated dialog released the freeze");
            XposedBridge.invoke("android.app.Dialog.dismiss", false, face);
            check(!MotionEngine.faceFrozen(), "the face dialog did not release the freeze");

        } else if (which.equals("dialog_dismiss_without_freeze_noop")) {
            configure(1);
            ResourceProbe.install(EngineRegression.class.getClassLoader());
            XposedBridge.invoke("android.app.Dialog.dismiss", false, new Dialog());
            check(readMode() == 1, "dismissing a dialog changed the mode to " + readMode());

        } else if (which.equals("face_text_exclusion_still_applies")) {
            configure(1);
            ResourceProbe.install(EngineRegression.class.getClassLoader());
            reportText(null, "报名后会随机进行人脸识别，请注意");
            check(!MotionEngine.faceFrozen(), "pre-race notice must not freeze the engine");

        } else if (which.equals("log_throttle_keeps_detection")) {
            configure(1);
            Field field = ResourceProbe.class.getDeclaredField("sLastLog");
            field.setAccessible(true);
            field.set(null, Long.valueOf(System.currentTimeMillis()));
            Method report = ResourceProbe.class.getDeclaredMethod("report", String.class, String.class);
            report.setAccessible(true);
            report.invoke(null, "fixture", "请进行人脸识别");
            check(MotionEngine.faceFrozen(), "a log within the throttle window skipped face detection");

        } else if (which.equals("new_intent_applies_callback_intent")) {
            configure(0);
            IntentBridge.install(EngineRegression.class.getClassLoader());
            Activity activity = new Activity();
            activity.setIntent(new Intent());
            Intent incoming = new Intent()
                    .putExtra("mokr_cfg", "speed=3.5;cadence=2.6")
                    .putExtra("mokr_origin", ORIGIN)
                    .putExtra("mokr_route", ROUTE)
                    .putExtra("mokr_run", 1);
            XposedBridge.invoke("android.app.Activity.onNewIntent", false, activity, incoming);
            check(MotionEngine.isRunningNow(), "new intent did not start the engine: mode=" + readMode());
            check(MotionEngine.speedNow() == 3.5f, "new intent speed ignored: " + MotionEngine.speedNow());

        } else if (which.equals("on_create_reads_activity_intent")) {
            configure(0);
            IntentBridge.install(EngineRegression.class.getClassLoader());
            Activity activity = new Activity();
            activity.setIntent(new Intent()
                    .putExtra("mokr_cfg", "speed=3.5;cadence=2.6")
                    .putExtra("mokr_origin", ORIGIN)
                    .putExtra("mokr_route", ROUTE)
                    .putExtra("mokr_run", 1));
            XposedBridge.invoke("android.app.Activity.onCreate", false, activity);
            check(MotionEngine.isRunningNow(), "onCreate ignored the launch intent: mode=" + readMode());
            check(MotionEngine.speedNow() == 3.5f, "onCreate speed ignored: " + MotionEngine.speedNow());

        } else if (which.equals("new_intent_without_extras_ignored")) {
            configure(0);
            IntentBridge.install(EngineRegression.class.getClassLoader());
            Activity activity = new Activity();
            XposedBridge.invoke("android.app.Activity.onNewIntent", false, activity, new Intent());
            check(readMode() == 0, "intent without extras changed the mode to " + readMode());
            // 未开始时 speedNow() 按合同回 0，配置速度本身不能被无配置的 Intent 改动
            check(f("sSpeed") == 4.8f, "intent without extras changed the configured speed");
            check(MotionEngine.speedNow() == 0f, "idle engine must report speed 0");

        } else if (which.equals("cfg_only_intent_keeps_route")) {
            configure(1);
            IntentBridge.install(EngineRegression.class.getClassLoader());
            Activity activity = new Activity();
            activity.setIntent(new Intent()
                    .putExtra("mokr_cfg", "speed=3.5;cadence=2.6")
                    .putExtra("mokr_run", 1));
            XposedBridge.invoke("android.app.Activity.onCreate", false, activity);
            check(MotionEngine.hasRoute(), "a cfg-only intent cleared the route");
            check(((double[]) get("sLat")).length == 3,
                    "route points lost: " + ((double[]) get("sLat")).length);
            check(MotionEngine.speedNow() == 3.5f, "cfg-only intent did not apply the speed: "
                    + MotionEngine.speedNow());

        } else if (which.equals("gnss_payload_order_units")) {
            Method build = GnssHook.class.getDeclaredMethod("build");
            build.setAccessible(true);
            GnssStatus status = (GnssStatus) build.invoke(null);
            check(status != null, "GNSS build failed");
            int count = status.getSatelliteCount();
            check(count >= 12, "expected 12~16 satellites, got " + count);
            for (int n = 0; n < count; n++) {
                float[] s = status.satellites.get(n);
                float constellation = s[0], svid = s[1], cn0 = s[2], elev = s[3], azim = s[4];
                float carrier = s[5], baseband = s[6];
                check(constellation == GnssStatus.CONSTELLATION_GPS,
                        "satellite " + n + " constellation=" + constellation);
                check(svid == n + 1, "satellite " + n + " svid=" + svid);
                check(carrier > 1.0e9f && carrier < 2.0e9f,
                        "satellite " + n + " carrierHz=" + carrier);
                check(baseband > 0f && baseband <= 63f,
                        "satellite " + n + " basebandCn0=" + baseband);
                check(elev >= 0f && elev <= 90f, "satellite " + n + " elevation=" + elev);
                check(azim >= 0f && azim < 360f, "satellite " + n + " azimuth=" + azim);
                check(cn0 >= 20f && cn0 <= 40f, "satellite " + n + " cn0=" + cn0);
            }

        } else {
            throw new IllegalArgumentException(which);
        }
        System.out.println("PASS " + which);
    }

    /** 人脸冻结的可测试接缝：生产代码用它把「文本 → 行为」和「日志输出」分开 */
    static void reportText(Object owner, String text) throws Exception {
        Method m;
        try {
            m = ResourceProbe.class.getDeclaredMethod("handleText", String.class, Object.class,
                    String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("ResourceProbe.handleText seam missing: "
                    + "face detection is not bound to a dialog owner");
        }
        m.setAccessible(true);
        m.invoke(null, "fixture", owner, text);
    }
}
"""

CASES = [
    # 数值入口与每 tick 循环（问题 1）
    "cadence_infinity_sanitized",
    "tick_survives_infinite_cadence",
    "nonfinite_and_negative_config_rejected",
    "tick_bounded_at_max_cadence",
    "tick_huge_clock_gap_bounded",
    "tick_gap_5s_keeps_sources_synced",
    "tick_gap_60s_keeps_sources_synced",
    "tick_normal_series_keeps_sources_synced",
    # 冻结 / 停止 / 暂停 / 配置轮询（问题 2、3）
    "freeze_timeout_restores_running",
    "freeze_timeout_from_idle_keeps_idle",
    "stop_command_cancels_freeze",
    "pause_command_cancels_freeze",
    "cfg_stop_command_cancels_freeze",
    "cfg_pause_command_cancels_freeze",
    "cfg_continue_command_cancels_freeze",
    "provider_change_during_freeze_cancels",
    "provider_poll_keeps_freeze",
    "provider_poll_keeps_explicit_pause",
    "provider_change_still_followed",
    # 路线语义（问题 5、6、11）
    "route_end_reverses_without_teleport",
    "route_end_repeat_no_jump",
    "clear_route_clears_points",
    "origin_only_does_not_advance",
    "no_route_rejects_run",
    "route_switch_resets_progress",
    "same_route_keeps_progress",
    # 暂停速度（问题 10）
    "paused_speed_reports_zero",
    # 传感器监听器（问题 7、9）
    "sensor_partial_unregister_keeps_step_types",
    "sensor_dedup_two_step_types",
    "sensor_listener_exception_isolated",
    "sensor_unregister_all_stops_events",
    "sensor_same_type_two_sensors",
    "sensor_equivalent_sensor_instance_unregister",
    # 定位监听器（问题 8）
    "location_remove_updates_stops_delivery",
    "location_single_shot_delivered_once",
    "location_listener_exception_isolated",
    "location_single_update_suppresses_system_request",
    "system_callback_suppressed_during_takeover",
    "legacy_system_callback_suppressed",
    # 弹窗冻结与日志限流（问题 13、14）
    "related_dialog_dismiss_resumes",
    "unrelated_dialog_dismiss_keeps_freeze",
    "dialog_dismiss_without_freeze_noop",
    "face_text_exclusion_still_applies",
    "log_throttle_keeps_detection",
    # 新 Intent 配置（问题 4）
    "new_intent_applies_callback_intent",
    "on_create_reads_activity_intent",
    "new_intent_without_extras_ignored",
    "cfg_only_intent_keeps_route",
    # GNSS 参数（问题 12）
    "gnss_payload_order_units",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    base = jvm_fakes.build(args.jdk, {"com/mokrun/app/xposed/EngineRegression.java": HARNESS},
                           PRODUCTION,
                           overrides={"android/content/Context.java": RECEIVER_CONTEXT})
    java = str(args.jdk / "bin" / "java.exe")
    output = jvm_fakes.ROOT / "build" / "regression-results.txt"

    results, failures = [], 0
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base), "com.mokrun.app.xposed.EngineRegression", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            reason = next((line.strip() for line in (run.stdout + run.stderr).splitlines()
                           if "Error:" in line or "Exception" in line), "see log")
            print(f"FAIL {case}: {reason}")
        else:
            print(f"PASS {case}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s) out of {len(CASES) if not args.case else 1}. "
          f"Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
