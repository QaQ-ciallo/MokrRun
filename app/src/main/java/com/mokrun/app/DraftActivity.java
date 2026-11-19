package com.mokrun.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.LocateTracker;
import com.mokrun.app.data.LocationHelper;
import com.mokrun.app.data.RouteDraft;
import com.mokrun.app.data.Store;

import java.util.Locale;
import java.util.UUID;

/**
 * 本次路线配置（草稿）界面。
 *
 * 这里做的所有编辑都只写草稿：
 *   - 修改起点 / 绘制路线 → 只改 prefs 里的 draft 键；
 *   - 目标应用的位置、里程、步数完全不动（引擎仍处于 PREPARED）；
 *   - 「确认路线并开始」才把完整配置**一次**下发，并等待目标进程回执。
 *
 * 取消 → 保留草稿/预备态，不累计、不跳位置。
 */
public class DraftActivity extends Activity {

    public static final String EXTRA_SESSION = "session";
    /** 回执等待时间：超过就明确告诉用户「没有确认」，不假装已运行 */
    private static final long ACK_TIMEOUT_MS = 9000L;

    private TextView tvOrigin;
    private TextView tvRoute;
    private TextView tvStatus;
    private Button btnConfirm;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver ackReceiver;
    private String pendingToken;
    private android.os.ResultReceiver confirmProbeReceiver;
    private final LocateTracker locateTracker = new LocateTracker();
    /** 确认请求代际：取消/销毁/重新发起都会 ++，旧回调据此失效 */
    private int confirmGen = 0;
    /** 页面销毁标记：替代 isFinishing()/isDestroyed()，夹具与真机行为一致 */
    private boolean destroyed = false;
    /** 确认时的草稿快照，下发前复核，避免「拿到的定位属于另一份草稿」 */
    private String snapshotOrigin = "";
    private String snapshotRoute = "";
    private boolean confirmInFlight;
    private boolean allowFarConfirmed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        registerAck();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        confirmGen++;
        confirmInFlight = false;
        LocationHelper.cancelFresh("destroy");
        handler.removeCallbacksAndMessages(null);
        AckReceiver.clearListener(null);
        if (ackReceiver != null) {
            try { unregisterReceiver(ackReceiver); } catch (Throwable ignored) {}
            ackReceiver = null;
        }
    }

    // ---- UI ---------------------------------------------------------------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("本次路线配置");
        title.setTextSize(20);
        title.setGravity(Gravity.START);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("编辑只写入草稿：目标应用仍显示真实位置，不会推进、不会计步。"
                + "确认后才一次下发并开始运行。");
        tip.setTextSize(12);
        tip.setPadding(0, dp(6), 0, dp(12));
        root.addView(tip);

        tvOrigin = new TextView(this);
        tvOrigin.setTextSize(14);
        root.addView(tvOrigin);

        tvRoute = new TextView(this);
        tvRoute.setTextSize(14);
        tvRoute.setPadding(0, dp(6), 0, dp(12));
        root.addView(tvRoute);

        btnConfirm = new Button(this);
        btnConfirm.setText("确认路线并开始");
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doConfirm(); }
        });
        root.addView(btnConfirm);

        Button btnOrigin = new Button(this);
        btnOrigin.setText("修改起点");
        btnOrigin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(DraftActivity.this, OriginActivity.class)
                        .putExtra(OriginActivity.EXTRA_DRAFT, true));
            }
        });
        root.addView(btnOrigin);

        Button btnRoute = new Button(this);
        btnRoute.setText("绘制路线");
        btnRoute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(DraftActivity.this, MapActivity.class)
                        .putExtra(MapActivity.EXTRA_DRAFT, true));
            }
        });
        root.addView(btnRoute);

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 取消：撤销定位请求、让迟到回调失效，再清草稿退出
                confirmGen++;
                confirmInFlight = false;
                LocationHelper.cancelFresh("cancel");
                Store.clearDraft(DraftActivity.this);
                finish();
            }
        });
        root.addView(btnCancel);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setPadding(0, dp(14), 0, 0);
        root.addView(tvStatus);
        return scroll;
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void setStatus(String text) {
        if (tvStatus != null) tvStatus.setText(text);
    }

    private void refresh() {
        String origin = Store.draftOrigin(this);
        String route = Store.draftWaypointsJson(this);
        int n = RouteDraft.waypointCount(route);
        tvOrigin.setText(origin == null || origin.length() == 0
                ? "起点：未设置" : "起点：" + origin);
        tvRoute.setText("途经点：" + (n < 0 ? "（数据异常）" : n + " 个")
                + "　" + ConfigSync.modeName(ConfigSync.getMode(this)));
        String shapeErr = RouteDraft.validateShape(origin, route);
        if (shapeErr != null) {
            setStatus(shapeErr);
        } else {
            // 草稿已经完整：清掉旧的提示，并恢复按钮文案
            allowFarConfirmed = false;
            if (btnConfirm != null) btnConfirm.setText("确认路线并开始");
            setStatus("");
        }
    }

    // ---- 确认 -------------------------------------------------------------

    private void doConfirm() {
        if (confirmInFlight) {
            setStatus("正在等待目标进程确认，请稍候…");
            return;
        }
        String origin = Store.draftOrigin(this);
        String route = Store.draftWaypointsJson(this);

        // 确认时必须**现取**一次定位：配置/画线往往要一两分钟，缓存会超过 30 s 新鲜度，
        // 实机就出现过「明明站着没动，确认却提示拿不到新鲜定位」。
        final String fOrigin = origin;
        final String fRoute = route;
        final int gen = ++confirmGen;
        snapshotOrigin = origin;
        snapshotRoute = route;
        confirmInFlight = true;
        setStatus("正在获取当前位置…");
        LocationHelper.requestFresh(this, locateTracker, new LocationHelper.FreshCallback() {
            @Override public void onPreview(double lat, double lon, float accuracy, long ageMs) { }

            @Override public void onFresh(double lat, double lon, float accuracy,
                                         long ageMs, boolean fromCache) {
                if (staleRequest(gen)) return;
                evaluateAndSend(fOrigin, fRoute, lat, lon, accuracy, ageMs, fromCache);
            }

            @Override public void onError(String message) {
                if (staleRequest(gen)) return;
                confirmInFlight = false;
                setStatus("拿不到当前位置：" + message + "；可返回修改起点或重试");
            }
        });
    }

    /**
     * 迟到回调的守门员：取消、页面销毁、会话切换、另一次确认请求开始，
     * 都会让这一次的定位结果失效（否则取消后仍会下发 CONFIRM）。
     */
    private boolean staleRequest(int gen) {
        if (gen != confirmGen) return true;        // 旧代际：新请求已接管，不动标志
        if (destroyed) { confirmInFlight = false; return true; }
        if (!confirmInFlight) return true;
        String session = Store.sessionId(this);
        if (session.length() == 0) {
            // 会话失效：释放进行中状态，避免页面卡在等待态无法重试
            confirmInFlight = false;
            setStatus("会话已失效：请回到首页重新「启用并打开体适能」。");
            return true;
        }
        if (!snapshotOrigin.equals(Store.draftOrigin(this))
                || !snapshotRoute.equals(Store.draftWaypointsJson(this))) {
            confirmInFlight = false;
            setStatus("草稿在等待定位时发生了变化，已取消本次下发，请重新确认。");
            return true;
        }
        return false;
    }

    private void evaluateAndSend(String origin, String route, double curLat, double curLon,
                                 double acc, long age, boolean fromCache) {
        RouteDraft.Result r = RouteDraft.check(origin, route, curLat, curLon, acc, age,
                allowFarConfirmed);
        if (fromCache && r.ok) {
            setStatus("使用 " + Math.max(0L, age / 1000) + " s 前的定位（精度 "
                    + Math.round(acc) + " m）确认");
        }
        if (!r.ok) {
            // 校验失败必须释放「进行中」状态：否则页面会停在等待态，
            // 之后即使拿到新鲜定位、再点确认也不会下发（实机 1.0.152 复现）。
            confirmInFlight = false;
            pendingToken = null;
            if (btnConfirm != null) btnConfirm.setEnabled(true);
            // 第一次遇到「起点离得很远」时，允许用户再点一次明确确认；
            // 绝不自作主张修改起点，也不生成过渡轨迹。
            if (r.needSecondTap) allowFarConfirmed = true;
            setStatus(r.message);
            if (btnConfirm != null) {
                btnConfirm.setText(r.needSecondTap ? "再次点击：仍使用该起点" : "确认路线并开始");
            }
            return;
        }

        String session = Store.sessionId(this);
        if (session.length() == 0) {
            setStatus("会话已失效：请回到首页重新「启用并打开体适能」。");
            confirmInFlight = false;
            return;
        }
        // 下发前再确认一次草稿没有被改动（定位等待期间用户可能又编辑了）
        if (!snapshotOrigin.equals(origin) || !snapshotRoute.equals(route)) {
            confirmInFlight = false;
            setStatus("草稿在等待定位时发生了变化，已取消本次下发，请重新确认。");
            return;
        }
        final String token = UUID.randomUUID().toString();
        pendingToken = token;
        confirmInFlight = true;
        btnConfirm.setEnabled(false);
        setStatus("已下发配置，等待目标进程确认…");

        confirmProbeReceiver = new android.os.ResultReceiver(
                new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode, android.os.Bundle resultData) {
                if (resultData == null) return;
                android.util.Log.i("MokrRunAckProbe", "confirm result op="
                        + resultData.getString("op") + " token="
                        + resultData.getString("token") + " ok="
                        + resultData.getBoolean("ok", false));
                Intent it = new Intent(ConfigSync.ACK_ACTION)
                        .putExtra("op", resultData.getString("op"))
                        .putExtra("session", resultData.getString("session"))
                        .putExtra("token", resultData.getString("token"))
                        .putExtra("ok", resultData.getBoolean("ok", false))
                        .putExtra("reason", resultData.getString("reason"));
                handleAck(it);
            }
        };
        boolean sent = ConfigSync.isTargetRunning(this)
                ? ConfigSync.sendConfirm(this, session, token, origin, route, confirmProbeReceiver)
                : ConfigSync.launchConfirm(this, session, token, origin, route, confirmProbeReceiver);
        if (!sent) {
            failConfirm("下发失败：无法把配置送到体适能");
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (confirmInFlight && token.equals(pendingToken)) {
                    failConfirm("目标进程没有回执（" + (ACK_TIMEOUT_MS / 1000)
                            + " s 超时）：请确认模块作用域与目标进程是否存活后重试");
                }
            }
        }, ACK_TIMEOUT_MS);
    }

    private void failConfirm(String message) {
        confirmInFlight = false;
        pendingToken = null;
        if (btnConfirm != null) btnConfirm.setEnabled(true);
        setStatus(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void registerAck() {
        AckReceiver.setListener(new AckReceiver.Listener() {
            @Override public void onAck(Intent intent) { handleAck(intent); }
        });
        ackReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                handleAck(intent);
            }
        };
        try {
            IntentFilter f = new IntentFilter(ConfigSync.ACK_ACTION);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(ackReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(ackReceiver, f);
            }
        } catch (Throwable t) {
            setStatus("注册回执接收失败：" + t);
        }
    }

    private void handleAck(Intent intent) {
                if (intent == null) return;
                String op = intent.getStringExtra("op");
                if (op != null && !"confirm".equals(op)) return;   // 忽略 PREPARE/CLEAR 回执
                String token = intent.getStringExtra("token");
                String session = intent.getStringExtra("session");
                boolean ok = intent.getBooleanExtra("ok", false);
                if (pendingToken == null || token == null || !pendingToken.equals(token)) return;
                if (!ok) {
                    failConfirm("目标进程拒绝了本次确认："
                            + intent.getStringExtra("reason"));
                    return;
                }
                if (!session.equals(Store.sessionId(DraftActivity.this))) {
                    failConfirm("回执属于其它会话，已忽略");
                    return;
                }
                confirmInFlight = false;
                pendingToken = null;
                Store.clearDraft(DraftActivity.this);
                Store.setRunMode(DraftActivity.this, ConfigSync.MODE_RUNNING);
                boolean nav = ConfigSync.bringTargetToFront(DraftActivity.this);
                if (nav) {
                    setStatus("已开始运行");
                    Toast.makeText(DraftActivity.this, "已开始运行", Toast.LENGTH_SHORT).show();
                } else {
                    // 状态已切到运行，但没能切回目标应用：两个结果都如实反馈
                    setStatus("已开始运行，但打开体适能失败：请手动切回体适能");
                    Toast.makeText(DraftActivity.this,
                            "已开始运行，但打开体适能失败：请手动切回", Toast.LENGTH_LONG).show();
                }
        finish();
    }
}
