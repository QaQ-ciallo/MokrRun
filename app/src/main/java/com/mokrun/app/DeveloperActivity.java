package com.mokrun.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

public class DeveloperActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer);

        ((TextView) findViewById(R.id.devBuild)).setText(buildInfo());
        ((TextView) findViewById(R.id.devDevice)).setText(deviceInfo());

        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(new InsetsListener(false));

        findViewById(R.id.toolLogCmd).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy("adb logcat -v time -s MokrRun:* XposedBridge:* | grep -Ei 'MokrRun|mokr'");
                toast("命令已复制");
            }
        });

        findViewById(R.id.toolExport).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy(routesJson());
                toast("路线 JSON 已复制");
            }
        });

        findViewById(R.id.toolResetParams).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Store.setSpeed(DeveloperActivity.this, 3.0f);
                Store.setCadence(DeveloperActivity.this, 2.2f);
                Store.setStride(DeveloperActivity.this, 1.1f);
                Store.setAnti(DeveloperActivity.this, true);
                toast("参数已重置");
            }
        });

        findViewById(R.id.toolSync).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String err = ConfigSync.push(DeveloperActivity.this);
                String back = ConfigSync.readBack(DeveloperActivity.this);
                toast(err == null ? "已下发，回读：" + back : "失败：" + err + "（缺 WRITE_SECURE_SETTINGS）");
            }
        });

        findViewById(R.id.toolClearRoutes).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(DeveloperActivity.this)
                        .setTitle("清空所有路线？")
                        .setMessage("该操作不可撤销")
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                Store.clearHistory(DeveloperActivity.this);
                                toast("已清空");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        findViewById(android.R.id.content).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private String buildInfo() {
        return "版本    v" + BuildConfigTag.VERSION + " (" + BuildConfigTag.CODE + ")\n"
                + "构建    " + BuildConfigTag.BUILD + "\n"
                + "包名    " + getPackageName() + "\n"
                + "dexd8   r8 9.5.29";
    }

    private String deviceInfo() {
        String rom = Build.MANUFACTURER + " " + Build.MODEL;
        return "厂商    " + Build.MANUFACTURER + "\n"
                + "型号    " + Build.MODEL + "\n"
                + "系统    Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "ROM     " + rom + " / " + Build.DISPLAY + "\n"
                + "ABI     " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "-") + "\n"
                + "作用域  " + targetScope();
    }

    private String targetScope() {
        return "com.bxkj.student（需在 LSPosed 中勾选）";
    }

    private String routesJson() {
        List<JSONObject> list = Store.history(this);
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        try {
            return arr.toString(2);
        } catch (Exception e) {
            return arr.toString();
        }
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("mokr", text));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
