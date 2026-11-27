package com.example.macroshizuku;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public class FloatingService extends Service {
    public static boolean isRunning=false;
    private WindowManager wm;
    private final List<View> views=new ArrayList<>();
    private View menuPanel,menuIconView;
    private boolean menuOpen=false;
    private android.content.SharedPreferences prefs(){return getSharedPreferences("floating_style",MODE_PRIVATE);}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private int withAlpha(int color,int percent){return (color&0x00FFFFFF)|(clamp(percent,0,100)*255/100<<24);}
    private int macroColor(){return prefs().getInt("color",0xFF202F4A);}
    private int menuSize(){return clamp(prefs().getInt("menu_size",56),36,120);}
    private int menuAlpha(){return clamp(prefs().getInt("menu_alpha",90),0,100);}
    private boolean recording(){return TouchRecordService.recording;}
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private View statusOverlay;
    private void showOverlayMessage(String message){
        try{
            if(statusOverlay!=null){wm.removeView(statusOverlay);statusOverlay=null;}
            TextView banner=new TextView(this);banner.setText(message);banner.setTextColor(Color.WHITE);banner.setTextSize(14);banner.setGravity(Gravity.CENTER);banner.setPadding(dp(18),dp(12),dp(18),dp(12));banner.setBackground(rounded(0xFF173B5E,98));
            WindowManager.LayoutParams p=params(0,dp(72));p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            statusOverlay=banner;wm.addView(banner,p);
            uiHandler.postDelayed(()->{try{if(statusOverlay==banner){wm.removeView(banner);statusOverlay=null;}}catch(Exception ignored){}},3500);
        }catch(Exception ignored){}
    }
    @Override public void onCreate(){super.onCreate();isRunning=true;wm=(WindowManager)getSystemService(WINDOW_SERVICE);migrateOldFloatingButtons();refresh();}
    private void migrateOldFloatingButtons(){
        if(prefs().getBoolean("single_menu_migrated",false))return;
        List<MacroStore.Macro> all=MacroStore.all(this);
        for(MacroStore.Macro m:all)m.floating=false;
        MacroStore.saveAll(this,all);
        prefs().edit().putBoolean("single_menu_migrated",true).apply();
    }
    private WindowManager.LayoutParams params(int x,int y){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.x=x;p.y=y;return p;
    }
    private GradientDrawable rounded(int color,int alpha){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{withAlpha(color,alpha),withAlpha(color,Math.max(5,alpha-12))});
        d.setCornerRadius(dp(16));d.setStroke(dp(1),withAlpha(0xFF79DFFF,Math.max(8,alpha/3)));return d;
    }
    private void addView(View v,WindowManager.LayoutParams p){wm.addView(v,p);views.add(v);}
    private void removeViews(){
        for(View v:views)try{wm.removeView(v);}catch(Exception ignored){}
        views.clear();menuPanel=null;
    }
    private void refresh(){
        removeViews();
        List<MacroStore.Macro> macros=MacroStore.all(this);
        int stackY=dp(90);
        for(MacroStore.Macro m:macros)if(m.floating){addMacro(m,stackY);stackY+=dp((m.buttonHeight>0?m.buttonHeight:prefs().getInt("size",72))+8);}
        addMenuIcon();
        if(menuOpen)showMenuPanel();
    }
    private void addMacro(MacroStore.Macro m,int defaultY){
        int h=m.buttonHeight>0?clamp(m.buttonHeight,36,400):clamp(prefs().getInt("size",72),36,400);
        int w=m.buttonWidth>0?clamp(m.buttonWidth,36,400):clamp(prefs().getInt("width",72),36,400);
        int alpha=m.buttonAlpha>=0?m.buttonAlpha:clamp(prefs().getInt("alpha",85),0,100);
        TextView b=new TextView(this);b.setText(m.name);b.setTextColor(withAlpha(Color.WHITE,alpha));b.setTextSize(10);b.setGravity(Gravity.CENTER);b.setMaxLines(3);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setPadding(dp(5),dp(5),dp(5),dp(5));b.setBackground(rounded(macroColor(),alpha));
        b.setContentDescription(m.name+(m.loops==1?" - chạm để chạy một lần":" - giữ để chạy, thả để dừng"));
        String key="macro_"+m.id+"_";int x=prefs().getInt(key+"x",20),y=prefs().getInt(key+"y",defaultY);
        b.setOnTouchListener(new View.OnTouchListener(){
            float sx,sy;int ox,oy;boolean moved,started;
            public boolean onTouch(View q,MotionEvent e){
                WindowManager.LayoutParams lp=(WindowManager.LayoutParams)q.getLayoutParams();
                if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=lp.x;oy=lp.y;moved=false;started=false;if(m.loops!=1){Player.play(FloatingService.this,m);started=true;}return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){
                    float dx=e.getRawX()-sx,dy=e.getRawY()-sy;
                    if(Math.abs(dx)+Math.abs(dy)>12)moved=true;
                    if(moved&&!m.buttonLocked){lp.x=ox+(int)dx;lp.y=oy+(int)dy;wm.updateViewLayout(q,lp);}return true;
                }
                if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){
                    if(m.loops!=1&&started)Player.stop(m.id);
                    if(!moved&&m.loops==1&&e.getAction()==MotionEvent.ACTION_UP)Player.play(FloatingService.this,m);
                    if(moved&&!m.buttonLocked)prefs().edit().putInt(key+"x",lp.x).putInt(key+"y",lp.y).apply();
                    return true;
                }return true;
            }
        });
        WindowManager.LayoutParams lp=params(x,y);lp.width=dp(w);lp.height=dp(h);addView(b,lp);
    }
    private void addMenuIcon(){
        TextView icon=new TextView(this);menuIconView=icon;icon.setText("☰");icon.setTextSize(21);icon.setTextColor(withAlpha(Color.WHITE,menuAlpha()));icon.setGravity(Gravity.CENTER);icon.setBackground(rounded(0xFF203A5C,menuAlpha()));icon.setContentDescription("Mở menu Macro");
        icon.setOnClickListener(v->{menuOpen=!menuOpen;refresh();});
        makeDraggable(icon,"menu");
        WindowManager.LayoutParams p=params(prefs().getInt("menux",20),prefs().getInt("menuy",dp(120)));
        p.width=dp(menuSize());p.height=dp(menuSize());addView(icon,p);
    }
    private void makeDraggable(View v,String key){
        v.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;
            public boolean onTouch(View q,MotionEvent e){
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)q.getLayoutParams();
                if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=p.x;oy=p.y;moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>dp(8))moved=true;if(moved){p.x=ox+(int)dx;p.y=oy+(int)dy;wm.updateViewLayout(q,p);}return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){if(!moved)q.performClick();else prefs().edit().putInt(key+"x",p.x).putInt(key+"y",p.y).apply();return true;}return true;
            }
        });
    }
    private TextView label(String value){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(0xFFEAF4FF);t.setTextSize(13);t.setPadding(dp(3),dp(5),dp(3),dp(3));return t;
    }
    private Button actionButton(String text){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(Color.WHITE);b.setPadding(dp(4),0,dp(4),0);b.setBackground(rounded(0xFF294D72,92));return b;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void addSlider(LinearLayout box,String title,int min,int max,int value,String suffix,java.util.function.IntConsumer changed){
        TextView t=label(title+" "+value+suffix);box.addView(t);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(clamp(value,min,max)-min);box.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){int v=p+min;t.setText(title+" "+v+suffix);if(user)changed.accept(v);}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void addSpeedSlider(LinearLayout box,MacroStore.Macro macro){
        float initial=Math.max(.25f,Math.min(10f,macro.speed));
        TextView title=label(String.format(java.util.Locale.US,"Tốc độ: %.2fx",initial));box.addView(title);
        SeekBar bar=new SeekBar(this);bar.setMax(39);bar.setProgress(clamp(Math.round(initial*4)-1,0,39));box.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){float speed=(p+1)*0.25f;title.setText(String.format(java.util.Locale.US,"Tốc độ: %.2fx",speed));if(user){MacroStore.Macro latest=MacroStore.find(FloatingService.this,macro.id);if(latest!=null){latest.speed=speed;MacroStore.upsert(FloatingService.this,latest);}}}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void makePanelDraggable(View handle,View panel){
        handle.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;
            public boolean onTouch(View v,MotionEvent e){
                WindowManager.LayoutParams lp=(WindowManager.LayoutParams)panel.getLayoutParams();
                if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=lp.x;oy=lp.y;moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>dp(6))moved=true;if(moved){lp.x=ox+(int)dx;lp.y=oy+(int)dy;try{wm.updateViewLayout(panel,lp);}catch(Exception ignored){}}return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){if(moved)prefs().edit().putInt("menu_panel_x",lp.x).putInt("menu_panel_y",lp.y).apply();return true;}
                return true;
            }
        });
    }
    private void showMenuPanel(){
        LinearLayout root=vertical();root.setPadding(dp(12),dp(10),dp(12),dp(10));root.setBackground(rounded(0xFF101D31,96));
        TextView heading=label("☰  MACRO CONTROL  •  KÉO ĐỂ DI CHUYỂN");heading.setTextSize(13);heading.setTypeface(null,1);heading.setPadding(dp(4),dp(4),dp(4),dp(4));heading.setBackground(rounded(0xFF294D72,90));root.addView(heading);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button record=actionButton(recording()?"■ ĐANG GHI":"● GHI");Button save=actionButton("■ DỪNG & LƯU");actions.addView(record,new LinearLayout.LayoutParams(0,-2,1));actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
        record.setOnClickListener(v->{
            if(recording()){Toast.makeText(this,"Đang ghi. Nhấn DỪNG & LƯU để kết thúc.",Toast.LENGTH_SHORT).show();return;}
            if(!ShizukuShell.ready()){Toast.makeText(this,"Hãy kết nối Shizuku Next và cấp quyền trước.",Toast.LENGTH_LONG).show();return;}
            if(TouchRecordService.beginRecording(this)){Toast.makeText(this,"Đang ghi thao tác chạm.",Toast.LENGTH_SHORT).show();refresh();}
            else Toast.makeText(this,TouchRecordService.getCaptureError(),Toast.LENGTH_LONG).show();
        });
        save.setOnClickListener(v->saveRecordingFromMenu());
        root.addView(label("HIỂN THỊ VÀ TÙY CHỈNH TỪNG MACRO"));
        ScrollView scroll=new ScrollView(this);LinearLayout list=vertical();list.setPadding(0,0,0,dp(4));
        List<MacroStore.Macro> macros=MacroStore.all(this);
        if(macros.isEmpty())list.addView(label("Chưa có macro nào được lưu."));
        for(MacroStore.Macro m:macros){
            LinearLayout card=vertical();card.setPadding(dp(8),dp(6),dp(8),dp(8));card.setBackground(rounded(0xFF21334C,88));
            CheckBox visible=new CheckBox(this);visible.setText("Hiện: "+m.name);visible.setTextColor(Color.WHITE);visible.setButtonTintList(android.root.res.ColorStateList.valueOf(0xFF42D9F5));visible.setChecked(m.floating);card.addView(visible);
            visible.setOnCheckedChangeListener((button,checked)->{MacroStore.Macro latest=MacroStore.find(this,m.id);if(latest!=null){latest.floating=checked;MacroStore.upsert(this,latest);refresh();}});
            CheckBox lock=new CheckBox(this);lock.setText("Khóa di chuyển");lock.setTextColor(Color.WHITE);lock.setButtonTintList(android.root.res.ColorStateList.valueOf(0xFF42D9F5));lock.setChecked(m.buttonLocked);card.addView(lock);
            lock.setOnCheckedChangeListener((button,checked)->{MacroStore.Macro latest=MacroStore.find(this,m.id);if(latest!=null){latest.buttonLocked=checked;MacroStore.upsert(this,latest);refresh();}});
            int initialW=m.buttonWidth>0?m.buttonWidth:prefs().getInt("width",72);
            int initialH=m.buttonHeight>0?m.buttonHeight:prefs().getInt("size",72);
            int initialA=m.buttonAlpha>=0?m.buttonAlpha:prefs().getInt("alpha",85);
            addSlider(card,"Rộng",36,400,initialW," dp",v->updateMacroStyle(m.id,"w",v));
            addSlider(card,"Cao",36,400,initialH," dp",v->updateMacroStyle(m.id,"h",v));
            addSlider(card,"Độ mờ",0,100,initialA,"%",v->updateMacroStyle(m.id,"a",v));
            addSpeedSlider(card,m);
            list.addView(card);
        }
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(300)));
        root.addView(label("ICON MENU NỔI"));
        addSlider(root,"Kích thước icon",36,120,menuSize()," dp",v->{prefs().edit().putInt("menu_size",v).apply();applyMenuIconStyle();});
        addSlider(root,"Độ mờ icon",0,100,menuAlpha(),"%",v->{prefs().edit().putInt("menu_alpha",v).apply();applyMenuIconStyle();});
        Button close=actionButton("ĐÓNG MENU");root.addView(close);close.setOnClickListener(v->{menuOpen=false;refresh();});
        int defaultX=prefs().getInt("menux",20)+dp(menuSize())+dp(8);
        int defaultY=prefs().getInt("menuy",dp(120));
        WindowManager.LayoutParams p=params(prefs().getInt("menu_panel_x",defaultX),prefs().getInt("menu_panel_y",defaultY));
        p.width=dp(300);p.height=-2;p.flags|=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        menuPanel=root;addView(root,p);makePanelDraggable(heading,root);
    }
    private void applyMenuIconStyle(){
        if(menuIconView==null)return;
        menuIconView.setBackground(rounded(0xFF203A5C,menuAlpha()));
        if(menuIconView instanceof TextView)((TextView)menuIconView).setTextColor(withAlpha(Color.WHITE,menuAlpha()));
        try{WindowManager.LayoutParams p=(WindowManager.LayoutParams)menuIconView.getLayoutParams();p.width=dp(menuSize());p.height=dp(menuSize());wm.updateViewLayout(menuIconView,p);}catch(Exception ignored){}
    }
    private void updateMacroStyle(String id,String property,int value){
        MacroStore.Macro m=MacroStore.find(this,id);if(m==null)return;
        if("w".equals(property))m.buttonWidth=value;
        else if("h".equals(property))m.buttonHeight=value;
        else if("a".equals(property))m.buttonAlpha=value;
        else if("speed".equals(property))m.speed=Math.max(.25f,Math.min(10f,value/4f));
        MacroStore.upsert(this,m);
        // Update macro views without rebuilding the menu while the user is dragging a slider.
        for(View view:new ArrayList<>(views))if(view!=menuPanel){try{wm.removeView(view);views.remove(view);}catch(Exception ignored){}}
        int stackY=dp(90);for(MacroStore.Macro item:MacroStore.all(this))if(item.floating){addMacro(item,stackY);stackY+=dp((item.buttonHeight>0?item.buttonHeight:prefs().getInt("size",72))+8);}
    }
    private void saveRecordingFromMenu(){
        if(!recording()&&!TouchRecordService.hasPendingCapture()){String msg="Chưa có bản ghi để lưu.";Toast.makeText(getApplicationContext(),msg,Toast.LENGTH_SHORT).show();showOverlayMessage(msg);return;}
        List<MacroStore.Action> captured=TouchRecordService.endRecording();
        if(captured==null||captured.isEmpty()){String msg="Bản ghi chưa có thao tác nào, chưa lưu.";Toast.makeText(getApplicationContext(),msg,Toast.LENGTH_LONG).show();showOverlayMessage(msg);refresh();return;}
        MacroStore.Macro m=new MacroStore.Macro("m"+System.currentTimeMillis(),"Macro "+(MacroStore.all(this).size()+1));
        m.actions=captured;m.speed=1f;m.loops=1;m.floating=false;m.normalizedCoordinates=true;
        MacroStore.add(this,m);TouchRecordService.clearPendingCapture();
        String msg="Đã lưu "+m.name+" • "+captured.size()+" thao tác.";
        Toast.makeText(getApplicationContext(),msg,Toast.LENGTH_LONG).show();showOverlayMessage(msg);
        refresh();
    }
    @Override public int onStartCommand(Intent i,int f,int id){if("REFRESH".equals(i!=null?i.getAction():null))refresh();return START_STICKY;}
    @Override public void onDestroy(){isRunning=false;removeViews();Player.stopAll();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}