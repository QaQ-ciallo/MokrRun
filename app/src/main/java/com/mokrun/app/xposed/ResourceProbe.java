package com.mokrun.app.xposed;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 纯观察：抓目标应用弹出的对话框 / Toast / PopupWindow，记录文案与调用栈。
 *
 * 目的：定位「GPS 轨迹异常」这条提示的抛出位置，以及它挂在哪类弹窗上。
 *
 * 历史教训：
 *   1. 只 hook Resources.getString 没命中——文案不一定走标准资源读取；
 *   2. 只 hook android.app.AlertDialog / Dialog / Toast 也没命中——
 *      AppCompat 与 Material 的对话框是另外的类，且自绘弹窗根本不走 setMessage。
 *   所以现在改为「谁 show 就抓谁 + 把 contentView 里的文本全扫一遍」，
 *   覆盖 Dialog / PopupWindow / AppCompat Builder / Toast 四类载体。
 */
public final class ResourceProbe {

    private static final String TAG = "MokrRunProbe";
    private static boolean sInstalled = false;
    private static long sLastLog = 0L;

    private ResourceProbe() {}

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;

        // 1) 任何 Dialog（含 AppCompat / Material 的父类目标）show 之后扫文本
        try {
            XposedHelpers.findAndHookMethod("android.app.Dialog", cl, "show",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Dialog d = (Dialog) param.thisObject;
                                String who = d.getClass().getName();
                                View root = d.getWindow() == null
                                        ? null : d.getWindow().peekDecorView();
                                reportAll("Dialog.show " + who, root);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Dialog.show");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Dialog.show failed " + t);
        }

        // 1b) 弹窗消失 → 若之前因为人脸识别冻结，这里恢复推进
        try {
            XposedHelpers.findAndHookMethod("android.app.Dialog", cl, "dismiss",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (MotionEngine.faceFrozen()) {
                                MotionEngine.resumeAfterFace();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Dialog.dismiss failed " + t);
        }

        // 2) AppCompat / Material 的 Builder（setMessage / setTitle），留着做交叉验证
        hookBuilder(cl, "androidx.appcompat.app.AlertDialog$Builder", "setMessage");
        hookBuilder(cl, "com.google.android.material.dialog.MaterialAlertDialogBuilder", "setMessage");

        // 3) PopupWindow 三个常用入口
        hookPopup(cl, "showAtLocation", new Class[]{View.class, int.class, int.class, int.class});
        hookPopup(cl, "showAsDropDown", new Class[]{View.class});
        hookPopup(cl, "showAsDropDown", new Class[]{View.class, int.class, int.class});

        // 4) Toast 文本
        try {
            XposedHelpers.findAndHookMethod("android.widget.Toast", cl, "makeText",
                    android.content.Context.class, CharSequence.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            report("Toast.makeText", String.valueOf(param.args[1]));
                        }
                    });
            XposedBridge.log(TAG + ": hooked Toast.makeText");
        } catch (Throwable ignored) {
        }

        XposedBridge.log(TAG + ": popup/dialog probe installed");
    }

    private static void hookBuilder(ClassLoader cl, final String cls, final String method) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, CharSequence.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            report(cls.substring(cls.lastIndexOf('.') + 1) + "." + method,
                                    String.valueOf(param.args[0]));
                        }
                    });
            XposedBridge.log(TAG + ": hooked " + cls + "." + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + cls + "." + method + " skipped");
        }
    }

    private static void hookPopup(ClassLoader cl, final String method, Class<?>[] sig) {
        try {
            Object[] args = new Object[sig.length + 1];
            System.arraycopy(sig, 0, args, 0, sig.length);
            args[sig.length] = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.widget.PopupWindow pw = (android.widget.PopupWindow) param.thisObject;
                        reportAll("PopupWindow." + method, pw.getContentView());
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.widget.PopupWindow", cl, method, args);
            XposedBridge.log(TAG + ": hooked PopupWindow." + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PopupWindow." + method + " skipped");
        }
    }

    /** 只记录文本，不做任何修改 */
    private static void report(String from, String text) {
        if (text == null || text.length() == 0) return;
        reportAll(from, null, text);
    }

    /** 扫描整棵 view 树里的 TextView / 直接上报单条文本 */
    private static void reportAll(String from, View root) {
        reportAll(from, root, null);
    }

    private static void reportAll(String from, View root, String single) {
        try {
            StringBuilder texts = new StringBuilder();
            if (single != null) {
                texts.append(single);
            } else if (root != null) {
                collect(root, texts, 0);
            }
            if (texts.length() == 0) return;

            long now = System.currentTimeMillis();
            if (now - sLastLog < 300L) return;
            sLastLog = now;

            String all = texts.toString();
            // 中途人脸识别：立刻冻结模拟位置，避免「超出150米允许识别范围」判无效。
            // 排除「会随机进行人脸识别，请注意」这类赛前说明文案。
            if (all.contains("人脸") && !all.contains("随机") && !all.contains("说明")) {
                XposedBridge.log(TAG + ": FACE-CHECK dialog -> " + all);
                MotionEngine.freezeForFace();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(from).append(" -> ").append(all).append('\n');
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            int shown = 0;
            for (int i = 0; i < st.length && shown < 15; i++) {
                String cn = st[i].getClassName();
                if (cn.startsWith("com.bxkj") || cn.startsWith("com.amap")
                        || cn.startsWith("com.autonavi") || cn.startsWith("com.blankj")
                        || cn.startsWith("com.lxj") || cn.startsWith("com.kongzue")) {
                    sb.append("    at ").append(cn).append('.')
                            .append(st[i].getMethodName()).append('(')
                            .append(st[i].getFileName()).append(':')
                            .append(st[i].getLineNumber()).append(")\n");
                    shown++;
                }
            }
            if (shown == 0) {
                for (int i = 0; i < st.length && shown < 12; i++) {
                    sb.append("    . ").append(st[i].getClassName()).append('.')
                            .append(st[i].getMethodName()).append(": ")
                            .append(st[i].getLineNumber()).append('\n');
                    shown++;
                }
            }
            XposedBridge.log(TAG + ": " + sb);
        } catch (Throwable ignored) {
        }
    }

    /** 递归收集可见文本，最多 1000 字，避免刷爆 logcat */
    private static void collect(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 40 || sb.length() > 1000) return;
        try {
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && t.length() > 0 && t.length() < 400) {
                    String name = v.getClass().getName();
                    sb.append('[').append(name.substring(name.lastIndexOf('.') + 1)).append("] ")
                            .append(t).append(" | ");
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    collect(g.getChildAt(i), sb, depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
