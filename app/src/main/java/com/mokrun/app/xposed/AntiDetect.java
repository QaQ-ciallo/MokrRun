package com.mokrun.app.xposed;

import android.location.Location;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes every Location read inside the target look like a genuine fix and
 * hides the usual "are you spoofing" probes.
 */
public final class AntiDetect {

    private static final String TAG = "MokrRun";

    private AntiDetect() {}

    public static void install(ClassLoader cl) {
        hookMockFlags(cl);
        hookFakeLocationApps(cl);
    }

    /** isMock()/isFromMockProvider() must always answer false */
    private static void hookMockFlags(ClassLoader cl) {
        String[] methods = {"isFromMockProvider", "isMock"};
        for (int i = 0; i < methods.length; i++) {
            final String name = methods[i];
            try {
                XposedHelpers.findAndHookMethod("android.location.Location", cl, name,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(Boolean.FALSE);
                            }
                        });
                XposedBridge.log(TAG + ": anti-detect hooked Location." + name + " -> false");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Location." + name + " hook skipped (" + t + ")");
            }
        }
    }

    /**
     * Hide known mock-location apps from the package list, so a
     * "do you have FakeLocation installed" scan comes back clean.
     * Our own package is included on purpose: nothing to find.
     */
    private static void hookFakeLocationApps(ClassLoader cl) {
        final String[] hidden = {
                "com.lexa.fakegps",
                "com.lexa.fakegpsdonate",
                "ru.gavrikov.mocklocations",
                "com.blogspot.newapphorizons.fakegps",
                "com.incorporateapps.fakegps.fre",
                "com.fakegps.mock",
                "com.cxorz.anywhere",
        };
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl,
                    "getInstalledApplications", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            filterApps(param, hidden);
                        }
                    });
            XposedBridge.log(TAG + ": anti-detect hooked getInstalledApplications");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": package filter skipped (" + t + ")");
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl,
                    "getInstalledPackages", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            filterPkgs(param, hidden);
                        }
                    });
            XposedBridge.log(TAG + ": anti-detect hooked getInstalledPackages");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": package filter #2 skipped (" + t + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static void filterApps(XC_MethodHook.MethodHookParam param, String[] hidden) {
        try {
            Object result = param.getResult();
            if (!(result instanceof List)) return;
            List<Object> list = (List<Object>) result;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object appInfo = list.get(i);
                Object pkg = XposedHelpers.getObjectField(appInfo, "packageName");
                if (pkg != null && match(String.valueOf(pkg), hidden)) {
                    list.remove(i);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void filterPkgs(XC_MethodHook.MethodHookParam param, String[] hidden) {
        try {
            Object result = param.getResult();
            if (!(result instanceof List)) return;
            List<Object> list = (List<Object>) result;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object pkgInfo = list.get(i);
                Object name = XposedHelpers.getObjectField(pkgInfo, "packageName");
                if (name != null && match(String.valueOf(name), hidden)) {
                    list.remove(i);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean match(String pkg, String[] hidden) {
        for (int i = 0; i < hidden.length; i++) {
            if (hidden[i].equals(pkg)) return true;
        }
        return false;
    }
}
