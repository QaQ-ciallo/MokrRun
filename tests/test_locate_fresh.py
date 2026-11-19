"""定位请求的取消/代际与新鲜度回归（提交前审查两条边界）。

吸收自仓库外脚本 `MokrRun-150取消确认独立验收.py` / `MokrRun-150定位新鲜度独立验收.py`
的业务断言，用仓库夹具执行真实生产代码（LocationHelper / LocateTracker / RouteDraft）：

1. 取消后迟到的定位回调不得再回来（否则取消的草稿仍会下发 CONFIRM）；
2. 新请求开始后，上一请求的监听器回调必须被丢弃；
3. 超时用 90 s 缓存兜底时，必须回传真实年龄且标记 fromCache；
4. RouteDraft 仍以 30 s 为新鲜度门槛：90 s 的定位不能被当成新点通过。

Run: python tests/test_locate_fresh.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
"""
import argparse
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

PRODUCTION = ["data.LocationHelper", "data.LocateTracker", "data.RouteDraft"]

# 真实 LocationHelper 需要的 Android 边界替身
CTX = """
package android.content;
import android.location.LocationManager;
public class Context {
    public static final String LOCATION_SERVICE = "location";
    public static final int RECEIVER_EXPORTED = 2;
    public static LocationManager lm = new LocationManager();
    public final ContentResolver resolver = new ContentResolver();
    public Context getApplicationContext() { return this; }
    public ContentResolver getContentResolver() { return resolver; }
    public Object getSystemService(String name) { return lm; }
    public int checkSelfPermission(String p) { return 0; }
    public java.util.concurrent.Executor getMainExecutor() {
        return new java.util.concurrent.Executor() { public void execute(Runnable r) { r.run(); } };
    }
    public void sendBroadcast(Intent i) { }
}
"""

LM = """
package android.location;
import java.util.*;
public class LocationManager {
    public static final String GPS_PROVIDER = "gps";
    public static final String NETWORK_PROVIDER = "network";
    public static final String PASSIVE_PROVIDER = "passive";
    public boolean gpsEnabled = true, networkEnabled = true;
    public List<LocationListener> listeners = new ArrayList<LocationListener>();
    public Location lastKnown = null;
    public int removed = 0;
    public boolean isProviderEnabled(String p) {
        return GPS_PROVIDER.equals(p) ? gpsEnabled : networkEnabled;
    }
    public void requestLocationUpdates(String p, long minTime, float minDist,
                                       LocationListener l, android.os.Looper looper) {
        listeners.add(l);
    }
    public Location getLastKnownLocation(String p) { return lastKnown; }
    public void requestSingleUpdate(String p, LocationListener l, android.os.Looper looper) {
        listeners.add(l);
    }
    public void getCurrentLocation(String p, android.os.CancellationSignal s,
                                   java.util.concurrent.Executor e,
                                   java.util.function.Consumer<Location> c) { }
    public void removeUpdates(LocationListener l) { removed++; listeners.remove(l); }
    public void removeUpdates(android.app.PendingIntent p) { removed++; }
    /** 投递一个点（模拟系统回调） */
    public void deliver(Location loc) {
        for (LocationListener l : new ArrayList<LocationListener>(listeners)) {
            l.onLocationChanged(loc);
        }
    }
}
"""

PM = "package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED = 0; }"
MANIFEST = "package android; public class Manifest { public static class permission { public static final String ACCESS_FINE_LOCATION = \"f\"; public static final String ACCESS_COARSE_LOCATION = \"c\"; } }"

HARNESS = r"""
package com.mokrun.app.data;

import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;

public class LocateFreshRegression {

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    static class Recorder implements LocationHelper.FreshCallback {
        int fresh, error, preview;
        double lat, lon;
        float acc;
        long age;
        boolean fromCache;
        String message;
        public void onPreview(double la, double lo, float a, long ageMs) { preview++; }
        public void onFresh(double la, double lo, float a, long ageMs, boolean cache) {
            fresh++; lat = la; lon = lo; acc = a; age = ageMs; fromCache = cache;
        }
        public void onError(String m) { error++; message = m; }
    }

    static Location fix(double lat, double lon, float acc, long ageMs) {
        Location l = new Location("gps");
        l.setLatitude(lat); l.setLongitude(lon); l.setAccuracy(acc);
        l.setTime(System.currentTimeMillis() - ageMs);
        return l;
    }

    static android.content.Context ctx() {
        android.content.Context ctx = new android.content.Context();
        ctx.lm = new LocationManager();
        Handler.resetClock();
        return ctx;
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];
        Handler.resetClock();

        if (which.equals("cancel_drops_late_callback")) {
            android.content.Context c = ctx();
            LocateTracker t = new LocateTracker();
            Recorder r = new Recorder();
            LocationHelper.requestFresh(c, t, r, false);
            check(c.lm.listeners.size() > 0, "no listener registered");
            LocationHelper.cancelFresh("cancel");
            c.lm.deliver(fix(23.06, 113.97, 5f, 0L));
            check(r.fresh == 0, "late callback after cancel delivered a fix");
            check(r.error == 0, "late callback after cancel reported error");
            check(c.lm.removed > 0, "cancelFresh did not remove updates");

        } else if (which.equals("new_request_rejects_previous_listener")) {
            android.content.Context c = ctx();
            LocateTracker t1 = new LocateTracker();
            Recorder r1 = new Recorder();
            LocationHelper.requestFresh(c, t1, r1, false);
            LocationListenerStub first = new LocationListenerStub(c.lm.listeners.get(0));
            LocateTracker t2 = new LocateTracker();
            Recorder r2 = new Recorder();
            LocationHelper.requestFresh(c, t2, r2, false);
            // 旧请求的监听器收到点：必须被代际检查丢弃
            first.listener.onLocationChanged(fix(23.06, 113.97, 5f, 0L));
            check(r1.fresh == 0, "previous request still delivered after a new request started");
            // 新请求正常拿到点
            c.lm.listeners.get(c.lm.listeners.size() - 1).onLocationChanged(fix(23.07, 113.98, 5f, 0L));
            check(r2.fresh == 1, "current request did not receive the fix");

        } else if (which.equals("cached_fallback_reports_real_age")) {
            android.content.Context c = ctx();
            LocateTracker t = new LocateTracker();
            Recorder r = new Recorder();
            // 90 秒前的缓存点，没有新点
            c.lm.lastKnown = fix(23.06, 113.97, 30f, 90000L);
            LocationHelper.requestFresh(c, t, r, false);
            // 推进虚拟时钟过 12 s 超时
            new Handler(android.os.Looper.getMainLooper()).advance(LocateTracker.TIMEOUT_MS + 1);
            check(r.fresh == 1, "timeout did not deliver the cached fallback");
            check(r.fromCache, "cached fallback not flagged as cache");
            check(r.age >= 85000L && r.age <= 95000L, "cached fallback age not truthful: " + r.age);

        } else if (which.equals("route_draft_rejects_stale_freshness")) {
            String origin = "23.0674,113.9752";
            String route = "[{\"lat\":23.0675,\"lng\":113.9753}]";
            // 位置就在起点旁边，但年龄 90 s：必须被 30 s 门槛拒绝
            RouteDraft.Result stale = RouteDraft.check(origin, route, 23.06745, 113.97525, 10, 90000L, false);
            check(!stale.ok, "90s-old fix accepted as fresh");
            check(stale.message.contains("新鲜定位"), "stale rejection message unclear: " + stale.message);
            // 同一点、年龄 1 s：通过
            RouteDraft.Result fresh = RouteDraft.check(origin, route, 23.06745, 113.97525, 10, 1000L, false);
            check(fresh.ok, "fresh fix rejected: " + fresh.message);

        } else {
            throw new IllegalArgumentException(which);
        }
        System.out.println("PASS " + which);
    }

    static class LocationListenerStub {
        final android.location.LocationListener listener;
        LocationListenerStub(android.location.LocationListener l) { listener = l; }
    }
}
"""

CASES = [
    "cancel_drops_late_callback",
    "new_request_rejects_previous_listener",
    "cached_fallback_reports_real_age",
    "route_draft_rejects_stale_freshness",
]


def check_source_contracts():
    """Activity 侧交接与 IntentBridge 顺序的静态契约。"""
    root = jvm_fakes.ROOT
    draft = (root / "app/src/main/java/com/mokrun/app/DraftActivity.java").read_text(encoding="utf-8")
    bridge = (root / "app/src/main/java/com/mokrun/app/xposed/IntentBridge.java").read_text(encoding="utf-8")
    problems = []
    if "long ageMs, boolean fromCache" not in draft:
        problems.append("DraftActivity 未接收定位年龄/来源")
    if "evaluateAndSend(fOrigin, fRoute, lat, lon, accuracy, ageMs, fromCache)" not in draft:
        problems.append("DraftActivity 未把真实年龄传给校验")
    if "LocationHelper.cancelFresh(" not in draft:
        problems.append("DraftActivity 未撤销定位请求")
    if "confirmGen" not in draft or "staleRequest(" not in draft:
        problems.append("DraftActivity 缺少请求代际/守门检查")
    first_read = bridge.find("getStringExtra")
    classloader = bridge.find("setExtrasClassLoader")
    if classloader < 0 or (first_read >= 0 and classloader > first_read):
        problems.append("IntentBridge 的 ClassLoader 未前置到读取 extras 之前")
    if problems:
        raise AssertionError("; ".join(problems))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    base = jvm_fakes.build(args.jdk,
                           {"com/mokrun/app/data/LocateFreshRegression.java": HARNESS},
                           PRODUCTION,
                           overrides={
                               "android/content/Context.java": CTX,
                               "android/location/LocationManager.java": LM,
                               "android/content/pm/PackageManager.java": PM,
                               "android/Manifest.java": MANIFEST,
                           })
    java = str(args.jdk / "bin" / "java.exe")
    output = jvm_fakes.ROOT / "build" / "locate-fresh-results.txt"

    failures, results = 0, []
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base),
                              "com.mokrun.app.data.LocateFreshRegression", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            reason = next((l.strip() for l in (run.stdout + run.stderr).splitlines()
                           if "AssertionError" in l or "Exception" in l), "see log")
            print(f"FAIL {case}: {reason}")
        else:
            print(f"PASS {case}")

    try:
        check_source_contracts()
        results.append("=== source_contracts (exit 0) ===\nPASS source_contracts")
        print("PASS source_contracts")
    except AssertionError as e:
        failures += 1
        results.append(f"=== source_contracts (exit 1) ===\nFAIL {e}")
        print(f"FAIL source_contracts: {e}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s). Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
