package com.mokrun.app.xposed;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 弹窗/Toast/PopupWindow 文本观察 + 人脸识别自动冻结。
 *
 * 目的：
 *   1. 定位「GPS 轨迹异常」这条提示的抛出位置，以及它挂在哪类弹窗上；
 *   2. 中途弹出人脸识别时立刻冻结模拟位置（必须在 150 m 识别窗口内停下）。
 *
 * 历史教训：
 *   1. 只 hook Resources.getString 没命中——文案不一定走标准资源读取；
 *   2. 只 hook android.app.AlertDialog / Dialog / Toast 也没命中——
 *      AppCompat 与 Material 的对话框是另外的类，且自绘弹窗根本不走 setMessage；
 *   3. 冻结曾经由**任意** Dialog.dismiss 解除，无关弹窗一关就把冻结丢了；
 *   4. 日志限流 return 写在冻结判断之前，300 ms 内的重复上报被整个跳过。
 *   所以：谁 show 就抓谁 + 扫 contentView 全部文本；行为判断与日志输出分离；
 *   冻结绑定到触发它的那个弹窗对象，只有它能解除。
 */
public final class ResourceProbe {

    private static final String TAG = "MokrRunProbe";
    private static boolean sInstalled = false;
    private static long sLastLog = 0L;
    /** 触发人脸冻结的弹窗对象（Dialog / PopupWindow）；身份比较，不用 equals */
    private static volatile Object sFaceOwner = null;

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
                                handleTree("Dialog.show " + who, d, root);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Dialog.show");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Dialog.show failed " + t);
        }

        // 1b) 弹窗消失 → 只有触发冻结的那个弹窗才解除冻结
        try {
            XposedHelpers.findAndHookMethod("android.app.Dialog", cl, "dismiss",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            onDismissed(param.thisObject, "Dialog.dismiss");
                        }
                    });
            XposedBridge.log(TAG + ": hooked Dialog.dismiss");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Dialog.dismiss failed " + t);
        }

        // 2) AppCompat / Material 的 Builder（setMessage / setTitle），留着做交叉验证
        hookBuilder(cl, "androidx.appcompat.app.AlertDialog$Builder", "setMessage");
        hookBuilder(cl, "com.google.android.material.dialog.MaterialAlertDialogBuilder", "setMessage");

        // 3) PopupWindow 三个常用入口 + 关闭
        hookPopup(cl, "showAtLocation", new Class[]{View.class, int.class, int.class, int.class});
        hookPopup(cl, "showAsDropDown", new Class[]{View.class});
        hookPopup(cl, "showAsDropDown", new Class[]{View.class, int.class, int.class});
        hookPopupDismiss(cl);

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
                        handleTree("PopupWindow." + method, pw, pw.getContentView());
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

    private static void hookPopupDismiss(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.widget.PopupWindow", cl, "dismiss",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            onDismissed(param.thisObject, "PopupWindow.dismiss");
                        }
                    });
            XposedBridge.log(TAG + ": hooked PopupWindow.dismiss");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PopupWindow.dismiss skipped");
        }
    }

    /** 只记录文本，不做任何修改（调用方不知道宿主对象时用这个） */
    private static void report(String from, String text) {
        handleText(from, null, text);
    }

    /** 扫描整棵 view 树里的 TextView */
    static void handleTree(String from, Object owner, View root) {
        if (root == null) return;
        try {
            StringBuilder texts = new StringBuilder();
            collect(root, texts, 0);
            handleTexts(from, owner, texts.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 一段可见文本的处理入口：**先做行为判断，再做日志输出**。
     *
     * 日志限流只允许影响日志本身。之前函数在限流处提前 return，
     * 300 ms 内第二次出现的「请进行人脸识别」不会触发冻结。
     */
    static void handleText(String from, Object owner, String text) {
        if (text == null || text.length() == 0) return;
        handleTexts(from, owner, text);
    }

    private static void handleTexts(String from, Object owner, String all) {
        if (all == null || all.length() == 0) return;
        if (detectFaceCheck(all, owner)) {
            log(from, all);
        }
    }

    /**
     * @return 是否需要写日志（命中人脸时总是要写，便于现场核对）
     */
    private static boolean detectFaceCheck(String all, Object owner) {
        // 排除「会随机进行人脸识别，请注意」这类赛前说明文案
        if (!all.contains("人脸") || all.contains("随机") || all.contains("说明")) {
            return true;
        }
        if (!MotionEngine.faceFrozen()) {
            sFaceOwner = owner;
            XposedBridge.log(TAG + ": FACE-CHECK dialog -> " + all);
            MotionEngine.freezeForFace();
            return true;
        }
        // 已经冻结：允许后续的宿主对象（例如先 setMessage 再 Dialog.show）补绑一次，
        // 这样「关闭人脸弹窗」才能精确匹配
        if (owner != null && sFaceOwner == null) sFaceOwner = owner;
        return true;
    }

    /** 弹窗关闭：只有触发冻结的那个弹窗才允许解除冻结 */
    static void onDismissed(Object dialog, String from) {
        try {
            if (!MotionEngine.faceFrozen()) {
                sFaceOwner = null;
                return;
            }
            Object owner = sFaceOwner;
            if (owner == null || owner != dialog) {
                XposedBridge.log(TAG + ": " + from + " ignored, not the face dialog");
                return;
            }
            sFaceOwner = null;
            MotionEngine.resumeAfterFace();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + from + " handling failed " + t);
        }
    }

    /** 日志输出（带限流，最多 300 ms 一条，避免刷爆 logcat） */
    private static void log(String from, String all) {
        try {
            long now = System.currentTimeMillis();
            if (now - sLastLog < 300L) return;
            sLastLog = now;

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
