package com.mokrun.app.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * 只读配置桥。
 *
 * 之前这条路失败的原因是 MIUI 拦「后台拉起 App 进程」，query 时 provider 还没启动
 * （日志：No provider info ... when providerRunning is false）。
 * 现在配一个常驻前台服务让进程一直活着，provider 就能被体适能进程读到，
 * 全程不需要任何特殊权限。
 */
public class ConfigProvider extends ContentProvider {

    private static final String TAG = "MokrRunCfg";
    public static final String AUTHORITY = "com.mokrun.app.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");

    @Override
    public boolean onCreate() {
        Log.i(TAG, "provider onCreate");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{
                "speed", "cadence", "stride", "anti", "origin", "route", "run"
        });
        Context ctx = getContext();
        if (ctx == null) return c;
        try {
            c.addRow(new Object[]{
                    Float.valueOf(Store.speed(ctx)),
                    Float.valueOf(Store.cadence(ctx)),
                    Float.valueOf(Store.stride(ctx)),
                    Integer.valueOf(Store.anti(ctx) ? 1 : 0),
                    originString(ctx),
                    ConfigSync.waypointsJson(ctx),
                    Integer.valueOf(ConfigSync.getMode(ctx))
            });
            Log.i(TAG, "provider served config");
        } catch (Throwable t) {
            Log.e(TAG, "provider query failed", t);
        }
        return c;
    }

    private String originString(Context ctx) {
        double[] o = Store.origin(ctx);
        return o == null ? "" : (o[0] + "," + o[1]);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.mokrun.app.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
