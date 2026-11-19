package com.mokrun.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 回执的静态入口。
 *
 * 目标进程通过**显式组件**广播（setClassName）把 PREPARE/CONFIRM/CLEAR 的结果送到这里：
 * 隐式广播在 MIUI 上会被后台限制丢掉（实机 1.0.129 复现：引擎已应用 PREPARE，
 * 模块首页仍停在「未开始」），显式组件广播不受该限制。
 */
public class AckReceiver extends BroadcastReceiver {

    public interface Listener {
        void onAck(Intent intent);
    }

    private static volatile Listener sListener;
    private static volatile Intent sLast;

    @Override public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            onDeliver(new Intent(intent));
        } catch (Throwable t) {
            android.util.Log.e("MokrRunAck", "ack failed", t);
        }
    }

    /** Provider 通道直接投递（不经过系统广播队列，避免 MIUI 排队延迟） */
    public static void deliver(android.os.Bundle extras) {
        try {
            if (extras == null) return;
            Intent intent = new Intent("com.mokrun.app.ACK");
            intent.putExtra("op", extras.getString("op", ""));
            intent.putExtra("session", extras.getString("session", ""));
            intent.putExtra("token", extras.getString("token", ""));
            intent.putExtra("ok", extras.getBoolean("ok", false));
            intent.putExtra("mode", extras.getInt("mode", -1));
            intent.putExtra("at", extras.getLong("at", System.currentTimeMillis()));
            if (extras.containsKey("reason")) {
                intent.putExtra("reason", extras.getString("reason"));
            }
            onDeliver(intent);
        } catch (Throwable t) {
            android.util.Log.e("MokrRunAck", "deliver failed", t);
        }
    }

    private static void onDeliver(Intent intent) {
        sLast = intent;
        Listener l = sListener;
        android.util.Log.i("MokrRunAck", "ack op=" + intent.getStringExtra("op")
                + " session=" + intent.getStringExtra("session")
                + " ok=" + intent.getBooleanExtra("ok", false)
                + " listener=" + (l != null));
        if (l != null) l.onAck(intent);
    }

    /** 界面注册监听；注册时补发最近一条（避免 Activity 重建期间丢回执） */
    public static void setListener(Listener l) {
        sListener = l;
        Intent last = sLast;
        if (l != null && last != null) {
            // 只补发很新的回执，避免旧回执干扰新一次操作
            long t = last.getLongExtra("at", 0L);
            if (t > 0 && System.currentTimeMillis() - t < 15000L) {
                try { l.onAck(last); } catch (Throwable ignored) {}
            }
        }
    }

    public static void clearListener(Listener l) {
        if (sListener == l) sListener = null;
    }
}
