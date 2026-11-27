package com.example.macroshizuku;

import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    LinearLayout list; EditText name; SeekBar speed; TextView speedText; Spinner loops, hotkeyMacro; CheckBox hotkeyEnabled; static final int REQ=77, EXPORT=91, IMPORT=92;
    private final Shizuku.OnRequestPermissionResultListener permissionListener=(requestCode,grantResult)->runOnUiThread(()->{
        if(requestCode==REQ){if(grantResult==0)toast("Đã cấp quyền Shizuku.");else toast("Bạn chưa cấp quyền Shizuku.");updateStatusIndicators();}
    });
    @Override public void onCreate(Bundle b){super.onCreate(b);Shizuku.addRequestPermissionResultListener(permissionListener);setContentView(R.layout.activity_main);
        list=findViewById(R.id.macroList);name=findViewById(R.id.name);speed=findViewById(R.id.speed);speedText=findViewById(R.id.speedText);loops=findViewById(R.id.loops);hotkeyMacro=findViewById(R.id.hotkeyMacro);hotkeyEnabled=findViewById(R.id.hotkeyEnabled);
        hotkeyEnabled.setChecked(getSharedPreferences("hotkeys",MODE_PRIVATE).getBoolean("enabled",false));
        findViewById(R.id.shizuku).setOnClickListener(v->{if(Shizuku.pingBinder()){try{if(Shizuku.checkSelfPermission()!=0)Shizuku.requestPermission(REQ);}catch(Exception ignored){}}else toast("Hãy mở Shizuku trước.");});
        findViewById(R.id.overlay).setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));else toast("Đã có quyền cửa sổ nổi.");});
        findViewById(R.id.accessibility).setOnClickListener(v->{
            try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            catch(Exception e){toast("Không mở được cài đặt Trợ năng: "+e.getMessage());}
        });
        findViewById(R.id.vneidMode).setOnClickListener(v->{
            setVneidCompatibilityMode(true);
            toast("Đã khóa chế độ VNeID. Macro Shizuku sẽ không tự bật lại Trợ năng.");
        });
        findViewById(R.id.restoreAccessibility).setOnClickListener(v->{
            setVneidCompatibilityMode(false);
            toast("Đã mở khóa chế độ VNeID. Bạn có thể bật Trợ năng thủ công khi cần.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        // Re-apply the user's VNeID choice whenever the app is opened.
        if(getSharedPreferences("compatibility",MODE_PRIVATE).getBoolean("vneid_mode",false))
            setVneidCompatibilityMode(true);
        speed.setProgress(100);speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){float x=Math.max(.25f,p/100f);speedText.setText(String.format(Locale.US,"Tốc độ %.2fx",x));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        findViewById(R.id.record).setOnClickListener(v->{
            String macroName=name.getText().toString().trim();
            if(macroName.isEmpty())macroName="Macro "+(MacroStore.all(this).size()+1);
            if(Build.VERSION.SDK_INT<34){toast("Ghi chạm yêu cầu Android 14 trở lên.");return;}
            if(!TouchRecordService.isConnected()){toast("Hãy bật Macro Shizuku trong cài đặt Trợ năng trước.");return;}
            final String finalName=macroName;
            new AlertDialog.Builder(this).setTitle("Lưu ý khi ghi macro")
                .setMessage("Trong lúc ghi, Android sẽ chuyển quyền nhận cảm ứng cho dịch vụ Macro Shizuku. Vì vậy màn hình sẽ không thao tác được trong thời gian ghi. Nhấn Volume Down hoặc nút Dừng để kết thúc.")
                .setPositiveButton("Bắt đầu ghi",(d,w)->{
                    if(TouchRecordService.beginRecording()){
                        name.setTag(finalName);
                        toast("Đang ghi: "+finalName+" • Cảm ứng tạm khóa • Volume Down để kết thúc");
                    }else toast(TouchRecordService.getCaptureError().isEmpty()?"Không bật được chế độ ghi chạm.":TouchRecordService.getCaptureError());
                }).setNegativeButton("Hủy",null).show();
        });
        findViewById(R.id.stop).setOnClickListener(v->{
            if(!TouchRecordService.recording && !TouchRecordService.hasPendingCapture()){toast("Chưa có bản ghi để lưu.");return;}
            List<MacroStore.Action> captured=TouchRecordService.endRecording();
            if(captured==null||captured.isEmpty()){
                String detail=TouchRecordService.getCaptureError();
                toast(detail==null||detail.trim().isEmpty()
                    ?"Không có thao tác nào được ghi. Macro trống không được lưu. Hãy kiểm tra quyền Shizuku và thử lại."
                    :detail+" Macro trống không được lưu.");
                return;
            }
            String n=String.valueOf(name.getTag());
            if(n.equals("null")||n.trim().isEmpty())n="Macro "+(MacroStore.all(this).size()+1);
            MacroStore.Macro m=new MacroStore.Macro("m"+System.currentTimeMillis(),n);
            m.speed=Math.max(.25f,speed.getProgress()/100f);m.loops=loopValue();m.actions=captured;m.floating=true;
            MacroStore.add(this,m);TouchRecordService.clearPendingCapture();toast("Đã lưu "+n+" • "+m.actions.size()+" thao tác");refresh();
        });
        findViewById(R.id.floatBtn).setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){toast("Hãy cấp quyền cửa sổ nổi.");return;}startService(new Intent(this,FloatingService.class));});
        SeekBar floatSize=findViewById(R.id.floatSize);
        SeekBar floatWidth=findViewById(R.id.floatWidth);
        SeekBar floatAlpha=findViewById(R.id.floatAlpha);
        TextView floatSizeText=findViewById(R.id.floatSizeText);
        TextView floatWidthText=findViewById(R.id.floatWidthText);
        TextView floatAlphaText=findViewById(R.id.floatAlphaText);
        android.content.SharedPreferences floatPrefs=getSharedPreferences("floating_style",MODE_PRIVATE);
        floatSize.setProgress(Math.max(0,Math.min(352,floatPrefs.getInt("size",72)-48)));
        floatWidth.setProgress(Math.max(0,Math.min(352,floatPrefs.getInt("width",floatSize.getProgress()+48)-48)));
        floatAlpha.setProgress(floatPrefs.getInt("alpha",85));
        floatSizeText.setText("Chiều cao: "+(floatSize.getProgress()+48)+" dp");
        floatWidthText.setText("Chiều rộng: "+(floatWidth.getProgress()+48)+" dp");
        floatAlphaText.setText("Độ trong suốt: "+floatAlpha.getProgress()+"%");
        SeekBar.OnSeekBarChangeListener styleListener=new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                floatSizeText.setText("Chiều cao: "+(floatSize.getProgress()+48)+" dp");
                floatWidthText.setText("Chiều rộng: "+(floatWidth.getProgress()+48)+" dp");
                floatAlphaText.setText("Độ trong suốt: "+floatAlpha.getProgress()+"%");
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){
                floatPrefs.edit().putInt("size",Math.min(400,floatSize.getProgress()+48)).putInt("width",Math.min(400,floatWidth.getProgress()+48)).putInt("alpha",floatAlpha.getProgress()).apply();
                if(FloatingService.isRunning && Settings.canDrawOverlays(MainActivity.this))
                    startService(new Intent(MainActivity.this,FloatingService.class).setAction("REFRESH"));
            }
        };
        findViewById(R.id.floatColor).setOnClickListener(v->{
            String[] labels={"Xanh navy","Xanh dương","Xanh cyan","Tím","Đỏ","Cam","Xanh lá","Đen","Trắng"};
            int[] colors={0xFF202F4A,0xFF2457C5,0xFF00A6C7,0xFF7040B8,0xFFC83E5A,0xFFE07824,0xFF258653,0xFF151515,0xFFEEEEEE};
            new AlertDialog.Builder(this).setTitle("Màu nút macro").setItems(labels,(d,w)->{floatPrefs.edit().putInt("color",colors[w]).apply();if(FloatingService.isRunning&&Settings.canDrawOverlays(this))startService(new Intent(this,FloatingService.class).setAction("REFRESH"));}).setNegativeButton("Hủy",null).show();
        });
        floatSize.setOnSeekBarChangeListener(styleListener);
        floatWidth.setOnSeekBarChangeListener(styleListener);
        floatAlpha.setOnSeekBarChangeListener(styleListener);

        findViewById(R.id.stopAll).setOnClickListener(v->Player.stopAll());
        findViewById(R.id.saveHotkey).setOnClickListener(v->saveHotkeys());
        findViewById(R.id.exportBtn).setOnClickListener(v->exportData()); findViewById(R.id.importBtn).setOnClickListener(v->importData());
        refresh();
    }
    private void setVneidCompatibilityMode(boolean enabled){
        getSharedPreferences("compatibility",MODE_PRIVATE).edit().putBoolean("vneid_mode",enabled).commit();
        ComponentName service=new ComponentName(this,TouchRecordService.class);
        getPackageManager().setComponentEnabledSetting(service,
            enabled?PackageManager.COMPONENT_ENABLED_STATE_DISABLED:PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP);
        android.util.Log.i("MacroShizuku","VNeID compatibility mode="+enabled);
    }
    private void saveHotkeys(){String id="";int pos=hotkeyMacro.getSelectedItemPosition();List<MacroStore.Macro> ms=MacroStore.all(this);if(pos>=0&&pos<ms.size())id=ms.get(pos).id;getSharedPreferences("hotkeys",MODE_PRIVATE).edit().putBoolean("enabled",hotkeyEnabled.isChecked()).putString("selected_id",id).apply();toast("Đã lưu: Volume Up = chạy 1 lần • Volume Down = STOP ALL (trừ khi đang ghi)");}
    private int loopValue(){int p=loops.getSelectedItemPosition();return p==0?1:p==1?5:p==2?10:0;}
    private void updateStatusIndicators(){
        setStatus(R.id.statusShizuku,"SHIZUKU",Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED);
        setStatus(R.id.statusOverlay,"CỬA SỔ NỔI",Settings.canDrawOverlays(this));
        String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component=getPackageName()+"/"+TouchRecordService.class.getName();
        boolean accessibility=false;
        if(enabled!=null)for(String item:enabled.split(":")){ComponentName cn=ComponentName.unflattenFromString(item);if(cn!=null && component.equals(cn.flattenToString())){accessibility=true;break;}}
        setStatus(R.id.statusAccessibility,"TRỢ NĂNG",accessibility);
    }
    private void setStatus(int id,String label,boolean enabled){
        TextView v=findViewById(id);if(v==null)return;
        v.setText((enabled?"●":"●")+"  "+label+": "+(enabled?"ĐÃ BẬT":"CHƯA BẬT"));
        v.setTextColor(enabled?0xFF42E6A4:0xFFFF718B);
    }
    private void refresh(){updateStatusIndicators();list.removeAllViews();List<MacroStore.Macro> ms=MacroStore.all(this);String selected=getSharedPreferences("hotkeys",MODE_PRIVATE).getString("selected_id","");ArrayList<String> names=new ArrayList<>();for(MacroStore.Macro m:ms)names.add(m.name);hotkeyMacro.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));for(int i=0;i<ms.size();i++)if(ms.get(i).id.equals(selected))hotkeyMacro.setSelection(i);if(ms.isEmpty()){TextView e=new TextView(this);e.setText("Chưa có macro. Bấm Ghi macro để tạo macro đầu tiên.");e.setPadding(8,24,8,24);list.addView(e);return;}for(MacroStore.Macro m:ms){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(12,12,12,12);card.setBackgroundResource(R.drawable.bg_panel);TextView t=new TextView(this);t.setText(m.name+"\n"+String.format(Locale.US,"%.2fx",m.speed)+" • "+(m.loops==0?"∞":m.loops)+" lần • "+m.actions.size()+" bước");t.setTextSize(16);t.setTextColor(0xFFF4F7FF);card.addView(t);Button play=btn("▶ Chạy");Button stop=btn("■ Dừng");Button edit=btn("Sửa");Button steps=btn("Bước");Button clone=btn("Nhân bản");Button del=btn("Xóa");card.addView(buttonRow(play,stop,edit));card.addView(buttonRow(steps,clone,del));list.addView(card);play.setOnClickListener(v->Player.play(this,m));stop.setOnClickListener(v->Player.stop(m.id));edit.setOnClickListener(v->editMacro(m));steps.setOnClickListener(v->editActions(m));clone.setOnClickListener(v->{MacroStore.Macro c=copy(m);c.id="m"+System.currentTimeMillis();c.name=m.name+" copy";MacroStore.add(this,c);refresh();});del.setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("Xóa macro?").setMessage(m.name).setPositiveButton("Xóa",(d,w)->{Player.stop(m.id);MacroStore.delete(this,m.id);refresh();}).setNegativeButton("Hủy",null).show();});}}
    private void editActions(MacroStore.Macro m){
        if(m.actions==null||m.actions.isEmpty()){toast("Macro chưa có thao tác.");return;}
        String[] labels=new String[m.actions.size()];
        for(int i=0;i<m.actions.size();i++){MacroStore.Action a=m.actions.get(i);labels[i]=(i+1)+". "+a.type.toUpperCase(Locale.ROOT)+" • "+Math.round(a.x1)+","+Math.round(a.y1)+( "swipe".equals(a.type)?" → "+Math.round(a.x2)+","+Math.round(a.y2):"");}
        new AlertDialog.Builder(this).setTitle("Chọn thao tác cần chỉnh").setItems(labels,(dialog,which)->editAction(m,which)).setNegativeButton("Đóng",null).show();
    }
    private EditText numberField(LinearLayout box,String label,String value,boolean decimal){
        TextView title=new TextView(this);title.setText(label);title.setTextColor(0xFFB8C7E5);box.addView(title);
        EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setTextColor(0xFFFFFFFF);e.setHintTextColor(0xFF8795B2);
        e.setInputType(decimal?8194:2);box.addView(e);return e;
    }
    private void editAction(MacroStore.Macro m,int index){
        if(index<0||index>=m.actions.size())return;
        MacroStore.Action a=m.actions.get(index);boolean swipe="swipe".equals(a.type);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(28,8,28,8);
        EditText x1=numberField(box,"X bắt đầu",String.valueOf(a.x1),true);
        EditText y1=numberField(box,"Y bắt đầu",String.valueOf(a.y1),true);
        EditText x2=null,y2=null;
        if(swipe){x2=numberField(box,"X kết thúc",String.valueOf(a.x2),true);y2=numberField(box,"Y kết thúc",String.valueOf(a.y2),true);}
        EditText delay=numberField(box,"Độ trễ trước thao tác (ms)",String.valueOf(a.delay),false);
        EditText duration=numberField(box,"Thời lượng vuốt (ms)",String.valueOf(a.duration),false);
        final EditText fx2=x2,fy2=y2;
        new AlertDialog.Builder(this).setTitle("Chỉnh "+a.type+" #"+(index+1)).setView(box)
            .setPositiveButton("Lưu",(d,w)->{try{
                a.x1=Math.max(0,Float.parseFloat(x1.getText().toString().trim()));
                a.y1=Math.max(0,Float.parseFloat(y1.getText().toString().trim()));
                if(swipe){a.x2=Math.max(0,Float.parseFloat(fx2.getText().toString().trim()));a.y2=Math.max(0,Float.parseFloat(fy2.getText().toString().trim()));}
                a.delay=Math.max(0,Long.parseLong(delay.getText().toString().trim()));
                a.duration=Math.max(1,Long.parseLong(duration.getText().toString().trim()));
                MacroStore.upsert(this,m);toast("Đã cập nhật thao tác "+(index+1));refresh();
            }catch(Exception ex){toast("Giá trị không hợp lệ. Hãy kiểm tra tọa độ và thời gian.");}}).setNegativeButton("Hủy",null).show();
    }
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setMinWidth(0);b.setPadding(3,0,3,0);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF173B63));b.setTextColor(0xFFEAF7FF);return b;}
    private LinearLayout buttonRow(Button... items){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);for(Button b:items){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);p.setMargins(3,2,3,2);row.addView(b,p);}return row;}
    private MacroStore.Macro copy(MacroStore.Macro m){MacroStore.Macro c=new MacroStore.Macro("",m.name);c.speed=m.speed;c.loops=m.loops;c.floating=m.floating;c.buttonWidth=m.buttonWidth;c.buttonHeight=m.buttonHeight;for(MacroStore.Action a:m.actions)c.actions.add(new MacroStore.Action(a.type,a.x1,a.y1,a.x2,a.y2,a.delay,a.duration));return c;}
    private void editMacro(MacroStore.Macro m){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(30,10,30,10);
        EditText n=new EditText(this);n.setText(m.name);box.addView(n);
        SeekBar s=new SeekBar(this);s.setMax(400);s.setProgress(Math.round(m.speed*100));box.addView(s);
        Spinner l=new Spinner(this);l.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"1 lần","5 lần","10 lần","Vô hạn"}));l.setSelection(m.loops==1?0:m.loops==5?1:m.loops==10?2:3);box.addView(l);
        CheckBox f=new CheckBox(this);f.setText("Hiện nút nổi cho macro này");f.setChecked(m.floating);box.addView(f);
        TextView widthLabel=new TextView(this);widthLabel.setTextColor(0xFFF4F7FF);box.addView(widthLabel);
        SeekBar widthBar=new SeekBar(this);widthBar.setMax(352);widthBar.setProgress(Math.max(0,(m.buttonWidth>0?m.buttonWidth:getSharedPreferences("floating_style",MODE_PRIVATE).getInt("width",72))-48));box.addView(widthBar);
        TextView heightLabel=new TextView(this);heightLabel.setTextColor(0xFFF4F7FF);box.addView(heightLabel);
        SeekBar heightBar=new SeekBar(this);heightBar.setMax(352);heightBar.setProgress(Math.max(0,(m.buttonHeight>0?m.buttonHeight:getSharedPreferences("floating_style",MODE_PRIVATE).getInt("size",72))-48));box.addView(heightBar);
        Runnable updateDimensions=()->{widthLabel.setText("Chiều rộng riêng: "+(widthBar.getProgress()+48)+" dp");heightLabel.setText("Chiều cao riêng: "+(heightBar.getProgress()+48)+" dp");};
        widthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){updateDimensions.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        heightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){updateDimensions.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        updateDimensions.run();
        TextView hint=new TextView(this);hint.setText("Kích thước được lưu riêng cho macro này (48–400 dp).");hint.setTextColor(0xFF8292B2);box.addView(hint);
        new AlertDialog.Builder(this).setTitle("Sửa macro").setView(box).setPositiveButton("Lưu",(d,w)->{
            m.name=n.getText().toString().trim();m.speed=Math.max(.25f,s.getProgress()/100f);
            m.loops=l.getSelectedItemPosition()==0?1:l.getSelectedItemPosition()==1?5:l.getSelectedItemPosition()==2?10:0;
            m.floating=f.isChecked();m.buttonWidth=widthBar.getProgress()+48;m.buttonHeight=heightBar.getProgress()+48;
            MacroStore.upsert(this,m);if(Settings.canDrawOverlays(this))startService(new Intent(this,FloatingService.class).setAction("REFRESH"));refresh();
        }).setNegativeButton("Hủy",null).show();
    }
    private void exportData(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"macroshizuku-v3-backup.json");startActivityForResult(i,EXPORT);}
    private void importData(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/json");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(c!=RESULT_OK||d==null)return;try{if(r==EXPORT){OutputStream o=getContentResolver().openOutputStream(d.getData());o.write(MacroStore.exportJson(this).getBytes("UTF-8"));o.close();toast("Đã xuất backup.");}else{InputStream i=getContentResolver().openInputStream(d.getData());ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] z=new byte[8192];int n;while((n=i.read(z))>0)b.write(z,0,n);i.close();MacroStore.importJson(this,b.toString("UTF-8"));refresh();toast("Đã nhập macro.");}}catch(Exception e){toast("Không thể xử lý file: "+e.getMessage());}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permissionListener);super.onDestroy();}
}
