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
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.LocationHelper;
import com.mokrun.app.data.Store;

import java.util.Locale;

/**
 * Floating control panel: the only thing the user touches while the target app
 * is in the foreground. Drives the hook through Settings.Global flags.
 */
public class FloatService extends Service {

    public static final String RUN_KEY = "mokr_run";
    private static final String CHANNEL = "mokr_float";
    private static final int NOTIF_ID = 2001;

    private WindowManager wm;
    private View panel;
    private TextView tvDistance;
    private TextView tvSteps;
    private TextView tvState;
    private TextView tvSpeed;
    private LinearLayout btnToggle;

    private boolean running = false;
    private long startedAt = 0L;
    private long accumulated = 0L;
    private float speed = 3.0f;
    private float cadence = 2.2f;

    private final Handler handler = new Handler(Looper.getMainLooper());
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
        speed = Store.speed(this);
        cadence = Store.cadence(this);
        startForeground(NOTIF_ID, buildNotification());
        showPanel();
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
        return b.setContentTitle("随心跑运行中")
                .setContentText("悬浮窗可控制模拟")
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
        int p = dp(14);
        root.setPadding(p, dp(10), p, dp(10));

        tvState = new TextView(this);
        tvState.setText("待启动");
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvState.setTextColor(0xFF9AA4B2);
        root.addView(tvState);

        tvDistance = new TextView(this);
        tvDistance.setText("0 m");
        tvDistance.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvDistance.setTextColor(Color.WHITE);
        root.addView(tvDistance);

        tvSteps = new TextView(this);
        tvSteps.setText("0 步");
        tvSteps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSteps.setTextColor(0xFF7FB2FF);
        root.addView(tvSteps);

        // 当前速度数值
        tvSpeed = new TextView(this);
        tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSpeed.setTextColor(0xFF7FB2FF);
        tvSpeed.setText(String.format(Locale.US, "%.1f m/s", speed));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(4);
        tvSpeed.setLayoutParams(tvLp);
        root.addView(tvSpeed);

        // 速度控制条：拖动即改速度，步频同步跟着变
        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(Math.round((Store.MAX_SPEED - Store.MIN_SPEED) * 10f));
        speedBar.setProgress(Math.round((speed - Store.MIN_SPEED) * 10f));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        sbLp.topMargin = dp(2);
        speedBar.setLayoutParams(sbLp);
        speedBar.setOnSeekBarChangeListener(new SpeedBarListener());
        root.addView(speedBar);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        rowLp.topMargin = dp(8);
        btnRow.setLayoutParams(rowLp);

        btnToggle = new LinearLayout(this);
        btnToggle.setGravity(Gravity.CENTER);
        btnToggle.setBackgroundColor(0xFF3482FF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        blp.rightMargin = dp(6);
        btnToggle.setLayoutParams(blp);
        TextView btnText = new TextView(this);
        btnText.setText("开始");
        btnText.setTextColor(Color.WHITE);
        btnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnToggle.addView(btnText);
        btnToggle.setOnClickListener(new ToggleClick(btnText));
        btnRow.addView(btnToggle);

        LinearLayout btnStop = new LinearLayout(this);
        btnStop.setGravity(Gravity.CENTER);
        btnStop.setBackgroundColor(0xFF44474F);
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(
                dp(46), LinearLayout.LayoutParams.MATCH_PARENT));
        TextView stopText = new TextView(this);
        stopText.setText("■");
        stopText.setTextColor(Color.WHITE);
        stopText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnStop.addView(stopText);
        btnStop.setOnClickListener(new StopClick());
        btnRow.addView(btnStop);

        root.addView(btnRow);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(132), WindowManager.LayoutParams.WRAP_CONTENT,
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
    }


    /** 拖动速度条：速度、步频、步长一起变，并节流下发 */
    class SpeedBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (!fromUser) return;
            float v = Store.MIN_SPEED + progress / 10f;
            Store.setSpeedWithDerived(FloatService.this, v);
            speed = Store.speed(FloatService.this);
            cadence = Store.cadence(FloatService.this);
            schedulePush();
            refresh();
        }

        @Override public void onStartTrackingTouch(SeekBar bar) {}

        @Override public void onStopTrackingTouch(SeekBar bar) {
            ConfigSync.push(FloatService.this);
        }
    }

    private long sLastPush = 0L;

    private void schedulePush() {
        long now = System.currentTimeMillis();
        if (now - sLastPush > 300L) {
            sLastPush = now;
            ConfigSync.push(this);
        }
    }

    class ToggleClick implements View.OnClickListener {
        private final TextView label;
        ToggleClick(TextView l) { this.label = l; }

        @Override public void onClick(View v) {
            running = !running;
            if (running) {
                startedAt = System.currentTimeMillis();
                Store.setRunMode(FloatService.this, 1);
                // 关键：把配置随 Intent 送进体适能进程（零权限通道）
                launchTargetWithConfig();
                label.setText("暂停");
                btnToggle.setBackgroundColor(0xFFB54708);
            } else {
                accumulated += System.currentTimeMillis() - startedAt;
                // 2 = paused; the fake position stays frozen where it is
                Store.setRunMode(FloatService.this, 2);
                label.setText("继续");
                btnToggle.setBackgroundColor(0xFF3482FF);
            }
            refresh();
        }
    }


    /** 把途经点 JSON 里的坐标批量反解成 WGS-84 */
    private String toWgsWaypoints(String json) {
        if (json == null || json.length() < 5) return "";
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject p = arr.getJSONObject(i);
                double[] w = LocationHelper.gcj2wgs(p.getDouble("lat"), p.getDouble("lng"));
                org.json.JSONObject q = new org.json.JSONObject();
                q.put("lat", w[0]);
                q.put("lng", w[1]);
                out.put(q);
            }
            return out.toString();
        } catch (Throwable t) {
            return json;
        }
    }

    /** 带配置拉起体适能：SINGLE_TOP + REORDER_TO_FRONT，已在前台时只走 onNewIntent */
    private void launchTargetWithConfig() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.bxkj.student");
            if (i == null) {
                Toast.makeText(this, "没找到体适能，请确认已安装", Toast.LENGTH_SHORT).show();
                return;
            }
            i.putExtra("mokr_cfg", ConfigSync.buildLine(this));

            // 起点由 GCJ-02 反解回 WGS-84：体适能会自己再转一次，
            // 传 GCJ-02 过去会导致双重转换、位置偏出几百米
            double[] o = Store.origin(this);
            if (o != null) {
                double[] w = LocationHelper.gcj2wgs(o[0], o[1]);
                i.putExtra("mokr_origin", w[0] + "," + w[1]);
            } else {
                i.putExtra("mokr_origin", "");
            }

            // 途经点同样反解
            i.putExtra("mokr_route", toWgsWaypoints(ConfigSync.waypointsJson(this)));
            i.putExtra("mokr_run", running ? 1 : 2);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "拉起体适能失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** reset the session and close the panel */
    class StopClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            running = false;
            startedAt = 0L;
            accumulated = 0L;
            try {
                Store.setRunMode(FloatService.this, 0);
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

    private void refresh() {
        long total = accumulated + (running ? System.currentTimeMillis() - startedAt : 0L);
        double seconds = total / 1000.0;
        double meters = speed * seconds;
        long steps = Math.round(cadence * seconds);
        if (tvDistance != null) {
            tvDistance.setText(meters >= 1000
                    ? String.format(Locale.US, "%.2f km", meters / 1000.0)
                    : String.format(Locale.US, "%.0f m", meters));
        }
        if (tvSteps != null) {
            tvSteps.setText(steps + " 步 · " + fmtTime(seconds));
        }
        if (tvState != null) {
            tvState.setText(running ? "运行中" : "已暂停");
        }
        if (tvSpeed != null) {
            tvSpeed.setText(String.format(Locale.US, "%.1f m/s", speed));
        }
    }

    private String fmtTime(double sec) {
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        if (panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) {}
        }
        panel = null;
        try {
            Settings.Global.putString(getContentResolver(), RUN_KEY, "0");
        } catch (Throwable ignored) {}
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /**
     * 统一入口：先确认悬浮窗权限。
     * 之前没权限时会启动一个立刻 stopSelf 的服务，用户只看到"悬浮窗没出来"，无从排查。
     */
    public static void start(Context ctx) {
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "需要「显示在其他应用上层」权限",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
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
