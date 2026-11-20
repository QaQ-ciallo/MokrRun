"""IntentBridge 不得改写宿主 Intent 的 extras 类加载器（1.0.153 闪退回归）。

实机事故：模块在 Activity.onCreate/onNewIntent 里对宿主 Intent 调
setExtrasClassLoader(模块 InMemoryDexFile 的 classloader)，该 Intent 被应用复用后，
它启动 FaceLiveV3Activity 时读自己的 Parcelable（PointData）就用模块加载器反序列化，
ClassNotFoundException → BadParcelableException → 闪退。

断言（业务层面）：
1. 不含模块 key 的 Intent 完全不被触碰；
2. 含模块 key 的 Intent，其 extras 类加载器保持宿主默认（null），我们仍能读到自己的回执句柄；
3. 读完之后，宿主仍能用默认加载器读取自己的 Parcelable。

Run: python tests/test_intent_classloader.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
"""
import argparse
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

PRODUCTION = ["xposed.IntentBridge"]

FAKE_ENGINE = """
package com.mokrun.app.xposed;
public class MotionEngine {
    public static final String OP_PREPARE = "prepare";
    public static final String OP_CLEAR = "clear";
    public static final String OP_CONFIRM = "confirm";
    public static android.os.Parcelable ack;
    public static String preparedSession;
    public static boolean confirmCalled;
    public static void setAckReceiver(Object rr) { ack = (android.os.Parcelable) rr; }
    public static void prepareSession(String session, String cfg) { preparedSession = session; }
    public static String clearExplicit(String session) { return null; }
    public static String applyConfirm(String s, String t, String c, String o, String r) { confirmCalled = true; return null; }
    public static void applyFromIntent(String cfg, String origin, String route, int run) { }
}
"""

HARNESS = r"""
package com.mokrun.app.xposed;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import java.lang.reflect.Method;

public class ClassLoaderRegression {

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    /** 模拟模块交给目标进程的回执句柄（只是个 Parcelable） */
    static class OurHandle implements Parcelable { }

    /** 模拟应用自己的 Parcelable（例如 PointData） */
    static class AppPoint implements Parcelable {
        final double lat, lon;
        AppPoint(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    static void read(Intent it) throws Exception {
        Method m = IntentBridge.class.getDeclaredMethod("read", Intent.class, String.class);
        m.setAccessible(true);
        m.invoke(null, it, "regression");
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];
        IntentBridge.install(ClassLoaderRegression.class.getClassLoader());

        if (which.equals("foreign_intent_untouched")) {
            Intent it = new Intent();
            it.putExtra("point", new AppPoint(23.1, 113.9));
            it.putExtra("plain", "hello");
            read(it);
            check(it.getExtras().getClassLoader() == null,
                    "foreign intent extras classloader was modified");
            check(it.getExtras().getParcelable("point") instanceof AppPoint,
                    "foreign intent lost its own parcelable");

        } else if (which.equals("our_intent_keeps_host_loader")) {
            Parcelable ack = new OurHandle();
            Intent it = new Intent();
            it.putExtra("mokr_op", "prepare");
            it.putExtra("mokr_session", "s1");
            it.putExtra("mokr_rr", ack);
            read(it);
            check(MotionEngine.ack == ack, "engine did not receive the result receiver");
            check("s1".equals(MotionEngine.preparedSession), "prepare did not reach the engine");
            check(it.getExtras().getClassLoader() == null,
                    "host extras classloader was left pointing at the module loader");

        } else if (which.equals("host_reads_own_parcelable_after_our_read")) {
            Intent it = new Intent();
            it.putExtra("mokr_op", "prepare");
            it.putExtra("mokr_rr", new OurHandle());
            it.putExtra("point", new AppPoint(23.2, 113.8));
            read(it);
            check(it.getExtras().getClassLoader() == null, "loader leaked into host activity intent");
            check(it.getExtras().getParcelable("point") instanceof AppPoint,
                    "host could no longer read its own parcelable");

        } else {
            throw new IllegalArgumentException(which);
        }
        System.out.println("PASS " + which);
    }
}
"""

CASES = [
    "foreign_intent_untouched",
    "our_intent_keeps_host_loader",
    "host_reads_own_parcelable_after_our_read",
]


def compile_harness(jdk):
    """自己拼编译命令：边界替身 + 假引擎 + 真实 IntentBridge。"""
    import tempfile
    base = Path(tempfile.mkdtemp(prefix="icl-", dir=str(jvm_fakes.ROOT / "build")))
    sources = jvm_fakes.boundary_sources()
    sources["com/mokrun/app/xposed/ClassLoaderRegression.java"] = HARNESS
    sources["com/mokrun/app/xposed/MotionEngine.java"] = FAKE_ENGINE
    jvm_fakes._write_tree(base, sources)
    prod = jvm_fakes.ROOT / "app/src/main/java/com/mokrun/app/xposed/IntentBridge.java"
    javac = str(jdk / "bin" / "javac.exe")
    r = subprocess.run([javac, "--release", "8", "-nowarn", "-encoding", "UTF-8",
                        "-d", str(base), *[str(f) for f in base.rglob("*.java")], str(prod)],
                       capture_output=True, encoding="utf-8", errors="replace")
    if r.returncode:
        raise RuntimeError((r.stdout or "") + (r.stderr or ""))
    return base


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    base = compile_harness(args.jdk)

    java = str(args.jdk / "bin" / "java.exe")
    output = jvm_fakes.ROOT / "build" / "intent-classloader-results.txt"

    failures, results = 0, []
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base),
                              "com.mokrun.app.xposed.ClassLoaderRegression", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            reason = next((l.strip() for l in (run.stdout + run.stderr).splitlines()
                           if "AssertionError" in l or "Exception" in l), "see log")
            print(f"FAIL {case}: {reason}")
        else:
            print(f"PASS {case}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s). Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
