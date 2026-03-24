package org.aspends.nglyphs.core;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.FlipToGlyphService;

import android.content.Intent;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnimationManager {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable volumeTimeoutRunnable;
    private static Runnable batteryTimeoutRunnable;
    public static final String ACTION_GLYPH_FREE = "org.aspends.nglyphs.ACTION_GLYPH_FREE";
    private static volatile boolean isAnimationRunning = false;
    private static android.content.Context appContext;

    public static void init(android.content.Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    // Priority levels
    public static final int PRIORITY_NONE = 0;
    public static final int PRIORITY_PROGRESS = 1; // LOW (Downloads/Music)
    public static final int PRIORITY_VISUALIZER = 2; // MEDIUM-LOW (Music Visualizer)
    public static final int PRIORITY_BATTERY = 3; // MEDIUM
    public static final int PRIORITY_VOLUME = 4; // HIGH
    public static final int PRIORITY_ASSISTANT = 5; // HIGH (Voice Pulsing)
    public static final int PRIORITY_HIGH = 6; // MAX (Patterns/Notifications)

    private static volatile int activePriority = PRIORITY_NONE;
    private static volatile int runningLoopPriority = PRIORITY_NONE;

    public static boolean isBusy() {
        return isAnimationRunning && activePriority != PRIORITY_NONE;
    }

    public static int getActivePriority() {
        return activePriority;
    }

    // Animation state for smooth transitions
    private static float targetLevel = 0f;
    private static float currentLevel = 0f;
    private static final float INTERPOLATION_SPEED = 0.25f; // Balanced for smoothness (was 0.4f)
    private static final int REFRESH_RATE_MS = 16; // 60fps

    /**
     * Attempts to acquire the animation lock for a specific priority.
     * Returns true if successful (can override lower or equal priority if allowed).
     */
    private static synchronized boolean requestLock(int priority) {
        if (priority >= activePriority) {
            if (activePriority != priority) {
                isAnimationRunning = false; // Interrupt lower priority
            }
            activePriority = priority;
            return true;
        }
        return false;
    }

    private static synchronized void releaseLock(int priority) {
        if (activePriority == priority) {
            activePriority = PRIORITY_NONE;
            isAnimationRunning = false;
            timeoutHandler.removeCallbacks(watchdogRunnable);
            if (appContext != null) {
                appContext.sendBroadcast(new android.content.Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                        .setPackage(appContext.getPackageName()));
                
                if (priority > PRIORITY_PROGRESS) {
                    appContext.sendBroadcast(new android.content.Intent(ACTION_GLYPH_FREE)
                            .setPackage(appContext.getPackageName()));
                }
            }
        }
    }

    /**
     * External hook for patterns/notifications which are managed outside this
     * class.
     */
    public static void setHighPriorityActive(boolean active) {
        if (active) {
            requestLock(PRIORITY_HIGH);
        } else {
            releaseLock(PRIORITY_HIGH);
        }
    }

    private static final java.util.Map<GlyphManagerV2.Glyph, Integer> backgroundState = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setBackgroundGlyph(GlyphManagerV2.Glyph glyph, int brightness) {
        if (activePriority == PRIORITY_HIGH) return; // Don't interrupt patterns
        
        if (brightness > 0) {
            backgroundState.put(glyph, brightness);
        } else {
            backgroundState.remove(glyph);
        }
        
        // Push background state immediately
        refreshBackgroundState();
    }

    public static void refreshBackgroundState() {
        if (activePriority == PRIORITY_HIGH) return;
        if (!isAnimationRunning) {
            updateMeter(currentLevel, runningLoopPriority == PRIORITY_PROGRESS ? 8 : 9);
        }
    }

    private static float animationBrightnessMultiplier = 1.0f;

    private static void updateMeter(float level, int maxZones) {
        if (activePriority == PRIORITY_HIGH && level < 0.01f) return;

        int[] frame = new int[15];
        int torchBrightness = 0;
        
        // 0. LAYER: GLOBAL TORCH (Bottom layer)
        if (appContext != null) {
            android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                appContext.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false)) {
                // Ensure we read the LATEST brightness from prefs on every frame
                torchBrightness = prefs.getInt("torch_brightness", 2048);
                for (int i = 0; i < 15; i++) frame[i] = torchBrightness;
            }
        }
        
        // 1. LAYER: BACKGROUND STATE (Essential Lights, constant LEDs)
        for (java.util.Map.Entry<GlyphManagerV2.Glyph, Integer> entry : backgroundState.entrySet()) {
            GlyphManagerV2.Glyph g = entry.getKey();
            int b = entry.getValue();
            switch (g) {
                case CAMERA: frame[0] = b; break;
                case DIAGONAL: frame[1] = Math.max(frame[1], b); break;
                case MAIN: 
                    frame[2] = Math.max(frame[2], b); 
                    frame[3] = Math.max(frame[3], b); 
                    frame[4] = Math.max(frame[4], b); 
                    frame[5] = Math.max(frame[5], b); 
                    break;
                case DOT: frame[6] = Math.max(frame[6], b); break;
                case LINE: 
                    for (int i = 7; i <= 14; i++) frame[i] = Math.max(frame[i], b);
                    break;
            }
        }

        // 2. LAYER: ANIMATION LAYER (Overrides background on the specific meter LEDs)
        if (level > 0.01f || isAnimationRunning) {
            int fullLeds = (int) Math.floor(level);
            float fraction = level - fullLeds;
            int startIdx = (maxZones == 9) ? 6 : 7;
            
            int brightness = (int) (GlyphManagerV2.MAX_BRIGHTNESS * animationBrightnessMultiplier);

            for (int i = 0; i < fullLeds && i < maxZones; i++) {
                frame[startIdx + i] = brightness;
            }

            if (fullLeds < maxZones && fraction > 0.01f) {
                frame[startIdx + fullLeds] = (int) (brightness * fraction);
            }
        }

        // 3. FINAL PUSH: Decide between fast Frame (Animation) or bright Individual (Static)
        if (level > 0.01f || isAnimationRunning) {
            GlyphManagerV2.getInstance().setFrame(frame);
        } else {
            // STATIC MODE: Use individual nodes for higher brightness peaks (Torch/Essentials)
            java.util.Map<GlyphManagerV2.Glyph, Integer> updates = new java.util.HashMap<>();
            updates.put(GlyphManagerV2.Glyph.CAMERA, frame[0]);
            updates.put(GlyphManagerV2.Glyph.DIAGONAL, frame[1]);
            updates.put(GlyphManagerV2.Glyph.MAIN, frame[2]); // Assumes uniform main LEDs in static
            updates.put(GlyphManagerV2.Glyph.DOT, frame[6]);
            updates.put(GlyphManagerV2.Glyph.LINE, frame[7]); // Assumes uniform line LEDs in static
            
            GlyphManagerV2.getInstance().setGlyphBrightness(updates);
        }
    }

    public static void showVolumeLevel(int percentage, android.content.Context context) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(context.getString(R.string.pref_file),
                    android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }

        if (!requestLock(PRIORITY_VOLUME))
            return;

        animationBrightnessMultiplier = 1.0f;
        targetLevel = (percentage / 100f) * 9.0f;

        if (volumeTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(volumeTimeoutRunnable);
        }

        if (runningLoopPriority != PRIORITY_VOLUME || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_VOLUME, 9);
        }

        volumeTimeoutRunnable = () -> {
            targetLevel = 0f;
        };
        timeoutHandler.postDelayed(volumeTimeoutRunnable, 2500);
    }

    public static void showAssistantLevel(int percentage) {
        if (!requestLock(PRIORITY_ASSISTANT))
            return;

        animationBrightnessMultiplier = 1.0f;
        // Assistant uses 9 zones (indices 6 to 14) for full strip activity
        targetLevel = (percentage / 100f) * 9.0f;

        if (runningLoopPriority != PRIORITY_ASSISTANT || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_ASSISTANT, 9);
        }
    }

    public static void showVisualizer(int[] zoneIntensities) {
        if (!requestLock(PRIORITY_VISUALIZER)) return;

        int[] frame = new int[15];
        
        // Map 5 zones to LEDs
        // Zone 0: Bass -> Main (2,3,4,5)
        // Zone 1: Low Mids -> Diagonal (1)
        // Zone 2: Mids -> Camera (0)
        // Zone 3: High Mids -> Line (7-14)
        // Zone 4: Highs -> Dot (6)

        if (zoneIntensities.length >= 5) {
            int bass = zoneIntensities[0];
            frame[2] = frame[3] = frame[4] = frame[5] = bass;
            
            frame[1] = zoneIntensities[1]; // Diagonal
            frame[0] = zoneIntensities[2]; // Camera
            
            int lineVal = zoneIntensities[3];
            for (int i = 7; i <= 14; i++) frame[i] = lineVal;
            
            frame[6] = zoneIntensities[4]; // Dot
        }

        // We bypass the standard interpolation loop for visualizer as it's already smoothed in GlyphAudioVisualizer
        // and needs to be ultra-fast
        GlyphManagerV2.getInstance().setFrame(frame);
        
        // Reset the inactivity timeout for the priority
        if (runningLoopPriority != PRIORITY_VISUALIZER) {
            runningLoopPriority = PRIORITY_VISUALIZER;
        }
    }

    public static void showProgressLevel(int percentage, android.content.Context context) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(context.getString(R.string.pref_file),
                    android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false)) {
                // Keep progress alive if it's already active, otherwise block
                if (activePriority != PRIORITY_PROGRESS) {
                    return;
                }
            }
            if (!prefs.getBoolean("glyph_progress_enabled", false)) {
                releaseLock(PRIORITY_PROGRESS);
                return;
            }
            if (prefs.getBoolean("glyph_progress_flipped_only", false) && !prefs.getBoolean("device_is_flipped", false)) {
                releaseLock(PRIORITY_PROGRESS);
                return;
            }
        }

        if (!requestLock(PRIORITY_PROGRESS))
            return;

        // Apply user-defined brightness factor (clamped 10-100% -> 0.1-1.0)
        float factor = 0.7f;
        if (context != null) {
             android.content.SharedPreferences prefs = context.getSharedPreferences(
                context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
             factor = prefs.getInt("glyph_progress_brightness_factor", 70) / 100f;
        }
        animationBrightnessMultiplier = factor;
        targetLevel = (percentage / 100f) * 8.0f;

        if (runningLoopPriority != PRIORITY_PROGRESS || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_PROGRESS, 8);
        }
    }

    public static void showBluetoothBattery(int batteryLevel, android.content.Context context, int holdMs) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(context.getString(R.string.pref_file),
                    android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }

        android.util.Log.i("AnimationManager", "showBluetoothBattery: level=" + batteryLevel + ", holdMs=" + holdMs);

        if (!requestLock(PRIORITY_BATTERY))
            return;

        animationBrightnessMultiplier = 1.0f;
        // bluetooth battery animation specifically uses 8 zones (indices 7 to 14)
        targetLevel = (batteryLevel / 100f) * 8.0f;

        if (batteryTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(batteryTimeoutRunnable);
        }

        if (runningLoopPriority != PRIORITY_BATTERY || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_BATTERY, 8);
        }

        batteryTimeoutRunnable = () -> {
            targetLevel = 0f;
        };
        timeoutHandler.postDelayed(batteryTimeoutRunnable, holdMs);
    }

    public static void playBatteryAnimation(int batteryLevel, android.content.Context context, int holdMs) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(context.getString(R.string.pref_file),
                    android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }

        android.util.Log.i("AnimationManager", "playBatteryAnimation: level=" + batteryLevel + ", holdMs=" + holdMs);

        if (!requestLock(PRIORITY_BATTERY))
            return;

        animationBrightnessMultiplier = 1.0f;
        // Restore to 9 zones
        targetLevel = (batteryLevel / 100f) * 9.0f;

        if (batteryTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(batteryTimeoutRunnable);
        }

        if (runningLoopPriority != PRIORITY_BATTERY || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_BATTERY, 9);
        }

        batteryTimeoutRunnable = () -> {
            targetLevel = 0f;
        };
        timeoutHandler.postDelayed(batteryTimeoutRunnable, holdMs);
    }

    private static final Runnable watchdogRunnable = () -> {
        android.util.Log.w("AnimationManager", "Watchdog triggered! Forcing glyph clear.");
        cancelAnimation();
    };

    private static void startAnimationLoop(final int priority, final int maxZones) {
        isAnimationRunning = true;
        runningLoopPriority = priority;

        // Safety watchdog: battery and volume should NEVER run longer than 15s
        timeoutHandler.removeCallbacks(watchdogRunnable);
        if (priority == PRIORITY_BATTERY || priority == PRIORITY_VOLUME) {
            timeoutHandler.postDelayed(watchdogRunnable, 15000);
        }

        executor.submit(() -> {
            currentLevel = 0f; // Force reset state for new animation cycle
            boolean forcedFirstFrame = true;
            
            // Optimization: Lower frame rate for background animations
            // Priority Volume gets 60fps (16ms), others get 30fps (32ms) to save CPU/Battery
            int loopInterval = (priority == PRIORITY_VOLUME) ? 16 : 32;

            try {
                while (isAnimationRunning && activePriority == priority && runningLoopPriority == priority) {
                    long now = SystemClock.uptimeMillis();
                    float diff = targetLevel - currentLevel;

                    if (forcedFirstFrame || Math.abs(diff) > 0.01f) {
                        currentLevel += diff * INTERPOLATION_SPEED;
                        updateMeter(currentLevel, maxZones);
                        forcedFirstFrame = false;
                    } else if (targetLevel < 0.01f) {
                        // Reached 0 target exactly or within precision
                        currentLevel = 0f;
                        updateMeter(0f, maxZones);
                        break;
                    } else {
                        // Hold precision at current non-zero target
                        updateMeter(targetLevel, maxZones);
                    }

                    long elapsed = SystemClock.uptimeMillis() - now;
                    long sleep = Math.max(1, loopInterval - elapsed);
                    SystemClock.sleep(sleep);
                }
            } catch (Exception e) {
                android.util.Log.e("AnimationManager", "Animation Loop Error", e);
            } finally {
                updateMeter(0f, maxZones);
                if (activePriority == priority) {
                    releaseLock(priority);
                }
                isAnimationRunning = false;
                runningLoopPriority = PRIORITY_NONE;

                // Signal that glyph is free for other services (e.g. restore progress bar)
                if (appContext != null) {
                    Intent intent = new Intent(ACTION_GLYPH_FREE);
                    intent.setPackage(appContext.getPackageName());
                    appContext.sendBroadcast(intent);
                }
                android.util.Log.d("AnimationManager", "Loop Terminated (priority=" + priority + ")");
            }
        });
    }

    public static void cancelAnimation() {
        isAnimationRunning = false;
        activePriority = PRIORITY_NONE;
        runningLoopPriority = PRIORITY_NONE;
        targetLevel = 0f;
        currentLevel = 0f;
        timeoutHandler.removeCallbacksAndMessages(null);
        executor.submit(() -> GlyphManagerV2.getInstance().setFrame(new int[15]));
    }

    public static void cancelPriority(int priority) {
        if (activePriority == priority) {
            targetLevel = 0f;
            // The running loop will animate down to 0 and naturally clear itself
            // If the loop isn't running or we want immediate clears:
            // isAnimationRunning = false;
            // releaseLock(priority);
        }
    }
}
