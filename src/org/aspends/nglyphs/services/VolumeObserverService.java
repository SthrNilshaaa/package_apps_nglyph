package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.util.SleepGuard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service that monitors system volume changes and dispatches
 * live progress to the AnimationManager.
 */
public class VolumeObserverService extends Service {

    private static final String CHANNEL_ID = "dglyphs_volume_channel";
    private static final int NOTIFICATION_ID = 4448;

    private AudioManager audioManager;
    private BroadcastReceiver volumeReceiver;
    private int lastMusicVolume = -1;
    private SharedPreferences prefs;

    private final android.database.ContentObserver volumeSettingsObserver = new android.database.ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (audioManager != null) {
                int currentMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (currentMusicVol != lastMusicVolume) {
                    lastMusicVolume = currentMusicVol;
                    handleVolumeChange(AudioManager.STREAM_MUSIC, currentMusicVol);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startForeground(NOTIFICATION_ID, createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Create the observer listening to changes on the system volume properties
        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                    int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                    int volume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                    int prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);

                    if (FlipToGlyphService.ringingActive) {
                        if (System.currentTimeMillis() - FlipToGlyphService.ringingStartTime > 2000) {
                            if (GlyphEffects.isActive()) {
                                GlyphEffects.muteActiveAudio();
                            }
                        }
                    }

                    handleVolumeChange(streamType, volume);
                }
            }
        };

        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, filter);
        }

        // Register ContentObserver fallback specifically for Media Volume which Nothing
        // OS occasionally suppresses from the direct Broadcast Action
        lastMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true,
                volumeSettingsObserver);
    }

    private void handleVolumeChange(int streamType, int currentVolume) {
        // Removed GlyphEffects.muteActiveAudio() to prevent custom ringtone being
        // killed prematurely due to system volume adjustments during incoming calls.

        if (audioManager == null || streamType == -1 || currentVolume == -1)
            return;
        if (SleepGuard.isBlocked(prefs))
            return;

        int maxVolume = audioManager.getStreamMaxVolume(streamType);

        if (maxVolume > 0) {
            boolean screenOffOnly = prefs.getBoolean("volume_flip_only", false); // Key remains same for persistence
            if (screenOffOnly) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    return; // Screen is ON, skip
                }
            }
            int percentage = (int) (((float) currentVolume / maxVolume) * 100);
            if (percentage == 0) {
                AnimationManager.cancelPriority(AnimationManager.PRIORITY_VOLUME);
            } else {
                AnimationManager.showVolumeLevel(percentage, this);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
        }
        if (volumeSettingsObserver != null) {
            getContentResolver().unregisterContentObserver(volumeSettingsObserver);
        }
        AnimationManager.cancelPriority(AnimationManager.PRIORITY_VOLUME);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Volume Glyph Monitor",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Glyph Volume Interface")
                .setContentText("Monitoring system volume keys...")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
