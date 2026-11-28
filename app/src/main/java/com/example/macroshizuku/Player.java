package com.example.macroshizuku;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Player {
    private static final String TAG="MacroPlayer";
    private static final int ACTIONS_PER_BATCH=4;
    private static final Map<String,Thread> running=new ConcurrentHashMap<>();
    private Player(){}
    public static boolean isRunning(String id){Thread t=running.get(id);return t!=null&&t.isAlive();}
    public static void play(Context c,MacroStore.Macro m){playInternal(c,m,m.loops);}
    public static void playWhileHeld(Context c,MacroStore.Macro m){playInternal(c,m,0);}
    public static void playOnce(Context c,MacroStore.Macro m){playInternal(c,m,1);}

    private static void playInternal(Context c,MacroStore.Macro m,int requestedLoops){
        if(m==null||m.actions==null||m.actions.isEmpty()){show(c,"Macro chưa có thao tác.");return;}
        if(!ShizukuShell.ready()){show(c,"Shizuku chưa chạy hoặc chưa được cấp quyền.");return;}
        final float speed=Math.max(.25f,Math.min(10f,m.speed));
        DisplayMetrics dm=new DisplayMetrics();
        try{
            WindowManager wm=(WindowManager)c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            if(wm!=null)wm.getDefaultDisplay().getRealMetrics(dm);
            else dm.setTo(c.getResources().getDisplayMetrics());
        }catch(Exception e){dm.setTo(c.getResources().getDisplayMetrics());}
        final int displayW=Math.max(1,dm.widthPixels),displayH=Math.max(1,dm.heightPixels);
        final int loops=requestedLoops<=0?Integer.MAX_VALUE:requestedLoops;
        final java.util.List<MacroStore.Action> actions=new java.util.ArrayList<>(m.actions);
        final String macroId=m.id;
        Thread t=new Thread(()->{
            int completed=0;
            try{
                for(int pass=0;pass<loops&&!Thread.currentThread().isInterrupted();pass++){
                    for(int start=0;start<actions.size()&&!Thread.currentThread().isInterrupted();start+=ACTIONS_PER_BATCH){
                        int end=Math.min(actions.size(),start+ACTIONS_PER_BATCH);
                        StringBuilder script=new StringBuilder("set -e\n");
                        for(int i=start;i<end;i++){
                            MacroStore.Action a=actions.get(i);
                            if(a==null)throw new IllegalArgumentException("Thao tác "+(i+1)+" bị trống.");
                            long delay=Math.max(0,Math.round(Math.max(0,a.delay)/speed));
                            if(delay>0)appendSleep(script,delay);
                            int x1=toCoordinate(a.x1,displayW,m.normalizedCoordinates);
                            int y1=toCoordinate(a.y1,displayH,m.normalizedCoordinates);
                            int x2=toCoordinate(a.x2,displayW,m.normalizedCoordinates);
                            int y2=toCoordinate(a.y2,displayH,m.normalizedCoordinates);
                            if("tap".equals(a.type))script.append("input tap ").append(x1).append(' ').append(y1).append('\n');
                            else if("swipe".equals(a.type)){
                                long duration=Math.max(1,Math.round(Math.max(1,a.duration)/speed));
                                script.append("input swipe ").append(x1).append(' ').append(y1).append(' ')
                                    .append(x2).append(' ').append(y2).append(' ').append(duration).append('\n');
                            }else throw new IllegalArgumentException("Loại thao tác không hỗ trợ ở bước "+(i+1)+": "+a.type);
                            if(i<actions.size()-1&&m.tapIntervalMs>0)appendSleep(script,Math.max(0,Math.round(m.tapIntervalMs/speed)));
                        }
                        if(Thread.currentThread().isInterrupted())break;
                        ShizukuShell.run("sh","-c",script.toString());
                        completed=end;
                    }
                }
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }catch(Throwable e){
                Log.e(TAG,"Playback failed for "+m.id+" after "+completed+" actions",e);
                show(c,"Macro bị dừng tại bước "+Math.min(actions.size(),completed+1)+": "+(e.getMessage()==null?"Lỗi Shizuku":e.getMessage()));
            }finally{
                running.remove(macroId,Thread.currentThread());
            }
        },"macro-"+macroId);
        synchronized(running){
            Thread active=running.get(macroId);
            if(active!=null&&active.isAlive())return;
            running.put(macroId,t);
            t.start();
        }
    }
    private static void appendSleep(StringBuilder script,long ms){
        if(ms<=0)return;
        script.append("sleep ").append(String.format(Locale.US,"%.3f",ms/1000.0)).append('\n');
    }
    private static int toCoordinate(float value,int dimension,boolean normalized){
        if(Float.isNaN(value)||Float.isInfinite(value))throw new IllegalArgumentException("Tọa độ không hợp lệ.");
        if(normalized)return Math.max(0,Math.min(dimension-1,Math.round(value*(dimension-1))));
        return Math.max(0,Math.min(dimension-1,Math.round(value)));
    }
    private static void show(Context c,String message){
        new Handler(Looper.getMainLooper()).post(()->Toast.makeText(c.getApplicationContext(),message,Toast.LENGTH_LONG).show());
    }
    public static void stop(String id){Thread t=running.remove(id);if(t!=null)t.interrupt();}
    public static void stopAll(){for(String id:running.keySet())stop(id);}
}
