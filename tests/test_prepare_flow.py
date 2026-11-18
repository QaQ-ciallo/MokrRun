"""预备态（PREPARED=3）与「启用—配置—确认启动」流程的 JVM 回归。

Run: python tests/test_prepare_flow.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
      python tests/test_prepare_flow.py --jdk ... --case prepared_without_route

每个场景独立 JVM，执行仓库里的**真实生产代码**（MotionEngine / LocationProbe /
CommandReceiver / IntentBridge / RouteDraft / LocateTracker），Android/Xposed 边界
用 tests/jvm_fakes.py 的替身（类名、签名与设备一致）。

边界说明：本文件只验证 JVM 逻辑与 hook 合同。模块 App 的 Activity/悬浮窗、
目标应用的实机行为、以及 GPS 异常/人脸掉登录的成因都不在这里的结论范围内。
"""
import argparse
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

PRODUCTION = ["MotionEngine", "SensorHook", "LocationProbe", "ResourceProbe",
              "IntentBridge", "GnssHook", "CommandReceiver",
              "data.RouteDraft", "data.LocateTracker"]

RECEIVER_CONTEXT = """
package android.content;
public class Context {
    public static final int RECEIVER_EXPORTED=2;
    public final ContentResolver resolver=new ContentResolver();
    public Context getApplicationContext() { return this; }
    public ContentResolver getContentResolver() { return resolver; }
    public Object registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public Object registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
    public void sendBroadcast(Intent i) { SentBroadcasts.add(i); }
    public static final java.util.List<Intent> SentBroadcasts = new java.util.ArrayList<Intent>();
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
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import com.mokrun.app.data.LocateTracker;
import com.mokrun.app.data.RouteDraft;

public class PrepareFlowRegression {

    static final String ORIGIN = "30.0,120.0";
    static final String ROUTE = "[{\"lat\":30.001,\"lng\":120.0},{\"lat\":30.001,\"lng\":120.001}]";
    static final String CFG = "speed=4.8;cadence=3.12;stride=1.538";

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static Field field(String n) throws Exception {
        Field f = MotionEngine.class.getDeclaredField(n); f.setAccessible(true); return f;
    }
    static Object get(String n) throws Exception { return field(n).get(null); }
    static void set(String n, Object v) throws Exception { field(n).set(null, v); }
    static double d(String n) throws Exception { return ((Double) get(n)).doubleValue(); }
    static long l(String n) throws Exception { return ((Long) get(n)).longValue(); }
    static int i(String n) throws Exception { return ((Integer) get(n)).intValue(); }
    static String s(String n) throws Exception { return (String) get(n); }
    static void call(String n) throws Exception {
        Method m = MotionEngine.class.getDeclaredMethod(n); m.setAccessible(true); m.invoke(null);
    }
    static int readMode() throws Exception {
        Method m = MotionEngine.class.getDeclaredMethod("readMode"); m.setAccessible(true);
        return ((Integer) m.invoke(null)).intValue();
    }
    static void tickAfter(long msAgo) throws Exception {
        set("sLastTick", Long.valueOf(System.currentTimeMillis() - msAgo));
        call("tick");
    }
    static MethodHookParam param(Object owner, Object... args) {
        MethodHookParam p = new MethodHookParam(); p.thisObject = owner; p.args = args; return p;
    }
    static void invokeBefore(String key, MethodHookParam p) throws Exception {
        java.util.List<XposedBridge.Registration> list = XposedBridge.hooks.get(key);
        check(list != null && !list.isEmpty(), "no hook registered for " + key);
        for (XposedBridge.Registration r : list) {
            Method before = r.hook.getClass().getDeclaredMethod("beforeHookedMethod",
                    XC_MethodHook.MethodHookParam.class);
            before.setAccessible(true);
            before.invoke(r.hook, p);
            if (p.stopped) break;
        }
    }
    static class LocListener implements LocationListener {
        int count;
        public void onLocationChanged(Location loc) { count++; }
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];
        Handler.resetClock();

        if (which.equals("prepared_without_route_no_motion")) {
            MotionEngine.prepareSession("s1", CFG);
            check(readMode() == 3, "prepare must enter PREPARED(3), got " + readMode());
            check(MotionEngine.isSessionEnabled(), "session must be enabled");
            check(!MotionEngine.shouldOverridePosition(), "PREPARED must not override position");
            check(!MotionEngine.shouldAdvanceMotion(), "PREPARED must not advance motion");
            tickAfter(1000L);
            check(MotionEngine.steps() == 0, "PREPARED produced steps=" + MotionEngine.steps());
            check(d("sDistTotal") == 0.0, "PREPARED accumulated distance");

        } else if (which.equals("prepared_passes_real_location_through")) {
            MotionEngine.prepareSession("s1", CFG);
            LocationProbe.install(PrepareFlowRegression.class.getClassLoader());
            // 连续注册回调：PREPARED 不应被丢弃
            LocListener l = new LocListener();
            XposedBridge.invoke("android.location.LocationManager.requestLocationUpdates",
                    true, null, "gps", Long.valueOf(1000L), Float.valueOf(0f), l);
            MethodHookParam p = param(null, "gps", l);
            invokeBefore("android.location.LocationManager.requestSingleUpdate", p);
            check(!p.stopped, "PREPARED must not short-circuit real single-shot requests");
            // 系统回调守卫：PREPARED 必须放行真实事件
            android.location.LocationManager$LocationListenerTransport transport =
                    new android.location.LocationManager$LocationListenerTransport();
            List<Location> batch = new ArrayList<Location>();
            batch.add(new Location("gps"));
            MethodHookParam q = param(transport, batch, null);
            invokeBefore("android.location.LocationManager$LocationListenerTransport.onLocationChanged", q);
            check(!q.stopped, "PREPARED must not suppress real system location callbacks");

        } else if (which.equals("prepared_ignores_old_route")) {
            // 先有一场确认过的运行，再进入预备：不得沿用旧路线推进或覆盖坐标
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            tickAfter(1000L);
            MotionEngine.endSession();
            double oldDist = d("sDistTotal");
            MotionEngine.prepareSession("s2", CFG);
            check(readMode() == 3, "second prepare must be PREPARED");
            check(!MotionEngine.shouldOverridePosition(), "old route must not override in PREPARED");
            tickAfter(5000L);
            check(d("sDistTotal") == oldDist, "PREPARED advanced using the old route");

        } else if (which.equals("confirm_starts_once")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "tok", CFG, ORIGIN, ROUTE) == null, "first confirm failed");
            check(readMode() == 1, "confirm must enter RUNNING");
            tickAfter(1000L);
            double dist1 = d("sDistTotal");
            long steps1 = MotionEngine.steps();
            check(dist1 > 4.0 && steps1 > 0, "first confirm did not advance");
            // 重复确认（双击/重建/重复广播）
            String err = MotionEngine.applyConfirm("s1", "tok", CFG, ORIGIN, ROUTE);
            check(err != null, "duplicate confirm must be rejected");
            check(d("sDistTotal") == dist1, "duplicate confirm reset distance");
            check(MotionEngine.steps() == steps1, "duplicate confirm reset steps");

        } else if (which.equals("confirm_after_end_rejected")) {
            MotionEngine.prepareSession("s1", CFG);
            MotionEngine.applyConfirm("s1", "tok", CFG, ORIGIN, ROUTE);
            tickAfter(1000L);
            MotionEngine.endSession();
            check(readMode() == 0, "end must return to OFF");
            String err = MotionEngine.applyConfirm("s1", "late", CFG, ORIGIN, ROUTE);
            check(err != null, "late confirm after end must be rejected");
            check(readMode() == 0, "late confirm restarted the engine: mode=" + readMode());
            check(!MotionEngine.shouldOverridePosition(), "late confirm re-enabled override");

        } else if (which.equals("confirm_invalid_route_rejected")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, "", ROUTE) != null, "empty origin accepted");
            check(MotionEngine.applyConfirm("s1", "t2", CFG, "abc", ROUTE) != null, "garbage origin accepted");
            check(MotionEngine.applyConfirm("s1", "t3", CFG, "NaN,120.0", ROUTE) != null, "NaN origin accepted");
            check(MotionEngine.applyConfirm("s1", "t4", CFG, ORIGIN, "") != null, "empty route accepted");
            check(MotionEngine.applyConfirm("s1", "t5", CFG, ORIGIN,
                    "[{\"lat\":30.001,\"lng\":120.0}]") == null, "single waypoint must be accepted");
            // 上面最后一次合法确认后应处于 RUNNING；非法的一次都没启动
            check(readMode() == 1, "valid confirm after rejects did not start");

        } else if (which.equals("prepared_no_catchup")) {
            MotionEngine.prepareSession("s1", CFG);
            // 预备态停留 10 分钟（用虚拟时钟推进 idle tick）
            for (int n = 0; n < 20; n++) tickAfter(30000L);
            check(MotionEngine.steps() == 0, "idle ticks in PREPARED produced steps");
            check(MotionEngine.applyConfirm("s1", "tok", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            tickAfter(250L);
            check(d("sDistTotal") < 3.0,
                    "confirm补算了预备时长：dist=" + d("sDistTotal"));
            check(MotionEngine.steps() <= 2, "confirm补算了预备步数：" + MotionEngine.steps());

        } else if (which.equals("confirm_rebuilds_clock_and_base")) {
            MotionEngine.prepareSession("s1", CFG);
            MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE);
            for (int n = 0; n < 4; n++) tickAfter(250L);
            long firstSessionSteps = MotionEngine.steps();
            long base1 = l("sStepBase");
            MotionEngine.endSession();
            MotionEngine.prepareSession("s2", CFG);
            check(MotionEngine.applyConfirm("s2", "t2", CFG, ORIGIN, ROUTE) == null, "second session confirm failed");
            check(MotionEngine.steps() == 0, "new session must start from 0 steps");
            check(l("sStepBase") >= firstSessionSteps,
                    "step base must stay monotonic: " + l("sStepBase") + " < " + firstSessionSteps);
            check(l("sStepBase") >= base1, "step base regressed");
            for (int n = 0; n < 4; n++) tickAfter(250L);
            check(MotionEngine.steps() > 0, "second session did not advance");

        } else if (which.equals("provider_poll_keeps_prepared")) {
            Context ctx = new Context();
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), ORIGIN, ROUTE, Integer.valueOf(3));
            MotionEngine.configure(ctx);
            check(readMode() == 3, "provider run=3 must map to PREPARED, got " + readMode());
            check(!MotionEngine.shouldOverridePosition(), "provider-prepared must not override");
            // provider 里的路线变化（例如别处保存的路线）不能变成「已确认」
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), ORIGIN,
                    "[{\"lat\":31.0,\"lng\":121.0}]", Integer.valueOf(3));
            set("sRunCheckedAt", Long.valueOf(0L));
            readMode();
            check(!MotionEngine.shouldOverridePosition(),
                    "provider route must not become a confirmed route");

        } else if (which.equals("command_receiver_prepare_then_confirm")) {
            new CommandReceiver.Receiver().onReceive(new Context(),
                    intent().putExtra("op", "prepare").putExtra("session", "s1")
                           .putExtra("cfg", CFG));
            check(readMode() == 3, "op=prepare via receiver failed: " + readMode());
            new CommandReceiver.Receiver().onReceive(new Context(),
                    intent().putExtra("op", "confirm").putExtra("session", "s1")
                           .putExtra("token", "tok").putExtra("cfg", CFG)
                           .putExtra("origin", ORIGIN).putExtra("route", ROUTE));
            check(readMode() == 1, "op=confirm via receiver failed: " + readMode());
            tickAfter(1000L);
            double dist = d("sDistTotal");
            // 重复广播
            new CommandReceiver.Receiver().onReceive(new Context(),
                    intent().putExtra("op", "confirm").putExtra("session", "s1")
                           .putExtra("token", "tok").putExtra("cfg", CFG)
                           .putExtra("origin", ORIGIN).putExtra("route", ROUTE));
            check(d("sDistTotal") == dist, "duplicate broadcast reset distance");

        } else if (which.equals("intent_bridge_confirm_once")) {
            IntentBridge.install(PrepareFlowRegression.class.getClassLoader());
            Activity activity = new Activity();
            activity.setIntent(prepareIntent());
            XposedBridge.invoke("android.app.Activity.onCreate", false, activity);
            check(readMode() == 3, "Intent prepare failed: " + readMode());
            Intent confirm = new Intent().putExtra("mokr_op", "confirm")
                    .putExtra("mokr_session", "s1").putExtra("mokr_token", "tok")
                    .putExtra("mokr_cfg", CFG).putExtra("mokr_origin", ORIGIN)
                    .putExtra("mokr_route", ROUTE);
            XposedBridge.invoke("android.app.Activity.onNewIntent", false, activity, confirm);
            check(readMode() == 1, "Intent confirm failed: " + readMode());
            tickAfter(500L);
            double dist = d("sDistTotal");
            XposedBridge.invoke("android.app.Activity.onNewIntent", false, activity, confirm);
            check(d("sDistTotal") == dist, "duplicate Intent confirm reset distance");

        } else if (which.equals("session_mismatch_rejected")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s2", "tok", CFG, ORIGIN, ROUTE) != null,
                    "confirm from another session accepted");
            check(readMode() == 3, "mismatched session changed mode");
            check(MotionEngine.applyConfirm("s1", "", CFG, ORIGIN, ROUTE) != null,
                    "empty token accepted");

        } else if (which.equals("route_draft_rules")) {
            check(RouteDraft.parseOrigin("30.0,120.0") != null, "valid origin rejected");
            check(RouteDraft.parseOrigin("NaN,120") == null, "NaN origin accepted");
            check(RouteDraft.parseOrigin("abc") == null, "garbage origin accepted");
            check(RouteDraft.validateShape("30.0,120.0", "") != null, "empty route accepted");
            check(RouteDraft.validateShape("30.0,120.0", ROUTE) == null, "valid draft rejected");
            check(RouteDraft.waypointCount(ROUTE) == 2, "waypoint count wrong");
            // 用能解析成 Infinity 的超长数字（替身的 JSON 解析只认 [-0-9.]）
            StringBuilder big = new StringBuilder("1");
            for (int z = 0; z < 400; z++) big.append('0');
            check(RouteDraft.waypointCount("[{\"lat\":" + big + ",\"lng\":2}]") == -1,
                    "non-finite waypoint accepted");
            // 新鲜位置 + 近起点 → 直接通过
            RouteDraft.Result near = RouteDraft.check(ORIGIN, ROUTE, 30.0001, 120.0001, 10, 1000L, false);
            check(near.ok && !near.needSecondTap, "near origin rejected: " + near.message);
            // 过期定位 → 拒绝
            RouteDraft.Result stale = RouteDraft.check(ORIGIN, ROUTE, 30.0001, 120.0001, 10,
                    RouteDraft.MAX_FIX_AGE_MS + 1, false);
            check(!stale.ok, "stale fix accepted");
            // 起点过远 → 第一次要求二次确认，第二次允许
            RouteDraft.Result far = RouteDraft.check(ORIGIN, ROUTE, 31.0, 121.0, 10, 1000L, false);
            check(!far.ok && far.needSecondTap, "far origin accepted without confirmation");
            RouteDraft.Result far2 = RouteDraft.check(ORIGIN, ROUTE, 31.0, 121.0, 10, 1000L, true);
            check(far2.ok && far2.needSecondTap, "second tap on far origin rejected");
            check(Math.abs(RouteDraft.toleranceM(10) - 50.0) < 0.001, "tolerance floor wrong");
            check(Math.abs(RouteDraft.toleranceM(40) - 120.0) < 0.001, "tolerance scale wrong");

        } else {
            throw new IllegalArgumentException(which);
        }
        System.out.println("PASS " + which);
    }

    static Intent intent() { return new Intent(); }
    static Intent prepareIntent() {
        return new Intent().putExtra("mokr_op", "prepare")
                .putExtra("mokr_session", "s1").putExtra("mokr_cfg", CFG);
    }
}
"""

CASES = [
    # 无路线也能启用；PREPARED 不推进/不计步
    "prepared_without_route_no_motion",
    # PREPARED 放行真实定位（注册、单次、系统回调）
    "prepared_passes_real_location_through",
    # 有旧路线时启用不跳旧起点
    "prepared_ignores_old_route",
    # 确认只启动一次（双击/重复广播/重复 Intent）
    "confirm_starts_once",
    "command_receiver_prepare_then_confirm",
    "intent_bridge_confirm_once",
    # 结束后迟到确认不能恢复
    "confirm_after_end_rejected",
    # 无效/空/非有限坐标与无效路线
    "confirm_invalid_route_rejected",
    # 预备很久再确认，不补算
    "prepared_no_catchup",
    # 跨场次步数基数单调
    "confirm_rebuilds_clock_and_base",
    # provider 轮询不覆盖会话命令、不把草稿变确认
    "provider_poll_keeps_prepared",
    # 会话标识校验
    "session_mismatch_rejected",
    # 草稿校验（形状/新鲜度/连续性容差）
    "route_draft_rules",
    # 定位按钮的请求状态机

]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    base = jvm_fakes.build(args.jdk, {"com/mokrun/app/xposed/PrepareFlowRegression.java": HARNESS},
                           PRODUCTION,
                           overrides={"android/content/Context.java": RECEIVER_CONTEXT})
    java = str(args.jdk / "bin" / "java.exe")
    output = jvm_fakes.ROOT / "build" / "prepare-flow-results.txt"

    results, failures = [], 0
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base),
                              "com.mokrun.app.xposed.PrepareFlowRegression", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            reason = next((line.strip() for line in (run.stdout + run.stderr).splitlines()
                           if "AssertionError" in line or "Exception" in line), "see log")
            print(f"FAIL {case}: {reason}")
        else:
            print(f"PASS {case}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s) out of {len(CASES) if not args.case else 1}. "
          f"Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
