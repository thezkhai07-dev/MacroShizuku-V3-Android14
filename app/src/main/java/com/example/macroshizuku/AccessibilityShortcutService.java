package com.example.macroshizuku;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Small draggable shortcut that opens Android Accessibility settings. */
public class AccessibilityShortcutService extends Service {
    private WindowManager windowManager;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private boolean attached;

    @Override public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setText("♿");
        bubble.setTextSize(23);
        bubble.setTextColor(Color.WHITE);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEE2457C5);
        bg.setStroke(2, 0xFF42D9F5);
        bubble.setBackground(bg);
        bubble.setElevation(8f);
        bubble.setContentDescription("Mở cài đặt Trợ năng Macro Shizuku");
        params = new WindowManager.LayoutParams(
                dp(56), dp(56),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(260);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = params.x; startY = params.y; moved = false;
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved = true;
                    if (moved) {
                        params.x = startX + (int) dx;
                        params.y = startY + (int) dy;
                        try { windowManager.updateViewLayout(bubble, params); } catch (Exception ignored) {}
                    }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved) {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                    return true;
                }
                return true;
            }
        });
        try {
            windowManager.addView(bubble, params);
            attached = true;
        } catch (Exception e) {
            stopSelf();
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (attached && windowManager != null && bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
        }
        attached = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}