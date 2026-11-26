package com.example.macroshizuku;

import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    LinearLayout list; EditText name; SeekBar speed; TextView speedText; Spinner loops, hotkeyMacro; CheckBox hotkeyEnabled; static final int REQ=77, EXPORT=91, IMPORT=92;
    private final Shizuku.OnRequestPermissionResultListener permissionListener=(requestCode,grantResult)->runOnUiThread(()->{
        if(requestCode==REQ)toast(grantResult==0?"Đã cấp quyền Shizuku.":"Bạn chưa cấp quyền Shizuku.");
    });
    @Override public void onCreate(Bundle b){super.onCreate(b);Shizuku.addRequestPermissionResultListener(permissionListener);setContentView(R.layout.activity_main);
        list=findViewById(R.id.macroList);name=findViewById(R.id.name);speed=findViewById(R.id.speed);speedText=findViewById(R.id.speedText);loops=findViewById(R.id.loops);hotkeyMacro=findViewById(R.id.hotkeyMacro);hotkeyEnabled=findViewById(R.id.hotkeyEnabled);
        hotkeyEnabled.setChecked(getSharedPreferences("hotkeys",MODE_PRIVATE).getBoolean("enabled",false));
        findViewById(R.id.shizuku).setOnClickListener(v->{if(Shizuku.pingBinder()){try{if(Shizuku.checkSelfPermission()!=0)Shizuku.requestPermission(REQ);}catch(Exception ignored){}}else toast("Hãy mở Shizuku trước.");});
        findViewById(R.id.overlay).setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));else toast("Đã có quyền cửa sổ nổi.");});
        findViewById(R.id.accessibility).setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        speed.setProgress(100);speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){float x=Math.max(.25f,p/100f);speedText.setText(String.format(Locale.US,"Tốc độ %.2fx",x));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        findViewById(R.id.record).setOnClickListener(v->{
            String macroName=name.getText().toString().trim();
            if(macroName.isEmpty())macroName="Macro "+(MacroStore.all(this).size()+1);
            if(Build.VERSION.SDK_INT<34){toast("Ghi chạm yêu cầu Android 14 trở lên.");return;}
            if(!TouchRecordService.isConnected()){toast("Hãy bật Macro Shizuku trong cài đặt Trợ năng trước.");return;}
            final String finalName=macroName;
            new AlertDialog.Builder(this).setTitle("Lưu ý khi ghi chạm")
                .setMessage("Android 14 không chuyển tiếp các sự kiện cảm ứng mà AccessibilityService đang theo dõi tới ứng dụng đích. Trong lúc ghi, thao tác chạm sẽ không tới ứng dụng đang mở; vì vậy chức năng này hiện chỉ thử nghiệm, chưa thể ghi macro tương tác bình thường. Volume Down kết thúc chế độ ghi.")
                .setPositiveButton("Bắt đầu ghi",(d,w)->{
                    if(TouchRecordService.beginRecording()){name.setTag(finalName);toast("Đang ghi: "+finalName+" • Volume Down để kết thúc");}
                    else toast("Không bật được chế độ ghi chạm.");
                }).setNegativeButton("Hủy",null).show();
        });
        findViewById(R.id.stop).setOnClickListener(v->{
            if(!TouchRecordService.recording && !TouchRecordService.hasPendingCapture()){toast("Chưa có bản ghi để lưu.");return;}
            List<MacroStore.Action> captured=TouchRecordService.endRecording();
            String n=String.valueOf(name.getTag());
            if(n.equals("null")||n.trim().isEmpty())n="Macro "+(MacroStore.all(this).size()+1);
            MacroStore.Macro m=new MacroStore.Macro("m"+System.currentTimeMillis(),n);
            m.speed=Math.max(.25f,speed.getProgress()/100f);m.loops=loopValue();m.actions=captured;m.floating=true;
            MacroStore.add(this,m);TouchRecordService.clearPendingCapture();toast("Đã lưu "+n+" • "+m.actions.size()+" thao tác");refresh();
        });
        findViewById(R.id.floatBtn).setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){toast("Hãy cấp quyền cửa sổ nổi.");return;}startService(new Intent(this,FloatingService.class));});
        findViewById(R.id.stopAll).setOnClickListener(v->Player.stopAll());
        findViewById(R.id.saveHotkey).setOnClickListener(v->saveHotkeys());
        findViewById(R.id.exportBtn).setOnClickListener(v->exportData()); findViewById(R.id.importBtn).setOnClickListener(v->importData());
        refresh();
    }
    private void saveHotkeys(){String id="";int pos=hotkeyMacro.getSelectedItemPosition();List<MacroStore.Macro> ms=MacroStore.all(this);if(pos>=0&&pos<ms.size())id=ms.get(pos).id;getSharedPreferences("hotkeys",MODE_PRIVATE).edit().putBoolean("enabled",hotkeyEnabled.isChecked()).putString("selected_id",id).apply();toast("Đã lưu: Volume Up = chạy 1 lần • Volume Down = STOP ALL (trừ khi đang ghi)");}
    private int loopValue(){int p=loops.getSelectedItemPosition();return p==0?1:p==1?5:p==2?10:0;}
    private void refresh(){list.removeAllViews();List<MacroStore.Macro> ms=MacroStore.all(this);String selected=getSharedPreferences("hotkeys",MODE_PRIVATE).getString("selected_id","");ArrayList<String> names=new ArrayList<>();for(MacroStore.Macro m:ms)names.add(m.name);hotkeyMacro.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));for(int i=0;i<ms.size();i++)if(ms.get(i).id.equals(selected))hotkeyMacro.setSelection(i);if(ms.isEmpty()){TextView e=new TextView(this);e.setText("Chưa có macro. Bấm Ghi macro để tạo macro đầu tiên.");e.setPadding(8,24,8,24);list.addView(e);return;}for(MacroStore.Macro m:ms){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(12,12,12,12);card.setBackgroundColor(0xFFF1F3F4);TextView t=new TextView(this);t.setText(m.name+"\n"+String.format(Locale.US,"%.2fx",m.speed)+" • "+(m.loops==0?"∞":m.loops)+" lần • "+m.actions.size()+" bước");t.setTextSize(16);card.addView(t);LinearLayout buttons=new LinearLayout(this);Button play=btn("▶");Button stop=btn("■");Button edit=btn("Sửa");Button clone=btn("Clone");Button del=btn("Xóa");buttons.addView(play);buttons.addView(stop);buttons.addView(edit);buttons.addView(clone);buttons.addView(del);card.addView(buttons);list.addView(card);play.setOnClickListener(v->Player.play(this,m));stop.setOnClickListener(v->Player.stop(m.id));edit.setOnClickListener(v->editMacro(m));clone.setOnClickListener(v->{MacroStore.Macro c=copy(m);c.id="m"+System.currentTimeMillis();c.name=m.name+" copy";MacroStore.add(this,c);refresh();});del.setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("Xóa macro?").setMessage(m.name).setPositiveButton("Xóa",(d,w)->{Player.stop(m.id);MacroStore.delete(this,m.id);refresh();}).setNegativeButton("Hủy",null).show();});}}
    private Button btn(String s){Button b=new Button(this);b.setText(s);return b;}
    private MacroStore.Macro copy(MacroStore.Macro m){MacroStore.Macro c=new MacroStore.Macro("",m.name);c.speed=m.speed;c.loops=m.loops;c.floating=m.floating;for(MacroStore.Action a:m.actions)c.actions.add(new MacroStore.Action(a.type,a.x1,a.y1,a.x2,a.y2,a.delay,a.duration));return c;}
    private void editMacro(MacroStore.Macro m){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(30,10,30,10);EditText n=new EditText(this);n.setText(m.name);box.addView(n);SeekBar s=new SeekBar(this);s.setMax(400);s.setProgress(Math.round(m.speed*100));box.addView(s);Spinner l=new Spinner(this);l.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"1 lần","5 lần","10 lần","Vô hạn"}));l.setSelection(m.loops==1?0:m.loops==5?1:m.loops==10?2:3);box.addView(l);CheckBox f=new CheckBox(this);f.setText("Hiện nút nổi cho macro này");f.setChecked(m.floating);box.addView(f);new AlertDialog.Builder(this).setTitle("Sửa macro").setView(box).setPositiveButton("Lưu",(d,w)->{m.name=n.getText().toString().trim();m.speed=Math.max(.25f,s.getProgress()/100f);m.loops=l.getSelectedItemPosition()==0?1:l.getSelectedItemPosition()==1?5:l.getSelectedItemPosition()==2?10:0;m.floating=f.isChecked();MacroStore.upsert(this,m);if(Settings.canDrawOverlays(this))startService(new Intent(this,FloatingService.class).setAction("REFRESH"));refresh();}).setNegativeButton("Hủy",null).show();}
    private void exportData(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"macroshizuku-v3-backup.json");startActivityForResult(i,EXPORT);}
    private void importData(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/json");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(c!=RESULT_OK||d==null)return;try{if(r==EXPORT){OutputStream o=getContentResolver().openOutputStream(d.getData());o.write(MacroStore.exportJson(this).getBytes("UTF-8"));o.close();toast("Đã xuất backup.");}else{InputStream i=getContentResolver().openInputStream(d.getData());ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] z=new byte[8192];int n;while((n=i.read(z))>0)b.write(z,0,n);i.close();MacroStore.importJson(this,b.toString("UTF-8"));refresh();toast("Đã nhập macro.");}}catch(Exception e){toast("Không thể xử lý file: "+e.getMessage());}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permissionListener);super.onDestroy();}
}
