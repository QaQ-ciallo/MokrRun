package com.mokrun.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.mokrun.app.data.ConfigSync;
import com.mokrun.app.data.Store;

import java.util.Locale;

/** 设置页：参数（速度联动步频/步长）、开发者入口、版本信息 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(new InsetsListener(false));

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.rowSpeed).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askNumber("目标速度 (m/s)", Store.speed(SettingsActivity.this), true);
            }
        });
        findViewById(R.id.rowCadence).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askNumber("步频上限 (步/秒)", Store.cadence(SettingsActivity.this), false);
            }
        });
        findViewById(R.id.rowStride).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askNumber("步长 (m)", Store.stride(SettingsActivity.this), false);
            }
        });
        findViewById(R.id.rowAnti).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Store.setAnti(SettingsActivity.this, !Store.anti(SettingsActivity.this));
                pushAndRefresh();
            }
        });
        findViewById(R.id.cardDeveloper).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, DeveloperActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * @param derive true 时按速度联动算出步频/步长
     */
    private void askNumber(String title, float current, final boolean derive) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.2f", current));
        input.setSelectAllOnFocus(true);
        input.selectAll();

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        float v;
                        try {
                            v = Float.parseFloat(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            Toast.makeText(SettingsActivity.this, "请输入数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (v <= 0f) {
                            Toast.makeText(SettingsActivity.this, "必须大于 0", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (derive) {
                            Store.setSpeedWithDerived(SettingsActivity.this, v);
                        } else if (title.startsWith("步频")) {
                            Store.setCadence(SettingsActivity.this, v);
                        } else {
                            Store.setStride(SettingsActivity.this, v);
                        }
                        pushAndRefresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pushAndRefresh() {
        new Thread(new Runnable() {
            @Override public void run() {
                ConfigSync.push(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        }).start();
    }

    private void refresh() {
        ((TextView) findViewById(R.id.valSpeed)).setText(
                String.format(Locale.US, "%.1f m/s", Store.speed(this)));
        ((TextView) findViewById(R.id.valCadence)).setText(
                String.format(Locale.US, "%.2f 步/秒", Store.cadence(this)));
        ((TextView) findViewById(R.id.valStride)).setText(
                String.format(Locale.US, "%.2f m", Store.stride(this)));
        ((TextView) findViewById(R.id.valAnti)).setText(
                Store.anti(this) ? "全部开启" : "已关闭");
        ((TextView) findViewById(R.id.versionText)).setText(
                "v" + BuildConfigTag.VERSION + " (" + BuildConfigTag.CODE + ") · " + BuildConfigTag.BUILD);
    }
}
