package com.mokrun.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 全屏画线页。起点固定为首页选定的起点，点的其它点都是途经点。
 * 保存后：写入历史 + 下发 + 拉起悬浮窗，但状态停在「就绪」，不自动跑。
 */
public class MapActivity extends Activity {

    private WebView web;
    private double originLat = 0, originLon = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        double[] o = Store.origin(this);
        if (o != null) {
            originLat = o[0];
            originLon = o[1];
        }

        web = (WebView) findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (originLat != 0 && originLon != 0) {
                    view.evaluateJavascript(String.format(Locale.US,
                            "setOrigin(%.7f,%.7f)", originLat, originLon), null);
                }
            }
        });
        web.addJavascriptInterface(new MapBridge(this), "Native");
        web.loadUrl("file:///android_asset/map.html");

        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(new InsetsListener(true));
    }

    /** 由 JS 回调 */
    public void onRouteJson(String json) {
        try {
            JSONObject src = new JSONObject(json);
            JSONObject origin = src.optJSONObject("origin");
            JSONArray pts = src.optJSONArray("points");
            if (origin == null || pts == null || pts.length() < 2) {
                Toast.makeText(this, "至少需要 2 个途经点", Toast.LENGTH_SHORT).show();
                return;
            }

            JSONObject route = new JSONObject();
            int count = Store.history(this).size() + 1;
            route.put("name", "路线 " + count);
            route.put("origin", origin);
            route.put("points", pts);
            route.put("distance", src.optInt("distance", 0));
            route.put("createdAt", System.currentTimeMillis());

            Store.addHistory(this, route);
            Store.syncOriginFromActive(this);
            ConfigSync.push(this);
            // 就绪但不开始：位置冻结在起点，等用户点悬浮窗的「开始」
            ConfigSync.setMode(this, 2);
            FloatService.start(this);

            Toast.makeText(this,
                    "路线已保存 · 点悬浮窗「开始」后才开始跑", Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    public void onStats(int points, double meters) {
        // 由页面自己的 HUD 显示，这里留空
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
