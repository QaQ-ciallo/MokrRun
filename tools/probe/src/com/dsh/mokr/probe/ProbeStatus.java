package com.dsh.mokr.probe;

import android.app.Activity;

/**
 * Bridge object the module calls through reflection from inside this process.
 * Everything here runs on the UI thread via the activity.
 */
public final class ProbeStatus {

    static volatile ProbeActivity sActivity;

    private ProbeStatus() {}

    static void attach(ProbeActivity a) {
        sActivity = a;
    }

    static void detach() {
        sActivity = null;
    }

    /** Called by the injected module when it attaches to this process. */
    public static void setInjected(final boolean injected) {
        final ProbeActivity a = sActivity;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                a.onInjectedState(injected);
            }
        });
    }

    /** Called by the module to push a line into the on-screen log. */
    public static void log(final String line) {
        final ProbeActivity a = sActivity;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                a.appendLog(line);
            }
        });
    }
}
