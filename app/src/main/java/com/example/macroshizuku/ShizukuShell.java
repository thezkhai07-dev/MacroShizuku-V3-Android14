package com.example.macroshizuku;

import android.content.pm.PackageManager;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;

public final class ShizukuShell {
    private ShizukuShell(){}
    public static boolean ready(){
        try{return Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(Throwable t){return false;}
    }
    public static void request(int code){try{Shizuku.requestPermission(code);}catch(Throwable ignored){}}
    private static Object newProcess(String...cmd)throws Exception{
        Method m=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);
        m.setAccessible(true);
        return m.invoke(null,(Object)cmd,null,null);
    }
    public static Object start(String...cmd)throws Exception{return newProcess(cmd);}
    public static InputStream stdout(Object p)throws Exception{return (InputStream)p.getClass().getMethod("getInputStream").invoke(p);}
    public static InputStream stderr(Object p)throws Exception{return (InputStream)p.getClass().getMethod("getErrorStream").invoke(p);}
    public static void destroy(Object p){if(p!=null)try{p.getClass().getMethod("destroy").invoke(p);}catch(Exception ignored){}}
    private static String readAll(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        byte[] z=new byte[8192];int n;
        while((n=in.read(z))!=-1)b.write(z,0,n);
        return new String(b.toByteArray(),"UTF-8");
    }
    public static String run(String...cmd)throws Exception{
        final Object p=newProcess(cmd);
        final InputStream outStream=stdout(p),errStream=stderr(p);
        final AtomicReference<String> out=new AtomicReference<>("");
        final AtomicReference<String> err=new AtomicReference<>("");
        final AtomicReference<IOException> readError=new AtomicReference<>();
        Thread outThread=new Thread(()->{try{out.set(readAll(outStream));}catch(IOException e){readError.compareAndSet(null,e);}},"shizuku-stdout");
        Thread errThread=new Thread(()->{try{err.set(readAll(errStream));}catch(IOException e){readError.compareAndSet(null,e);}},"shizuku-stderr");
        outThread.setDaemon(true);errThread.setDaemon(true);
        outThread.start();errThread.start();
        try{
            Method waitFor=p.getClass().getMethod("waitFor");
            int exit=((Number)waitFor.invoke(p)).intValue();
            outThread.join();errThread.join();
            IOException streamError=readError.get();
            if(streamError!=null)throw streamError;
            String output=out.get().trim();
            String errors=err.get().trim();
            if(exit!=0)throw new IOException("Lệnh Shizuku thất bại (exit "+exit+"): "+errors);
            return output+(errors.isEmpty()?"":"\n"+errors);
        }catch(InvocationTargetException e){
            destroy(p);
            Throwable cause=e.getCause();
            if(cause instanceof InterruptedException){Thread.currentThread().interrupt();throw (InterruptedException)cause;}
            if(cause instanceof Exception)throw (Exception)cause;
            throw new RuntimeException(cause);
        }catch(InterruptedException e){
            destroy(p);Thread.currentThread().interrupt();throw e;
        }catch(Exception e){
            destroy(p);throw e;
        }
    }
}
