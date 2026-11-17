"""Audit actual Java sources against behavioral invariants, without using a phone.

Run: python tests/audit_module.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
Exit 1 means at least one production invariant failed; full output goes to build/audit-results.txt.
Android/Xposed boundaries use small fakes, not a copy of module business logic.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

from test_sensor_hook import SOURCES as SENSOR_FAKES

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {key: value for key, value in SENSOR_FAKES.items()
           if key.startswith(("android/hardware/", "de/robv/android/xposed/"))}
SOURCES["de/robv/android/xposed/XC_MethodHook.java"] = """
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
"""
SOURCES["de/robv/android/xposed/XposedBridge.java"] = """
package de.robv.android.xposed;
import java.util.*;
public class XposedBridge {
    public static final Map<String, XC_MethodHook> hooks=new HashMap<>();
    public static void log(String msg) { System.out.println("LOG " + msg); }
    public static void hookAllMethods(Class<?> c, String name, XC_MethodHook hook) {
        hooks.put(c.getName()+"."+name, hook);
    }
    public static void invoke(String key, boolean before, Object owner, Object... args) throws Exception {
        XC_MethodHook hook=hooks.get(key);
        if(hook==null) throw new AssertionError("No hook: "+key);
        XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
        p.thisObject=owner; p.args=args;
        if(before) hook.beforeHookedMethod(p); else hook.afterHookedMethod(p);
    }
}
"""
SOURCES["de/robv/android/xposed/XposedHelpers.java"] = """
package de.robv.android.xposed;
public class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader cl) {
        try { return Class.forName(name); } catch(Exception e) { throw new RuntimeException(e); }
    }
    public static Object findAndHookMethod(String cls, ClassLoader cl, String name, Object... args) {
        // Keep the first overload for this small boundary model. LocationProbe's
        // first requestLocationUpdates hook is the four-argument listener form.
        XposedBridge.hooks.putIfAbsent(cls+"."+name, (XC_MethodHook)args[args.length-1]); return null;
    }
    public static Object getObjectField(Object obj, String name) {
        for(Class<?> c=obj.getClass(); c!=null; c=c.getSuperclass()) {
            try { java.lang.reflect.Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
            catch(NoSuchFieldException e) { continue; }
            catch(Exception e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("Missing field: "+name);
    }
}
"""
SOURCES.update({
    "android/os/Looper.java": """
package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }
""",
    "android/os/Handler.java": """
package android.os;
import java.util.*;
public class Handler {
    public final List<Runnable> tasks=new ArrayList<>();
    public Handler() {} public Handler(Looper looper) {}
    public boolean post(Runnable r) { tasks.add(r); return true; }
    public boolean postDelayed(Runnable r, long ms) { tasks.add(r); return true; }
    public void removeCallbacksAndMessages(Object token) { tasks.clear(); }
    public void runPending() { List<Runnable> copy=new ArrayList<>(tasks); tasks.clear(); for(Runnable r:copy) r.run(); }
}
""",
    "android/os/Bundle.java": """
package android.os;
public class Bundle { public void putInt(String key, int value) {} }
""",
    "android/os/CancellationSignal.java": "package android.os; public class CancellationSignal {}",
    "android/app/PendingIntent.java": "package android.app; public class PendingIntent {}",
    "android/net/Uri.java": """
package android.net; public class Uri { public static Uri parse(String s) { return new Uri(); } }
""",
    "android/database/Cursor.java": """
package android.database;
public class Cursor {
    public Object[] row;
    public Cursor(Object... row) { this.row=row; }
    public boolean moveToFirst() { return true; }
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
    public Cursor row;
    public Cursor query(Uri uri, String[] p, String s, String[] a, String sort) { return row; }
}
""",
    "android/content/Context.java": """
package android.content;
public class Context {
    public final ContentResolver resolver=new ContentResolver();
    public Context getApplicationContext() { return this; }
    public ContentResolver getContentResolver() { return resolver; }
}
""",
    "android/content/Intent.java": """
package android.content;
import java.util.*;
public class Intent {
    final Map<String,Object> extras=new HashMap<>();
    public Intent putExtra(String key, String value) { extras.put(key,value); return this; }
    public Intent putExtra(String key, int value) { extras.put(key,value); return this; }
    public String getStringExtra(String key) { return (String)extras.get(key); }
    public int getIntExtra(String key, int fallback) { Object v=extras.get(key); return v==null?fallback:(Integer)v; }
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
    "android/app/Dialog.java": """
package android.app;
public class Dialog { public android.view.Window getWindow() { return null; } }
""",
    "android/widget/TextView.java": """
package android.widget;
public class TextView extends android.view.View { public CharSequence getText() { return ""; } }
""",
    "android/widget/PopupWindow.java": """
package android.widget;
public class PopupWindow { public android.view.View getContentView() { return null; } }
""",
    "android/location/Location.java": """
package android.location;
public class Location {
    double lat, lon; float speed;
    public Location(String provider) {}
    public void setLatitude(double v) { lat=v; } public double getLatitude() { return lat; }
    public void setLongitude(double v) { lon=v; } public double getLongitude() { return lon; }
    public void setSpeed(float v) { speed=v; } public float getSpeed() { return speed; }
    public void setAccuracy(float v) {} public void setAltitude(double v) {}
    public void setTime(long v) {} public void setElapsedRealtimeNanos(long v) {}
    public void setExtras(android.os.Bundle b) {}
}
""",
    "android/location/LocationListener.java": """
package android.location; public interface LocationListener { void onLocationChanged(Location l); }
""",
    "android/location/LocationManager.java": """
package android.location;
public class LocationManager { public void removeUpdates(LocationListener l) {} }
""",
    "android/location/GnssStatus.java": """
package android.location;
import java.util.*;
public class GnssStatus {
    public static final int CONSTELLATION_GPS=1;
    public final List<float[]> satellites;
    GnssStatus(List<float[]> s) { satellites=s; }
    public int getSatelliteCount() { return satellites.size(); }
    public boolean usedInFix(int i) { return true; }
    public static class Callback { public void onSatelliteStatusChanged(GnssStatus status) {} }
    public static class Builder {
        final List<float[]> satellites=new ArrayList<>();
        // Android's public 12-parameter signature, API 30-36.
        public Builder addSatellite(int constellationType, int svid, float cn0, float elev, float azim,
            boolean ephemeris, boolean almanac, boolean used, boolean hasCarrier, float carrierHz,
            boolean hasBaseband, float basebandCn0) {
            satellites.add(new float[]{constellationType,svid,carrierHz,basebandCn0}); return this;
        }
        public GnssStatus build() { return new GnssStatus(satellites); }
    }
}
""",
    "org/json/JSONObject.java": """
package org.json;
import java.util.*;
public class JSONObject {
    final Map<String,Double> values=new HashMap<>();
    public double getDouble(String key) { if(!values.containsKey(key)) throw new IllegalArgumentException(key); return values.get(key); }
}
""",
    "org/json/JSONArray.java": r"""
package org.json;
import java.util.*;
import java.util.regex.*;
public class JSONArray {
    final List<JSONObject> values=new ArrayList<>();
    public JSONArray(String json) {
        // This boundary fake accepts only the simple valid numeric fixtures below.
        // The audit makes no claims about behavior on malformed JSON.
        if(!json.startsWith("[") || !json.endsWith("]")) throw new IllegalArgumentException(json);
        Matcher objects=Pattern.compile("\\{([^{}]*)\\}").matcher(json);
        while(objects.find()) {
            JSONObject o=new JSONObject();
            Matcher fields=Pattern.compile("\"(lat|lng)\"\\s*:\\s*([-0-9.]+)").matcher(objects.group(1));
            while(fields.find()) o.values.put(fields.group(1),Double.parseDouble(fields.group(2)));
            values.add(o);
        }
    }
    public int length() { return values.size(); }
    public JSONObject getJSONObject(int i) { return values.get(i); }
}
""",
    "com/mokrun/app/xposed/SDKLocationHook.java": """
package com.mokrun.app.xposed; public class SDKLocationHook { public static void dumpStats() {} }
""",
})

HARNESS = r"""
package com.mokrun.app.xposed;
import android.app.*;
import android.content.*;
import android.database.*;
import android.hardware.*;
import android.location.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
public class ModuleAudit {
    static final String ORIGIN="30.0,120.0";
    static final String ROUTE="[{\"lat\":30.001,\"lng\":120.0},{\"lat\":30.001,\"lng\":120.001}]";
    static Field field(String name) throws Exception {
        Field f=MotionEngine.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static void set(String name,Object value) throws Exception { field(name).set(null,value); }
    static Object get(String name) throws Exception { return field(name).get(null); }
    static Object call(String name) throws Exception {
        Method m=MotionEngine.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(null);
    }
    static void check(boolean good,String message) { if(!good) throw new AssertionError(message); }
    static void configure(int mode) { MotionEngine.applyFromIntent("speed=4.8;cadence=3.1",ORIGIN,ROUTE,mode); }
    static MethodHookParam param(Object owner,Object... args) {
        MethodHookParam p=new MethodHookParam(); p.thisObject=owner; p.args=args; return p;
    }
    static class Listener implements SensorEventListener {
        int count; boolean fail;
        public void onSensorChanged(SensorEvent e) { if(fail) throw new IllegalStateException("fixture listener"); count++; }
    }
    static class LocListener implements LocationListener {
        int count; public void onLocationChanged(Location l) { count++; }
    }
    static void register(Listener l,Sensor sensor) { new SensorHook.RegHook().beforeHookedMethod(param(null,l,sensor,0)); }
    public static void main(String[] args) throws Exception {
        String which=args[0];
        if(which.equals("route_fixture")) {
            configure(1);
            check(((double[])get("sLat")).length==3,"valid fixture must load three points");
            check(((double[])get("sLon"))[2]==120.001,"fixture coordinate parsed incorrectly");
        } else if(which.equals("normal_tick")) {
            configure(1); set("sLastTick",System.currentTimeMillis()-1000L); call("tick");
            check(MotionEngine.steps()==3 && (Double)get("sDistTotal")>=4.8,"normal tick fixture failed");
        } else if(which.equals("paused_tick")) {
            configure(2); set("sLastTick",System.currentTimeMillis()-1000L); call("tick");
            check(MotionEngine.steps()==0 && (Double)get("sDistTotal")==0.0,"pause fixture failed");
        } else if(which.equals("stop_during_freeze")) {
            configure(1); MotionEngine.freezeForFace(); MotionEngine.setMode(0);
            MotionEngine.resumeAfterFace();
            check(!MotionEngine.isRunningNow(),"STOP overwritten: mode="+get("sMode"));
        } else if(which.equals("freeze_while_stopped")) {
            configure(0); MotionEngine.freezeForFace(); MotionEngine.resumeAfterFace();
            check(!MotionEngine.isRunningNow(),"idle process unexpectedly started: mode="+get("sMode"));
        } else if(which.equals("provider_overwrites_pause")) {
            Context ctx=new Context();
            ctx.resolver.row=new Cursor(4.8f,3.1f,1.55f,1,ORIGIN,ROUTE,1);
            MotionEngine.configure(ctx); MotionEngine.setMode(2); set("sRunCheckedAt",0L);
            check(!MotionEngine.isRunningNow(),"provider overwrote explicit PAUSE: mode="+get("sMode"));
        } else if(which.equals("clear_route")) {
            configure(1); MotionEngine.applyFromIntent("speed=4.8","","[]",1);
            check(!MotionEngine.hasRoute(),"cleared route retained "+((double[])get("sLat")).length+" old points");
        } else if(which.equals("paused_speed")) {
            configure(2);
            check(MotionEngine.current().getSpeed()==0f,"paused Location speed="+MotionEngine.current().getSpeed());
        } else if(which.equals("run_without_route")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=3.1","","[]",1);
            set("sLastTick",System.currentTimeMillis()-1000L); call("tick");
            check(MotionEngine.steps()==0,"no route but steps="+MotionEngine.steps()+" dist="+get("sDistTotal"));
        } else if(which.equals("route_wrap_jump")) {
            configure(1);
            Method distance=MotionEngine.class.getDeclaredMethod("dist",double.class,double.class,double.class,double.class);
            distance.setAccessible(true);
            double length=(Double)distance.invoke(null,30.001,120.0,30.001,120.001);
            double beforeLat=30.001,beforeLon=120.001-(0.2/length)*0.001;
            set("sSeg",1); set("sSegProgress",length-0.2);
            set("sCurLat",beforeLat); set("sCurLon",beforeLon);
            set("sLastTick",System.currentTimeMillis()-250L); call("tick");
            double jump=(Double)distance.invoke(null,beforeLat,beforeLon,MotionEngine.lat(),MotionEngine.lon());
            check(jump<5.0,"one tick moved "+jump+"m, engine counted "+get("sDistTotal")+"m");
        } else if(which.equals("unregister_other_sensor")) {
            Listener l=new Listener(); register(l,new Sensor(19)); SensorHook.pushStep(1);
            new SensorHook.UnregHook().beforeHookedMethod(param(null,l,new Sensor(1)));
            SensorHook.pushStep(2);
            check(l.count==2,"unregistering accelerometer removed step listener; received="+l.count);
        } else if(which.equals("unregister_one_step_type")) {
            Listener l=new Listener(); Sensor counter=new Sensor(19),detector=new Sensor(18);
            register(l,counter); register(l,detector);
            new SensorHook.UnregHook().beforeHookedMethod(param(null,l,counter));
            SensorHook.pushStep(2);
            check(l.count==1,"remaining detector registration lost; received="+l.count);
        } else if(which.equals("listener_exception")) {
            Listener a=new Listener(),b=new Listener(); register(a,new Sensor(19)); register(b,new Sensor(19));
            Field f=SensorHook.class.getDeclaredField("sListeners"); f.setAccessible(true);
            Map<?,?> listeners=(Map<?,?>)f.get(null);
            Listener first=(Listener)listeners.keySet().iterator().next(); first.fail=true;
            Listener healthy=first==a?b:a;
            SensorHook.pushStep(1);
            check(healthy.count==1,"one throwing listener prevented healthy listener; received="+healthy.count);
        } else if(which.equals("new_intent")) {
            configure(0);
            IntentBridge.install(ModuleAudit.class.getClassLoader());
            Activity activity=new Activity(); activity.setIntent(new Intent());
            Intent incoming=new Intent().putExtra("mokr_cfg","speed=3.5;cadence=2.6")
                .putExtra("mokr_origin",ORIGIN).putExtra("mokr_route",ROUTE).putExtra("mokr_run",1);
            XposedBridge.invoke("android.app.Activity.onNewIntent",false,activity,incoming);
            check(MotionEngine.isRunningNow() && MotionEngine.speedNow()==3.5f,
                "new extras ignored: mode="+get("sMode")+" speed="+MotionEngine.speedNow());
        } else if(which.equals("gnss_payload")) {
            Method build=GnssHook.class.getDeclaredMethod("build"); build.setAccessible(true);
            GnssStatus status=(GnssStatus)build.invoke(null);
            check(status!=null,"GNSS build failed");
            float[] second=status.satellites.get(1);
            System.out.println("OBSERVED satellite2 constellation="+second[0]+" svid="+second[1]
                +" carrierHz="+second[2]+" basebandCn0="+second[3]);
            check(second[0]==1 && second[1]==2 && second[2]>1e9f && second[3]<=63f,"GNSS parameter order/units wrong");
        } else if(which.equals("cadence_infinity")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=99999999999999999999999999999999999999999999999999",ORIGIN,ROUTE,1);
            check(Float.isFinite((Float)get("sCadence")),"cadence accepted Infinity; tick's accumulator cannot decrease");
        } else if(which.equals("cadence_infinity_tick")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=99999999999999999999999999999999999999999999999999",ORIGIN,ROUTE,1);
            check(Float.isInfinite((Float)get("sCadence")),"fixture must overflow to Infinity");
            Thread worker=new Thread(new Runnable() { public void run() {
                try { call("tick"); } catch(Exception e) { throw new RuntimeException(e); }
            }});
            worker.setDaemon(true); worker.start(); worker.join(200L);
            check(!worker.isAlive(),"tick did not return in 200ms; steps="+MotionEngine.steps());
        } else if(which.equals("location_unregister")) {
            configure(1); LocationProbe.install(ModuleAudit.class.getClassLoader());
            LocListener l=new LocListener();
            XposedBridge.invoke("android.location.LocationManager.requestLocationUpdates",true,null,"gps",1000L,0f,l);
            call("emitLocation");
            check(l.count==1,"fixture failed to capture registered location listener");
            // Model framework unregistration: the real removeUpdates method runs normally.
            new LocationManager().removeUpdates(l);
            if(XposedBridge.hooks.containsKey("android.location.LocationManager.removeUpdates"))
                XposedBridge.invoke("android.location.LocationManager.removeUpdates",true,null,l);
            set("sLastTick",System.currentTimeMillis()-250L); call("tick");
            check(l.count==1,"removed location listener still received "+(l.count-1)+" extra synthetic update(s)");
        } else if(which.equals("unrelated_dialog_dismiss")) {
            configure(1); ResourceProbe.install(ModuleAudit.class.getClassLoader());
            MotionEngine.freezeForFace();
            XposedBridge.invoke("android.app.Dialog.dismiss",false,new Dialog());
            check(MotionEngine.faceFrozen(),"dismissing an unrelated dialog released active face freeze");
        } else if(which.equals("log_throttle_skips_freeze")) {
            configure(1);
            Field lastLog=ResourceProbe.class.getDeclaredField("sLastLog"); lastLog.setAccessible(true);
            lastLog.set(null,System.currentTimeMillis());
            Method report=ResourceProbe.class.getDeclaredMethod("report",String.class,String.class);
            report.setAccessible(true); report.invoke(null,"fixture","请进行人脸识别");
            check(MotionEngine.faceFrozen(),"a log within 300ms caused face detection to be skipped");
        } else { throw new IllegalArgumentException(which); }
        System.out.println("PASS "+which);
    }
}
"""

CASES = ["route_fixture", "normal_tick", "paused_tick", "stop_during_freeze", "freeze_while_stopped", "provider_overwrites_pause",
         "clear_route", "paused_speed", "run_without_route", "route_wrap_jump",
         "unregister_other_sensor", "unregister_one_step_type", "listener_exception", "new_intent",
         "gnss_payload", "cadence_infinity", "cadence_infinity_tick", "location_unregister",
         "unrelated_dialog_dismiss", "log_throttle_skips_freeze"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()
    output = ROOT / "build"
    output.mkdir(exist_ok=True)
    results = []
    failures = 0
    with tempfile.TemporaryDirectory(prefix="audit-", dir=output) as temp:
        base = Path(temp)
        SOURCES["com/mokrun/app/xposed/ModuleAudit.java"] = HARNESS
        sources = []
        for name, source in SOURCES.items():
            path = base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            sources.append(str(path))
        for name in ("MotionEngine", "SensorHook", "IntentBridge", "GnssHook", "LocationProbe", "ResourceProbe"):
            sources.append(str(ROOT / f"app/src/main/java/com/mokrun/app/xposed/{name}.java"))
        subprocess.run([str(args.jdk / "bin/javac.exe"), "--release", "8", "-nowarn", "-encoding", "UTF-8",
                        "-d", str(base), *sources], check=True)
        for case in [args.case] if args.case else CASES:
            run = subprocess.run([str(args.jdk / "bin/java.exe"), "-cp", str(base),
                                  "com.mokrun.app.xposed.ModuleAudit", case],
                                 capture_output=True, encoding="utf-8", errors="replace", timeout=10)
            results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
            if run.returncode:
                failures += 1
                reason = next((line.strip() for line in run.stderr.splitlines() if "AssertionError:" in line),
                              run.stderr.strip())
                print(f"FAIL {case}: {reason}")
            else:
                print(f"PASS {case}")
    (output / "audit-results.txt").write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} invariant failure(s). Full output: {output / 'audit-results.txt'}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
