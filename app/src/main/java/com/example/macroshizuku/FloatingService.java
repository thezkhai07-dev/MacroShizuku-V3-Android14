package com.example.macroshizuku;

import android.app.*;import android.content.*;import android.graphics.Color;import android.graphics.PixelFormat;import android.os.*;import android.view.*;import android.widget.*;import java.util.*;

public class FloatingService extends Service {
    public static boolean isRunning=false;
    private WindowManager wm; private final List<View> views=new ArrayList<>(); private boolean collapsed=false;
    private android.content.SharedPreferences prefs(){return getSharedPreferences("floating_style",MODE_PRIVATE);}
    private int buttonSize(){return Math.max(48,Math.min(400,prefs().getInt("size",72)));}
    private int buttonWidth(){return Math.max(48,Math.min(400,prefs().getInt("width",buttonSize())));}
    private int buttonAlpha(){return Math.max(0,Math.min(100,prefs().getInt("alpha",85)));}
    private int buttonColor(){return prefs().getInt("color",0xFF202F4A);}
    private boolean locked(){return prefs().getBoolean("locked",false);}
    @Override public void onCreate(){super.onCreate();isRunning=true;wm=(WindowManager)getSystemService(WINDOW_SERVICE);refresh();}
    private void refresh(){for(View v:views)try{wm.removeView(v);}catch(Exception ignored){}views.clear();int y=80;if(!collapsed){List<MacroStore.Macro> ms=MacroStore.all(this);for(MacroStore.Macro m:ms)if(m.floating){addMacro(m,y);y+=buttonSize()+8;}}addToggle(collapsed?80:y+8);addLock(collapsed?140:y+8);}
    private WindowManager.LayoutParams params(int x,int y){int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.START;p.x=x;p.y=y;return p;}
    private int withAlpha(int color,int percent){return (color&0x00FFFFFF)|((int)(255f*percent/100f)<<24);}
    private android.graphics.drawable.GradientDrawable circle(int color){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setShape(android.graphics.drawable.GradientDrawable.OVAL);d.setColor(withAlpha(color,buttonAlpha()));d.setStroke(2,withAlpha(0xFF42D9F5,buttonAlpha()));return d;}
    private void addMacro(MacroStore.Macro m,int y){
        int size=buttonSize();TextView b=new TextView(this);b.setText(m.name);b.setTextColor(withAlpha(Color.WHITE,buttonAlpha()));b.setTextSize(10);b.setGravity(Gravity.CENTER);b.setMaxLines(3);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setPadding(5,5,5,5);b.setBackground(circle(buttonColor()));
        b.setContentDescription(m.name+(m.loops==1?" - chạm để chạy một lần":" - giữ để chạy, thả để dừng"));
        String key="macro_"+m.id+"_";int x=prefs().getInt(key+"x",20),py=prefs().getInt(key+"y",y);
        b.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved,started;public boolean onTouch(View q,MotionEvent e){
            WindowManager.LayoutParams lp=(WindowManager.LayoutParams)q.getLayoutParams();
            if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=lp.x;oy=lp.y;moved=false;started=false;if(m.loops!=1){Player.play(FloatingService.this,m);started=true;}return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>12)moved=true;if(moved&&!locked()){lp.x=ox+(int)dx;lp.y=oy+(int)dy;wm.updateViewLayout(q,lp);}return true;}
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){if(m.loops!=1&&started)Player.stop(m.id);if(!moved&&m.loops==1&&e.getAction()==MotionEvent.ACTION_UP)Player.play(FloatingService.this,m);if(moved&&!locked())prefs().edit().putInt(key+"x",lp.x).putInt(key+"y",lp.y).apply();return true;}return true;
        }});
        WindowManager.LayoutParams lp=params(x,py);lp.width=buttonWidth();lp.height=size;wm.addView(b,lp);views.add(b);
    }
    private void addToggle(int y){TextView t=new TextView(this);t.setText(collapsed?"＋":"☰");t.setTextSize(20);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);t.setBackground(circle(0xEE263B59));t.setOnClickListener(v->{collapsed=!collapsed;refresh();});makeDraggable(t,"toggle");WindowManager.LayoutParams p=params(prefs().getInt("togglex",20),prefs().getInt("toggley",y));p.width=52;p.height=52;wm.addView(t,p);views.add(t);}
    private void addLock(int y){TextView t=new TextView(this);t.setText(locked()?"🔒":"🔓");t.setTextSize(19);t.setGravity(Gravity.CENTER);t.setBackground(circle(0xEE263B59));t.setContentDescription(locked()?"Mở khóa vị trí nút nổi":"Khóa vị trí nút nổi");t.setOnClickListener(v->{prefs().edit().putBoolean("locked",!locked()).apply();refresh();});makeDraggable(t,"lock");WindowManager.LayoutParams p=params(prefs().getInt("lockx",80),prefs().getInt("locky",y));p.width=52;p.height=52;wm.addView(t,p);views.add(t);}
    private void makeDraggable(View v,String key){v.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;public boolean onTouch(View q,MotionEvent e){WindowManager.LayoutParams p=(WindowManager.LayoutParams)q.getLayoutParams();if(e.getAction()==0){sx=e.getRawX();sy=e.getRawY();ox=p.x;oy=p.y;moved=false;return true;}if(e.getAction()==2){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>10)moved=true;if(moved&&!locked()){p.x=ox+(int)dx;p.y=oy+(int)dy;wm.updateViewLayout(q,p);}return true;}if(e.getAction()==1){if(!moved)q.performClick();else if(!locked())prefs().edit().putInt(key+"x",p.x).putInt(key+"y",p.y).apply();return true;}return true;}});}
    @Override public int onStartCommand(Intent i,int f,int id){if("REFRESH".equals(i!=null?i.getAction():null))refresh();return START_STICKY;}
    @Override public void onDestroy(){isRunning=false;for(View v:views)try{wm.removeView(v);}catch(Exception ignored){}views.clear();Player.stopAll();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}