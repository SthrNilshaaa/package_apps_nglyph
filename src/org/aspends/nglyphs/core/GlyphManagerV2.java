package org.aspends.nglyphs.core;

import org.aspends.nglyphs.R;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production-ready GlyphManager acting as a persistent system controller for the
 * Nothing Phone LEDs.
 * Provides thread-safe, batched, IPC-optimized access to the hardware paths.
 */
public class GlyphManagerV2 {
    public static final int MAX_BRIGHTNESS = 4095;
    private static final String PATH_ROOT = "/sys/class/leds/aw210xx_led";

    private static GlyphManagerV2 instance;
    private final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();
    private android.os.PowerManager.WakeLock wakeLock;
    private android.content.Context context;

    public static synchronized GlyphManagerV2 getInstance() {
        if (instance == null) {
            instance = new GlyphManagerV2();
        }
        return instance;
    }

    private GlyphManagerV2() {
    }

    public void init(android.content.Context context) {
        this.context = context.getApplicationContext();
        android.os.PowerManager pm = (android.os.PowerManager) this.context
                .getSystemService(android.content.Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dGlyphs:HardwareExecutor");
        }
    }

    public enum Glyph {
        CAMERA(PATH_ROOT + "/rear_cam_led_br"),
        DIAGONAL(PATH_ROOT + "/front_cam_led_br"),
        MAIN(PATH_ROOT + "/round_leds_br"),
        LINE(PATH_ROOT + "/vline_leds_br"),
        DOT(PATH_ROOT + "/dot_led_br"),
        SINGLE_LED(PATH_ROOT + "/video_leds_br");

        public final String path;

        Glyph(String path) {
            this.path = path;
        }

        public static Glyph[] getBasicGlyphs() {
            return new Glyph[] { CAMERA, DIAGONAL, MAIN, LINE, DOT };
        }
    }

    public enum NativeEffect {
        ALL_WHITE(PATH_ROOT + "/all_white_leds_br"),
        FRAME(PATH_ROOT + "/frame_leds_effect"),
        BOOT(PATH_ROOT + "/bootan_leds_effect"),
        BREATH(PATH_ROOT + "/leds_breath_set"),
        RINGTONE(PATH_ROOT + "/ringtone_leds_effect"),
        ASSISTANT(PATH_ROOT + "/ga_leds_effect"),
        FLIP(PATH_ROOT + "/flip_leds_effect"),
        MUSIC(PATH_ROOT + "/music_leds_effect"),
        RANDOM(PATH_ROOT + "/random_leds_effect"),
        VIDEO(PATH_ROOT + "/video_leds_effect"),
        KEYBOARD(PATH_ROOT + "/keybd_leds_effect"),
        ALL_EFFECT(PATH_ROOT + "/all_leds_effect"),
        HORSE_RACE(PATH_ROOT + "/horse_race_leds_br"),
        NF_EFFECT(PATH_ROOT + "/nf_leds_effect"),
        EXCLAMATION(PATH_ROOT + "/exclamation_leds_effect");

        public final String path;

        NativeEffect(String path) {
            this.path = path;
        }
    }

    public void setBrightness(Glyph glyph, int brightness) {
        if (glyph == Glyph.SINGLE_LED) {
            int toggle = brightness > 0 ? 1 : 0;
            String effectPath = PATH_ROOT + "/video_leds_effect";
            writeSysfs(effectPath, String.valueOf(toggle));
            return;
        }

        int safeBrightness = Math.max(0, Math.min(brightness, MAX_BRIGHTNESS));
        writeSysfs(glyph.path, String.valueOf(safeBrightness));
    }

    /**
     * Updates multiple glyphs in a single shell command to reduce IPC overhead.
     * Note: With System UID, we write sequentially or use a script if needed, 
     * but direct file writes are fast enough.
     */
    public void setGlyphBrightness(java.util.Map<Glyph, Integer> updates) {
        if (updates == null || updates.isEmpty())
            return;

        for (java.util.Map.Entry<Glyph, Integer> entry : updates.entrySet()) {
            setBrightness(entry.getKey(), entry.getValue());
        }
    }

    public void setFrame(int[] values) {
        if (values == null || values.length < 15)
            return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append(Math.max(0, Math.min(values[i], MAX_BRIGHTNESS)));
            if (i < 14)
                sb.append(" ");
        }

        writeSysfs(PATH_ROOT + "/frame_leds_effect", sb.toString());
    }

    public void setBrightnessSingle(int index, int brightness) {
        writeSysfs(PATH_ROOT + "/single_led_br", index + " " + Math.max(0, Math.min(brightness, MAX_BRIGHTNESS)));
    }

    private void writeSysfs(final String path, final String value) {
        shellExecutor.execute(() -> {
            if (wakeLock != null) {
                try {
                    wakeLock.acquire(3000);
                } catch (Exception ignored) {}
            }

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(path);
                fos.write(value.getBytes());
                fos.flush();
            } catch (IOException e) {
                android.util.Log.e("GlyphManager", "Failed to write to " + path, e);
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ignored) {}
                }
                if (wakeLock != null && wakeLock.isHeld()) {
                    try {
                        wakeLock.release();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    public void resetAll() {
        toggleAll(false);
    }

    public void setNativeEffect(NativeEffect effect, int value) {
        writeSysfs(effect.path, String.valueOf(value));
    }

    public void toggleAll(boolean turnOn) {
        int val = turnOn ? MAX_BRIGHTNESS : 0;
        int[] frame = new int[15];
        java.util.Arrays.fill(frame, val);
        setFrame(frame);

        if (!turnOn) {
            setBrightness(Glyph.SINGLE_LED, 0);
        }
    }

    public int clampBrightness(int brightness) {
        return Math.max(0, Math.min(brightness, MAX_BRIGHTNESS));
    }
}

    public void toggleAll(boolean turnOn) {
        int val = turnOn ? MAX_BRIGHTNESS : 0;
        int[] frame = new int[15];
        java.util.Arrays.fill(frame, val);
        setFrame(frame);

        if (!turnOn) {
            setBrightness(Glyph.SINGLE_LED, 0);
        }
    }

    public int clampBrightness(int brightness) {
        return Math.max(0, Math.min(brightness, MAX_BRIGHTNESS));
    }
}
