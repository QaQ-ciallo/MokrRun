package com.mokrun.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.Store;

/**
 * 悬浮控制窗。
 *
 * 只承担两件事：显示模块当前处于什么阶段、提供开始/暂停/结束的入口。
 * 距离、步数、计时一律不显示——那些数字体适能自己会算、也以它为准，
 * 在这边再报一份只会互相矛盾。
 * 速度也只在模块的设置页定好，悬浮窗不再做实时调速。
 */
public class FloatService extends Service {

    private static final String CHANNEL = "mokr_float";
    private static final int NOTIF_ID = 2001;
    private static final String CMD_ACTION = "com.mokrun.app.CMD";

    private WindowManager wm;
    private View panel;
    private TextView tvState;
    private View dot;
    private LinearLayout btnToggle;
    private TextView btnToggleText;

    private boolean running = false;
    /** 是否已经把配置通过 Intent 送进目标进程 */
    private boolean configPushed = false;

    private final Handler handler = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            android.util.Log.e("MokrRunFloat", "startForeground failed", t);
        }
        try {
            showPanel();
        } catch (Throwable t) {
            android.util.Log.e("MokrRunFloat", "showPanel failed", t);
        }
        handler.post(ticker);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "随心跑",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("随心跑")
                .setContentText("悬浮窗控制已就绪")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void showPanel() {
        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.w("MokrRunFloat", "overlay permission lost, stopping");
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF21E1F24);
        int p = dp(12);
        root.setPadding(p, dp(9), p, dp(9));

        // ── 状态行：圆点 + 文字 ──────────────────────────────
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        dot = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFF3482FF);
        dot.setBackground(g);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(7);
        statusRow.addView(dot, dotLp);

        tvState = new TextView(this);
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvState.setTextColor(0xFF3482FF);
        statusRow.addView(tvState);

        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(statusRow, srLp);

        // ── 按钮行 ──────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        rowLp.topMargin = dp(9);
        btnRow.setLayoutParams(rowLp);

        btnToggle = new LinearLayout(this);
        btnToggle.setGravity(Gravity.CENTER);
        btnToggle.setBackgroundColor(0xFF3482FF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        blp.rightMargin = dp(6);
        btnToggle.setLayoutParams(blp);
        btnToggleText = new TextView(this);
        btnToggleText.setText("开始");
        btnToggleText.setTextColor(Color.WHITE);
        btnToggleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnToggle.addView(btnToggleText);
        btnToggle.setOnClickListener(new ToggleClick());
        btnRow.addView(btnToggle);

        LinearLayout btnStop = new LinearLayout(this);
        btnStop.setGravity(Gravity.CENTER);
        btnStop.setBackgroundColor(0xFF44474F);
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(
                dp(42), LinearLayout.LayoutParams.MATCH_PARENT));
        TextView stopText = new TextView(this);
        stopText.setText("■");
        stopText.setTextColor(Color.WHITE);
        stopText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnStop.addView(stopText);
        btnStop.setOnClickListener(new StopClick());
        btnRow.addView(btnStop);

        root.addView(btnRow);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(126), WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16);
        lp.y = dp(160);

        root.setOnTouchListener(new DragTouch(lp));
        panel = root;
        wm.addView(panel, lp);
        refresh();
    }

    /** 只刷新状态：颜色 + 文字 */
    private void refresh() {
        if (tvState == null) return;
        final String text;
        final int color;
        if (running) {
            text = "运行中";
            color = 0xFF34C759;              // 绿
        } else if (configPushed) {
            text = "已暂停";
            color = 0xFFFF9F0A;              // 橙
        } else {
            text = "就绪 · 待开始";
            color = 0xFF3482FF;              // 蓝
        }
        tvState.setText(text);
        tvState.setTextColor(color);
        if (dot != null) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(color);
            dot.setBackground(g);
        }
        if (btnToggleText != null) {
            btnToggleText.setText(running ? "暂停" : (configPushed ? "继续" : "开始"));
        }
        if (btnToggle != null) {
            btnToggle.setBackgroundColor(running ? 0xFFB54708 : 0xFF3482FF);
        }
    }

    class ToggleClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            running = !running;
            if (running) {
                Store.setRunMode(FloatService.this, 1);
                // 第一次开始要把配置送进目标进程；之后的继续只发命令
                if (!configPushed) {
                    launchTargetWithConfig();
                    configPushed = true;
                } else {
                    sendCommand(1);
                }
            } else {
                Store.setRunMode(FloatService.this, 2);
                sendCommand(2);
            }
            refresh();
        }
    }

    /** 通过显式广播改运行状态，不切前台 */
    private void sendCommand(int mode) {
        try {
            Intent i = new Intent(CMD_ACTION);
            i.setPackage("com.bxkj.student");
            i.putExtra("mode", mode);
            sendBroadcast(i);
            android.util.Log.i("MokrRunFloat", "command sent mode=" + mode);
        } catch (Throwable t) {
            android.util.Log.e("MokrRunFloat", "command failed", t);
        }
    }

    /** 带配置拉起目标：单顶 + 提到前台，已在前台时只走 onNewIntent */
    private void launchTargetWithConfig() {
        try {
            // 不用 getLaunchIntentForPackage：HMA 之类的隐藏模块会把它挡掉，返回 null。
            // 直接点名组件，绕过包管理器的可见性过滤。
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName("com.bxkj.student", "com.bxkj.student.splash.SplashActivity");
            i.putExtra("mokr_cfg", ConfigSync.buildLine(this));

            // 直接下发 GCJ-02：目标应用的地图就是高德底图，拿到坐标不做转换。
            double[] o = Store.origin(this);
            i.putExtra("mokr_origin", o == null ? "" : (o[0] + "," + o[1]));
            i.putExtra("mokr_route", ConfigSync.waypointsJson(this));
            i.putExtra("mokr_run", 1);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
            android.util.Log.i("MokrRunFloat", "launch issued");
        } catch (Throwable t) {
            Toast.makeText(this, "拉起目标失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 结束：交还真实定位并关闭面板 */
    class StopClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            running = false;
            try {
                Store.setRunMode(FloatService.this, 0);
                sendCommand(0);
            } catch (Throwable ignored) {}
            stopSelf();
        }
    }

    class DragTouch implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private int startX, startY;
        private float touchX, touchY;

        DragTouch(WindowManager.LayoutParams lp) { this.lp = lp; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + (int) (e.getRawX() - touchX);
                    lp.y = startY + (int) (e.getRawY() - touchY);
                    if (panel != null && wm != null) {
                        wm.updateViewLayout(panel, lp);
                    }
                    return true;
            }
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        if (panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) {}
        }
        panel = null;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 统一入口：先确认悬浮窗权限，没有就直接引导授权 */
    public static void start(Context ctx) {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "需要「显示在其他应用上层」权限",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(new Intent(ctx, FloatService.class));
            } else {
                ctx.startService(new Intent(ctx, FloatService.class));
            }
        } catch (Throwable t) {
            Toast.makeText(ctx, "启动悬浮窗失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, FloatService.class));
    }
}
