package com.mokrun.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.LocationHelper;
import com.mokrun.app.data.Store;

import java.util.Locale;

/**
 * 拖动地图、中心准星即起点。确定后立刻生效：
 * 体适能进程读到的位置就冻结在这个点（mokr_run=2）。
 */
public class OriginActivity extends Activity {

    private WebView web;
    private TextView coordText;
    private double curLat = 0, curLon = 0;
    private LocationManager lm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_origin);

        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(new InsetsListener(false));
        coordText = (TextView) findViewById(R.id.originCoord);
        web = (WebView) findViewById(R.id.originMap);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript("requestCenter()", null);
            }
        });
        web.addJavascriptInterface(new OriginBridge(), "Native");
        web.loadUrl("file:///android_asset/origin.html");

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btnConfirm).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirm(); }
        });

        findViewById(R.id.btnUseCurrent).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useCurrentLocation(); }
        });

        // 默认把地图移到上次的起点（若有）
        double[] o = Store.origin(this);
        if (o != null) {
            curLat = o[0];
            curLon = o[1];
            moveTo(o[0], o[1]);
            showCoord(o[0], o[1]);
        }
    }

    class OriginBridge {
        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(new Runnable() {
                @Override public void run() { useCurrentLocation(); }
            });
        }

        @JavascriptInterface
        public void onCenter(double lat, double lon) {
            curLat = lat;
            curLon = lon;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    showCoord(curLat, curLon);
                }
            });
        }
    }

    private void showCoord(double lat, double lon) {
        coordText.setText(String.format(Locale.US,
                "起点 %.6f, %.6f\n拖动地图把准星对准要作为起点的地方", lat, lon));
    }

    private void moveTo(final double lat, final double lon) {
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript(
                        String.format(Locale.US, "setCenter(%.7f,%.7f)", lat, lon), null);
            }
        });
    }

    private void useCurrentLocation() {
        if (!LocationHelper.hasPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9);
            Toast.makeText(this, "请允许定位权限后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在获取位置…", Toast.LENGTH_SHORT).show();
        LocationHelper.requestOnce(this, new LocationHelper.Callback() {
            @Override public void onLocation(double lat, double lon) {
                moveTo(lat, lon);
                Toast.makeText(OriginActivity.this, "已定位到当前位置", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirm() {
        if (curLat == 0 && curLon == 0) {
            Toast.makeText(this, "请先在地图上选一个点", Toast.LENGTH_SHORT).show();
            return;
        }
        Store.setOrigin(this, curLat, curLon);
        ConfigSync.push(this);
        // 冻结在这个起点，让体适能立刻看到
        ConfigSync.setMode(this, 2);
        Toast.makeText(this, "起点已确定", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == 9) useCurrentLocation();
    }
}
