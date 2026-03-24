package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.util.SleepGuard;
import org.aspends.nglyphs.util.CustomRingtoneManager;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class FlipToGlyphService extends Service implements SensorEventListener {

    public static final String ACTION_NOTIFICATION_GLYPH = "org.aspends.nglyphs.ACTION_NOTIFICATION_GLYPH";
    public static final String ACTION_CALL_GLYPH = "org.aspends.nglyphs.ACTION_CALL_GLYPH";
    public static final String ACTION_STOP_CALL_GLYPH = "org.aspends.nglyphs.ACTION_STOP_CALL_GLYPH";
    public static final String ACTION_REFRESH_ESSENTIAL = "org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL";

    private SensorManager sensorManager;
    private AudioManager audioManager;
    private Vibrator vibrator;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean isFaceDown, isProximityCovered, isActive, isRinging;
    public static volatile boolean ringingActive = false;
    public static volatile long ringingStartTime = 0;
    private int originalRingerMode;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable activationRunnable;
    private Runnable deactivationRunnable;
    private final ExecutorService executor = Executors.newCachedThreadPool();



    /**
     * Called when the service is first created.
     * Initializes sensors (accelerometer, proximity), audio manager,
     * preferences, and acquires a partial wakelock.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);


        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "dGlyphs:Lock");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("flip_service", "Flip To Glyph",
                    NotificationManager.IMPORTANCE_MIN);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null)
                manager.createNotificationChannel(channel);
        }



        startSensorWork();


        Notification notification = new NotificationCompat.Builder(this, "flip_service")
                .setContentTitle("Flip To Glyph Active")
                .setContentText("Monitoring orientation sensors")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        int foregroundType = 0;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
             foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        startForeground(4447, notification, foregroundType);
    }

    /**
     * Called when the service is asked to start a specific action.
     * Handles external requests for notification or call glyph effects.
     *
     * @param intent  The intent specifying the action to perform.
     * @param flags   Additional data about this start request.
     * @param startId A unique integer representing this specific request to start.
     * @return The semantics for how the system should handle this service's
     *         lifecycle.
     */
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean("master_allow", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_NOTIFICATION_GLYPH:
                    if (isRinging) return START_STICKY; // Don't interrupt calls
                    int notifStream = android.media.AudioManager.STREAM_NOTIFICATION;
                    if (isActive || (intent != null && intent.getBooleanExtra("suppress_audio", false))) {
                        notifStream = -1;
                    }
                    triggerEffect("glyph_blink_style", notifStream);
                    break;

                case ACTION_CALL_GLYPH:
                    startCallBlinking();
                    break;
                case ACTION_STOP_CALL_GLYPH:
                    stopCallBlinking();
                    break;
            }
        }
        return START_STICKY;
    }

    /**
     * Triggers a specific glyph effect if it is not currently sleep time.
     *
     * @param prefKey    The SharedPreferences key where the effect name is stored.
     * @param streamType The AudioManager stream to use for audio playback.
     */
    private void triggerEffect(String prefKey, int streamType) {
        if (SleepGuard.isBlocked(prefs))
            return;
        executor.execute(() -> {
            String fallback = "flip_style_value".equals(prefKey) ? "native_flip" : "static";
            String style = prefs.getString(prefKey, fallback);

            boolean isLegacyOrNative = style.startsWith("native_") || isLegacyStyle(style);
            java.io.File customOgg = CustomRingtoneManager.getCustomRingtoneFile(this, style);

            if (customOgg != null || isLegacyOrNative) {
                // GlyphEffects.run() internally checks for customOgg first, then legacy
                // switches
                GlyphEffects.run(style, prefs.getInt("brightness", 2048), vibrator, this, streamType);
                if (customOgg == null) {
                    // run() does not broadcast when running legacy switches (though it should now
                    // for native).
                    // Safest to send here, wait, native sends it internally. Legacy run() does not.
                    // But actually wait, we already added broadcast to native_ and run() finally
                    // blocks in GlyphEffects.
                    // We can just keep sendBroadcast here for legacy ones to be safe.
                    sendBroadcast(new Intent(ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
                }
            } else {
                // It's a built-in CSV pattern from assets/notification
                GlyphEffects.play(this, "notification", style, vibrator, prefs.getInt("brightness", 2048));
                sendBroadcast(new Intent(ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
            }
        });
    }

    private boolean isLegacyStyle(String style) {
        String[] legacy = {
                "static", "breath", "blink", "stock", "pneumatic", "abra", "strobe", "strobe_fast",
                "warp", "siren", "pulse", "crescendo", "sos", "twinkle", "random", "ping", "fade",
                "knight_rider", "cylon", "fireflies", "heartbeat", "marquee", "marquee_fast", "snake",
                "snake_fast", "rain", "sparkle", "blink_sync", "random_walk", "burst"
        };
        for (String s : legacy) {
            if (s.equals(style))
                return true;
        }
        return false;
    }

    /**
     * Starts a continuous blinking visual effect for an incoming call.
     */
    private void startCallBlinking() {
        if (isRinging)
            return;
        if (SleepGuard.isBlocked(prefs))
            return;

        isRinging = true;
        ringingActive = true;
        ringingStartTime = System.currentTimeMillis();

        // Acquire WakeLock for the duration of the call to prevent CPU sleep
        if (wakeLock != null) {
            try {
                wakeLock.acquire(60000); // 1 minute max for safety
            } catch (Exception e) {}
        }

        executor.execute(() -> {
            String style = prefs.getString("call_style_value", "static");
            int callStream = isActive ? -1 : android.media.AudioManager.STREAM_RING;

            // Watchdog Loop: ensures the effect keeps running until isRinging is false
            while (isRinging) {
                if (!GlyphEffects.isActive()) {
                    java.io.File customOgg = CustomRingtoneManager.getCustomRingtoneFile(this, style);
                    boolean isLegacyOrNative = style.startsWith("native_") || isLegacyStyle(style);

                    if (customOgg != null || isLegacyOrNative) {
                         GlyphEffects.run(style, prefs.getInt("brightness", 2048), vibrator, this, callStream);
                    } else {
                         GlyphEffects.play(this, "call", style, vibrator, prefs.getInt("brightness", 2048));
                    }
                }
                
                // Sleep between watchdog checks
                SystemClock.sleep(style.startsWith("native_") ? 5000 : 500);
            }
            
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception e) {}
            }
            sendBroadcast(new Intent(ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
        });
    }

    /**
     * Stops the continuous blinking effect for an incoming call and turns off LEDs.
     */
    private void stopCallBlinking() {
        isRinging = false;
        ringingActive = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) {}
        }
        GlyphEffects.stopCustomRingtone();
        restoreTorchState();
        sendBroadcast(new Intent(ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
    }

    /**
     * Re-acquires the wakelock briefly and executes a specific glyph effect.
     *
     * @param style The name of the effect style to run.
     */
    private void runEffect(String style) {
        if (wakeLock != null)
            wakeLock.acquire(1000);
        GlyphEffects.play(this, "notification", style, vibrator, prefs.getInt("brightness", 2048));
        restoreTorchState();
    }

    /**
     * Directly updates the brightness of all glyph LEDs via the GlyphManagerV2.
     *
     * @param val The brightness level to apply.
     */
    private void updateHardware(int val) {
        java.util.Map<GlyphManagerV2.Glyph, Integer> updates = new java.util.HashMap<>();
        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs())
            updates.put(g, val);
        updates.put(GlyphManagerV2.Glyph.SINGLE_LED, val);

        GlyphManagerV2.getInstance().setGlyphBrightness(updates);

        if (val == 0) {
            sendBroadcast(new Intent(ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
        }
    }

    /**
     * Checks the master tile state and restores the LEDs either to ON or OFF.
     */
    private void restoreTorchState() {
        if (prefs.getBoolean("is_light_on", false)) {
            updateHardware(prefs.getInt("brightness", 2048));
        } else {
            updateHardware(0);
        }
    }

    /**
     * Called when sensor values have changed. Evaluates the phone's orientation
     * and proximity to determine if the "Flip to Glyph" mode should be activated.
     *
     * @param event The SensorEvent containing new sensor data.
     */
    private long lastBatteryTapTime = 0;

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Only process sensor events if flip_to_glyph_enabled is true
        if (!prefs.getBoolean("flip_to_glyph_enabled", false) || isRinging) {
            return;
        }

        if (SleepGuard.isBlocked(prefs))
            return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            // Stricter flat face-down check
            boolean flatFaceDown = z < -9.0 && Math.abs(x) < 2.5 && Math.abs(y) < 2.5;

            if (flatFaceDown && !isFaceDown) {
                // Subtle haptic feedback when detecting face-down
                // if (!isActive && vibrator != null && vibrator.hasVibrator()) {
                // vibrator.vibrate(VibrationEffect.createOneShot(10, 50));
                // }
            }
            isFaceDown = flatFaceDown;
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            isProximityCovered = event.values[0] < event.sensor.getMaximumRange();
        }

        if (isFaceDown && isProximityCovered) {
            if (deactivationRunnable != null) {
                handler.removeCallbacks(deactivationRunnable);
                deactivationRunnable = null;
            }
            if (!isActive && activationRunnable == null) {
                activationRunnable = this::activateFlipMode;
                handler.postDelayed(activationRunnable, 1500);
            }
        } else {
            if (activationRunnable != null) {
                handler.removeCallbacks(activationRunnable);
                activationRunnable = null;
            }
            if (isActive && deactivationRunnable == null) {
                deactivationRunnable = () -> {
                    deactivateFlipMode();
                    deactivationRunnable = null;
                };
                handler.postDelayed(deactivationRunnable, 1000);
            }
        }
    }

    private int getBatteryLevel() {
        android.content.Intent batteryIntent = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (level != -1 && scale != -1) {
                return (int) ((level / (float) scale) * 100);
            }
        }
        return 0;
    }

    private boolean isCharging() {
        android.content.Intent batteryIntent = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        }
        return false;
    }

    /**
     * Activates the "Flip to Glyph" mode. Puts the phone in vibrate mode
     * and shows a visual confirmation effect on the LEDs.
     */
    private void activateFlipMode() {
        isActive = true;
        activationRunnable = null;
        originalRingerMode = audioManager.getRingerMode();
        audioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);

        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        prefs.edit().putBoolean("device_is_flipped", true).apply();
        triggerEffect("flip_style_value", android.media.AudioManager.STREAM_MUSIC);
    }

    /**
     * Deactivates the "Flip to Glyph" mode. Restores the original ringer mode
     * and turns off any active LEDs.
     */
    private void deactivateFlipMode() {
        isActive = false;
        prefs.edit().putBoolean("device_is_flipped", false).apply();
        audioManager.setRingerMode(originalRingerMode);

        restoreTorchState();

        // Notify listener to re-evaluate essential light state
        sendBroadcast(new Intent(ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
    }

    /**
     * Checks if the current time falls within the configured Sleep Mode window.
     *
     * @return True if Sleep Mode is enabled and active right now, false otherwise.
     */
    private boolean isSleepTime() {
        return SleepGuard.isBlocked(prefs);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Called when the service is destroyed. Ensures all hardware states
     * (ringer mode, sensors, LEDs) are restored property.
     */
    @Override
    public void onDestroy() {
        isRinging = false;
        ringingActive = false;
        if (isActive)
            audioManager.setRingerMode(originalRingerMode);
        
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        restoreTorchState();
        super.onDestroy();
    }


    private void startSensorWork() {
        if (!prefs.getBoolean("master_allow", false)) return;
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (accel != null)
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
        if (prox != null)
            sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stopSensorWork() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        // If we were active, deactivate if the screen comes on
        if (isActive) {
            deactivateFlipMode();
        }
    }
}