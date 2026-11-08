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
