"""确认被拒绝后，同页重试必须能再次下发（实机 1.0.152 缺陷回归）。

编译**真实 DraftActivity**（RouteDraft 也是真实的），只替换 Android UI 与
LocationHelper/Store/ConfigSync/AckReceiver 这几个边界：

1. 第一次确认 → 拿到 90 s 旧定位 → 被 30 s 新鲜度拦下（不下发）；
2. 同一页面再点确认 → 这次给新鲜定位 → **必须下发一次 CONFIRM**；
3. 远起点（needSecondTap）被拒后，再点一次也要能下发。

Run: python tests/test_draft_confirm_retry.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
"""
import argparse
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

PRODUCTION = ["data.RouteDraft", "data.LocateTracker"]

# ---------------- 边界替身 ----------------
UI = {
    "android/app/Activity.java": """
package android.app;
public class Activity extends android.content.Context {
    public boolean finished;
    protected void onCreate(android.os.Bundle b) {}
    protected void onResume() {}
    protected void onDestroy() {}
    public void setContentView(android.view.View v) {}
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    public void startActivity(android.content.Intent i) {}
    public void finish() { finished = true; }
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver r,
                                                   android.content.IntentFilter f) { return null; }
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver r,
                                                   android.content.IntentFilter f, int flags) { return null; }
    public void unregisterReceiver(android.content.BroadcastReceiver r) {}
    public void runOnUiThread(Runnable r) { r.run(); }
}
""",
    "android/content/res/Resources.java": """
package android.content.res;
public class Resources {
    public android.util.DisplayMetrics getDisplayMetrics() { return new android.util.DisplayMetrics(); }
}
""",
    "android/util/DisplayMetrics.java": "package android.util; public class DisplayMetrics {}",
    "android/util/TypedValue.java": """
package android.util;
public class TypedValue {
    public static final int COMPLEX_UNIT_DIP = 1;
    public static float applyDimension(int u, float v, DisplayMetrics m) { return v; }
}
""",
    "android/view/Gravity.java": "package android.view; public class Gravity { public static final int START = 1; }",
    "android/graphics/Typeface.java": "package android.graphics; public class Typeface { public static final Typeface DEFAULT_BOLD = new Typeface(); }",
    "android/view/View.java": """
package android.view;
public class View {
    public interface OnClickListener { void onClick(View v); }
    public OnClickListener click;
    public boolean enabled = true;
    public void setOnClickListener(OnClickListener l) { click = l; }
    public void performClick() { if (enabled && click != null) click.onClick(this); }
    public void setPadding(int a, int b, int c, int d) {}
    public void setEnabled(boolean b) { enabled = b; }
    public boolean isEnabled() { return enabled; }
}
""",
    "android/widget/TextView.java": """
package android.widget;
public class TextView extends android.view.View {
    public String text = "";
    public TextView(android.content.Context c) {}
    public void setText(CharSequence s) { text = s.toString(); }
    public CharSequence getText() { return text; }
    public void setTextSize(float n) {}
    public void setGravity(int n) {}
    public void setTypeface(android.graphics.Typeface t) {}
}
""",
    "android/widget/Button.java": """
package android.widget;
public class Button extends TextView {
    public static final java.util.List<Button> all = new java.util.ArrayList<Button>();
    public Button(android.content.Context c) { super(c); all.add(this); }
}
""",
    "android/widget/LinearLayout.java": """
package android.widget;
public class LinearLayout extends android.view.View {
    public static final int VERTICAL = 1;
    public LinearLayout(android.content.Context c) {}
    public void setOrientation(int i) {}
    public void addView(android.view.View v) {}
}
""",
    "android/widget/ScrollView.java": """
package android.widget;
public class ScrollView extends android.view.View {
    public ScrollView(android.content.Context c) {}
    public void addView(android.view.View v) {}
}
""",
    "android/widget/Toast.java": """
package android.widget;
public class Toast {
    public static final int LENGTH_LONG = 1, LENGTH_SHORT = 0;
    public static java.util.List<String> shown = new java.util.ArrayList<String>();
    public static Toast makeText(android.content.Context c, CharSequence s, int d) {
        shown.add(s == null ? "" : s.toString());
        return new Toast();
    }
    public void show() {}
}
""",
    "android/os/ResultReceiver.java": """
package android.os;
public class ResultReceiver implements Parcelable {
    public ResultReceiver(Handler h) {}
    protected void onReceiveResult(int c, Bundle b) {}
    public void send(int c, Bundle b) { onReceiveResult(c, b); }
}
""",
}

APP_FAKES = {
    "com/mokrun/app/AckReceiver.java": """
package com.mokrun.app;
public class AckReceiver {
    public interface Listener { void onAck(android.content.Intent i); }
    public static void setListener(Listener l) {}
    public static void clearListener(Listener l) {}
}
""",
    "com/mokrun/app/OriginActivity.java": """
package com.mokrun.app;
public class OriginActivity { public static final String EXTRA_DRAFT = "draft"; }
""",
    "com/mokrun/app/MapActivity.java": """
package com.mokrun.app;
public class MapActivity { public static final String EXTRA_DRAFT = "draft"; }
""",
    "com/mokrun/app/data/Store.java": """
package com.mokrun.app.data;
public class Store {
    public static String origin = "30.0,120.0";
    public static String route = "[{\\"lat\\":30.001,\\"lng\\":120.0}]";
    public static String session = "s1";
    public static String draftOrigin(android.content.Context c) { return origin; }
    public static String draftWaypointsJson(android.content.Context c) { return route; }
    public static void clearDraft(android.content.Context c) { }
    public static String sessionId(android.content.Context c) { return session; }
    public static void setRunMode(android.content.Context c, int mode) { }
}
""",
    "com/mokrun/app/data/ConfigSync.java": """
package com.mokrun.app.data;
public class ConfigSync {
    public static final String ACK_ACTION = "ack";
    public static final int MODE_RUNNING = 1;
    public static int sent;
    public static int getMode(android.content.Context c) { return 3; }
    public static String modeName(int m) { return "prepared"; }
    public static boolean isTargetRunning(android.content.Context c) { return true; }
    public static boolean sendConfirm(android.content.Context c, String s, String t,
                                      String o, String r, android.os.Parcelable rr) { sent++; return true; }
    public static boolean launchConfirm(android.content.Context c, String s, String t,
                                        String o, String r, android.os.Parcelable rr) { sent++; return true; }
    public static boolean bringTargetToFront(android.content.Context c) { return true; }
}
""",
    # 可控的定位边界：脚本化回调，便于「先旧后新」
    "com/mokrun/app/data/LocationHelper.java": """
package com.mokrun.app.data;
public class LocationHelper {
    public interface FreshCallback {
        void onPreview(double lat, double lon, float accuracy, long ageMs);
        void onFresh(double lat, double lon, float accuracy, long ageMs, boolean fromCache);
        void onError(String message);
    }
    public static FreshCallback pending;
    public static int requests;
    public static boolean hasPermission(android.content.Context c) { return true; }
    public static boolean locationEnabled(android.content.Context c) { return true; }
    public static double[] cachedWithAge(android.content.Context c) { return null; }
    public static void cancelFresh(String reason) { pending = null; }
    public static void requestFresh(android.content.Context c, LocateTracker t, FreshCallback cb) {
        requestFresh(c, t, cb, false);
    }
    public static void requestFresh(android.content.Context c, LocateTracker t,
                                    FreshCallback cb, boolean preferPrecise) {
        requests++;
        pending = cb;
    }
    /** 测试投递 */
    public static void deliver(double lat, double lon, float acc, long age, boolean cache) {
        FreshCallback cb = pending;
        pending = null;
        if (cb != null) cb.onFresh(lat, lon, acc, age, cache);
    }
    public static void error(String msg) {
        FreshCallback cb = pending; pending = null;
        if (cb != null) cb.onError(msg);
    }
}
""",
}

HARNESS = r"""
package com.mokrun.app;

import android.widget.Button;
import android.widget.Toast;
import java.lang.reflect.Method;

public class DraftRetryRegression {

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    static DraftActivity newActivity() throws Exception {
        DraftActivity a = new DraftActivity();
        Method onCreate = android.app.Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class);
        onCreate.setAccessible(true);
        onCreate.invoke(a, new android.os.Bundle());
        return a;
    }

    static Button confirmButton() {
        for (Button b : Button.all) {
            if (b.text != null && (b.text.contains("确认路线并开始") || b.text.contains("再次点击"))) return b;
        }
        throw new AssertionError("confirm button not found");
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];

        if (which.equals("retry_after_stale_rejection")) {
            DraftActivity a = newActivity();
            Button btn = confirmButton();
            Button.all.clear();
            btn.performClick();
            check(com.mokrun.app.data.LocationHelper.requests == 1, "first confirm did not request a fix");
            // 90 s 旧定位 → 必须被 30 s 新鲜度拦下
            com.mokrun.app.data.LocationHelper.deliver(30.00005, 120.00005, 10f, 90000L, true);
            check(com.mokrun.app.data.ConfigSync.sent == 0, "stale fix was sent as confirm");
            // 同页再点一次 → 新鲜定位 → 必须下发
            btn.performClick();
            check(com.mokrun.app.data.LocationHelper.requests == 2, "retry did not request a new fix");
            com.mokrun.app.data.LocationHelper.deliver(30.00005, 120.00005, 10f, 500L, false);
            check(com.mokrun.app.data.ConfigSync.sent == 1, "retry after rejection did not send confirm");

        } else if (which.equals("retry_after_far_origin")) {
            DraftActivity a = newActivity();
            Button btn = confirmButton();
            Button.all.clear();
            btn.performClick();
            // 起点与当前位置相距很远 → needSecondTap
            com.mokrun.app.data.LocationHelper.deliver(30.0100, 120.0100, 10f, 500L, false);
            check(com.mokrun.app.data.ConfigSync.sent == 0, "far origin sent without second tap");
            check(btn.text.contains("再次点击"), "button text not updated for second tap: " + btn.text);
            btn.performClick();
            com.mokrun.app.data.LocationHelper.deliver(30.0100, 120.0100, 10f, 500L, false);
            check(com.mokrun.app.data.ConfigSync.sent == 1, "second tap on far origin did not send confirm");

        } else if (which.equals("draft_change_blocks_send")) {
            DraftActivity a = newActivity();
            Button btn = confirmButton();
            btn.performClick();
            com.mokrun.app.data.Store.route = "[{\"lat\":30.002,\"lng\":120.0}]";  // 等待期间改草稿
            com.mokrun.app.data.LocationHelper.deliver(30.00005, 120.00005, 10f, 500L, false);
            check(com.mokrun.app.data.ConfigSync.sent == 0, "changed draft still sent confirm");
            com.mokrun.app.data.Store.route = "[{\"lat\":30.001,\"lng\":120.0}]";
            btn.performClick();
            com.mokrun.app.data.LocationHelper.deliver(30.00005, 120.00005, 10f, 500L, false);
            check(com.mokrun.app.data.ConfigSync.sent == 1, "retry after draft change did not send confirm");

        } else {
            throw new IllegalArgumentException(which);
        }
        System.out.println("PASS " + which);
    }
}
"""

CASES = ["retry_after_stale_rejection", "retry_after_far_origin", "draft_change_blocks_send"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    overrides = dict(UI)
    overrides.update(APP_FAKES)
    overrides["com/mokrun/app/DraftActivity.java"] = (
        jvm_fakes.ROOT / "app/src/main/java/com/mokrun/app/DraftActivity.java"
    ).read_text(encoding="utf-8")
    # Context 需要 RECEIVER_EXPORTED；Intent 需要 (Context, Class) 构造
    overrides["android/content/Context.java"] = jvm_fakes.SOURCES["android/content/Context.java"].replace(
        "public class Context {", "public class Context { public static final int RECEIVER_EXPORTED = 2;")
    overrides["android/content/Intent.java"] = jvm_fakes.SOURCES["android/content/Intent.java"].replace(
        "public Intent() {}", "public Intent() {}\n    public Intent(android.content.Context c, Class<?> k) {}")

    base = jvm_fakes.build(args.jdk,
                           {"com/mokrun/app/DraftRetryRegression.java": HARNESS},
                           PRODUCTION, overrides=overrides)
    java = str(args.jdk / "bin" / "java.exe")
    output = jvm_fakes.ROOT / "build" / "draft-retry-results.txt"

    failures, results = 0, []
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base),
                              "com.mokrun.app.DraftRetryRegression", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            print(f"FAIL {case}")
        else:
            print(f"PASS {case}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s). Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
