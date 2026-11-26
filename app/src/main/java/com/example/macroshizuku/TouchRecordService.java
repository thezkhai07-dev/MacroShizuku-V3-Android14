package com.example.macroshizuku;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.util.Log;
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
 private static Device touchDevice;
 private static class Device{String path;int minX,maxX,minY,maxY;Device(String p,int a,int b,int c,int d){path=p;minX=a;maxX=b;minY=c;maxY=d;}}
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
  try {
   AccessibilityServiceInfo info=getServiceInfo();
   if(info!=null){
    info.flags|=AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
    setServiceInfo(info);
    Log.i("TouchRecordService","Key filtering requested; flags="+info.flags);
   }
  } catch(Exception e){Log.e("TouchRecordService","Cannot enable key filtering",e);}
 }
 private static Device findTouchDevice(String listing){
  String[] blocks=listing.split("(?=add device\\s+\\d+:)");
  Pattern pp=Pattern.compile("(/dev/input/event\\d+)"),px=Pattern.compile("ABS_MT_POSITION_X.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)"),py=Pattern.compile("ABS_MT_POSITION_Y.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
  for(String b:blocks){Matcher p=pp.matcher(b),x=px.matcher(b),y=py.matcher(b);if(p.find()&&x.find()&&y.find()){String z=b.toLowerCase(Locale.ROOT);if(z.contains("touch")||z.contains("direct"))return new Device(p.group(1),Integer.parseInt(x.group(1)),Integer.parseInt(x.group(2)),Integer.parseInt(y.group(1)),Integer.parseInt(y.group(2)));}}
  return null;
 }
 public static boolean beginRecording(){
  TouchRecordService s=instance;if(s==null||!ShizukuShell.ready())return false;
  stopEventProcess();synchronized(actions){actions.clear();}pendingCapture=false;captureError="";lastActionTime=0;
  try{
   String listing=ShizukuShell.run("getevent","-lp");touchDevice=findTouchDevice(listing);
   if(touchDevice==null){captureError=listing.contains("Permission denied")?"Shizuku không có quyền đọc thiết bị cảm ứng.":"Không tìm thấy thiết bị cảm ứng hoặc getevent không được hỗ trợ.";return false;}
   DisplayMetrics dm=new DisplayMetrics();((WindowManager)s.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);screenW=dm.widthPixels;screenH=dm.heightPixels;
   eventProcess=ShizukuShell.start("getevent","-lt",touchDevice.path);final Object proc=eventProcess;final Device dev=touchDevice;
   recording=true;captureThread=new Thread(()->readEvents(proc,dev),"touch-recorder");captureThread.start();return true;
  }catch(Exception e){captureError="Không khởi động được bộ đọc cảm ứng: "+e.getMessage();stopEventProcess();return false;}
 }
 private static long timeMs(String line){Matcher m=Pattern.compile("\\[\\s*(\\d+)\\.(\\d+)\\]").matcher(line);if(!m.find())return System.nanoTime()/1000000L;return Long.parseLong(m.group(1))*1000L+Long.parseLong((m.group(2)+"000").substring(0,3));}
 private static int hex(String s){try{return (int)Long.parseLong(s,16);}catch(Exception e){return 0;}}
 private static int scale(int v,int min,int max,int pixels){if(max<=min||pixels<=1)return Math.max(0,v);return Math.max(0,Math.min(pixels-1,Math.round((v-min)*(pixels-1f)/(max-min))));}
 private static void readEvents(Object proc,Device d){
  boolean down=false,tracking=false;int rx=0,ry=0,x1=0,y1=0,x2=0,y2=0;long start=0;
  try(BufferedReader r=new BufferedReader(new InputStreamReader(ShizukuShell.stdout(proc),"UTF-8"))){String line;while(recording&&(line=r.readLine())!=null){
   String[] a=line.trim().split("\\s+");if(a.length<4)continue;String code=a[a.length-2],val=a[a.length-1];long now=timeMs(line);
   if(code.equals("ABS_MT_POSITION_X"))rx=hex(val);else if(code.equals("ABS_MT_POSITION_Y"))ry=hex(val);
   else if(code.equals("ABS_MT_TRACKING_ID")){tracking=true;int id=hex(val);if(id!=-1&&!down){down=true;start=now;x1=x2=scale(rx,d.minX,d.maxX,screenW);y1=y2=scale(ry,d.minY,d.maxY,screenH);}else if(id==-1&&down){x2=scale(rx,d.minX,d.maxX,screenW);y2=scale(ry,d.minY,d.maxY,screenH);save(x1,y1,x2,y2,start,now);down=false;}}
   else if(code.equals("BTN_TOUCH")&&!tracking){int state=hex(val);if(state!=0&&!down){down=true;start=now;x1=x2=scale(rx,d.minX,d.maxX,screenW);y1=y2=scale(ry,d.minY,d.maxY,screenH);}else if(state==0&&down){x2=scale(rx,d.minX,d.maxX,screenW);y2=scale(ry,d.minY,d.maxY,screenH);save(x1,y1,x2,y2,start,now);down=false;}}
   if(down&&(code.equals("ABS_MT_POSITION_X")||code.equals("ABS_MT_POSITION_Y"))){x2=scale(rx,d.minX,d.maxX,screenW);y2=scale(ry,d.minY,d.maxY,screenH);}
  }}catch(Exception e){if(recording)captureError=e.getMessage()==null?"Lỗi đọc cảm ứng":e.getMessage();}
  finally{if(recording){recording=false;pendingCapture=!snapshot().isEmpty();}}
 }
 private static void save(int x1,int y1,int x2,int y2,long start,long end){long delay=lastActionTime==0?0:Math.max(0,start-lastActionTime),duration=Math.max(1,end-start);synchronized(actions){if(Math.hypot(x2-x1,y2-y1)<12)actions.add(new MacroStore.Action("tap",x1,y1,x1,y1,delay,duration));else actions.add(new MacroStore.Action("swipe",x1,y1,x2,y2,delay,Math.max(60,duration)));}lastActionTime=end;}
 private static void stopEventProcess(){recording=false;Object p=eventProcess;eventProcess=null;ShizukuShell.destroy(p);}
 public static List<MacroStore.Action> endRecording(){recording=false;stopEventProcess();Thread t=captureThread;if(t!=null)try{t.join(300);}catch(InterruptedException ignored){}List<MacroStore.Action> c=snapshot();pendingCapture=!c.isEmpty();return c;}
 @Override public void onDestroy(){endRecording();if(instance==this)instance=null;super.onDestroy();}
 @Override public boolean onKeyEvent(KeyEvent e){
  int c=e.getKeyCode();
  if(c!=KeyEvent.KEYCODE_VOLUME_UP&&c!=KeyEvent.KEYCODE_VOLUME_DOWN)return false;
  SharedPreferences p=getSharedPreferences("hotkeys",MODE_PRIVATE);
  boolean enabled=p.getBoolean("enabled",false);
  Log.i("TouchRecordService","Volume key received: code="+c+", action="+e.getAction()+", repeat="+e.getRepeatCount()+", recording="+recording+", hotkeys="+enabled);
  if(recording){
   if(c==KeyEvent.KEYCODE_VOLUME_DOWN&&e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)endRecording();
   return true;
  }
  if(!enabled)return false;
  if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){
   if(c==KeyEvent.KEYCODE_VOLUME_UP)playSelectedOnce();
   else Player.stopAll();
  }
  return true;
 }
 private void playSelectedOnce(){String id=getSharedPreferences("hotkeys",MODE_PRIVATE).getString("selected_id","");if(id.isEmpty())return;MacroStore.Macro m=MacroStore.find(this,id);if(m==null||Player.isRunning(id))return;Player.playOnce(this,m);}
 @Override public void onAccessibilityEvent(AccessibilityEvent e){}
 @Override public void onInterrupt(){}
}