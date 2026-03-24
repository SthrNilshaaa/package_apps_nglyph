package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.SleepGuard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AutoBrightnessService extends Service implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SharedPreferences prefs;

    private static final long CHECK_INTERVAL_MIN = 30;
    private static final long SAMPLE_WINDOW_SEC = 30;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<Float> sampleBuffer = new ArrayList<>();
    private int lastHardwareBrightness = -1;
    private boolean isSampling = false;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getString(R.string.pref_file), Context.MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean("auto_brightness_enabled", false) || !prefs.getBoolean("master_allow", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("auto_brightness", "Auto Glyph Brightness",
                    NotificationManager.IMPORTANCE_MIN);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, "auto_brightness")
                .setContentTitle("Auto Brightness Active")
                .setContentText("Listening to ambient light")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        int foregroundType = 0;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
             foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        startForeground(4446, notification, foregroundType);

        scheduleCheck();
        return START_STICKY;
    }

    private void scheduleCheck() {
        scheduler.scheduleAtFixedRate(this::startSampling, 0, CHECK_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    private void startSampling() {
        if (!prefs.getBoolean("auto_brightness_enabled", false) || SleepGuard.isBlocked(prefs)
            || prefs.getBoolean("device_is_flipped", false))
            return;

        isSampling = true;
        sampleBuffer.clear();
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Stop sampling after window expires
        scheduler.schedule(this::stopSampling, SAMPLE_WINDOW_SEC, TimeUnit.SECONDS);
    }

    private void stopSampling() {
        isSampling = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        applyAveragedBrightness();
    }

    private void applyAveragedBrightness() {
        if (sampleBuffer.isEmpty())
            return;

        // Take average of last 3 readings
        int toTake = Math.min(3, sampleBuffer.size());
        float sum = 0;
        for (int i = sampleBuffer.size() - toTake; i < sampleBuffer.size(); i++) {
            sum += sampleBuffer.get(i);
        }
        float lux = sum / toTake;

        // Map Lux to 0-1.0 intensity using Logarithmic smoothing
        float luxFactor = (float) Math.log10(lux);
        if (luxFactor < 0) luxFactor = 0;
        if (luxFactor > 4.0f) luxFactor = 4.0f;
        luxFactor = luxFactor / 4.0f; // 0.0 to 1.0

        // Time-based Base Brightness (Positions from MainActivity mapping)
        // Pos 1: 100, 2: 900, 3: 1700, 4: 2500, 5: 3300, 6: 4095
        int baseBrightness;
        int nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        
        if (nowHour >= 0 && nowHour < 6) {
            // Mid-night: lowest
            baseBrightness = 100;
        } else if (nowHour >= 6 && nowHour < 12) {
            // Morning: 2nd position
            baseBrightness = 900;
        } else if (nowHour >= 12 && nowHour < 17) {
            // Afternoon: 5th or 6th position (choosing 3300 for afternoon)
            baseBrightness = 3300;
        } else {
            // Evening: 3rd position
            baseBrightness = 1700;
        }

        // Blend: baseBrightness is the MINIMUM for that time of day, 
        // ambient light can push it up to max.
        int newBrightness = baseBrightness + (int) (luxFactor * (4095 - baseBrightness));
        
        // Ensure absolute bounds
        newBrightness = Math.max(100, Math.min(newBrightness, 4095));

        if (lastHardwareBrightness == -1 || Math.abs(newBrightness - lastHardwareBrightness) > 150) {
            lastHardwareBrightness = newBrightness;
            prefs.edit().putInt("brightness", newBrightness).apply();

            // Broadcast intent to refresh UI if MainActivity is open
            sendBroadcast(new Intent("org.aspends.nglyphs.ACTION_BRIGHTNESS_UPDATED"));
            sendBroadcast(new Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL").setPackage(getPackageName()));

            if (prefs.getBoolean("is_light_on", false)) {
                for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
                    GlyphManagerV2.getInstance().setBrightness(g, newBrightness);
                }
            }
        }
    }

    private boolean isNightTime() {
        if (!prefs.getBoolean("sleep_mode_enabled", false)) {
            // Default night window if Sleep Mode is off: 11 PM to 7 AM
            int nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            return nowHour >= 23 || nowHour < 7;
        }

        try {
            String[] s = prefs.getString("sleep_start", "23:00").split(":");
            String[] e = prefs.getString("sleep_end", "07:00").split(":");
            int startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
            int endMin = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);
            int nowMin = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) * 60
                    + java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE);

            return startMin < endMin ? (nowMin >= startMin && nowMin <= endMin)
                    : (nowMin >= startMin || nowMin <= endMin);
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        scheduler.shutdownNow();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isSampling && event.sensor.getType() == Sensor.TYPE_LIGHT) {
            sampleBuffer.add(event.values[0]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Ignored
    }
}
