package com.example.macroshizuku;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

public class FloatingService extends Service {
    public static boolean isRunning=false;
    private WindowManager wm;
    private final List<View> views=new ArrayList<>();
    private View menuView;
    private boolean menuOpen=false;
    private String recordingName="Macro";
    private android.content.SharedPreferences prefs(){return getSharedPreferences("floating_style",MODE_PRIVATE);}
    private int buttonSize(){return Math.max(48,Math.min(200,prefs().getInt("size",72)));}
    private int buttonAlpha(){return Math.max(20,Math.min(100,prefs().getInt("alpha",85)));}
    private int buttonColor(){return prefs().getInt("color",0xFF202F4A);}
    private boolean locked(){return prefs().getBoolean("locked",false);}
    @Override public void onCreate(){super.onCreate();isRunning=true;wm=(WindowManager)getSystemService(WINDOW_SERVICE);refresh();}
    private void refresh(){
        if(menuView!=null){try{wm.removeView(menuView);}catch(Exception ignored){}menuView=null;}
        for(View v:new ArrayList<>(views))try{wm.removeView(v);}catch(Exception ignored){}
        views.clear();
        int y=80;
        for(MacroStore.Macro m:MacroStore.all(this))if(m.floating){addMacro(m,y);y+=buttonSize()+8;}
        addToggle();
        if(menuOpen)showMenu();
    }
    private WindowManager.LayoutParams params(int x,int y){
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.x=x;p.y=y;return p;
    }
    private int withAlpha(int color,int percent){return (color&0x00FFFFFF)|((int)(255f*percent/100f)<<24);}
    private android.graphics.drawable.GradientDrawable circle(int color){
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);d.setColor(withAlpha(color,buttonAlpha()));d.setStroke(2,withAlpha(0xFF42D9F5,buttonAlpha()));return d;
    }
    private void addMacro(MacroStore.Macro m,int y){
        int size=buttonSize();TextView b=new TextView(this);b.setText(m.name);b.setTextColor(Color.WHITE);b.setTextSize(10);b.setGravity(Gravity.CENTER);b.setMaxLines(3);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setPadding(5,5,5,5);b.setBackground(circle(buttonColor()));
        b.setContentDescription(m.name+" - chạm để chạy");
        String key="macro_"+m.id+"_";int x=prefs().getInt(key+"x",20),py=prefs().getInt(key+"y",y);
        b.setOnTouchListener(new View.OnTouchListener(){
            float sx,sy;int ox,oy;boolean moved,started;
            public boolean onTouch(View q,MotionEvent e){
                WindowManager.LayoutParams lp=(WindowManager.LayoutParams)q.getLayoutParams();
                if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=lp.x;oy=lp.y;moved=false;started=false;if(m.loops!=1){Player.play(FloatingService.this,m);started=true;}return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>12)moved=true;if(moved&&!locked()){lp.x=ox+(int)dx;lp.y=oy+(int)dy;wm.updateViewLayout(q,lp);}return true;}
                if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){if(m.loops!=1&&started)Player.stop(m.id);if(!moved&&m.loops==1&&e.getAction()==MotionEvent.ACTION_UP)Player.play(FloatingService.this,m);if(moved&&!locked())prefs().edit().putInt(key+"x",lp.x).putInt(key+"y",lp.y).apply();return true;}return true;
            }});
        WindowManager.LayoutParams lp=params(x,py);lp.width=size;lp.height=size;wm.addView(b,lp);views.add(b);
    }
    private void addToggle(){
        TextView t=new TextView(this);t.setText("☰");t.setTextSize(21);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);t.setBackground(circle(0xEE263B59));t.setContentDescription("Mở bảng điều khiển Macro Shizuku");
        t.setOnClickListener(v->{menuOpen=!menuOpen;if(menuOpen)showMenu();else closeMenu();});
        makeDraggable(t,"toggle");
        WindowManager.LayoutParams p=params(prefs().getInt("togglex",20),prefs().getInt("toggley",80));p.width=52;p.height=52;wm.addView(t,p);views.add(t);
    }
    private void makeDraggable(View v,String key){
        v.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;
            public boolean onTouch(View q,MotionEvent e){WindowManager.LayoutParams p=(WindowManager.LayoutParams)q.getLayoutParams();
                if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=p.x;oy=p.y;moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>10)moved=true;if(moved&&!locked()){p.x=ox+(int)dx;p.y=oy+(int)dy;wm.updateViewLayout(q,p);}return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){if(!moved)q.performClick();else if(!locked())prefs().edit().putInt(key+"x",p.x).putInt(key+"y",p.y).apply();return true;}return true;
            }});
    }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private Button menuButton(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF263B59));return b;}
    private TextView heading(String text){TextView t=new TextView(this);t.setText(text);t.setTextColor(0xFF42D9F5);t.setTextSize(13);t.setTypeface(null,1);t.setPadding(0,dp(10),0,dp(5));return t;}
    private void showMenu(){
        if(menuView!=null)return;
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(12),dp(14),dp(12));body.setBackgroundColor(0xF20D1729);
        TextView title=new TextView(this);title.setText("MACRO SHIZUKU • ĐIỀU KHIỂN");title.setTextColor(Color.WHITE);title.setTextSize(16);title.setTypeface(null,1);body.addView(title);
        TextView hint=new TextView(this);hint.setText("Macro vẫn nằm bên ngoài. Các công cụ được gom tại đây.");hint.setTextColor(0xFF9EB0D0);hint.setTextSize(11);body.addView(hint);
        EditText macroName=new EditText(this);macroName.setSingleLine(true);macroName.setText(recordingName);macroName.setHint("Tên macro mới");macroName.setTextColor(Color.WHITE);macroName.setHintTextColor(0xFF8292B2);body.addView(macroName);
        body.addView(heading("GHI VÀ ĐIỀU KHIỂN"));
        LinearLayout recRow=new LinearLayout(this);recRow.setOrientation(LinearLayout.HORIZONTAL);
        Button record=menuButton("● GHI MACRO"),save=menuButton("■ LƯU");recRow.addView(record,new LinearLayout.LayoutParams(0,-2,1));recRow.addView(save,new LinearLayout.LayoutParams(0,-2,1));body.addView(recRow);
        record.setOnClickListener(v->{
            String n=macroName.getText().toString().trim();recordingName=n.isEmpty()?"Macro "+(MacroStore.all(this).size()+1):n;
            if(!TouchRecordService.isConnected()){toast("Hãy bật Macro Shizuku trong Trợ năng.");openAccessibility();return;}
            if(!ShizukuShell.ready()){toast("Hãy mở Shizuku và cấp quyền cho ứng dụng.");openShizuku();return;}
            if(TouchRecordService.beginRecording())toast("Đang ghi: "+recordingName+" • Bạn vẫn thao tác bình thường • Volume Down để kết thúc.");
            else toast(TouchRecordService.getCaptureError().isEmpty()?"Không thể bắt đầu ghi.":TouchRecordService.getCaptureError());
        });
        save.setOnClickListener(v->{
            if(!TouchRecordService.recording&&!TouchRecordService.hasPendingCapture()){toast("Chưa có bản ghi để lưu.");return;}
            List<MacroStore.Action> captured=TouchRecordService.endRecording();
            if(captured.isEmpty()){toast("Không có thao tác nào được ghi.");return;}
            MacroStore.Macro m=new MacroStore.Macro("m"+System.currentTimeMillis(),recordingName);m.speed=1f;m.loops=1;m.actions=captured;m.floating=true;
            MacroStore.add(this,m);TouchRecordService.clearPendingCapture();toast("Đã lưu "+m.name+" • "+m.actions.size()+" thao tác.");refresh();
        });
        LinearLayout stopRow=new LinearLayout(this);stopRow.setOrientation(LinearLayout.HORIZONTAL);
        Button stopAll=menuButton("⛔ STOP ALL"),lock=menuButton(locked()?"🔒 MỞ KHÓA VỊ TRÍ":"🔓 KHÓA VỊ TRÍ");stopRow.addView(stopAll,new LinearLayout.LayoutParams(0,-2,1));stopRow.addView(lock,new LinearLayout.LayoutParams(0,-2,1));body.addView(stopRow);
        stopAll.setOnClickListener(v->{Player.stopAll();toast("Đã dừng tất cả macro.");});
        lock.setOnClickListener(v->{prefs().edit().putBoolean("locked",!locked()).apply();toast(locked()?"Đã khóa vị trí nút macro.":"Đã mở khóa vị trí nút macro.");refresh();});
        body.addView(heading("PHÍM TẮT ÂM LƯỢNG"));
        android.content.SharedPreferences hot=getSharedPreferences("hotkeys",MODE_PRIVATE);
        Switch hotSwitch=new Switch(this);hotSwitch.setText("Bật phím tắt Volume Up / Down");hotSwitch.setTextColor(Color.WHITE);hotSwitch.setChecked(hot.getBoolean("enabled",false));body.addView(hotSwitch);
        Spinner macroPicker=new Spinner(this);List<MacroStore.Macro> macros=MacroStore.all(this);ArrayList<String> labels=new ArrayList<>();int selected=0;
        String selectedId=hot.getString("selected_id","");for(int i=0;i<macros.size();i++){labels.add(macros.get(i).name);if(macros.get(i).id.equals(selectedId))selected=i;}
        if(labels.isEmpty())labels.add("Chưa có macro");
        macroPicker.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));if(!macros.isEmpty())macroPicker.setSelection(selected);body.addView(macroPicker);
        TextView hotHint=new TextView(this);hotHint.setText("Volume Up: chạy macro đã chọn một lần • Volume Down: dừng tất cả (khi ghi: kết thúc ghi).");hotHint.setTextColor(0xFF9EB0D0);hotHint.setTextSize(10);body.addView(hotHint);
        hotSwitch.setOnCheckedChangeListener((b,checked)->hot.edit().putBoolean("enabled",checked).apply());
        macroPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(pos<macros.size())hot.edit().putString("selected_id",macros.get(pos).id).apply();}});
        body.addView(heading("DỊCH VỤ VÀ QUYỀN"));
        Button shizuku=menuButton("MỞ SHIZUKU"),access=menuButton("CÀI ĐẶT TRỢ NĂNG"),overlay=menuButton("QUYỀN CỬA SỔ NỔI");
        body.addView(shizuku);body.addView(access);body.addView(overlay);
        shizuku.setOnClickListener(v->openShizuku());access.setOnClickListener(v->openAccessibility());overlay.setOnClickListener(v->{try{Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}catch(Exception e){toast("Không mở được cài đặt quyền nổi.");}});
        body.addView(heading("TÙY CHỈNH NÚT NỔI"));
        Button size=menuButton("Kích thước: "+buttonSize()+" dp"),alpha=menuButton("Độ trong suốt: "+buttonAlpha()+"%"),color=menuButton("🎨 Đổi màu nút macro");body.addView(size);body.addView(alpha);body.addView(color);
        size.setOnClickListener(v->styleDialog(true));alpha.setOnClickListener(v->styleDialog(false));color.setOnClickListener(v->colorDialog());
        body.addView(heading("QUẢN LÝ MACRO"));
        Button manage=menuButton("MỞ DANH SÁCH / CHỈNH SỬA MACRO"),backup=menuButton("SAO LƯU / KHÔI PHỤC");body.addView(manage);body.addView(backup);
        manage.setOnClickListener(v->{Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);});
        backup.setOnClickListener(v->backupDialog());
        Button close=menuButton("ĐÓNG BẢNG ĐIỀU KHIỂN");body.addView(close);close.setOnClickListener(v->{menuOpen=false;closeMenu();});
        ScrollView scroll=new ScrollView(this);scroll.addView(body);menuView=scroll;
        int width=Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(28));
        WindowManager.LayoutParams p=params(dp(12),dp(70));p.width=width;p.height=Math.min(getResources().getDisplayMetrics().heightPixels-dp(110),dp(620));p.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try{wm.addView(menuView,p);}catch(Exception e){menuView=null;toast("Không mở được bảng điều khiển nổi.");}
    }
    private void closeMenu(){if(menuView!=null){try{wm.removeView(menuView);}catch(Exception ignored){}menuView=null;}}
    private void styleDialog(boolean isSize){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView value=new TextView(this);SeekBar seek=new SeekBar(this);
        if(isSize){seek.setMax(152);seek.setProgress(buttonSize()-48);value.setText("Kích thước: "+buttonSize()+" dp");}
        else{seek.setMax(80);seek.setProgress(buttonAlpha()-20);value.setText("Độ trong suốt: "+buttonAlpha()+"%");}
        box.addView(value);box.addView(seek);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){value.setText(isSize?"Kích thước: "+(p+48)+" dp":"Độ trong suốt: "+(p+20)+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        AlertDialog d=new AlertDialog.Builder(this).setTitle(isSize?"Kích thước nút macro":"Độ trong suốt").setView(box).setPositiveButton("Áp dụng",(a,b)->{prefs().edit().putInt(isSize?"size":"alpha",seek.getProgress()+(isSize?48:20)).apply();refresh();}).setNegativeButton("Hủy",null).create();showOverlayDialog(d);
    }
    private void colorDialog(){
        String[] names={"Xanh navy","Xanh dương","Cyan","Tím","Đỏ","Cam","Xanh lá","Đen","Trắng"};
        int[] colors={0xFF202F4A,0xFF2457C5,0xFF00A6C7,0xFF7040B8,0xFFC83E5A,0xFFE07824,0xFF258653,0xFF151515,0xFFEEEEEE};
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Màu nút macro").setItems(names,(dialog,w)->{prefs().edit().putInt("color",colors[w]).apply();refresh();}).setNegativeButton("Hủy",null).create();showOverlayDialog(d);
    }
    private void showOverlayDialog(AlertDialog d){if(d.getWindow()!=null&&Build.VERSION.SDK_INT>=26)d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);d.show();}
    private void backupDialog(){
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Sao lưu / khôi phục").setItems(new String[]{"Xuất file backup JSON","Nhập file backup JSON"},(dialog,w)->{
            Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);i.putExtra("backup_action",w==0?"export":"import");startActivity(i);
        }).setNegativeButton("Hủy",null).create();showOverlayDialog(d);
    }
    private void openAccessibility(){try{Intent i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}catch(Exception e){toast("Không mở được cài đặt Trợ năng.");}}
    private void openShizuku(){try{Intent i=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}else toast("Chưa tìm thấy ứng dụng Shizuku.");}catch(Exception e){toast("Không mở được Shizuku.");}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public int onStartCommand(Intent i,int f,int id){if("REFRESH".equals(i!=null?i.getAction():null))refresh();return START_STICKY;}
    @Override public void onDestroy(){isRunning=false;closeMenu();for(View v:views)try{wm.removeView(v);}catch(Exception ignored){}views.clear();Player.stopAll();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}