package com.example.macroshizuku;

import android.content.Context;
import android.widget.Toast;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import java.util.Map;import java.util.concurrent.ConcurrentHashMap;

public final class Player {
    private static final Map<String,Thread> running=new ConcurrentHashMap<>();
    private Player(){}
    public static boolean isRunning(String id){Thread t=running.get(id);return t!=null&&t.isAlive();}
    public static void play(Context c,MacroStore.Macro m){ playInternal(c,m,m.loops); }
    /** Plays exactly one pass, ignoring the macro's saved repeat count. */
    public static void playOnce(Context c,MacroStore.Macro m){ playInternal(c,m,1); }
    private static void playInternal(Context c,MacroStore.Macro m,int requestedLoops){
        stop(m.id);
        if(m.actions==null||m.actions.isEmpty()){Toast.makeText(c.getApplicationContext(),"Macro chưa có thao tác.",Toast.LENGTH_SHORT).show();return;}
        if(!ShizukuShell.ready()){Toast.makeText(c.getApplicationContext(),"Shizuku chưa chạy hoặc chưa được cấp quyền.",Toast.LENGTH_SHORT).show();return;}
        final float speed=Math.max(.25f,Math.min(10f,m.speed));
        final int displayW,displayH;
        try{
            WindowManager wm=(WindowManager)c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm=new DisplayMetrics();
            if(wm!=null)wm.getDefaultDisplay().getRealMetrics(dm);
            else dm.setTo(c.getResources().getDisplayMetrics());
            displayW=Math.max(1,dm.widthPixels);displayH=Math.max(1,dm.heightPixels);
        }catch(Exception e){
            DisplayMetrics dm=c.getResources().getDisplayMetrics();
            displayW=Math.max(1,dm.widthPixels);displayH=Math.max(1,dm.heightPixels);
        }
        Thread t=new Thread(()->{
            try{
                int loops=requestedLoops<=0?Integer.MAX_VALUE:requestedLoops;
                for(int n=0;n<loops&&!Thread.currentThread().isInterrupted();n++){
                    for(MacroStore.Action x:m.actions){
                        if(Thread.currentThread().isInterrupted())break;
                        if(x.delay>0)Thread.sleep(Math.max(0,Math.round(x.delay/speed)));
                        String cmd;
                        int x1=toDisplayCoordinate(x.x1,displayW,m.normalizedCoordinates);
                        int y1=toDisplayCoordinate(x.y1,displayH,m.normalizedCoordinates);
                        int x2=toDisplayCoordinate(x.x2,displayW,m.normalizedCoordinates);
                        int y2=toDisplayCoordinate(x.y2,displayH,m.normalizedCoordinates);
                        if("tap".equals(x.type))cmd="input tap "+x1+" "+y1;
                        else if("swipe".equals(x.type))cmd="input swipe "+x1+" "+y1+" "+x2+" "+y2+" "+Math.max(1,Math.round(x.duration/speed));
                        else continue;
                        ShizukuShell.run("sh","-c",cmd);
                    }
                }
            }catch(Exception ignored){}
            finally{running.remove(m.id,Thread.currentThread());}
        },"macro-"+m.id);
        running.put(m.id,t);
        t.start();
    }
    private static int toDisplayCoordinate(float value,int dimension,boolean normalized){
        if(normalized)return Math.max(0,Math.min(dimension-1,Math.round(value*(dimension-1))));
        return Math.max(0,Math.round(value));
    }
    public static void stop(String id){Thread t=running.remove(id);if(t!=null)t.interrupt();}
    public static void stopAll(){for(String id:running.keySet())stop(id);}
}
