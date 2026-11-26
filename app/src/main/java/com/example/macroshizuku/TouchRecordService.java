package com.example.macroshizuku;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import android.content.SharedPreferences;

/** Records read-only kernel touch events through Shizuku; touches continue to reach apps. */
public class TouchRecordService extends AccessibilityService {
 public static volatile boolean recording=false;
 private static volatile TouchRecordService instance;
 private static volatile boolean pendingCapture=false;
 private static final List<MacroStore.Action> actions=new ArrayList<>();
 private static Object eventProcess;
 private static Thread captureThread;
 private static volatile String captureError="";
 private static long lastActionTime=0;
 private static int screenW=0,screenH=0;
 private volatile Object hotkeyProcess;
 private volatile Thread hotkeyThread;
 private static volatile long lastHotkeyDispatchMs=0;
 private static volatile int lastHotkeyDispatchCode=0;
 private long downTime=0,lastActionTime=0; private float downX,downY; private boolean down=false;
 public static void clear(){synchronized(actions){actions.clear();}}
 public static List<MacroStore.Action> snapshot(){synchronized(actions){return new ArrayList<>(actions);}}
 public static boolean isConnected(){return instance!=null;}
 public static boolean hasPendingCapture(){return pendingCapture;}
 public static String getCaptureError(){return captureError;}
 public static void clearPendingCapture(){pendingCapture=false;synchronized(actions){actions.clear();}}
 @Override public void onServiceConnected(){
  super.onServiceConnected();
  instance=this;
  captureError="";
  // Keep the service configuration static. Vivo ROMs may restart or drop
  // accessibility services when their runtime configuration is rewritten.
  // Key filtering is declared in XML. Also monitor input events through Shizuku
  // as a fallback because some vendor ROMs do not forward volume keys to
  // AccessibilityService.onKeyEvent(). This reader is passive and never injects touch.
  startHotkeyMonitor();
 }
 @Override public boolean onUnbind(android.content.Intent intent){
  android.util.Log.w("TouchRecordService","Accessibility service unbound by system");
  endRecording();
  stopHotkeyMonitor();
  if(instance==this)instance=null;
  return super.onUnbind(intent);
 }
 public static boolean beginRecording(){
  TouchRecordService s=instance;
  if(s==null||Build.VERSION.SDK_INT<34)return false;
  synchronized(actions){actions.clear();}
  pendingCapture=false;
  s.lastActionTime=0;s.down=false;
  try{
   AccessibilityServiceInfo info=s.getServiceInfo();
   info.setMotionEventSources(android.view.InputDevice.SOURCE_TOUCHSCREEN);
   s.setServiceInfo(info);
   recording=true;
   android.util.Log.i("TouchRecordService","Legacy Accessibility MotionEvent recording started; touchscreen events are captured exclusively.");
   return true;
  }catch(Exception e){
   recording=false;
   captureError="Không bật được chế độ ghi cảm ứng: "+e.getMessage();
   android.util.Log.e("TouchRecordService","Unable to enable touchscreen capture",e);
   return false;
  }
 }
 public static List<MacroStore.Action> endRecording(){
  recording=false;
  TouchRecordService s=instance;
  if(s!=null&&Build.VERSION.SDK_INT>=34){
   try{
    AccessibilityServiceInfo info=s.getServiceInfo();
    info.setMotionEventSources(0);
    s.setServiceInfo(info);
    s.down=false;
   }catch(Exception e){android.util.Log.e("TouchRecordService","Unable to release touchscreen capture",e);}
  }
  List<MacroStore.Action> copy=snapshot();
  pendingCapture=!copy.isEmpty();
  return copy;
 }
 private void recordMotion(MotionEvent e){
  if(!recording||Build.VERSION.SDK_INT<34)return;
  int action=e.getActionMasked();long now=e.getEventTime();
  if(action==MotionEvent.ACTION_DOWN){
   downX=e.getX();downY=e.getY();down=true;downTime=now;
  }else if(down&&action==MotionEvent.ACTION_UP){
   float x=e.getX(),y=e.getY();
   long delay=lastActionTime==0?0:Math.max(0,downTime-lastActionTime);
   long duration=Math.max(1,now-downTime);
   synchronized(actions){
    if(Math.hypot(x-downX,y-downY)<12)actions.add(new MacroStore.Action("tap",downX,downY,downX,downY,delay,duration));
    else actions.add(new MacroStore.Action("swipe",downX,downY,x,y,delay,Math.max(60,duration)));
   }
   down=false;lastActionTime=now;
  }else if(action==MotionEvent.ACTION_CANCEL){down=false;}
 }
 @Override public void onDestroy(){endRecording();stopHotkeyMonitor();if(instance==this)instance=null;super.onDestroy();}
 private void startHotkeyMonitor(){
  if(hotkeyThread!=null&&hotkeyThread.isAlive())return;
  hotkeyThread=new Thread(()->{
   while(instance==this&&!Thread.currentThread().isInterrupted()){
    Object proc=null;
    try{
     SharedPreferences prefs=getSharedPreferences("hotkeys",MODE_PRIVATE);
     if(!prefs.getBoolean("enabled",false)||!ShizukuShell.ready()){
      Thread.sleep(1000);
      continue;
     }
     // getevent -lt reads Linux input events without consuming or injecting them.
     proc=ShizukuShell.start("getevent","-lt");
     hotkeyProcess=proc;
     try(BufferedReader reader=new BufferedReader(new InputStreamReader(ShizukuShell.stdout(proc),"UTF-8"))){
      String line;
      while(instance==this&&!Thread.currentThread().isInterrupted()&&(line=reader.readLine())!=null){
       if(!line.contains("EV_KEY"))continue;
       if(line.contains("KEY_VOLUMEUP")&&line.trim().endsWith("DOWN"))dispatchVolumeHotkey(KeyEvent.KEYCODE_VOLUME_UP,"Shizuku");
       else if(line.contains("KEY_VOLUMEDOWN")&&line.trim().endsWith("DOWN"))dispatchVolumeHotkey(KeyEvent.KEYCODE_VOLUME_DOWN,"Shizuku");
      }
     }
    }catch(InterruptedException e){Thread.currentThread().interrupt();}
    catch(Exception e){android.util.Log.w("TouchRecordService","Shizuku volume monitor failed: "+e.getMessage());}
    finally{if(hotkeyProcess==proc)hotkeyProcess=null;ShizukuShell.destroy(proc);}
    if(instance==this&&!Thread.currentThread().isInterrupted())try{Thread.sleep(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   }
  },"volume-hotkey-monitor");
  hotkeyThread.setDaemon(true);
  hotkeyThread.start();
 }
 private void stopHotkeyMonitor(){
  Thread t=hotkeyThread;hotkeyThread=null;
  Object p=hotkeyProcess;hotkeyProcess=null;
  ShizukuShell.destroy(p);
  if(t!=null)t.interrupt();
 }
 private void dispatchVolumeHotkey(int code,String source){
  long now=SystemClock.elapsedRealtime();
  // Accessibility and getevent can report the same physical press. Debounce it.
  synchronized(TouchRecordService.class){
   if(code==lastHotkeyDispatchCode&&now-lastHotkeyDispatchMs<350)return;
   lastHotkeyDispatchCode=code;lastHotkeyDispatchMs=now;
  }
  SharedPreferences prefs=getSharedPreferences("hotkeys",MODE_PRIVATE);
  boolean enabled=prefs.getBoolean("enabled",false);
  android.util.Log.i("TouchRecordService","Volume hotkey: code="+code+", source="+source+", recording="+recording+", enabled="+enabled);
  if(recording){
   if(code==KeyEvent.KEYCODE_VOLUME_DOWN)endRecording();
   return;
  }
  if(!enabled)return;
  if(code==KeyEvent.KEYCODE_VOLUME_UP)playSelectedOnce();
  else if(code==KeyEvent.KEYCODE_VOLUME_DOWN)Player.stopAll();
 }
 @Override public boolean onKeyEvent(KeyEvent e){
  int code=e.getKeyCode();
  if(code!=KeyEvent.KEYCODE_VOLUME_UP&&code!=KeyEvent.KEYCODE_VOLUME_DOWN)return false;
  if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)dispatchVolumeHotkey(code,"Accessibility");
  SharedPreferences prefs=getSharedPreferences("hotkeys",MODE_PRIVATE);
  return recording||prefs.getBoolean("enabled",false);
 }
 private void playSelectedOnce(){
  String id=getSharedPreferences("hotkeys",MODE_PRIVATE).getString("selected_id","");
  if(id.isEmpty()){android.util.Log.w("TouchRecordService","Volume Up ignored: no selected macro id");return;}
  MacroStore.Macro m=MacroStore.find(this,id);
  if(m==null){android.util.Log.w("TouchRecordService","Volume Up ignored: selected macro not found: "+id);return;}
  if(Player.isRunning(id))return;
  Player.playOnce(this,m);
 }
 @Override public void onMotionEvent(MotionEvent e){recordMotion(e);}
 @Override public void onAccessibilityEvent(AccessibilityEvent e){}
 @Override public void onInterrupt(){}
}