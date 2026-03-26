package org.aspends.nglyphs.core;

import org.aspends.nglyphs.R;

import com.topjohnwu.superuser.Shell;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.Arrays;

/**
 * Production-ready GlyphManager acting as a persistent root controller for the
 * Nothing Phone LEDs.
 * Provides thread-safe, batched, IPC-optimized access to the hardware paths.
 */
public class GlyphManagerV2 {
    public static final int MAX_BRIGHTNESS = 4095;
    private static final String PATH_ROOT = "/sys/class/leds/aw210xx_led";

    private static GlyphManagerV2 instance;
    private final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private Context context;

    public static synchronized GlyphManagerV2 getInstance() {
        if (instance == null) {
            instance = new GlyphManagerV2();
        }
        return instance;
    }

    private GlyphManagerV2() {
    }

    public void init(Context context) {
        this.context = context.getApplicationContext();
        PowerManager pm = (PowerManager) this.context
                .getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dGlyphs:HardwareExecutor");
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
        if (!isRootReady())
            return;

        if (glyph == Glyph.SINGLE_LED) {
            int toggle = brightness > 0 ? 1 : 0;
            String effectPath = PATH_ROOT + "/video_leds_effect";
            executeCommand("echo " + toggle + " > " + effectPath);
            return;
        }

        int safeBrightness = Math.max(0, Math.min(brightness, MAX_BRIGHTNESS));
        executeCommand("echo " + safeBrightness + " > " + glyph.path);
    }

    /**
     * Updates multiple glyphs in a single shell command to reduce IPC overhead.
     */
    public void setGlyphBrightness(Map<Glyph, Integer> updates) {
        if (!isRootReady() || updates == null || updates.isEmpty())
            return;

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Glyph, Integer> entry : updates.entrySet()) {
            Glyph g = entry.getKey();
            int b = Math.max(0, Math.min(entry.getValue(), MAX_BRIGHTNESS));
            if (g == Glyph.SINGLE_LED) {
                sb.append("echo ").append(b > 0 ? 1 : 0).append(" > ").append(PATH_ROOT).append("/video_leds_effect; ");
            } else {
                sb.append("echo ").append(b).append(" > ").append(g.path).append("; ");
            }
        }
        executeCommand(sb.toString());
    }

    public void setFrame(int[] values) {
        if (!isRootReady() || values == null || values.length < 15)
            return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append(Math.max(0, Math.min(values[i], MAX_BRIGHTNESS)));
            if (i < 14)
                sb.append(" ");
        }

        executeCommand("echo " + sb.toString() + " > " + PATH_ROOT + "/frame_leds_effect");
    }

    public void setBrightnessSingle(int index, int brightness) {
        if (!isRootReady())
            return;

        executeCommand("echo " + index + " " + Math.max(0, Math.min(brightness, MAX_BRIGHTNESS)) + " > " + PATH_ROOT
                + "/single_led_br");
    }

    public void executeCommand(final String command) {
        if (command == null || command.isEmpty() || !isRootReady()) return;

        shellExecutor.execute(() -> {
            // Acquire WakeLock immediately before execution
            if (wakeLock != null) {
                try {
                    wakeLock.acquire(3000); // 3s is plenty for single or batched sysfs writes
                } catch (Exception ignored) {}
            }

            try {
                // Use .exec() for synchronous execution within the executor thread
                // to ensure the WakeLock covers the entire operation accurately.
                Shell.cmd(command).exec();
            } catch (Exception e) {
                Log.e("GlyphManager", "Shell execution failed: " + command, e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try {
                        wakeLock.release();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private boolean isRootReady() {
        try {
            return Shell.getShell().isRoot();
        } catch (Exception e) {
            return false;
        }
    }

    public void resetAll() {
        toggleAll(false);
    }

    public void setNativeEffect(NativeEffect effect, int value) {
        if (!isRootReady())
            return;
        executeCommand("echo " + value + " > " + effect.path);
    }

    public void toggleAll(boolean turnOn) {
        int val = turnOn ? MAX_BRIGHTNESS : 0;
        int[] frame = new int[15];
        Arrays.fill(frame, val);
        setFrame(frame);

        if (!turnOn) {
            setBrightness(Glyph.SINGLE_LED, 0);
        }
    }

    public int clampBrightness(int brightness) {
        return Math.max(0, Math.min(brightness, MAX_BRIGHTNESS));
    }
}
