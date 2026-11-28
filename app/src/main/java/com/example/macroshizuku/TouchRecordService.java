package com.example.macroshizuku;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Touch recording is performed by reading the Linux input stream through the
 * Shizuku shell identity. No Accessibility MotionEvent source is requested,
 * so events continue to reach the app currently under the user's finger.
 */
public class TouchRecordService extends AccessibilityService {
    public static volatile boolean recording = false;
    private static volatile TouchRecordService instance;
    private static volatile boolean pendingCapture = false;
    private static final List<MacroStore.Action> actions = new ArrayList<>();
    private static volatile String captureError = "";
    private static volatile Object touchProcess;
    private static volatile Thread touchThread;
    private static volatile int screenW = 0, screenH = 0, displayRotation = 0;
    private static volatile int rawMinX = 0, rawMaxX = 1, rawMinY = 0, rawMaxY = 1;
    private static volatile long lastActionTime = 0;
    private static final Object recorderLock = new Object();

    private volatile Object hotkeyProcess;
    private volatile Thread hotkeyThread;
    private static volatile long lastHotkeyDispatchMs = 0;
    private static volatile long consumeVolumeDownUntilMs = 0;
    private static Toast stopRecordingToast;
    private android.view.View stopOverlay;
    private final android.os.Handler stopOverlayHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile int lastHotkeyDispatchCode = 0;
    private static volatile boolean volumeUpPressed = false;
    private volatile String volumeUpMacroId = "";
    private volatile boolean volumeUpOneShot = false;

    private static final class TouchDevice {
        String path;
        String name;
        int minX, maxX, minY, maxY;
    }

    private static final class PointerState {
        float x, y, startX, startY;
        boolean hasX, hasY, pendingDown, active;
        long downTime;
    }

    private static final class TouchParser {
        final Map<Integer, PointerState> pointers = new HashMap<>();
        int slot = 0;
        final int minX, maxX, minY, maxY, width, height, rotation;
        long lastAction = 0;

        TouchParser(int minX, int maxX, int minY, int maxY, int width, int height, int rotation) {
            this.minX = minX; this.maxX = Math.max(minX + 1, maxX);
            this.minY = minY; this.maxY = Math.max(minY + 1, maxY);
            this.width = Math.max(1, width); this.height = Math.max(1, height);
            this.rotation = rotation;
        }

        private PointerState current() {
            PointerState p = pointers.get(slot);
            if (p == null) { p = new PointerState(); pointers.put(slot, p); }
            return p;
        }

        private static long parseHex(String s) {
            if (s == null) return 0;
            try {
                // getevent reports ABS_MT_TRACKING_ID=-1 as the unsigned
                // 32-bit value "ffffffff". Long.parseLong() rejects that
                // value because it exceeds Long.MAX_VALUE for signed parsing.
                // Parse it as unsigned so finger-up is correctly recognized.
                long v = Long.parseUnsignedLong(s.trim(), 16);
                return (v == 0xffffffffL || v == 0xffffffffffffffffL) ? -1L : v;
            } catch (Exception e) {
                android.util.Log.w("TouchRecordService", "Invalid getevent hex value: " + s);
                return 0;
            }
        }

        private float[] mapped(float rawX, float rawY) {
            float nx = clamp((rawX - minX) / (float)(maxX - minX));
            float ny = clamp((rawY - minY) / (float)(maxY - minY));
            float x = nx, y = ny;
            // getevent reports kernel touchscreen coordinates in the panel's
            // natural coordinate space. Convert them exactly once using Android's
            // display rotation. Axis-range aspect ratios are NOT a reliable signal
            // that the driver has already rotated coordinates: ranges describe the
            // physical sensor and can remain landscape-shaped in every orientation.
            if (rotation == SurfaceRotation.ROTATION_90) {
                // Vivo X100s reports raw X along the physical vertical axis
                // and raw Y along the physical horizontal axis in landscape.
                // Four-corner calibration confirms this mapping for rotation=1.
                x = ny; y = 1f - nx;
            } else if (rotation == SurfaceRotation.ROTATION_180) {
                x = 1f - nx; y = 1f - ny;
            } else if (rotation == SurfaceRotation.ROTATION_270) {
                x = 1f - ny; y = nx;
            }
            // Store display-relative fractions so playback adapts to orientation and resolution.
            return new float[]{clamp(x), clamp(y)};
        }

        private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

        private void finish(PointerState p, long now) {
            if (p == null || !p.active) return;
            float[] a = mapped(p.startX, p.startY);
            float[] b = mapped(p.x, p.y);
            long delay = lastAction == 0 ? 0 : Math.max(0, p.downTime - lastAction);
            long duration = Math.max(1, now - p.downTime);
            String actionType = Math.hypot((b[0] - a[0]) * (width - 1), (b[1] - a[1]) * (height - 1)) < 12 ? "tap" : "swipe";
            synchronized (actions) {
                if ("tap".equals(actionType)) {
                    actions.add(new MacroStore.Action("tap", a[0], a[1], a[0], a[1], delay, duration));
                } else {
                    actions.add(new MacroStore.Action("swipe", a[0], a[1], b[0], b[1], delay, Math.max(60, duration)));
                }
            }
            android.util.Log.i("TouchRecordService", "RECORDED " + actionType
                + " raw=(" + p.startX + "," + p.startY + ")->(" + p.x + "," + p.y + ")"
                + " normalized=(" + a[0] + "," + a[1] + ")->(" + b[0] + "," + b[1] + ")"
                + " display=" + width + "x" + height + " rotation=" + rotation);
            lastAction = now;
            p.active = false;
            p.pendingDown = false;
        }

        private final Pattern eventPattern = Pattern.compile("\\b(EV_ABS|EV_KEY|EV_SYN)\\s+([A-Z0-9_]+)\\s+([0-9a-fA-F]+|DOWN|UP)\\b");

        void accept(String line) {
            if (!recording || line == null) return;
            Matcher event = eventPattern.matcher(line);
            if (!event.find()) return;

            String type = event.group(1);
            String code = event.group(2);
            String valueText = event.group(3);
            long now = SystemClock.elapsedRealtime();

            if ("EV_ABS".equals(type)) {
                if ("ABS_MT_SLOT".equals(code)) {
                    slot = (int)Math.max(0, Math.min(15, parseHex(valueText)));
                    return;
                }

                PointerState p = current();
                if ("ABS_MT_TRACKING_ID".equals(code)) {
                    long trackingId = parseHex(valueText);
                    if (trackingId < 0) {
                        finish(p, now);
                    } else {
                        if (p.active) finish(p, now);
                        p.pendingDown = true;
                        p.active = false;
                        p.hasX = false;
                        p.hasY = false;
                    }
                } else if ("ABS_MT_POSITION_X".equals(code) || "ABS_X".equals(code)) {
                    p.x = parseHex(valueText);
                    p.hasX = true;
                } else if ("ABS_MT_POSITION_Y".equals(code) || "ABS_Y".equals(code)) {
                    p.y = parseHex(valueText);
                    p.hasY = true;
                }
                return;
            }

            if ("EV_KEY".equals(type) && "BTN_TOUCH".equals(code)) {
                PointerState p = pointers.get(0);
                if (p == null) {
                    p = new PointerState();
                    pointers.put(0, p);
                }
                if ("DOWN".equals(valueText) || "00000001".equalsIgnoreCase(valueText)) {
                    p.pendingDown = true;
                    p.active = false;
                } else if ("UP".equals(valueText) || "00000000".equalsIgnoreCase(valueText)) {
                    finish(p, now);
                }
                return;
            }

            if ("EV_SYN".equals(type) && "SYN_REPORT".equals(code)) {
                for (PointerState p : pointers.values()) {
                    if (p.pendingDown && p.hasX && p.hasY) {
                        p.startX = p.x;
                        p.startY = p.y;
                        p.downTime = now;
                        p.pendingDown = false;
                        p.active = true;
                    }
                }
            }
        }
    }

    // Avoid depending on newer display APIs; values match Display.getRotation().
    private static final class SurfaceRotation {
        static final int ROTATION_0 = 0, ROTATION_90 = 1, ROTATION_180 = 2, ROTATION_270 = 3;
    }

    public static void clear() { synchronized (actions) { actions.clear(); } }
    public static List<MacroStore.Action> snapshot() { synchronized (actions) { return new ArrayList<>(actions); } }
    public static boolean isConnected() { return instance != null; }
    public static boolean hasPendingCapture() { return pendingCapture; }
    public static String getCaptureError() { return captureError; }
    public static void clearPendingCapture() { pendingCapture = false; synchronized (actions) { actions.clear(); } }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            // Deliberately do not call setMotionEventSources(): it steals touch from other apps.
            setServiceInfo(info);
        } catch (Exception e) {
            android.util.Log.e("TouchRecordService", "Unable to initialize key filtering", e);
        }
        startHotkeyMonitor();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        android.util.Log.w("TouchRecordService", "Accessibility service unbound");
        endRecording();
        stopHotkeyMonitor();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    /** Starts a non-intercepting Linux input reader. Accessibility can remain disabled. */
    public static boolean beginRecording(Context context) {
        synchronized (recorderLock) {
            if (recording) return false;
            captureError = "";
            pendingCapture = false;
            if (!ShizukuShell.ready()) {
                captureError = "Shizuku Next chưa kết nối hoặc Macro By Nhật ĐZ chưa được cấp quyền Shizuku.";
                return false;
            }
            try {
                String listing = ShizukuShell.run("getevent", "-lp");
                TouchDevice device = findTouchDevice(listing);
                if (device == null) {
                    captureError = "Không tìm thấy thiết bị màn hình cảm ứng qua Shizuku. Hãy kiểm tra quyền Shizuku.\n" + shortOutput(listing);
                    return false;
                }
                WindowManager wm = (WindowManager) context.getApplicationContext().getSystemService(WINDOW_SERVICE);
                Display display = wm == null ? null : wm.getDefaultDisplay();
                DisplayMetrics dm = new DisplayMetrics();
                if (display != null) display.getRealMetrics(dm);
                screenW = Math.max(1, dm.widthPixels);
                screenH = Math.max(1, dm.heightPixels);
                displayRotation = display == null ? 0 : display.getRotation();
                rawMinX = device.minX; rawMaxX = device.maxX;
                rawMinY = device.minY; rawMaxY = device.maxY;
                synchronized (actions) { actions.clear(); }
                lastActionTime = 0;
                final TouchParser parser = new TouchParser(device.minX, device.maxX, device.minY, device.maxY, screenW, screenH, displayRotation);
                final Object process = ShizukuShell.start("getevent", "-lt", device.path);
                touchProcess = process;
                recording = true;
                touchThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(ShizukuShell.stdout(process), "UTF-8"))) {
                        String line;
                        while (recording && (line = reader.readLine()) != null) parser.accept(line);
                        if (recording) {
                            captureError = "Luồng cảm ứng đã dừng đột ngột. Kiểm tra quyền đọc thiết bị input của Shizuku.";
                            recording = false;
                        }
                    } catch (Exception e) {
                        if (recording) captureError = "Không đọc được cảm ứng: " + e.getMessage();
                        recording = false;
                        android.util.Log.e("TouchRecordService", "Touch input reader failed", e);
                    } finally {
                        if (touchProcess == process) touchProcess = null;
                    }
                }, "shizuku-touch-recorder");
                touchThread.setDaemon(true);
                touchThread.start();
                android.util.Log.i("TouchRecordService", "Touch recording started: " + device.name + " " + device.path
                    + " rawX=" + device.minX + ".." + device.maxX + " rawY=" + device.minY + ".." + device.maxY
                    + " display=" + screenW + "x" + screenH + " rotation=" + displayRotation
                    + " rawRangeX=" + device.minX + ".." + device.maxX
                    + " rawRangeY=" + device.minY + ".." + device.maxY);
                return true;
            } catch (Exception e) {
                recording = false;
                captureError = "Không khởi động được ghi cảm ứng bằng Shizuku: " + e.getMessage();
                android.util.Log.e("TouchRecordService", "Unable to start Shizuku touch recorder", e);
                ShizukuShell.destroy(touchProcess);
                touchProcess = null;
                return false;
            }
        }
    }

    private static TouchDevice findTouchDevice(String output) {
        if (output == null) return null;
        String[] blocks = output.split("(?=add device\\s+\\d+:)");
        TouchDevice best = null;
        int bestScore = -1;
        Pattern pathPattern = Pattern.compile("(/dev/input/event\\d+)");
        Pattern namePattern = Pattern.compile("name:\\s*\\x22([^\\x22]*)\\x22");
        Pattern mtX = Pattern.compile("ABS_MT_POSITION_X\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
        Pattern mtY = Pattern.compile("ABS_MT_POSITION_Y\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
        Pattern absX = Pattern.compile("ABS_X\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
        Pattern absY = Pattern.compile("ABS_Y\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
        for (String block : blocks) {
            Matcher path = pathPattern.matcher(block);
            Matcher name = namePattern.matcher(block);
            if (!path.find()) continue;
            TouchDevice d = new TouchDevice();
            d.path = path.group(1);
            d.name = name.find() ? name.group(1) : d.path;
            Matcher x = mtX.matcher(block), y = mtY.matcher(block);
            if (!x.find() || !y.find()) {
                x = absX.matcher(block); y = absY.matcher(block);
                if (!x.find() || !y.find()) continue;
            }
            try {
                d.minX = Integer.parseInt(x.group(1)); d.maxX = Integer.parseInt(x.group(2));
                d.minY = Integer.parseInt(y.group(1)); d.maxY = Integer.parseInt(y.group(2));
            } catch (Exception ignored) { continue; }
            if (d.maxX <= d.minX || d.maxY <= d.minY) continue;
            int score = d.name.toLowerCase(Locale.ROOT).contains("touch") ? 10 : 0;
            if (block.contains("INPUT_PROP_DIRECT")) score += 5;
            if (block.contains("ABS_MT_TRACKING_ID")) score += 2;
            if (score > bestScore) { best = d; bestScore = score; }
        }
        return best;
    }

    private static String shortOutput(String s) {
        if (s == null || s.trim().isEmpty()) return "getevent -lp không trả về thông tin thiết bị.";
        s = s.trim();
        return s.length() > 900 ? s.substring(0, 900) : s;
    }

    public static List<MacroStore.Action> endRecording() {
        recording = false;
        Object p = touchProcess;
        touchProcess = null;
        ShizukuShell.destroy(p);
        Thread t = touchThread;
        touchThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        List<MacroStore.Action> copy = snapshot();
        pendingCapture = !copy.isEmpty();
        return copy;
    }

    @Override public void onDestroy() {
        endRecording();
        stopOverlayHandler.removeCallbacksAndMessages(null);
        if (stopOverlay != null) { try { ((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(stopOverlay); } catch (Exception ignored) {} stopOverlay = null; }
        stopHotkeyMonitor();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void startHotkeyMonitor() {
        if (hotkeyThread != null && hotkeyThread.isAlive()) return;
        hotkeyThread = new Thread(() -> {
            while (instance == this && !Thread.currentThread().isInterrupted()) {
                Object proc = null;
                try {
                    SharedPreferences prefs = getSharedPreferences("hotkeys", MODE_PRIVATE);
                    if (!prefs.getBoolean("enabled", false) || !ShizukuShell.ready()) {
                        Thread.sleep(1000); continue;
                    }
                    proc = ShizukuShell.start("getevent", "-lt");
                    hotkeyProcess = proc;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(ShizukuShell.stdout(proc), "UTF-8"))) {
                        String line;
                        while (instance == this && !Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                            if (!line.contains("EV_KEY")) continue;
                            if (line.contains("KEY_VOLUMEUP")) {
                                if (line.trim().endsWith("DOWN")) dispatchVolumeHotkey(KeyEvent.KEYCODE_VOLUME_UP, "Shizuku");
                                else if (line.trim().endsWith("UP")) stopVolumeUpPlayback("Shizuku");
                            } else if (line.contains("KEY_VOLUMEDOWN") && line.trim().endsWith("DOWN")) {
                                dispatchVolumeHotkey(KeyEvent.KEYCODE_VOLUME_DOWN, "Shizuku");
                            }
                        }
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { android.util.Log.w("TouchRecordService", "Shizuku volume monitor failed: " + e.getMessage()); }
                finally { if (hotkeyProcess == proc) hotkeyProcess = null; ShizukuShell.destroy(proc); }
                if (instance == this && !Thread.currentThread().isInterrupted()) try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "volume-hotkey-monitor");
        hotkeyThread.setDaemon(true);
        hotkeyThread.start();
    }

    private void stopHotkeyMonitor() {
        Thread t = hotkeyThread; hotkeyThread = null;
        Object p = hotkeyProcess; hotkeyProcess = null;
        ShizukuShell.destroy(p);
        if (t != null) t.interrupt();
    }

    private void dispatchVolumeHotkey(int code, String source) {
        long now = SystemClock.elapsedRealtime();
        synchronized (TouchRecordService.class) {
            // A physical Volume Up hold can be reported by both Accessibility and
            // Shizuku getevent. Latch the whole press, not just a 250 ms time window.
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (volumeUpPressed) return;
                volumeUpPressed = true;
            } else {
                if (code == lastHotkeyDispatchCode && now - lastHotkeyDispatchMs < 250) return;
                lastHotkeyDispatchCode = code; lastHotkeyDispatchMs = now;
            }
        }
        SharedPreferences prefs = getSharedPreferences("hotkeys", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enabled", false);
        android.util.Log.i("TouchRecordService", "Volume hotkey: code=" + code + ", source=" + source + ", recording=" + recording + ", enabled=" + enabled);
        if (recording) {
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) stopRecordingFromVolumeDown();
            return;
        }
        if (!enabled) return;
        if (code == KeyEvent.KEYCODE_VOLUME_UP) playSelectedMacro();
        else if (code == KeyEvent.KEYCODE_VOLUME_DOWN) Player.stopAll();
    }

    private void stopRecordingFromVolumeDown() {
        consumeVolumeDownUntilMs = SystemClock.elapsedRealtime() + 1500;
        if (!recording) return;
        List<MacroStore.Action> stopped = endRecording();
        int count = stopped == null ? 0 : stopped.size();
        String message = count > 0
            ? "Đã dừng ghi macro • " + count + " thao tác. Quay lại app và nhấn LƯU."
            : "Đã dừng ghi macro nhưng chưa có thao tác nào. Hãy ghi lại rồi thử lại.";
        showRecordingStoppedMessage(message);
        android.util.Log.i("TouchRecordService", "Recording stopped by Volume Down; captured actions=" + count);
        // Restore the floating control panel after the recording has ended.
        try {
            Intent refreshIntent = new Intent(this, FloatingService.class);
            refreshIntent.setAction("RECORDING_STOPPED");
            startService(refreshIntent);
        } catch (Exception e) {
            android.util.Log.w("TouchRecordService", "Unable to refresh floating menu after recording: " + e.getMessage());
        }
    }

    private void showRecordingStoppedMessage(String message) {
        stopOverlayHandler.post(() -> {
            try {
                WindowManager manager = (WindowManager)getSystemService(WINDOW_SERVICE);
                if (stopOverlay != null) { try { manager.removeView(stopOverlay); } catch (Exception ignored) {} stopOverlay = null; }
                android.widget.TextView banner = new android.widget.TextView(this);
                banner.setText(message); banner.setTextColor(android.graphics.Color.WHITE); banner.setTextSize(14);
                banner.setGravity(android.view.Gravity.CENTER); banner.setPadding(24, 16, 24, 16);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xEE173B5E); bg.setCornerRadius(36); bg.setStroke(2, 0xFF55CFFF); banner.setBackground(bg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL; lp.y = 72;
                manager.addView(banner, lp); stopOverlay = banner;
                stopOverlayHandler.postDelayed(() -> { if (stopOverlay == banner) { try { manager.removeView(banner); } catch (Exception ignored) {} stopOverlay = null; } }, 4500);
                android.util.Log.i("TouchRecordService", "Shown accessibility overlay stop message: " + message);
            } catch (Exception e) {
                android.util.Log.e("TouchRecordService", "Unable to show accessibility overlay stop message", e);
                try { if (stopRecordingToast != null) stopRecordingToast.cancel(); stopRecordingToast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG); stopRecordingToast.show(); } catch (Exception ignored) {}
            }
        });
    }
    @Override public boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (recording && code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                stopRecordingFromVolumeDown();
                return true;
            }
            dispatchVolumeHotkey(code, "Accessibility");
        } else if (e.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_VOLUME_UP) {
            stopVolumeUpPlayback("Accessibility");
        }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN &&
            SystemClock.elapsedRealtime() <= consumeVolumeDownUntilMs) return true;
        SharedPreferences prefs = getSharedPreferences("hotkeys", MODE_PRIVATE);
        return recording || prefs.getBoolean("enabled", false);
    }

    /** Volume Up starts the selected macro using its saved repeat count. */
    private void playSelectedMacro() {
        String id = getSharedPreferences("hotkeys", MODE_PRIVATE).getString("selected_id", "");
        if (id.isEmpty()) { android.util.Log.w("TouchRecordService", "Volume Up ignored: no selected macro id"); return; }
        MacroStore.Macro m = MacroStore.find(this, id);
        if (m == null) { android.util.Log.w("TouchRecordService", "Volume Up ignored: selected macro not found: " + id); return; }
        volumeUpMacroId = id;
        volumeUpOneShot = (m.loops == 1);
        android.util.Log.i("TouchRecordService", "Volume Up pressed: macro=" + id + ", savedLoops=" + m.loops);
        // Match the floating button: a one-shot macro starts after key release
        // and must be allowed to finish. Repeat modes run while held.
        if (!volumeUpOneShot) Player.play(this, m);
    }

    /** One-shot runs to completion; repeat modes stop when Volume Up is released. */
    private void stopVolumeUpPlayback(String source) {
        final String id;
        final boolean oneShot;
        synchronized (TouchRecordService.class) {
            if (!volumeUpPressed) return;
            volumeUpPressed = false;
            id = volumeUpMacroId;
            oneShot = volumeUpOneShot;
            volumeUpMacroId = "";
            volumeUpOneShot = false;
        }
        if (id.isEmpty()) return;
        if (oneShot) {
            MacroStore.Macro m = MacroStore.find(this, id);
            if (m != null) Player.playOnce(this, m);
        } else {
            Player.stop(id);
        }
        android.util.Log.i("TouchRecordService", "Volume Up released: source=" + source + ", macro=" + id + ", oneShot=" + oneShot);
    }

    @Override public void onMotionEvent(MotionEvent e) {
        // Intentionally empty: recording through Accessibility MotionEvent would block normal touch.
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}