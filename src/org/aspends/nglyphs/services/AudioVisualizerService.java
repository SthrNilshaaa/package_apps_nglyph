package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphAudioVisualizer;
import org.aspends.nglyphs.core.GlyphManagerV2;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import java.util.List;

public class AudioVisualizerService extends Service {

    private AudioManager audioManager;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private GlyphAudioVisualizer visualizer;

    public static final String ACTION_REFRESH_SETTINGS = "org.aspends.nglyphs.ACTION_REFRESH_VISUALIZER_SETTINGS";

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        visualizer = new GlyphAudioVisualizer();
        visualizer.init(this);

        recordingCallback = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                boolean isAssistantRecording = false;
                for (AudioRecordingConfiguration config : configs) {
                    try {
                        if (!config.isClientSilenced()) {
                            int source = config.getClientAudioSource();
                            if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION || 
                                source == 1999) { // HOTWORD
                                isAssistantRecording = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (isAssistantRecording) {
                    visualizer.startPulsing();
                } else {
                    // Only stop if manual visualizer is also off
                    android.content.SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
                    if (!prefs.getBoolean("music_visualizer_enabled", false)) {
                        visualizer.stopPulsing();
                    }
                }
            }
        };

        audioManager.registerAudioRecordingCallback(recordingCallback, new Handler(Looper.getMainLooper()));

        // Initial check for manual activation
        android.content.SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        if (prefs.getBoolean("music_visualizer_enabled", false)) {
            visualizer.startPulsing();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REFRESH_SETTINGS.equals(intent.getAction())) {
            if (visualizer != null) {
                visualizer.refreshSettings(this);
            }
            return START_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("audio_vis_service", "Audio Visualizer", NotificationManager.IMPORTANCE_MIN);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        
        Notification notification = new NotificationCompat.Builder(this, "audio_vis_service")
                .setContentTitle("Assistant Mic Active")
                .setContentText("Listening for Assistant Audio")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
                
        int foregroundType = 0;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
             foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        startForeground(4446, notification, foregroundType);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (audioManager != null && recordingCallback != null) {
            audioManager.unregisterAudioRecordingCallback(recordingCallback);
        }
        if (visualizer != null) {
            visualizer.stop();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
