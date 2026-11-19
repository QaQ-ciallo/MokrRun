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
              "data.RouteDraft", "data.LocateTracker", "data.EnableFlow"]

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

        } else if (which.equals("confirm_initializes_position_and_segment")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            android.location.Location cur = MotionEngine.current();
            check(Math.abs(cur.getLatitude() - 30.0) < 1e-9,
                    "confirm 后当前位置未初始化：lat=" + cur.getLatitude());
            check(Math.abs(cur.getLongitude() - 120.0) < 1e-9,
                    "confirm 后当前位置未初始化：lon=" + cur.getLongitude());
            check(i("sSeg") == 0, "confirm 后段索引未重置：" + i("sSeg"));
            tickAfter(250L);
            check(d("sDistTotal") < 3.0, "首个 tick 出现跳跃：" + d("sDistTotal"));

        } else if (which.equals("short_route_after_long_no_overflow")) {
            String longRoute = "[{\"lat\":30.001,\"lng\":120.0},{\"lat\":30.002,\"lng\":120.0},"
                    + "{\"lat\":30.003,\"lng\":120.0},{\"lat\":30.004,\"lng\":120.0}]";
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, longRoute) == null, "long confirm failed");
            for (int k = 0; k < 200; k++) tickAfter(250L);
            check(i("sSeg") > 0, "long route never advanced past seg 0");
            MotionEngine.endSession();
            MotionEngine.prepareSession("s2", CFG);
            check(MotionEngine.applyConfirm("s2", "t2", CFG, ORIGIN,
                    "[{\"lat\":30.0005,\"lng\":120.0}]") == null, "short confirm failed");
            for (int k = 0; k < 8; k++) tickAfter(250L);   // 不允许抛 ArrayIndexOutOfBounds
            check(d("sDistTotal") > 5.0, "short route did not advance: " + d("sDistTotal"));
            check(MotionEngine.steps() > 0, "short route produced no steps");

        } else if (which.equals("provider_does_not_replace_confirmed_route")) {
            Context ctx = new Context();
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), ORIGIN, ROUTE, Integer.valueOf(1));
            MotionEngine.configure(ctx);
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            tickAfter(1000L);
            double dist = d("sDistTotal");
            // prefs 里换成另一条路线（模拟「保存过别的路线」或旧配置）
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), "31.0,121.0",
                    "[{\"lat\":31.01,\"lng\":121.0},{\"lat\":31.02,\"lng\":121.0}]",
                    Integer.valueOf(1));
            set("sRunCheckedAt", Long.valueOf(0L));
            tickAfter(250L);
            check(d("sDistTotal") >= dist, "provider 轮询把进度清零了");
            check(Math.abs(d("sDistTotal") - dist) < 5.0, "provider 轮询换了路线导致位置跳变");
            android.location.Location cur = MotionEngine.current();
            check(Math.abs(cur.getLatitude() - 31.0) > 0.5, "路线被 prefs 里的旧路线替换了");

        } else if (which.equals("pause_command_does_not_replace_route")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            tickAfter(1000L);
            double dist = d("sDistTotal");
            int seg = i("sSeg");
            // 悬浮窗暂停：历史实现会带上 prefs 里的起点/路线
            MotionEngine.applyFromIntent(CFG, "31.0,121.0",
                    "[{\"lat\":31.01,\"lng\":121.0},{\"lat\":31.02,\"lng\":121.0}]", 2);
            check(readMode() == 2, "pause 未生效");
            check(Math.abs(d("sDistTotal") - dist) < 1e-9, "pause 重置了里程");
            check(i("sSeg") == seg, "pause 换了路线段");
            MotionEngine.setMode(1);
            tickAfter(250L);
            check(Math.abs(MotionEngine.current().getLatitude() - 31.0) > 0.5,
                    "pause 把路线换成了 prefs 里的旧路线");

        } else if (which.equals("second_confirm_same_session_rejected")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            for (int k = 0; k < 3; k++) tickAfter(250L);
            long steps = MotionEngine.steps();
            check(steps > 0, "没有步数可用于判断");
            String err = MotionEngine.applyConfirm("s1", "t2", CFG, ORIGIN, ROUTE);
            check(err != null, "换 token 后同一会话被允许重复启动");
            check(MotionEngine.steps() == steps, "重复确认把步数清零了：" + MotionEngine.steps());
            check(readMode() == 1, "重复确认改变了模式");

        } else if (which.equals("stale_freeze_timeout_ignored")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            MotionEngine.freezeForFace();
            check(readMode() == 2, "冻结未生效");
            MotionEngine.endSession();
            // 旧超时到点时会调用 resumeAfterFace：不得把状态改回 RUNNING
            MotionEngine.resumeAfterFace();
            check(readMode() == 0, "会话结束后旧冻结恢复把状态改了：" + readMode());
            check(!MotionEngine.shouldOverridePosition(), "旧冻结恢复重新接管了位置");

        } else if (which.equals("rejected_confirm_sends_failure_ack")) {
            Context ctx = new Context();
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), ORIGIN, ROUTE, Integer.valueOf(3));
            MotionEngine.configure(ctx);
            MotionEngine.prepareSession("s1", CFG);
            Context.SentBroadcasts.clear();
            String err = MotionEngine.applyConfirm("s1", "t1", CFG, "abc", ROUTE);
            check(err != null, "非法路线未被拒绝");
            check(!Context.SentBroadcasts.isEmpty(), "拒绝确认没有发出任何回执");
            Intent last = Context.SentBroadcasts.get(Context.SentBroadcasts.size() - 1);
            check("com.mokrun.app.ACK".equals(last.getAction()), "回执 action 不对");
            check(!last.getBooleanExtra("ok", true), "回执没有标记失败");
            check(last.getStringExtra("reason") != null, "回执没有带拒绝原因");

        } else if (which.equals("draft_origin_uses_lon_field")) {
            org.json.JSONObject originObj = new org.json.JSONObject()
                    .put("lat", 30.1).put("lon", 120.2);
            org.json.JSONObject routeObj = new org.json.JSONObject().put("origin", originObj);
            check("30.1,120.2".equals(RouteDraft.originFromRouteJson(routeObj)),
                    "路线 JSON 的 lon 字段没有被正确读取");
            org.json.JSONObject legacy = new org.json.JSONObject()
                    .put("lat", 30.1).put("lng", 120.2);
            check("30.1,120.2".equals(RouteDraft.originFromRouteJson(
                    new org.json.JSONObject().put("origin", legacy))), "兼容 lng 字段失败");
            org.json.JSONObject bad = new org.json.JSONObject().put("lon", 120.2);
            check(RouteDraft.originFromRouteJson(new org.json.JSONObject().put("origin", bad)) == null,
                    "非法起点未被拒绝");
            check(RouteDraft.parseDraftOrigin("") == null, "空草稿起点未被拒绝");
        } else if (which.equals("first_confirm_survives_empty_provider")) {
            // 首次只画草稿：活动存储里没有路线（Provider 返回空 origin/route）
            Context ctx = new Context();
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), "", "", Integer.valueOf(3));
            MotionEngine.configure(ctx);
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            // 确认后 Provider 仍是空快照，且 run 变成 1
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), "", "", Integer.valueOf(1));
            set("sRunCheckedAt", Long.valueOf(0L));
            for (int k = 0; k < 8; k++) tickAfter(250L);
            check(MotionEngine.shouldOverridePosition(), "empty provider snapshot revoked confirmed draft");
            check(MotionEngine.shouldAdvanceMotion(), "engine stopped advancing after provider poll");
            check(MotionEngine.hasRoute(), "confirmed route was erased by empty snapshot");
            check(MotionEngine.steps() > 0, "no steps after provider poll");

        } else if (which.equals("explicit_clear_still_works")) {
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            // 旧会话的清除命令必须被拒绝
            check(MotionEngine.clearExplicit("other-session") != null, "stale clear accepted");
            check(MotionEngine.hasRoute(), "stale clear wiped the route");
            // 当前会话的显式清除必须生效
            check(MotionEngine.clearExplicit("s1") == null, "explicit clear rejected");
            check(!MotionEngine.hasRoute(), "explicit clear did not clear route");
            check(!MotionEngine.shouldOverridePosition(), "cleared route still overrides position");
            check(!MotionEngine.shouldAdvanceMotion(), "cleared route still advances");

        } else if (which.equals("stale_freeze_isolated_at_prepare")) {
            // OFF 时出现冻结（此时没有会话）
            MotionEngine.freezeForFace();
            check(readMode() == 2, "freeze in OFF did not enter PAUSED");
            MotionEngine.prepareSession("s1", CFG);
            check(readMode() == 3, "prepare did not enter PREPARED");
            // 旧超时到点（真实回调会在超时后调用 resumeAfterFace）
            MotionEngine.resumeAfterFace();
            check(readMode() == 3, "old freeze timeout changed new PREPARED session: " + readMode());
            // 新会话自己的冻结/恢复必须正常
            MotionEngine.freezeForFace();
            check(readMode() == 2, "own freeze in PREPARED failed");
            MotionEngine.resumeAfterFace();
            check(readMode() == 3, "own resume did not return to PREPARED: " + readMode());

        } else if (which.equals("legacy_pause_cannot_confirm_route")) {
            // 上一场留下路线
            MotionEngine.applyFromIntent(CFG, ORIGIN, ROUTE, 1);
            MotionEngine.endSession();
            MotionEngine.prepareSession("s1", CFG);
            // 真实接收器收到旧式「只带 cfg + mode=2」的暂停命令
            Intent pause = new Intent().putExtra("mode", Integer.valueOf(2))
                    .putExtra("cfg", CFG).putExtra("origin", ORIGIN).putExtra("route", ROUTE);
            new CommandReceiver.Receiver().onReceive(new Context(), pause);
            check(!MotionEngine.isRouteConfirmed(), "pause silently confirmed old route");
            check(!MotionEngine.shouldOverridePosition(), "unconfirmed session took over position");
            check(readMode() == 3, "pause changed PREPARED mode: " + readMode());

        } else if (which.equals("prepare_ack_matches_session")) {
            Context ctx = new Context();
            ctx.resolver.row = new Cursor(Float.valueOf(4.8f), Float.valueOf(3.12f),
                    Float.valueOf(1.538f), Integer.valueOf(1), "", "", Integer.valueOf(3));
            MotionEngine.configure(ctx);
            Context.SentBroadcasts.clear();
            MotionEngine.prepareSession("s-ack", CFG);
            check(!Context.SentBroadcasts.isEmpty(), "prepare sent no ack");
            Intent ack = Context.SentBroadcasts.get(Context.SentBroadcasts.size() - 1);
            check("com.mokrun.app.ACK".equals(ack.getAction()), "ack action wrong");
            check("prepare".equals(ack.getStringExtra("op")), "ack op must be prepare");
            check(ack.getBooleanExtra("ok", false), "prepare ack must report ok");
            check("s-ack".equals(ack.getStringExtra("session")), "ack session mismatch");
            check(ack.getIntExtra("mode", -1) == 3, "ack mode must be PREPARED");

        } else if (which.equals("enable_flow_three_outcomes")) {
            com.mokrun.app.data.EnableFlow f = new com.mokrun.app.data.EnableFlow();
            check(f.begin("s1", 1000L), "begin rejected");
            check(!f.begin("s1", 1100L), "duplicate click not ignored");
            check(!f.onAck("other", true, null, 1200L), "ack from other session accepted");
            check(f.phase() == com.mokrun.app.data.EnableFlow.Phase.SENT, "phase changed by foreign ack");
            check(f.onAck("s1", true, null, 1300L), "matching ack rejected");
            check(f.phase() == com.mokrun.app.data.EnableFlow.Phase.ACKED, "ack did not mark enabled");
            check(!f.checkTimeout(1300L + com.mokrun.app.data.EnableFlow.ACK_TIMEOUT_MS + 1),
                    "timeout fired after ack");
            // 导航失败是第二个结果，不回滚「已启用」
            f.recordNav(false, "打开体适能失败");
            check(f.phase() == com.mokrun.app.data.EnableFlow.Phase.ACKED, "nav failure rolled back enabled");
            check(!f.navOk(), "nav result lost");
            // 无回执 → 超时失败，不得宣告已启用
            com.mokrun.app.data.EnableFlow g = new com.mokrun.app.data.EnableFlow();
            g.begin("s2", 1000L);
            check(g.checkTimeout(1000L + com.mokrun.app.data.EnableFlow.ACK_TIMEOUT_MS + 1),
                    "timeout not detected");
            check(g.phase() == com.mokrun.app.data.EnableFlow.Phase.FAILED, "timeout did not fail");
            check(g.failure().length() > 0, "timeout carries no message");
        } else if (which.equals("float_pause_keeps_takeover")) {
            // 与当前 FloatService.sendCommand 完全一致：只带 cfg + mode，没有 origin/route
            MotionEngine.prepareSession("s1", CFG);
            check(MotionEngine.applyConfirm("s1", "t1", CFG, ORIGIN, ROUTE) == null, "confirm failed");
            for (int k = 0; k < 8; k++) tickAfter(250L);
            double dist = d("sDistTotal");
            long steps = MotionEngine.steps();
            double lat = MotionEngine.lat();
            double lon = MotionEngine.lon();
            Intent pause = new Intent().putExtra("mode", Integer.valueOf(2)).putExtra("cfg", CFG);
            new CommandReceiver.Receiver().onReceive(new Context(), pause);
            check(readMode() == 2, "pause did not enter PAUSED: " + readMode());
            check(MotionEngine.isRouteConfirmed(), "ordinary float pause revoked confirmation");
            check(MotionEngine.shouldOverridePosition(), "pause released real GPS");
            check(Math.abs(d("sDistTotal") - dist) < 1e-9, "pause reset distance");
            check(MotionEngine.steps() == steps, "pause reset steps");
            check(Math.abs(MotionEngine.lat() - lat) < 1e-9
                    && Math.abs(MotionEngine.lon() - lon) < 1e-9, "paused position moved");
            // 继续：只带 cfg + mode=1，沿用已确认路线
            Intent resume = new Intent().putExtra("mode", Integer.valueOf(1)).putExtra("cfg", CFG);
            new CommandReceiver.Receiver().onReceive(new Context(), resume);
            check(readMode() == 1, "resume failed: " + readMode());
            check(MotionEngine.isRouteConfirmed(), "resume lost confirmation");
            tickAfter(250L);
            check(d("sDistTotal") > dist, "resume did not advance");
            // 结束：才交还真实定位
            new CommandReceiver.Receiver().onReceive(new Context(),
                    new Intent().putExtra("mode", Integer.valueOf(0)).putExtra("cfg", CFG));
            check(readMode() == 0, "end failed: " + readMode());
            check(!MotionEngine.shouldOverridePosition(), "end kept overriding position");

        } else if (which.equals("legacy_start_cannot_confirm_new_session")) {
            // 上场留下有效路线（旧协议、无会话）
            MotionEngine.applyFromIntent(CFG, ORIGIN, ROUTE, 1);
            check(MotionEngine.isRouteConfirmed(), "legacy start without session must still work");
            MotionEngine.endSession();
            // 新会话：只有 CONFIRM 能启动
            MotionEngine.prepareSession("s2", CFG);
            double lat = MotionEngine.lat();
            Intent legacyStart = new Intent().putExtra("mode", Integer.valueOf(1))
                    .putExtra("cfg", CFG);
            new CommandReceiver.Receiver().onReceive(new Context(), legacyStart);
            check(readMode() == 3, "legacy run=1 changed PREPARED mode: " + readMode());
            check(!MotionEngine.isRouteConfirmed(), "legacy run=1 confirmed new session");
            check(!MotionEngine.shouldOverridePosition(), "legacy run=1 took over position");
            tickAfter(250L);
            check(Math.abs(MotionEngine.lat() - lat) < 1e-9, "legacy run=1 moved the position");
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
    "confirm_initializes_position_and_segment",
    "short_route_after_long_no_overflow",
    "provider_does_not_replace_confirmed_route",
    "pause_command_does_not_replace_route",
    "second_confirm_same_session_rejected",
    "stale_freeze_timeout_ignored",
    "rejected_confirm_sends_failure_ack",
    "draft_origin_uses_lon_field",
    "first_confirm_survives_empty_provider",
    "explicit_clear_still_works",
    "stale_freeze_isolated_at_prepare",
    "legacy_pause_cannot_confirm_route",
    "prepare_ack_matches_session",
    "enable_flow_three_outcomes",
    "float_pause_keeps_takeover",
    "legacy_start_cannot_confirm_new_session",
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
            print(f"FAIL {case}: {reason.encode(chr(97)+chr(115)+chr(99)+chr(105)+chr(105), chr(114)+chr(101)+chr(112)+chr(108)+chr(97)+chr(99)+chr(101)).decode(chr(97)+chr(115)+chr(99)+chr(105)+chr(105))}")
        else:
            print(f"PASS {case}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s) out of {len(CASES) if not args.case else 1}. "
          f"Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
