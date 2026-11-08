package com.mokrun.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务。
 *
 * 存在的唯一目的：让 App 进程一直活着，这样 ConfigProvider 随时可被
 * 体适能进程读取（否则 MIUI 会拦住冷启动，provider query 拿到 null）。
 * 没有这个服务，配置通道就得依赖 WRITE_SECURE_SETTINGS，而那不是普通用户能给的。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "mokr_keepalive";
    private static final int NOTIF_ID = 2002;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "随心跑配置服务", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("随心跑")
                .setContentText("配置通道就绪（可随时关闭）")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }
}
