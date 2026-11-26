package com.example.macroshizuku;

import android.app.*;import android.content.*;import android.graphics.Color;import android.graphics.PixelFormat;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.util.*;

public class FloatingService extends Service {
    public static boolean isRunning=false;
    private WindowManager wm; private final List<View> views=new ArrayList<>(); private boolean collapsed=false;
    private int buttonSize(){return getSharedPreferences("floating_style",MODE_PRIVATE).getInt("size",64);}
    private int buttonAlpha(){return Math.max(0,Math.min(100,getSharedPreferences("floating_style",MODE_PRIVATE).getInt("alpha",85)));}
    @Override public void onCreate(){super.onCreate();isRunning=true;wm=(WindowManager)getSystemService(WINDOW_SERVICE);refresh();}
    private void refresh(){for(View v:views)try{wm.removeView(v);}catch(Exception ignored){}views.clear();int y=80;if(!collapsed){List<MacroStore.Macro> ms=MacroStore.all(this);for(MacroStore.Macro m:ms)if(m.floating){addMacro(m,y);y+=buttonSize()+8;}addStopAll(y);}addToggle(collapsed?80:y+64);}
    private WindowManager.LayoutParams params(int x,int y){int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.START;p.x=x;p.y=y;return p;}
    private int withAlpha(int color,int percent){return (color&0x00FFFFFF)|((int)(255f*percent/100f)<<24);}
    private android.graphics.drawable.GradientDrawable circle(int color){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setShape(android.graphics.drawable.GradientDrawable.OVAL);d.setColor(withAlpha(color,buttonAlpha()));d.setStroke(2,withAlpha(0xFF42D9F5,buttonAlpha()));return d;}
    private void addMacro(MacroStore.Macro m,int y){
        int size=buttonSize();
        TextView b=new TextView(this);
        b.setText((Player.isRunning(m.id)?"■ ":"▶ ")+m.name);
        b.setTextColor(withAlpha(Color.WHITE,buttonAlpha()));
        b.setTextSize(10);b.setGravity(Gravity.CENTER);b.setMaxLines(3);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setPadding(5,5,5,5);
        b.setBackground(circle(0xEE202F4A));
        b.setContentDescription((Player.isRunning(m.id)?"Dừng ":"Chạy ")+m.name);
        b.setOnClickListener(v->{if(Player.isRunning(m.id))Player.stop(m.id);else Player.play(this,m);b.setText((Player.isRunning(m.id)?"■ ":"▶ ")+m.name);});
        makeDraggable(b);WindowManager.LayoutParams p=params(20,y);p.width=size;p.height=size;
        wm.addView(b,p);views.add(b);
    }
    private void addStopAll(int y){
        int size=buttonSize();TextView b=new TextView(this);b.setText("STOP ALL");
        b.setTextColor(withAlpha(Color.WHITE,buttonAlpha()));b.setTextSize(11);b.setTypeface(null,android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);b.setPadding(8,0,8,0);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(withAlpha(0xFFC83E5A,buttonAlpha()));bg.setCornerRadius(size/2f);
        bg.setStroke(2,withAlpha(0xFFFFA0AD,buttonAlpha()));b.setBackground(bg);
        b.setOnClickListener(v->Player.stopAll());makeDraggable(b);
        WindowManager.LayoutParams p=params(20,y);p.width=(int)(size*1.65f);p.height=size;
        wm.addView(b,p);views.add(b);
    }
    private void addToggle(int y){TextView toggle=new TextView(this);toggle.setText(collapsed?"＋":"☰");toggle.setTextSize(20);toggle.setTextColor(Color.WHITE);toggle.setGravity(Gravity.CENTER);toggle.setBackground(new android.graphics.drawable.GradientDrawable(){ {setColor(0xEE263B59);setCornerRadius(1000f);setStroke(2,0xFF42D9F5);} });toggle.setContentDescription(collapsed?"Hiện nút macro và STOP ALL":"Ẩn nút macro và STOP ALL");toggle.setOnClickListener(v->{collapsed=!collapsed;refresh();});makeDraggable(toggle);WindowManager.LayoutParams p=params(20,y);p.width=52;p.height=52;wm.addView(toggle,p);views.add(toggle);}
    private void makeDraggable(View v){v.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;public boolean onTouch(View q,MotionEvent e){WindowManager.LayoutParams p=(WindowManager.LayoutParams)q.getLayoutParams();if(e.getAction()==0){sx=e.getRawX();sy=e.getRawY();ox=p.x;oy=p.y;moved=false;return true;}if(e.getAction()==2){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>10)moved=true;p.x=ox+(int)dx;p.y=oy+(int)dy;wm.updateViewLayout(q,p);return true;}if(e.getAction()==1&&!moved)q.performClick();return true;}});}
    @Override public int onStartCommand(Intent i,int f,int id){if("REFRESH".equals(i!=null?i.getAction():null))refresh();return START_STICKY;}
    @Override public void onDestroy(){isRunning=false;for(View v:views)try{wm.removeView(v);}catch(Exception ignored){}views.clear();Player.stopAll();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
