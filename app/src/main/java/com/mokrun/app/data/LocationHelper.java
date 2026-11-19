package com.mokrun.app.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 拿一次真实位置，并转换成 GCJ-02。
 *
 * 为什么要转：地图瓦片用的是高德（GCJ-02 火星坐标），而 Android 的
 * LocationManager 返回的是 WGS-84 原始坐标。两者在国内相差 300~600 米，
 * 直接拿去打点就会看到"当前位置偏了几百米"。这里统一转成 GCJ-02，
 * 让地图上的点位与瓦片地物对齐。
 */
public final class LocationHelper {

    public interface Callback {
        void onLocation(double lat, double lon);
    }

    // ---- WGS-84 -> GCJ-02 --------------------------------------------------
    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /** WGS-84 → GCJ-02；境外原样返回 */
    public static double[] wgs2gcj(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }


    /**
     * GCJ-02 → WGS-84（近似反解）。
     *
     * 体适能内部会把收到的坐标当作 WGS-84 处理再转 GCJ-02，
     * 所以传给它的必须是原始坐标，否则会转两次、偏出几百米。
     */
    public static double[] gcj2wgs(double lat, double lon) {
        double[] gcj = wgs2gcj(lat, lon);
        double dLat = gcj[0] - lat;
        double dLon = gcj[1] - lon;
        // 迭代两次足够精确（误差 < 0.1m）
        double wLat = lat - dLat;
        double wLon = lon - dLon;
        for (int i = 0; i < 2; i++) {
            double[] g2 = wgs2gcj(wLat, wLon);
            wLat -= (g2[0] - lat);
            wLon -= (g2[1] - lon);
        }
        return new double[]{wLat, wLon};
    }

    // ---- 定位 ---------------------------------------------------------------

    public static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 缓存位置（已转 GCJ-02），可能为 null */
    public static double[] cached(Context ctx) {
        if (!hasPermission(ctx)) {
            android.util.Log.w("MokrRunLoc", "no permission");
            return null;
        }
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            if (l == null) {
                android.util.Log.w("MokrRunLoc", "cached=null");
                return null;
            }
            double[] gcj = wgs2gcj(l.getLatitude(), l.getLongitude());
            android.util.Log.i("MokrRunLoc", "cached=" + l.getProvider()
                    + " wgs=" + l.getLatitude() + "," + l.getLongitude()
                    + " gcj=" + gcj[0] + "," + gcj[1]);
            return gcj;
        } catch (Throwable t) {
            android.util.Log.e("MokrRunLoc", "cached failed", t);
            return null;
        }
    }

    /** 带年龄的缓存：{lat, lon, accuracy, ageMs}；不可用返回 null */
    public static double[] cachedWithAge(Context ctx) {
        if (!hasPermission(ctx)) return null;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            String[] providers = {LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            long now = System.currentTimeMillis();
            for (int i = 0; i < providers.length; i++) {
                Location l = lm.getLastKnownLocation(providers[i]);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            if (best == null) return null;
            double[] gcj = wgs2gcj(best.getLatitude(), best.getLongitude());
            long age = Math.max(0L, now - best.getTime());
            return new double[]{gcj[0], gcj[1], best.getAccuracy(), age};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 定位开关是否可用 */
    public static boolean locationEnabled(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 新鲜定位请求的回调：预览、成功、失败三态互斥，一定有结束状态 */
    public interface FreshCallback {
        /** 只有预览意义的缓存（可能为空），不能据此宣布定位完成 */
        void onPreview(double lat, double lon, float accuracy, long ageMs);

        /**
         * @param ageMs     这个点离现在多久（毫秒）：新点≈0，缓存兜底会是真实年龄
         * @param fromCache 是否来自缓存兜底（调用方必须按真实年龄校验，不能当新点）
         */
        void onFresh(double lat, double lon, float accuracy, long ageMs, boolean fromCache);

        void onError(String message);
    }

    // ---- 请求生命周期 ------------------------------------------------------
    // 取消/代际：取消、页面销毁或新请求开始时，旧回调必须失效
    private static volatile int sGen = 0;
    private static volatile LocationManager sActiveLm;
    private static volatile LocationListener sActiveListener;

    /** 撤销当前定位请求：迟到回调会被代际检查拒绝 */
    public static void cancelFresh(String reason) {
        sGen++;
        LocationManager lm = sActiveLm;
        LocationListener l = sActiveListener;
        sActiveLm = null;
        sActiveListener = null;
        if (lm != null && l != null) {
            try { lm.removeUpdates(l); } catch (Throwable ignored) {
            }
        }
        android.util.Log.i("MokrRunLoc", "fresh request cancelled (" + reason + ")");
    }

    /**
     * 请求一次**新鲜**定位（「定位当前位置」按钮专用）。
     * 请求进行中重复点击会被 LocateTracker 忽略；必定以 onFresh 或 onError 结束。
     */
    public static void requestFresh(final Context ctx, final LocateTracker tracker,
                                    final FreshCallback cb) {
        requestFresh(ctx, tracker, cb, false);
    }

    /** 需要「足够准」的点时使用（选起点）：先等精度达标的点，超时才接受较粗的或缓存 */
    public static final float PRECISE_ACCURACY_M = 20f;

    public static void requestFresh(final Context ctx, final LocateTracker tracker,
                                    final FreshCallback cb, final boolean preferPrecise) {
        if (tracker == null || cb == null) return;
        if (!hasPermission(ctx)) {
            tracker.fail("未授予定位权限");
            cb.onError("未授予定位权限：请允许定位后再试");
            return;
        }
        if (!locationEnabled(ctx)) {
            tracker.fail("系统定位已关闭");
            cb.onError("系统定位已关闭：请打开定位开关后再试");
            return;
        }
        if (!tracker.begin(System.currentTimeMillis())) return;   // 重复点击

        final int gen = ++sGen;
        final double[] cache = cachedWithAge(ctx);
        if (cache != null && (long) cache[3] <= LocateTracker.FRESH_ENOUGH_MS
                && (!preferPrecise || cache[2] <= PRECISE_ACCURACY_M)) {
            tracker.succeed();
            android.util.Log.i("MokrRunLoc", "cache accepted acc=" + cache[2]
                    + " age=" + (long) cache[3]);
            cb.onFresh(cache[0], cache[1], (float) cache[2], (long) cache[3], true);
            return;
        }
        if (cache != null && LocateTracker.cacheUsable((long) cache[3])) {
            cb.onPreview(cache[0], cache[1], (float) cache[2], (long) cache[3]);
        }

        final android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
        final LocationManager lm;
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        } catch (Throwable t) {
            tracker.fail("定位服务不可用");
            cb.onError("定位服务不可用");
            return;
        }
        if (lm == null) {
            tracker.fail("定位服务不可用");
            cb.onError("定位服务不可用");
            return;
        }

        // 精度不够时先记最好的一条，超时后用它兜底
        final Location[] best = new Location[1];

        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (l == null || !tracker.isRequesting()) return;
                if (gen != sGen) {
                    android.util.Log.i("MokrRunLoc", "stale callback dropped (gen " + gen
                            + " != " + sGen + ")");
                    return;
                }
                if (preferPrecise && l.getAccuracy() > PRECISE_ACCURACY_M) {
                    if (best[0] == null || l.getAccuracy() < best[0].getAccuracy()) {
                        best[0] = new Location(l);
                    }
                    return;
                }
                h.removeCallbacksAndMessages(null);
                try { lm.removeUpdates(this); } catch (Throwable ignored) {}
                sActiveLm = null;
                sActiveListener = null;
                double[] g = wgs2gcj(l.getLatitude(), l.getLongitude());
                long age = Math.max(0L, System.currentTimeMillis() - l.getTime());
                tracker.succeed();
                android.util.Log.i("MokrRunLoc", "fresh " + l.getProvider()
                        + " wgs=" + l.getLatitude() + "," + l.getLongitude()
                        + " gcj=" + g[0] + "," + g[1] + " acc=" + l.getAccuracy()
                        + " age=" + age);
                cb.onFresh(g[0], g[1], l.getAccuracy(), age, false);
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };

        final Runnable giveUp = new Runnable() {
            @Override public void run() {
                if (!tracker.isRequesting()) return;
                if (gen != sGen) {
                    android.util.Log.i("MokrRunLoc", "stale timeout dropped (gen " + gen
                            + " != " + sGen + ")");
                    return;
                }
                try { lm.removeUpdates(listener); } catch (Throwable ignored) {}
                sActiveLm = null;
                sActiveListener = null;
                if (best[0] != null) {
                    double[] g = wgs2gcj(best[0].getLatitude(), best[0].getLongitude());
                    long age = Math.max(0L, System.currentTimeMillis() - best[0].getTime());
                    tracker.succeed();
                    android.util.Log.i("MokrRunLoc", "coarse fallback acc="
                            + best[0].getAccuracy() + " age=" + age);
                    cb.onFresh(g[0], g[1], best[0].getAccuracy(), age, false);
                    return;
                }
                double[] late = cachedWithAge(ctx);
                if (late != null && LocateTracker.usableFallback((long) late[3])) {
                    tracker.succeed();
                    android.util.Log.i("MokrRunLoc", "cached fallback age="
                            + ((long) late[3] / 1000) + "s");
                    // 真实年龄照实回传：调用方必须按 age 校验，不能当成刚取到的点
                    cb.onFresh(late[0], late[1], (float) late[2], (long) late[3], true);
                    return;
                }
                tracker.fail("定位超时");
                cb.onError("定位超时：没有在 " + (LocateTracker.TIMEOUT_MS / 1000)
                        + " s 内拿到结果，请到开阔处重试");
            }
        };
        h.postDelayed(giveUp, LocateTracker.TIMEOUT_MS);

        try {
            boolean any = false;
            sActiveLm = lm;
            sActiveListener = listener;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f,
                        listener, Looper.getMainLooper());
                any = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f,
                        listener, Looper.getMainLooper());
                any = true;
            }
            if (!any) {
                h.removeCallbacks(giveUp);
                tracker.fail("没有可用的定位提供者");
                cb.onError("没有可用的定位提供者：请检查定位设置");
            }
        } catch (Throwable t) {
            h.removeCallbacks(giveUp);
            tracker.fail("定位请求异常");
            cb.onError("定位请求异常：" + t);
        }
    }

    /** 缓存优先；没有就请求一次，回调在主线程，坐标已转 GCJ-02 */
    public static void requestOnce(final Context ctx, final Callback cb) {
        double[] c = cached(ctx);
        if (c != null) {
            cb.onLocation(c[0], c[1]);
            return;
        }
        if (!hasPermission(ctx)) return;
        try {
            final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            final String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;

            if (Build.VERSION.SDK_INT >= 30) {
                Executor exec = ctx.getMainExecutor();
                lm.getCurrentLocation(provider, (CancellationSignal) null, exec,
                        new Consumer<Location>() {
                            @Override public void accept(Location l) {
                                if (l == null) return;
                                double[] g = wgs2gcj(l.getLatitude(), l.getLongitude());
                                android.util.Log.i("MokrRunLoc", "fresh wgs="
                                        + l.getLatitude() + "," + l.getLongitude()
                                        + " gcj=" + g[0] + "," + g[1]);
                                cb.onLocation(g[0], g[1]);
                            }
                        });
            } else {
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override public void onLocationChanged(Location l) {
                        if (l == null) return;
                        double[] g = wgs2gcj(l.getLatitude(), l.getLongitude());
                        cb.onLocation(g[0], g[1]);
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
            }
        } catch (Throwable ignored) {
        }
    }
}
