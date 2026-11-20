"""Android / Xposed 边界的 JVM 替身（唯一一份，供审计与回归测试共用）。

设计约束：
  * 替身只模拟「hook 边界」的可观察契约——参数、返回值、回调时序、注册表；
  * 不含任何模块业务逻辑（路线推进、状态机、计步、冻结判断都在生产代码里跑）；
  * 需要可控时间的场景（冻结超时、每 tick 补步）由 Handler 的虚拟时钟驱动，
    测试不 sleep，也不依赖「碰巧合适的等待时间」；
  * Xposed 注册表按「类名.方法名 + 签名」登记，可以同时挂同名重载，
    这与真实 XposedBridge.hookAllMethods 的行为一致。

局限（不要被这些替身过度解读）：
  * 不是 Android 运行时，OEM 字段差异、真实线程切换、LSPosed 注入时序都未覆盖；
  * org.json 只解析测试用到的合法数字坐标，不验证畸形 JSON 行为；
  * GnssStatus.Builder 只提供 API 30+ 的 12 参 addSatellite 与 5/6 参旧签名，
    不模拟 OEM 对错误参数的容错。
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "app" / "src" / "main" / "java" / "com" / "mokrun" / "app" / "xposed"

SOURCES = {
    # ---- de.robv.android.xposed -------------------------------------------
    "de/robv/android/xposed/XC_MethodHook.java": """
package de.robv.android.xposed;
public class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam p) {}
    protected void afterHookedMethod(MethodHookParam p) {}
    public static class MethodHookParam {
        public Object[] args; public Object thisObject; public boolean stopped;
        private Object result;
        public Object getResult() { return result; }
        public void setResult(Object value) { stopped=true; result=value; }
    }
}
""",
    "de/robv/android/xposed/XposedBridge.java": """
package de.robv.android.xposed;
import java.util.*;
public class XposedBridge {
    /** key = "类名.方法名" → 该方法的全部 hook（同名重载可以共存） */
    public static final Map<String, List<Registration>> hooks =
            new LinkedHashMap<String, List<Registration>>();

    public static class Registration {
        public final String key; public final Class<?>[] signature; public final XC_MethodHook hook;
        Registration(String key, Class<?>[] signature, XC_MethodHook hook) {
            this.key=key; this.signature=signature; this.hook=hook;
        }
    }

    public static void log(String msg) { System.out.println("LOG " + msg); }

    static Registration add(String key, Class<?>[] signature, XC_MethodHook hook) {
        Registration r=new Registration(key, signature, hook);
        List<Registration> list=hooks.get(key);
        if (list==null) { list=new ArrayList<Registration>(); hooks.put(key, list); }
        list.add(r);
        return r;
    }

    public static void hookAllMethods(Class<?> c, String name, XC_MethodHook hook) {
        add(c.getName()+"."+name, null, hook);   // 签名未知 = 匹配任意重载
        System.out.println("HOOK " + c.getName() + "." + name + " [all]");
    }

    public static int hookCount(String key) {
        List<Registration> list=hooks.get(key);
        return list==null ? 0 : list.size();
    }

    static Class<?> box(Class<?> p) {
        if (!p.isPrimitive()) return p;
        if (p==long.class) return Long.class;
        if (p==int.class) return Integer.class;
        if (p==float.class) return Float.class;
        if (p==double.class) return Double.class;
        if (p==boolean.class) return Boolean.class;
        if (p==short.class) return Short.class;
        if (p==byte.class) return Byte.class;
        if (p==char.class) return Character.class;
        return Void.class;
    }

    static boolean accepts(Class<?>[] signature, Object[] args) {
        if (signature==null) return true;
        if (signature.length!=args.length) return false;
        for (int i=0; i<signature.length; i++) {
            Object a=args[i];
            if (a==null) continue;                 // null 视为「任意兼容值」
            Class<?> expected=signature[i];
            if (expected==null) continue;          // 无法解析的类型名
            if (!box(expected).isInstance(a)) return false;
        }
        return true;
    }

    /** 只触发指定方法的 hook；签名不匹配的 hook 视为未被调用 */
    public static void invoke(String key, boolean before, Object owner, Object... args) throws Exception {
        List<Registration> list=hooks.get(key);
        if (list==null) throw new AssertionError("No hook registered: "+key);
        List<Registration> match=new ArrayList<Registration>();
        for (Registration r : list) if (accepts(r.signature, args)) match.add(r);
        if (match.isEmpty()) throw new AssertionError("No matching hook: "+key);
        for (Registration r : match) {
            XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
            p.thisObject=owner; p.args=args;
            if (before) r.hook.beforeHookedMethod(p); else r.hook.afterHookedMethod(p);
        }
    }
}
""",
    "de/robv/android/xposed/XposedHelpers.java": """
package de.robv.android.xposed;
public class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader cl) {
        try { return Class.forName(name); } catch(Exception e) { throw new RuntimeException(e); }
    }

    public static Object findAndHookMethod(String cls, ClassLoader cl, String name, Object... args) {
        Class<?> owner=findClass(cls, cl);
        Object hookObj=args[args.length-1];
        java.util.List<Class<?>> sig=new java.util.ArrayList<Class<?>>();
        for (int i=0; i<args.length-1; i++) {
            Object a=args[i];
            if (a instanceof Class) {
                sig.add((Class<?>) a);
            } else if (a instanceof String) {
                Class<?> resolved=null;
                try { resolved=Class.forName((String) a); } catch (Throwable ignored) {
                    return null;   // 边界类不在本替身里：不登记，避免误报匹配
                }
                sig.add(resolved);
            }
        }
        XposedBridge.add(owner.getName()+"."+name, sig.toArray(new Class<?>[0]),
                (XC_MethodHook) hookObj);
        System.out.println("HOOK " + cls + "." + name + " [" + sig.size() + "]");
        return null;
    }

    public static Object getObjectField(Object obj, String name) {
        for (Class<?> c=obj.getClass(); c!=null; c=c.getSuperclass()) {
            try {
                java.lang.reflect.Field f=c.getDeclaredField(name);
                f.setAccessible(true); return f.get(obj);
            } catch (NoSuchFieldException e) { continue; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("Missing field: "+name);
    }
}
""",
    # ---- android.os -------------------------------------------------------
    "android/os/Looper.java": """
package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }
""",
    "android/os/Handler.java": """
package android.os;
import java.util.*;
/**
 * 虚拟时钟 Handler：postDelayed 只登记到期时间，只有 advance(ms) 才推进时钟并执行到期任务。
 * 测试因此可以精确验证「3 分钟冻结超时」而不真的等待。
 */
public class Handler {
    public static long clock = 0L;
    static final class Task { long due; Runnable runnable; }
    // 与真机一致：主线程 Handler 共享同一条消息队列，虚拟时钟下也共用一个任务表
    private static final List<Task> tasks=new ArrayList<Task>();
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { return postDelayed(r, 0L); }
    public boolean postDelayed(Runnable r, long ms) {
        Task t=new Task(); t.due=clock+ms; t.runnable=r; tasks.add(t); return true;
    }
    public void removeCallbacksAndMessages(Object token) { tasks.clear(); }
    public void removeCallbacks(Runnable r) { tasks.removeIf(x -> x.runnable == r); }
    public static void resetClock() { clock=0L; }
    public int pending() { return tasks.size(); }
    /** 执行此刻已经到期的任务（新投递的任务不会在本轮执行，避免自旋） */
    public void runPending() { runDue(clock); }
    public void advance(long ms) { clock+=ms; runDue(clock); }
    private void runDue(long now) {
        List<Task> due=new ArrayList<Task>();
        for (Iterator<Task> it=tasks.iterator(); it.hasNext();) {
            Task t=it.next();
            if (t.due<=now) { due.add(t); it.remove(); }
        }
        for (int i=0; i<due.size(); i++) due.get(i).runnable.run();
    }
}
""",
    "android/os/Parcelable.java": "package android.os; public interface Parcelable {}",
    "android/os/ResultReceiver.java": """
package android.os;
public abstract class ResultReceiver implements Parcelable {
    public ResultReceiver() {}
    public ResultReceiver(Handler h) {}
    protected abstract void onReceiveResult(int resultCode, Bundle resultData);
    public void send(int resultCode, Bundle resultData) { onReceiveResult(resultCode, resultData); }
}
""",
    "android/os/Bundle.java": """
package android.os;
import java.util.*;
public class Bundle {
    final java.util.Map<String,Object> map=new java.util.HashMap<String,Object>();
    public void putString(String k, String v) { map.put(k,v); }
    public void putBoolean(String k, boolean v) { map.put(k,v); }
    public void putLong(String k, long v) { map.put(k,v); }
    public void putParcelable(String k, android.os.Parcelable v) { map.put(k,v); }
    public String getString(String k, String d) { Object v=map.get(k); return v==null?d:(String)v; }
    public String getString(String k) { return getString(k, null); }
    public boolean getBoolean(String k, boolean d) { Object v=map.get(k); return v==null?d:((Boolean)v).booleanValue(); }
    public boolean getBoolean(String k) { return getBoolean(k, false); }
    public long getLong(String k, long d) { Object v=map.get(k); return v==null?d:((Long)v).longValue(); }
    public java.util.Set<String> keySet() {
        java.util.Set<String> all = new java.util.HashSet<String>(map.keySet());
        all.addAll(values.keySet());
        return all;
    }
    public boolean containsKey(String k) {
        return map.containsKey(k) || values.containsKey(k);
    }
    // 与真机对齐：Bundle 记住自己的类加载器，Intent.setExtrasClassLoader 会改到 Bundle 上
    private ClassLoader classLoader;
    public void setClassLoader(ClassLoader cl) { classLoader = cl; }
    public ClassLoader getClassLoader() { return classLoader; }
    public Bundle() {}
    public Bundle(Bundle o) {
        if (o != null) { map.putAll(o.map); values.putAll(o.values); classLoader = o.classLoader; }
    }
    @SuppressWarnings("unchecked")
    public <T extends android.os.Parcelable> T getParcelable(String k) {
        return (T) map.get(k);
    }
    final Map<String,Object> values=new HashMap<String,Object>();
    public void putInt(String key, int value) { values.put(key, Integer.valueOf(value)); }
    public int getInt(String key, int fallback) {
        Object v=values.get(key); return v==null ? fallback : ((Integer) v).intValue();
    }
}
""",
    "android/os/CancellationSignal.java": "package android.os; public class CancellationSignal {}",
    # ---- android.app ------------------------------------------------------
    "android/app/PendingIntent.java": "package android.app; public class PendingIntent {}",
    "android/app/Dialog.java": """
package android.app;
public class Dialog {
    boolean shown;
    public android.view.Window getWindow() { return new android.view.Window(); }
    public void show() { shown=true; }
    public void dismiss() { shown=false; }
    public boolean isShowing() { return shown; }
}
""",
    "android/app/Activity.java": """
package android.app;
import android.content.*;
public class Activity extends Context {
    Intent intent;
    public Intent getIntent() { return intent; }
    public void setIntent(Intent it) { intent=it; }
}
""",
    # ---- android.content --------------------------------------------------
    "android/net/Uri.java": """
package android.net; public class Uri { public static Uri parse(String s) { return new Uri(); } }
""",
    "android/database/Cursor.java": """
package android.database;
public class Cursor {
    public Object[] row;
    public Cursor(Object... row) { this.row=row; }
    public boolean moveToFirst() { return row!=null && row.length>0; }
    public float getFloat(int i) { return ((Number)row[i]).floatValue(); }
    public int getInt(int i) { return ((Number)row[i]).intValue(); }
    public String getString(int i) { return (String)row[i]; }
    public void close() {}
}
""",
    "android/content/ContentResolver.java": """
package android.content;
import android.database.Cursor;
import android.net.Uri;
public class ContentResolver {
    public android.os.Bundle call(android.net.Uri uri, String method, String arg, android.os.Bundle extras) { return null; }
    /** 测试通过给 row 赋值来模拟 App 侧 prefs 的当前内容 */
    public Cursor row;
    public int queries=0;
    public Cursor query(Uri uri, String[] p, String s, String[] a, String sort) {
        queries++;
        return row;
    }
}
""",
    "android/content/BroadcastReceiver.java": """
package android.content;
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context c, Intent i);
}
""",
    "android/content/IntentFilter.java": """
package android.content; public class IntentFilter { public IntentFilter(String action) {} }
""",
    "android/os/Build.java": """
package android.os;
public class Build { public static class VERSION { public static final int SDK_INT=36; } }
""",
    "android/content/Context.java": """
package android.content;
public class Context {
    public final ContentResolver resolver=new ContentResolver();
    public Context getApplicationContext() { return this; }
    public ContentResolver getContentResolver() { return resolver; }
    /** 确认回执等出站广播：替身只记录，不投递 */
    public void sendBroadcast(Intent i) { }
}
""",
    "android/content/Intent.java": """
package android.content;
import java.util.*;
public class Intent {
    final android.os.Bundle extras = new android.os.Bundle();
    String action; String pkg; String cls;
    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent setPackage(String p) { pkg = p; return this; }
    public Intent setClassName(String pkg, String cls) { this.pkg = pkg; this.cls = cls; return this; }
    public String getComponentClassName() { return cls; }
    public String getPackage() { return pkg; }
    public String getAction() { return action; }
    public Intent putExtra(String key, String value) { extras.putString(key, value); return this; }
    public Intent putExtra(String key, int value) { extras.putInt(key, value); return this; }
    public Intent putExtra(String key, boolean value) { extras.putBoolean(key, value); return this; }
    public Intent putExtra(String key, long value) { extras.putLong(key, value); return this; }
    public Intent putExtra(String key, android.os.Parcelable value) { extras.putParcelable(key, value); return this; }
    public String getStringExtra(String key) { return extras.getString(key); }
    public int getIntExtra(String key, int fallback) { return extras.getInt(key, fallback); }
    public boolean getBooleanExtra(String key, boolean fallback) { return extras.getBoolean(key, fallback); }
    public long getLongExtra(String key, long fallback) { return extras.getLong(key, fallback); }
    public Intent setExtrasClassLoader(ClassLoader cl) { extras.setClassLoader(cl); return this; }
    public android.os.Bundle getExtras() { return extras; }
}
""",

    # ---- android.hardware -------------------------------------------------
    "android/hardware/Sensor.java": """
package android.hardware;
public class Sensor {
    public static final int TYPE_ACCELEROMETER=1, TYPE_STEP_DETECTOR=18, TYPE_STEP_COUNTER=19;
    private final int type;
    public Sensor(int type) { this.type=type; }
    public int getType() { return type; }
}
""",
    "android/hardware/SensorEvent.java": """
package android.hardware;
public class SensorEvent {
    public Sensor sensor; public long timestamp; public int accuracy;
    public final float[] values;
    SensorEvent(int size) { values=new float[size]; }
}
""",
    "android/hardware/SensorEventListener.java": """
package android.hardware;
public interface SensorEventListener {
    void onSensorChanged(SensorEvent event);
    void onAccuracyChanged(Sensor sensor, int accuracy);
}
""",
    # ---- android.location -------------------------------------------------
    "android/util/Log.java": """
package android.util;
public class Log {
    public static int i(String t, String m) { return 0; }
    public static int w(String t, String m) { return 0; }
    public static int e(String t, String m) { return 0; }
    public static int e(String t, String m, Throwable x) { return 0; }
    public static int d(String t, String m) { return 0; }
}
""",
    "android/location/Location.java": """
package android.location;
public class Location {
    double lat, lon; float speed; float accuracy; double altitude; long time; long elapsed;
    String provider="gps";
    public Location(String provider) { this.provider=provider; }
    public Location(Location o) { lat=o.lat; lon=o.lon; speed=o.speed; accuracy=o.accuracy;
        altitude=o.altitude; time=o.time; elapsed=o.elapsed; provider=o.provider; }
    public String getProvider() { return provider; }
    public void setLatitude(double v) { lat=v; } public double getLatitude() { return lat; }
    public void setLongitude(double v) { lon=v; } public double getLongitude() { return lon; }
    public void setSpeed(float v) { speed=v; } public float getSpeed() { return speed; }
    public void setAccuracy(float v) { accuracy=v; } public float getAccuracy() { return accuracy; }
    public void setAltitude(double v) { altitude=v; } public double getAltitude() { return altitude; }
    public void setTime(long v) { time=v; } public long getTime() { return time; }
    public void setElapsedRealtimeNanos(long v) { elapsed=v; }
    public void setExtras(android.os.Bundle b) {}
}
""",
    "android/location/LocationListener.java": """
package android.location;
/** 与设备接口一致：旧 API 的三个回调也要存在（默认方法，实现类可不覆写） */
public interface LocationListener {
    void onLocationChanged(Location l);
    default void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
    default void onProviderEnabled(String provider) {}
    default void onProviderDisabled(String provider) {}
}
""",
    "android/location/LocationManager.java": """
package android.location;
public class LocationManager {
    public int removed;
    public void removeUpdates(LocationListener l) { removed++; }
    public void removeUpdates(android.app.PendingIntent p) { removed++; }
}
""",
    "android/os/IRemoteCallback.java": """
package android.os;
/** 系统在 onLocationChanged(List, IRemoteCallback) 里等待的完成回调（AOSP IRemoteCallback.aidl） */
public interface IRemoteCallback { void sendResult(Bundle data); }
""",
    "android/os/RemoteCallbackProxyFixture.java": """
package android.os;
/**
 * 设备 framework 里的真实可见性形态（见第三轮验收证据）：
 *   android.os.IRemoteCallback            public
 *   android.os.IRemoteCallback$Stub$Proxy 非 public，sendResult 为 public
 * 非公开实现类 + 公开接口，是跨包反射 invoke 的反例来源。
 * 本夹具把它们放在 android.os 包（与生产代码不同包），
 * 因此任何「按实现类取 Method 再 invoke」的写法都会在这里失败。
 */
public final class RemoteCallbackProxyFixture {
    public static int completed;
    public static int attempts;
    public static void reset() { completed = 0; attempts = 0; }

    /** 正常代理形态：非公开静态内部类实现公开接口 */
    public static IRemoteCallback create() { return new Proxy(); }
    /** sendResult 抛异常的实现（同样非公开） */
    public static IRemoteCallback createFailing() { return new Failing(); }

    private RemoteCallbackProxyFixture() {}

    private static final class Proxy implements IRemoteCallback {
        public void sendResult(Bundle data) { attempts++; completed++; }
    }

    private static final class Failing implements IRemoteCallback {
        public void sendResult(Bundle data) {
            attempts++;
            throw new IllegalStateException("fixture sendResult failure");
        }
    }
}
""",
    "android/location/LocationManager$LocationListenerTransport.java": """
package android.location;
import java.util.List;
/**
 * AOSP(Android 11~16) 内部回调载体 android.location.LocationManager$LocationListenerTransport。
 * 实测/源码确认的投递签名：onLocationChanged(List<Location>, IRemoteCallback)；
 * Android 12 起该内部类同时保留了单 Location 的重载，这里一并建模，
 * 用于验证「按类名候选 + 对 onLocationChanged 所有重载挂钩」的合同。
 */
public class LocationManager$LocationListenerTransport {
    public int delivered;
    public int completed;
    public void onLocationChanged(List<Location> locations,
            android.os.IRemoteCallback onComplete) {
        delivered++;
        if (onComplete != null) { completed++; onComplete.sendResult(null); }
    }
    public void onLocationChanged(Location location) { delivered++; }
}
""",
    "android/location/LocationManager$ListenerTransport.java": """
package android.location;
/**
 * Android 10 及更早的旧命名 LocationManager$ListenerTransport（单 Location 签名）。
 * 设备（API 36）上不存在；保留该替身用于验证候选类名的回退路径。
 */
public class LocationManager$ListenerTransport {
    public int delivered;
    public void onLocationChanged(Location location) { delivered++; }
}
""",
    "android/location/GnssStatus.java": """
package android.location;
import java.util.*;
public class GnssStatus {
    public static final int CONSTELLATION_UNKNOWN=0, CONSTELLATION_GPS=1;
    /** 每颗星记录 [constellationType, svid, cn0, elev, azim, carrierHz, basebandCn0] */
    public final List<float[]> satellites;
    GnssStatus(List<float[]> s) { satellites=s; }
    public int getSatelliteCount() { return satellites.size(); }
    public boolean usedInFix(int i) { return true; }
    public static class Callback { public void onSatelliteStatusChanged(GnssStatus status) {} }
    public static class Builder {
        final List<float[]> satellites=new ArrayList<float[]>();
        /** Android 的 12 参公共签名（API 30~36） */
        public Builder addSatellite(int constellationType, int svid, float cn0, float elev, float azim,
            boolean ephemeris, boolean almanac, boolean used, boolean hasCarrier, float carrierHz,
            boolean hasBaseband, float basebandCn0) {
            satellites.add(new float[]{constellationType, svid, cn0, elev, azim, carrierHz, basebandCn0});
            return this;
        }
        /** API 26 之前的 5 参签名 */
        public Builder addSatellite(int svid, float cn0, float elev, float azim, float carrierHz) {
            satellites.add(new float[]{CONSTELLATION_GPS, svid, cn0, elev, azim, carrierHz, cn0});
            return this;
        }
        /** API 26 的 6 参签名 */
        public Builder addSatellite(int svid, float cn0, float elev, float azim, float carrierHz,
                                    int constellationType) {
            satellites.add(new float[]{constellationType, svid, cn0, elev, azim, carrierHz, cn0});
            return this;
        }
        public GnssStatus build() { return new GnssStatus(satellites); }
    }
}
""",
    # ---- android.view / widget -------------------------------------------
    "android/view/View.java": "package android.view; public class View {}",
    "android/view/ViewGroup.java": """
package android.view;
public class ViewGroup extends View {
    public int getChildCount() { return 0; } public View getChildAt(int i) { return null; }
}
""",
    "android/view/Window.java": """
package android.view; public class Window { public View peekDecorView() { return null; } }
""",
    "android/widget/TextView.java": """
package android.widget;
public class TextView extends android.view.View {
    private CharSequence text="";
    public void setText(CharSequence t) { text=t; }
    public CharSequence getText() { return text; }
}
""",
    "android/widget/PopupWindow.java": """
package android.widget;
public class PopupWindow {
    boolean showing;
    public android.view.View getContentView() { return null; }
    public void showAtLocation(android.view.View parent, int gravity, int x, int y) { showing=true; }
    public void showAsDropDown(android.view.View anchor) { showing=true; }
    public void showAsDropDown(android.view.View anchor, int x, int y) { showing=true; }
    public void dismiss() { showing=false; }
    public boolean isShowing() { return showing; }
}
""",
    "android/widget/Toast.java": """
package android.widget;
public class Toast {
    public static Toast makeText(android.content.Context ctx, CharSequence text, int duration) {
        return new Toast();
    }
    public void show() {}
}
""",
    # ---- org.json（只覆盖测试用到的合法数字坐标）-------------------------
    "org/json/JSONObject.java": """
package org.json;
import java.util.*;
public class JSONObject {
    final Map<String,Double> values=new HashMap<String,Double>();
    final Map<String,JSONObject> objects=new HashMap<String,JSONObject>();
    public double getDouble(String key) {
        if (!values.containsKey(key)) throw new IllegalArgumentException(key);
        return values.get(key).doubleValue();
    }
    public double optDouble(String key, double fallback) {
        Double v=values.get(key); return v==null ? fallback : v.doubleValue();
    }
    public JSONObject put(String key, double value) { values.put(key, Double.valueOf(value)); return this; }
    public JSONObject put(String key, JSONObject value) { objects.put(key, value); return this; }
    public JSONObject optJSONObject(String key) { return objects.get(key); }
}
""",
    "org/json/JSONArray.java": r"""
package org.json;
import java.util.*;
import java.util.regex.*;
public class JSONArray {
    final List<JSONObject> values=new ArrayList<JSONObject>();
    public JSONArray(String json) {
        // 只接受 [{ "lat":..,"lng":.. }, ...] 这种简单数字数组；
        // 畸形 JSON 的行为不在这些测试的结论范围内。
        if (json==null) throw new IllegalArgumentException("null");
        if (!json.startsWith("[") || !json.endsWith("]")) throw new IllegalArgumentException(json);
        Matcher objects=Pattern.compile("\\{([^{}]*)\\}").matcher(json);
        while (objects.find()) {
            JSONObject o=new JSONObject();
            Matcher fields=Pattern.compile("\"(lat|lng)\"\\s*:\\s*([-0-9.]+)").matcher(objects.group(1));
            while (fields.find()) o.values.put(fields.group(1), Double.valueOf(Double.parseDouble(fields.group(2))));
            values.add(o);
        }
    }
    public int length() { return values.size(); }
    public JSONObject getJSONObject(int i) { return values.get(i); }
}
""",
    # ---- 生产代码里被调用、但与本轮缺陷无关的类 ---------------------------
    "com/mokrun/app/xposed/SDKLocationHook.java": """
package com.mokrun.app.xposed; public class SDKLocationHook { public static void dumpStats() {} }
""",
}


def boundary_sources():
    """返回替身源码的副本，调用方可以按需覆盖个别文件。"""
    return dict(SOURCES)


def _write_tree(base, files):
    paths = []
    for name, source in files.items():
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        paths.append(str(path))
    return paths


def build(jdk, harness_files, production, overrides=None):
    """编译替身 + 生产源码 + harness，返回 classpath 根目录。

    harness_files: {相对路径: 源码}
    production:    生产源码类名列表（app/src/main/java/com/mokrun/app/xposed/ 下）
    overrides:     可选，{相对路径: 源码}，按需替换/追加边界替身
                   （例如只给需要 CommandReceiver 的用例补 Context.registerReceiver）
    顺序：替身与生产源码一起编译，再单独编译 harness（harness 依赖生产类）。
    """
    output = ROOT / "build"
    output.mkdir(exist_ok=True)
    base = Path(tempfile.mkdtemp(prefix="mokr-jvm-", dir=str(output)))
    javac = str(jdk / "bin" / "javac.exe")

    sources = boundary_sources()
    if overrides:
        sources.update(overrides)
    fakes = _write_tree(base, sources)
    prod = []
    for name in production:
        if "." in name:
            # 带点的名字按 FQCN 相对 com/mokrun/app 解析（例如 data.RouteDraft）
            prod.append(str(PRODUCTION.parents[3] / "com" / "mokrun" / "app" / (name.replace(".", "/") + ".java")))
        else:
            prod.append(str(PRODUCTION / (name + ".java")))
    subprocess.run([javac, "--release", "8", "-nowarn", "-encoding", "UTF-8",
                    "-d", str(base), *fakes, *prod], check=True)

    harness = _write_tree(base, harness_files)
    subprocess.run([javac, "--release", "8", "-nowarn", "-encoding", "UTF-8",
                    "-cp", str(base), "-d", str(base), *harness], check=True)
    return base
