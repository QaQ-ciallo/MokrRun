package com.mokrun.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条「当前路线」+ 最近 3 条历史。
 *
 * 一条路线的结构：
 *   { "name":"路线 1",
 *     "origin":{"lat":..,"lon":..},              // 起点（体适能看到的固定位置）
 *     "points":[{"lat":..,"lng":..}, ...],       // 只含途经点，不含起点
 *     "distance":512, "createdAt":1699999999999 }
 */
public final class Store {

    private static final String PREF = "mokr";

    private static final String KEY_HISTORY = "routeHistory";   // JSON array, 最多 3
    private static final String KEY_ACTIVE = "activeHistory";   // index into history
    private static final String KEY_ORIGIN = "origin";          // "lat,lon"

    private static final String KEY_SPEED = "speed";
    private static final String KEY_CADENCE = "cadence";
    private static final String KEY_STRIDE = "stride";
    private static final String KEY_ANTI = "anti";

    public static final int MAX_HISTORY = 3;

    private Store() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---- origin ------------------------------------------------------------

    /** @return {lat, lon} or null */
    public static double[] origin(Context ctx) {
        String raw = prefs(ctx).getString(KEY_ORIGIN, "");
        if (raw == null || raw.indexOf(',') < 0) return null;
        try {
            String[] p = raw.split(",");
            return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setOrigin(Context ctx, double lat, double lon) {
        prefs(ctx).edit().putString(KEY_ORIGIN, lat + "," + lon).apply();
    }

    public static void clearOrigin(Context ctx) {
        prefs(ctx).edit().remove(KEY_ORIGIN).apply();
    }

    // ---- history -----------------------------------------------------------

    public static List<JSONObject> history(Context ctx) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        String raw = prefs(ctx).getString(KEY_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getJSONObject(i));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void writeHistory(Context ctx, List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size() && i < MAX_HISTORY; i++) {
            arr.put(list.get(i));
        }
        prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    /**
     * 新画的路线插到最前；超过 3 条时裁掉最旧的。
     * @return 新路线在列表中的位置（永远是 0）
     */
    public static int addHistory(Context ctx, JSONObject route) {
        List<JSONObject> list = history(ctx);
        list.add(0, route);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        writeHistory(ctx, list);
        prefs(ctx).edit().putInt(KEY_ACTIVE, 0).apply();
        return 0;
    }

    public static int activeIndex(Context ctx) {
        int idx = prefs(ctx).getInt(KEY_ACTIVE, 0);
        int size = history(ctx).size();
        if (size == 0) return -1;
        if (idx < 0 || idx >= size) return 0;
        return idx;
    }

    public static void setActiveIndex(Context ctx, int idx) {
        prefs(ctx).edit().putInt(KEY_ACTIVE, idx).apply();
    }

    /** 当前选中的那条路线，没有则 null */
    public static JSONObject activeRoute(Context ctx) {
        List<JSONObject> list = history(ctx);
        int idx = activeIndex(ctx);
        return idx < 0 || idx >= list.size() ? null : list.get(idx);
    }

    public static void removeHistory(Context ctx, int idx) {
        List<JSONObject> list = history(ctx);
        if (idx < 0 || idx >= list.size()) return;
        list.remove(idx);
        writeHistory(ctx, list);
        prefs(ctx).edit().putInt(KEY_ACTIVE, 0).apply();
    }

    public static void clearHistory(Context ctx) {
        prefs(ctx).edit().putString(KEY_HISTORY, "[]").putInt(KEY_ACTIVE, 0).apply();
    }

    public static void renameHistory(Context ctx, int idx, String name) {
        List<JSONObject> list = history(ctx);
        if (idx < 0 || idx >= list.size()) return;
        try {
            list.get(idx).put("name", name);
        } catch (JSONException ignored) {
            return;
        }
        writeHistory(ctx, list);
    }

    /** 把选中路线的起点同步到 origin 键（引擎读这个） */
    public static void syncOriginFromActive(Context ctx) {
        JSONObject r = activeRoute(ctx);
        if (r == null) return;
        JSONObject o = r.optJSONObject("origin");
        if (o == null) return;
        setOrigin(ctx, o.optDouble("lat", 0), o.optDouble("lon", 0));
    }

    // ---- settings ----------------------------------------------------------



    // ---- 运行状态（0 停止 / 1 运行 / 2 就绪暂停）--------------------------

    public static int runMode(Context ctx) {
        return prefs(ctx).getInt("runMode", 0);
    }

    public static void setRunMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt("runMode", mode).apply();
    }

    // ---- 速度 / 步频 / 步长 联动 -------------------------------------------

    /** 基准：2.5 m/s 对应 2.2 步/秒，越快步子迈得越频 */
    public static final float BASE_SPEED = 2.5f;
    public static final float BASE_CADENCE = 2.2f;
    public static final float CADENCE_SLOPE = 0.4f;
    public static final float MIN_SPEED = 1.0f;
    public static final float MAX_SPEED = 5.0f;

    public static float cadenceFor(float speed) {
        float c = BASE_CADENCE + (speed - BASE_SPEED) * CADENCE_SLOPE;
        return Math.max(1.0f, Math.min(5.0f, c));
    }

    public static float strideFor(float speed, float cadence) {
        return speed / Math.max(0.5f, cadence);
    }

    /** 改速度，同时把步频、步长算好 */
    public static void setSpeedWithDerived(Context ctx, float speed) {
        float v = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        float c = cadenceFor(v);
        setSpeed(ctx, v);
        setCadence(ctx, c);
        setStride(ctx, strideFor(v, c));
    }

    public static float speed(Context ctx) {
        return prefs(ctx).getFloat(KEY_SPEED, 2.5f);
    }

    public static void setSpeed(Context ctx, float v) {
        prefs(ctx).edit().putFloat(KEY_SPEED, v).apply();
    }

    public static float cadence(Context ctx) {
        return prefs(ctx).getFloat(KEY_CADENCE, 2.2f);
    }

    public static void setCadence(Context ctx, float v) {
        prefs(ctx).edit().putFloat(KEY_CADENCE, v).apply();
    }

    public static float stride(Context ctx) {
        return prefs(ctx).getFloat(KEY_STRIDE, 1.1f);
    }

    public static void setStride(Context ctx, float v) {
        prefs(ctx).edit().putFloat(KEY_STRIDE, v).apply();
    }

    public static boolean anti(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ANTI, true);
    }

    public static void setAnti(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(KEY_ANTI, v).apply();
    }
}
