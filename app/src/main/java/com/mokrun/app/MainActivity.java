package com.mokrun.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.LocateTracker;
import com.mokrun.app.data.LocationHelper;
import com.mokrun.app.data.RouteDraft;
import com.mokrun.app.data.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView homeMap;
    private View pageHome;
    private boolean mapCentered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 把实测得到的最佳参数（4.8 m/s / 3.1 步/秒 / 1.55 m）一次性写进 prefs，
        // 之后用户自己的改动不会被覆盖（靠 cfgVer 只迁移一次）。
        Store.migrateDefaults(this);

        FrameLayout content = (FrameLayout) findViewById(R.id.content);
        pageHome = getLayoutInflater().inflate(R.layout.page_home, content, false);
        content.addView(pageHome);

        content.setOnApplyWindowInsetsListener(new InsetsListener(false));

        pageHome.findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        pageHome.findViewById(R.id.btnOrigin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, OriginActivity.class));
            }
        });

        View btnEnable = pageHome.findViewById(R.id.btnEnable);
        if (btnEnable != null) {
            btnEnable.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { enableAndOpen(); }
            });
        }

        pageHome.findViewById(R.id.btnRoute).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Store.origin(MainActivity.this) == null) {
                    Toast.makeText(MainActivity.this, "请先确定起点", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(MainActivity.this, OriginActivity.class));
                    return;
                }
                startActivity(new Intent(MainActivity.this, MapActivity.class));
            }
        });

        setupHomeMap();
        KeepAliveService.start(this);
        ensureLocationPermission();
        ensureNotificationPermission();


    }

    /**
     * 「启用并打开体适能」：建立一次预备会话（不要求有路线），然后打开目标应用。
     * 目标进程在跑 → 显式广播；没在跑 → 拉起并由 onCreate 应用同一份配置。
     */
    private void enableAndOpen() {
        String session = Store.newSessionId();
        Store.setSessionId(this, session);
        ConfigSync.setMode(this, ConfigSync.MODE_PREPARED);
        boolean sent;
        if (ConfigSync.isTargetRunning(this)) {
            sent = ConfigSync.sendPrepare(this, session);
            // 已在前台时广播只更新状态，必须再显式把体适能切到前台，
            // 否则用户点完「启用」还停在模块首页。
            if (sent) ConfigSync.bringTargetToFront(this);
        } else {
            sent = ConfigSync.launchPrepared(this, session);
        }
        if (!sent) {
            Toast.makeText(this, "启用失败：无法把会话送到体适能", Toast.LENGTH_LONG).show();
            return;
        }
        FloatService.start(this);
        Toast.makeText(this, "已启用 · 目标应用显示真实位置，点悬浮窗「开始」配置路线",
                Toast.LENGTH_LONG).show();
        refreshAll();
    }

    /** 从历史路线建立**草稿**（不改变正在运行的位置，也不立即生效） */
    private void openDraftFromHistory(JSONObject r) {
        try {
            String draftOrigin = RouteDraft.originFromRouteJson(r);
            if (draftOrigin != null) Store.setDraftOrigin(this, draftOrigin);
            JSONArray pts = r.optJSONArray("points");
            if (pts != null) Store.setDraftWaypointsJson(this, pts.toString());
            Store.setDraftName(this, r.optString("name", ""));
            startActivity(new Intent(this, DraftActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this, "载入草稿失败：" + t, Toast.LENGTH_LONG).show();
        }
    }
    // ---- home map ----------------------------------------------------------

    private void setupHomeMap() {
        homeMap = (WebView) pageHome.findViewById(R.id.homeMap);
        WebSettings s = homeMap.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        homeMap.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                renderMap();
            }
        });
        homeMap.addJavascriptInterface(new HomeBridge(), "Native");
        homeMap.loadUrl("file:///android_asset/home.html");
    }

    /** 首次进入才居中；后续刷新只更新图层，不抢回用户正在拖动的视野 */
    private void renderMap() { renderMap(!mapCentered); }

    private void renderMap(boolean center) {
        if (homeMap == null) return;
        if (center) mapCentered = true;
        double[] o = Store.origin(this);
        double oLat = o == null ? 0.0 : o[0];
        double oLon = o == null ? 0.0 : o[1];
        String wpsJs = jsString(ConfigSync.waypointsJson(this));
        // 缓存只用于「先显示」，不宣布定位完成，也不修改路线
        double[] cache = LocationHelper.cachedWithAge(this);
        double curLat = 0.0, curLon = 0.0;
        if (cache != null && LocateTracker.cacheUsable((long) cache[3])) {
            curLat = cache[0];
            curLon = cache[1];
        }
        pushMapData(oLat, oLon, wpsJs, curLat, curLon, center);
    }

    private void pushMapData(final double oLat, final double oLon, final String wpsJs,
                             final double curLat, final double curLon, final boolean center) {
        if (homeMap == null) return;
        final String js = String.format(Locale.US, "setData(%.7f,%.7f,%s,%.7f,%.7f,%s)",
                oLat, oLon, wpsJs, curLat, curLon, center ? "true" : "false");
        homeMap.post(new Runnable() {
            @Override public void run() {
                homeMap.evaluateJavascript(js, null);
            }
        });
    }

    private void callJs(final String js) {
        if (homeMap == null) return;
        homeMap.post(new Runnable() {
            @Override public void run() {
                homeMap.evaluateJavascript(js, null);
            }
        });
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** JS 调原生：定位按钮 / 打开起点编辑 */
    class HomeBridge {
        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(new Runnable() {
                @Override public void run() { renderMap(false); }
            });
        }

        /** 「定位当前位置」：请求新鲜定位，只居中当前位置，不改路线起点 */
        @JavascriptInterface
        public void locateCurrent() {
            runOnUiThread(new Runnable() {
                @Override public void run() { doLocate(); }
            });
        }

        @JavascriptInterface
        public void openOrigin() {
            startActivity(new Intent(MainActivity.this, OriginActivity.class));
        }
    }

    private final LocateTracker locateTracker = new LocateTracker();

    private void doLocate() {
        LocationHelper.requestFresh(this, locateTracker, new LocationHelper.FreshCallback() {
            @Override public void onPreview(double lat, double lon, float accuracy, long ageMs) {
                callJs(String.format(Locale.US,
                        "onLocatePreview(%.7f,%.7f,%.1f,'%d 秒')",
                        lat, lon, accuracy, Math.max(0L, ageMs / 1000)));
            }

            @Override public void onFresh(double lat, double lon, float accuracy) {
                callJs(String.format(Locale.US,
                        "onLocateResult('%.7f','%.7f','%.1f','精度 %.0f m')",
                        lat, lon, accuracy, accuracy));
            }

            @Override public void onError(String message) {
                callJs("onLocateResult('','','','" + message.replace("'", " ") + "')");
            }
        });
    }



    /** 定位权限是运行时权限，装完必须主动要一次，否则地图定位按钮是死的 */
    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, 1001);
    }

    /**
     * Android 13+ 的前台服务通知需要这个权限。
     * 没有它 startForeground 在 MIUI 上会直接失败，悬浮窗根本起不来，
     * 表现就是"点了没反应"。
     */
    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 1001) {
            renderMap();   // 拿到定位权限后刷新一次
        }
        if (req == 1002) {
            if (res.length > 0 && res[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "建议允许通知权限，否则悬浮窗可能无法启动",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    // ---- refresh -----------------------------------------------------------

    private void refreshAll() {
        refreshStatus();
        refreshRouteInfo();
        refreshHistory();
        renderMap();
    }

    private void refreshStatus() {
        TextView st = (TextView) pageHome.findViewById(R.id.statusText);
        TextView hint = (TextView) pageHome.findViewById(R.id.statusHint);
        if (st == null || hint == null) return;

        int mode = ConfigSync.getMode(this);
        double[] origin = Store.origin(this);
        boolean hasRoute = Store.activeRoute(this) != null;

        if (mode == ConfigSync.MODE_PREPARED) {
            st.setText("已启用 · 待配置");
            st.setTextColor(getColor(R.color.primary));
            hint.setText("目标应用显示真实位置 · 点悬浮窗「开始」配置并确认路线");
        } else if (mode == ConfigSync.MODE_RUNNING) {
            st.setText("运行中");
            st.setTextColor(getColor(R.color.ok));
            hint.setText("位置与步数同步推进 · 悬浮窗可暂停/结束");
        } else if (mode == ConfigSync.MODE_PAUSED) {
            st.setText("已暂停");
            st.setTextColor(getColor(R.color.primary));
            hint.setText("位置冻结在暂停点 · 悬浮窗可继续或结束");
        } else {
            st.setText("未开始");
            st.setTextColor(getColor(R.color.text_primary));
            hint.setText(origin == null
                    ? "先点「修改起点」"
                    : "点「绘制路线」，完成后从悬浮窗开始");
        }
    }

    private void refreshRouteInfo() {
        TextView info = (TextView) pageHome.findViewById(R.id.routeInfo);
        if (info == null) return;
        JSONObject r = Store.activeRoute(this);
        if (r == null) {
            info.setText("尚未绘制路线");
            return;
        }
        double d = r.optDouble("distance", 0);
        JSONArray pts = r.optJSONArray("points");
        info.setText(String.format(Locale.US, "当前路线：%s · %d 个途经点",
                d >= 1000 ? String.format(Locale.US, "%.2f km", d / 1000.0)
                        : String.format(Locale.US, "%.0f m", d),
                pts == null ? 0 : pts.length()));
    }

    private void refreshHistory() {
        LinearLayout box = (LinearLayout) pageHome.findViewById(R.id.historyContainer);
        if (box == null) return;
        box.removeAllViews();

        List<JSONObject> list = Store.history(this);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有画过路线");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(dp(6), dp(4), dp(6), dp(4));
            box.addView(empty);
            return;
        }
        int active = Store.activeIndex(this);
        for (int i = 0; i < list.size(); i++) {
            box.addView(makeHistoryRow(list.get(i), i, i == active));
        }
    }

    private View makeHistoryRow(JSONObject r, final int index, boolean active) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setElevation(dp(1));
        int p = dp(14);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setText(active ? r.optString("name", "路线") + "  ·当前" : r.optString("name", "路线"));
        name.setTextColor(active ? getColor(R.color.primary) : getColor(R.color.text_primary));
        row.addView(name);

        TextView sub = new TextView(this);
        double d = r.optDouble("distance", 0);
        JSONArray pts = r.optJSONArray("points");
        sub.setText(String.format(Locale.US, "%s · %d 个途经点",
                d >= 1000 ? String.format(Locale.US, "%.2f km", d / 1000.0)
                        : String.format(Locale.US, "%.0f m", d),
                pts == null ? 0 : pts.length()));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(getColor(R.color.text_secondary));
        sub.setPadding(0, dp(3), 0, 0);
        row.addView(sub);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 选中历史路线 = 建立草稿，不立即改变目标应用的位置；
                // 真正生效要等配置界面的「确认路线并开始」。
                openDraftFromHistory(r);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除这条路线？")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                Store.removeHistory(MainActivity.this, index);
                                ConfigSync.push(MainActivity.this);
                                refreshAll();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
        });
        return row;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
