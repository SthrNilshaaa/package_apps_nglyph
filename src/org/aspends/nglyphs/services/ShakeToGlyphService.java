package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphAudioVisualizer;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.SleepGuard;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/*
 * `ShakeToGlyphService`: Acceleration sensors are immediately ignored when asleep,
 * unless the "Allow Shake to Glyph" exception is enabled in Sleep Mode settings.
 * `AutoBrightnessService`: Prevents the 30-minute periodic light sampling entirely during sleep.
 */
public class ShakeToGlyphService extends Service implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor proximitySensor;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private android.os.PowerManager powerManager;

    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    // --- PRO SHAKE DETECTION VARIABLES ---
    private float shakeThreshold;
    private int requiredShakes;
    private int currentShakeCount = 0;
    private long lastShakeTime = 0;
    private long firstShakeSequenceTime = 0;
    private boolean isProximityCovered = false;

    // Ignore spikes within 350ms of each other (prevents counting a back-swing as a
    // new shake)
    private static final int MIN_TIME_BETWEEN_SHAKES_MS = 350;

    // If more than 1.5 seconds pass between shakes, reset the sequence back to 0
    private static final int MAX_TIME_BETWEEN_SHAKES_MS = 1500;

    private float[] gravity = new float[3];

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getString(R.string.pref_file), Context.MODE_PRIVATE);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        vibrator = getSystemService(Vibrator.class);
        powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);

        prefListener = (sharedPreferences, key) -> {
            if ("shake_sensitivity".equals(key) || "shake_count".equals(key)) {
                updateSettings();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        updateSettings();
    }

    private void updateSettings() {
        // Range provided by user: 2.5f -> 8.0f (Increased from 1.5-5.0)
        int progress = prefs.getInt("shake_sensitivity", 50);
        float minThreshold = 2.5f;
        float maxThreshold = 8.0f;
        float range = maxThreshold - minThreshold;
        shakeThreshold = maxThreshold - ((progress / 100f) * range);

        requiredShakes = prefs.getInt("shake_count", 3); // Default to 3 instead of 2
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("shake_service", "Shake To Glyph",
                    NotificationManager.IMPORTANCE_MIN);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null)
                manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, "shake_service")
                .setContentTitle("Shake To Glyph Active")
                .setContentText("Listening for shake events")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        int foregroundType = 0;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
             foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        startForeground(4445, notification, foregroundType);

        updateSettings(); // Refresh settings if user just toggled something
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (prefs != null && prefs.getBoolean("is_light_on", false)) {
            prefs.edit().putBoolean("is_light_on", false).apply();
            GlyphManagerV2.getInstance().toggleAll(false);
        }
        if (prefs != null && prefListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not binding to anything
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (SleepGuard.isBlocked(prefs) && !prefs.getBoolean("shake_allow_in_sleep", false))
            return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Check if device is interactive (screen on/user using it)
            // We only want shake to trigger if the user isn't actively using the phone
            // (e.g., phone is locked or screen is off), unless "shake_while_screen_on" is
            // enabled.
            if (powerManager != null && powerManager.isInteractive()
                    && !prefs.getBoolean("shake_while_screen_on", false)) {
                return;
            }

            if (isProximityCovered)
                return; // Prevent shake trigger if proximity sensor is covered

            final float alpha = 0.8f;
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0];
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1];
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2];

            float x = event.values[0] - gravity[0];
            float y = event.values[1] - gravity[1];
            float z = event.values[2] - gravity[2];

            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;

            // gForce without gravity
            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);

            // Adjust threshold since gravity is now removed
            float pureMovementThreshold = shakeThreshold - 1.0f;
            if (pureMovementThreshold < 0.3f)
                pureMovementThreshold = 0.3f;

            if (gForce > pureMovementThreshold) {
                long now = System.currentTimeMillis();

                if (now - lastShakeTime < MIN_TIME_BETWEEN_SHAKES_MS) {
                    return;
                }

                if (currentShakeCount == 0 || now - lastShakeTime > MAX_TIME_BETWEEN_SHAKES_MS) {
                    currentShakeCount = 0;
                    firstShakeSequenceTime = now;
                }

                if (now - firstShakeSequenceTime > 2000) {
                    currentShakeCount = 0;
                    firstShakeSequenceTime = now;
                }

                lastShakeTime = now;
                currentShakeCount++;

                if (currentShakeCount >= requiredShakes) {
                    onTargetShakesReached();
                    currentShakeCount = 0;
                    firstShakeSequenceTime = 0;
                    gravity = new float[3]; // Reset filter
                }
            }
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            isProximityCovered = event.values[0] < event.sensor.getMaximumRange();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Ignored
    }

    private void onTargetShakesReached() {
        if (SleepGuard.isBlocked(prefs) && !prefs.getBoolean("shake_allow_in_sleep", false))
            return;

        if (vibrator != null && vibrator.hasVibrator()) {
            android.os.VibrationAttributes attrs = new android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build();
            vibrator.vibrate(VibrationEffect.createOneShot(40, 150), attrs);
        }

        // Toggle the Torch behavior
        boolean wasOn = prefs.getBoolean("is_light_on", false);
        boolean newLightState = !wasOn;
        prefs.edit().putBoolean("is_light_on", newLightState).apply();

        if (newLightState) {
            for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
                GlyphManagerV2.getInstance().setBrightness(g, prefs.getInt("torch_brightness", 2048));
            }
        } else {
            GlyphManagerV2.getInstance().toggleAll(false);
            sendBroadcast(new Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL).setPackage(getPackageName()));
        }
    }
}
