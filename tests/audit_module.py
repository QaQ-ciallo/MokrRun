"""审计场景回归：编译并运行仓库真实的生产 Java 文件，逐项校验行为约束。

Run: python tests/audit_module.py --jdk C:/mokrtools/jdk/jdk-21.0.13+9
Exit 1 means at least one production invariant failed; full output goes to build/audit-results.txt.

每个场景独立 JVM。Android/Xposed 边界使用 tests/jvm_fakes.py 的替身
（Handler 虚拟时钟、按签名登记的 hook 注册表），替身里没有模块业务逻辑。
"""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))

import jvm_fakes  # noqa: E402

PRODUCTION = ["MotionEngine", "SensorHook", "IntentBridge", "GnssHook", "LocationProbe",
              "ResourceProbe"]

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
        public void onAccuracyChanged(Sensor sensor,int accuracy) {}
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
                +" carrierHz="+second[5]+" basebandCn0="+second[6]);
            check(second[0]==1 && second[1]==2 && second[5]>1e9f && second[6]<=63f,"GNSS parameter order/units wrong");
        } else if(which.equals("cadence_infinity")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=99999999999999999999999999999999999999999999999999",ORIGIN,ROUTE,1);
            check(Float.isFinite((Float)get("sCadence")),"cadence accepted Infinity; tick's accumulator cannot decrease");
        } else if(which.equals("cadence_infinity_tick")) {
            MotionEngine.applyFromIntent("speed=4.8;cadence=99999999999999999999999999999999999999999999999999",ORIGIN,ROUTE,1);
            check(Float.isFinite((Float)get("sCadence")),"fixture must be sanitized before ticking");
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

CASES = ["route_fixture", "normal_tick", "paused_tick", "stop_during_freeze", "freeze_while_stopped",
         "provider_overwrites_pause", "clear_route", "paused_speed", "run_without_route",
         "route_wrap_jump", "unregister_other_sensor", "unregister_one_step_type",
         "listener_exception", "new_intent", "gnss_payload", "cadence_infinity",
         "cadence_infinity_tick", "location_unregister", "unrelated_dialog_dismiss",
         "log_throttle_skips_freeze"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--case", choices=CASES)
    args = parser.parse_args()

    base = jvm_fakes.build(args.jdk, {"com/mokrun/app/xposed/ModuleAudit.java": HARNESS},
                           PRODUCTION)
    java = str(args.jdk / "bin" / "java.exe")
    output = jvm_fakes.ROOT / "build" / "audit-results.txt"

    results, failures = [], 0
    for case in [args.case] if args.case else CASES:
        run = subprocess.run([java, "-cp", str(base), "com.mokrun.app.xposed.ModuleAudit", case],
                             capture_output=True, encoding="utf-8", errors="replace", timeout=60)
        results.append(f"=== {case} (exit {run.returncode}) ===\n{run.stdout}{run.stderr}")
        if run.returncode:
            failures += 1
            reason = next((line.strip() for line in (run.stdout + run.stderr).splitlines()
                           if "AssertionError:" in line or "Exception" in line), "see log")
            print(f"FAIL {case}: {reason}")
        else:
            print(f"PASS {case}")

    output.write_text("\n".join(results), encoding="utf-8")
    print(f"{failures} invariant failure(s). Full output: {output}")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
