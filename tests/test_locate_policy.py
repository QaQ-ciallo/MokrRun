"""首页「定位当前位置」的策略与静态契约回归（主题一）。

Run: python tests/test_locate_policy.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9

两部分：
  1) LocateTracker：单飞（重复点击忽略）、超时必结束、缓存新鲜度；
  2) home.html 的静态契约：路线起点/当前位置/视野分离，定位按钮不修改路线。

home.html 是 WebView 资源，JVM 里没有 JS 引擎；这里只做**结构契约**断言
（关键函数与变量存在且相互独立），行为层面的验证需要在实机上做，
已列入交付文档的「待实机验证清单」。
"""
import argparse
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

ROOT = jvm_fakes.ROOT
PRODUCTION = ["data.LocateTracker"]

HARNESS = r"""
package com.mokrun.app.xposed;

import com.mokrun.app.data.LocateTracker;

public class LocatePolicyRegression {

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    public static void main(String[] args) throws Exception {
        String which = args[0];
        if (which.equals("locate_tracker_rules")) {
            LocateTracker t = new LocateTracker();
            check(t.state() == LocateTracker.State.IDLE, "fresh tracker must start IDLE");
            check(t.begin(1000L), "first begin rejected");
            check(t.state() == LocateTracker.State.REQUESTING, "begin must enter REQUESTING");
            check(!t.begin(1100L), "duplicate click must be ignored");
            check(t.requestCount() == 1, "duplicate click changed request count");
            check(!t.checkTimeout(1000L + LocateTracker.TIMEOUT_MS), "timeout reported too early");
            check(t.checkTimeout(1000L + LocateTracker.TIMEOUT_MS + 1), "timeout not reported");
            check(t.state() == LocateTracker.State.FAILED, "timeout must end the request");
            check(t.lastError().length() > 0, "timeout must carry a message");
            check(t.begin(20000L), "begin after failure rejected");
            t.succeed();
            check(t.state() == LocateTracker.State.DONE, "success must end the request");
            check(t.requestCount() == 2, "request count wrong after retry");
            check(LocateTracker.cacheUsable(0), "fresh cache rejected");
            check(LocateTracker.cacheUsable(LocateTracker.PREVIEW_MAX_AGE_MS),
                    "cache at the edge rejected");
            check(!LocateTracker.cacheUsable(LocateTracker.PREVIEW_MAX_AGE_MS + 1),
                    "stale cache accepted for preview");
            t.reset();
            check(t.state() == LocateTracker.State.IDLE, "reset must return to IDLE");
        } else {
            throw new IllegalArgumentException(which);
        }
        System.out.println("PASS " + which);
    }
}
"""

CASES = ["locate_tracker_rules"]


def check_home_html():
    """定位按钮与路线起点必须分开：静态契约。"""
    html = (ROOT / "app/src/main/assets/home.html").read_text(encoding="utf-8")
    checks = {
        "分开保存路线起点": "var routeOrigin" in html,
        "分开保存当前位置": "var currentLocation" in html,
        "定位入口调用原生": "Native.locateCurrent" in html,
        "定位成功后居中当前位置": "map.setView([lat, lon], 17)" in html,
        "数据刷新受 center 控制": "if (center)" in html,
        "不存在旧的 anchor 共用": "var anchor" not in html,
    }
    problems = [name for name, ok in checks.items() if not ok]
    if problems:
        raise AssertionError("home.html 契约不满足: " + ", ".join(problems))
    # 定位回调不得写入 routeOrigin
    gua = html.find("window.onLocateResult")
    if gua < 0:
        raise AssertionError("home.html 缺少 onLocateResult 回调")
    tail = html[gua:]
    if "routeOrigin =" in tail:
        raise AssertionError("onLocateResult 不得修改 routeOrigin")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    base = jvm_fakes.build(args.jdk,
                           {"com/mokrun/app/xposed/LocatePolicyRegression.java": HARNESS},
                           PRODUCTION)
    java = str(args.jdk / "bin" / "java.exe")
    output = ROOT / "build" / "locate-policy-results.txt"

    failures, results = 0, []
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base),
                              "com.mokrun.app.xposed.LocatePolicyRegression", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            print(f"FAIL {case}")
        else:
            print(f"PASS {case}")

    try:
        check_home_html()
        results.append("=== home_html_contract (exit 0) ===\nPASS home_html_contract")
        print("PASS home_html_contract")
    except AssertionError as e:
        failures += 1
        results.append(f"=== home_html_contract (exit 1) ===\nFAIL {e}")
        print(f"FAIL home_html_contract: {e}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} failure(s). Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
