package com.example.macroshizuku;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.os.Build;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Accessibility service for touch recording and global volume hotkeys. */
public class TouchRecordService extends AccessibilityService {
    public static volatile boolean recording=false;
    private static volatile TouchRecordService instance;
    private static volatile boolean pendingCapture=false;
    private static final List<MacroStore.Action> actions=new ArrayList<>();
    private long downTime=0,lastActionTime=0;
    private float downX,downY;
    private boolean down=false;

    public static void clear(){synchronized(actions){actions.clear();}}
    public static List<MacroStore.Action> snapshot(){synchronized(actions){return new ArrayList<>(actions);}}

    @Override public void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        AccessibilityServiceInfo i=getServiceInfo();
        i.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        if(Build.VERSION.SDK_INT>=34) i.setMotionEventSources(0);
        setServiceInfo(i);
    }

    /** Enable touch capture only while the user is explicitly recording. */
    public static boolean isConnected(){return instance!=null;}
    public static boolean hasPendingCapture(){return pendingCapture;}
    public static void clearPendingCapture(){pendingCapture=false; synchronized(actions){actions.clear();}}

    public static boolean beginRecording(){
        TouchRecordService s=instance;
        if(s==null || Build.VERSION.SDK_INT<34) return false;
        synchronized(actions){actions.clear();}
        pendingCapture=false;
        s.lastActionTime=0;
        AccessibilityServiceInfo info=s.getServiceInfo();
        info.setMotionEventSources(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        s.setServiceInfo(info);
        recording=true;
        return true;
    }

    public static List<MacroStore.Action> endRecording(){
        recording=false;
        TouchRecordService s=instance;
        if(s!=null && Build.VERSION.SDK_INT>=34){
            AccessibilityServiceInfo info=s.getServiceInfo();
            info.setMotionEventSources(0);
            s.setServiceInfo(info);
        }
        List<MacroStore.Action> copy=snapshot();
        pendingCapture=!copy.isEmpty();
        return copy;
    }

    @Override public void onDestroy(){
        recording=false;
        if(Build.VERSION.SDK_INT>=34){
            try{AccessibilityServiceInfo info=getServiceInfo();info.setMotionEventSources(0);setServiceInfo(info);}catch(Exception ignored){}
        }
        if(instance==this)instance=null;
        super.onDestroy();
    }

    @Override public boolean onKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if(code!=KeyEvent.KEYCODE_VOLUME_UP && code!=KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(recording){
            if(code==KeyEvent.KEYCODE_VOLUME_DOWN && event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) endRecording();
            return true;
        }

        SharedPreferences p=getSharedPreferences("hotkeys",MODE_PRIVATE);
        if(!p.getBoolean("enabled",false)) return false;

        // Consume both DOWN and UP so Android does not receive an incomplete key event pair.
        if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0){
            if(code==KeyEvent.KEYCODE_VOLUME_UP) playSelectedOnce();
            else Player.stopAll();
        }
        return true;
    }

    private void playSelectedOnce(){
        String id=getSharedPreferences("hotkeys",MODE_PRIVATE).getString("selected_id","");
        if(id.isEmpty()) return;
        MacroStore.Macro m=MacroStore.find(this,id);
        if(m==null || Player.isRunning(id)) return;
        Player.playOnce(this,m);
    }

    @Override public void onMotionEvent(MotionEvent e){
        if(!recording || Build.VERSION.SDK_INT<34) return;
        int action=e.getActionMasked();
        long now=e.getEventTime();
        if(action==MotionEvent.ACTION_DOWN){
            downX=e.getX();downY=e.getY();down=true;downTime=now;
        } else if(down && action==MotionEvent.ACTION_UP){
            float x=e.getX(),y=e.getY();
            long delay=lastActionTime==0?0:Math.max(0,downTime-lastActionTime);
            long duration=Math.max(1,now-downTime);
            synchronized(actions){
                if(Math.hypot(x-downX,y-downY)<12){
                    actions.add(new MacroStore.Action("tap",downX,downY,downX,downY,delay,duration));
                } else {
                    actions.add(new MacroStore.Action("swipe",downX,downY,x,y,delay,Math.max(60,duration)));
                }
            }
            down=false;lastActionTime=now;
        } else if(action==MotionEvent.ACTION_CANCEL){
            down=false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
}
