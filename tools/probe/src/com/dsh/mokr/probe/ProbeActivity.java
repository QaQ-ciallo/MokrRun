package com.dsh.mokr.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Test fixture: shows exactly what the module feeds into this process.
 * Not part of the shipped feature set.
 */
public class ProbeActivity extends Activity implements SensorEventListener, LocationListener {

    private TextView tvInject;
    private TextView tvLoc;
    private TextView tvStep;
    private TextView tvLog;

    private LocationManager lm;
    private SensorManager sm;
    private float steps = -1f;
    private int locCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProbeStatus.attach(this);
        setContentView(buildUi());

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION
        }, 1);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("随心跑 · 探针");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("验证模块注入与数据伪造是否生效");
        sub.setTextSize(12);
        sub.setAlpha(0.6f);
        root.addView(sub);

        tvInject = card(root, "注入状态", "未注入");
        tvLoc = card(root, "位置", "未开始");
        tvStep = card(root, "步数", "未注册");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        row.setLayoutParams(rlp);
        root.addView(row);

        row.addView(button("定位", new View.OnClickListener() {
            @Override public void onClick(View v) { startLocation(); }
        }));
        row.addView(button("计步", new View.OnClickListener() {
            @Override public void onClick(View v) { startSteps(); }
        }));
        row.addView(button("清日志", new View.OnClickListener() {
            @Override public void onClick(View v) {
                tvLog.setText("");
                locCount = 0;
            }
        }));

        TextView logTitle = new TextView(this);
        logTitle.setText("日志");
        logTitle.setTextSize(12);
        logTitle.setAlpha(0.6f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        logTitle.setLayoutParams(tlp);
        root.addView(logTitle);

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setText("");
        tvLog.setBackgroundColor(0x11000000);
        int lp = dp(10);
        tvLog.setPadding(lp, lp, lp, lp);
        root.addView(tvLog);

        return scroll;
    }

    private TextView card(LinearLayout parent, String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        box.setLayoutParams(lp);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackgroundColor(0x0F000000);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setAlpha(0.6f);
        box.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(15);
        v.setTextIsSelectable(true);
        box.addView(v);

        parent.addView(box);
        return v;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            appendLog("缺少定位权限");
            return;
        }
        appendLog("请求位置更新: gps + network");
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (Throwable t) {
            appendLog("请求失败: " + t);
        }
    }

    private void startSteps() {
        try {
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (s == null) {
                appendLog("设备没有 TYPE_STEP_COUNTER");
                return;
            }
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
            appendLog("已注册 step_counter");
        } catch (Throwable t) {
            appendLog("注册失败: " + t);
        }
    }

    // ---- sensor ----
    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER
                || e.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            steps = e.values[0];
            tvStep.setText(String.format(Locale.US, "%.0f 步 (type=%d)", steps, e.sensor.getType()));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ---- location ----
    @Override
    public void onLocationChanged(Location loc) {
        locCount++;
        String s = String.format(Locale.US,
                "lat=%.6f\nlon=%.6f\nspeed=%.2f m/s\nacc=%.1f m\nmock=%s\nprovider=%s\n#%d",
                loc.getLatitude(), loc.getLongitude(), loc.getSpeed(),
                loc.getAccuracy(), loc.isFromMockProvider(), loc.getProvider(), locCount);
        tvLoc.setText(s);
        if (locCount <= 3 || locCount % 10 == 0) {
            appendLog(String.format(Locale.US, "[loc#%d] %.6f,%.6f v=%.2f mock=%s",
                    locCount, loc.getLatitude(), loc.getLongitude(),
                    loc.getSpeed(), loc.isFromMockProvider()));
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    // ---- called by ProbeStatus (from the module) ----
    void onInjectedState(boolean injected) {
        tvInject.setText(injected ? "已注入 ✓" : "未注入");
        tvInject.setTextColor(injected ? 0xFF12B76A : 0xFFF79009);
        appendLog("模块注入状态: " + (injected ? "已注入" : "未注入"));
    }

    void appendLog(String line) {
        String old = tvLog.getText().toString();
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date());
        tvLog.setText(ts + "  " + line + "\n" + old);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProbeStatus.detach();
        try {
            lm.removeUpdates(this);
            sm.unregisterListener(this);
        } catch (Throwable ignored) {}
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
