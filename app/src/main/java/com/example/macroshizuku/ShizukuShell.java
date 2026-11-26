package com.example.macroshizuku;
import android.content.pm.PackageManager;
import java.io.*;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;
public final class ShizukuShell {
 private ShizukuShell(){}
 public static boolean ready(){try{return Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Throwable t){return false;}}
 public static void request(int code){try{Shizuku.requestPermission(code);}catch(Throwable ignored){}}
 private static String readAll(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] z=new byte[8192];int n;while((n=in.read(z))!=-1)b.write(z,0,n);return new String(b.toByteArray(),"UTF-8");}
 private static Object newProcess(String...cmd)throws Exception{Method m=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);m.setAccessible(true);return m.invoke(null,(Object)cmd,null,null);}
 public static Object start(String...cmd)throws Exception{return newProcess(cmd);}
 public static InputStream stdout(Object p)throws Exception{return (InputStream)p.getClass().getMethod("getInputStream").invoke(p);}
 public static InputStream stderr(Object p)throws Exception{return (InputStream)p.getClass().getMethod("getErrorStream").invoke(p);}
 public static void destroy(Object p){if(p!=null)try{p.getClass().getMethod("destroy").invoke(p);}catch(Exception ignored){}}
 public static String run(String...cmd)throws Exception{Object p=newProcess(cmd);InputStream in=stdout(p),err=stderr(p);AtomicReference<String> out=new AtomicReference<>("");Thread t=new Thread(()->{try{out.set(readAll(in));}catch(Exception ignored){}});t.start();String e=readAll(err);t.join(2500);destroy(p);return out.get().trim()+(e.isEmpty()?"":"\nERR: "+e.trim());}
}