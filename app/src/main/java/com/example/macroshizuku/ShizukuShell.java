package com.example.macroshizuku;

import android.content.pm.PackageManager;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;

public final class ShizukuShell {
    private ShizukuShell() {}
    public static boolean ready(){
        try{return Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Throwable t){return false;}
    }
    public static void request(int code){ try{Shizuku.requestPermission(code);}catch(Throwable ignored){} }
    private static String readAll(InputStream in) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1)b.write(buf,0,n); return new String(b.toByteArray()); }
    public static String run(String... cmd) throws Exception {
        Method m=Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Object p=m.invoke(null,(Object)cmd,null,null);
        InputStream in=(InputStream)p.getClass().getMethod("getInputStream").invoke(p);
        InputStream err=(InputStream)p.getClass().getMethod("getErrorStream").invoke(p);
        AtomicReference<String> out=new AtomicReference<>("");
        Thread t=new Thread(()->{try{out.set(readAll(in));}catch(Exception ignored){}}); t.start();
        String e=readAll(err);
        t.join(2000);
        try{p.getClass().getMethod("destroy").invoke(p);}catch(Exception ignored){}
        return out.get().trim()+(e.isEmpty()?"":"\nERR: "+e.trim());
    }
}
