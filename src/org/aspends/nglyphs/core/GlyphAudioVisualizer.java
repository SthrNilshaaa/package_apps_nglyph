package org.aspends.nglyphs.core;

import org.aspends.nglyphs.R;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GlyphAudioVisualizer {

    private boolean isPulsing = false;

    // Audio configuration
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 512; // Must be power of 2
    private AudioRecord audioRecord;
    private int bufferSize;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final float[] smoothedZones = new float[5];
    private volatile float decayRate = 0.85f; // Configurable smoothing
    private volatile float sensitivity = 1.0f;

    public void init(Context context) {
        refreshSettings(context);
    }

    public void refreshSettings(Context context) {
        // Load settings from prefs
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                context.getString(R.string.pref_file), Context.MODE_PRIVATE);
        sensitivity = prefs.getInt("visualizer_sensitivity", 50) / 50.0f;
        decayRate = prefs.getInt("visualizer_decay", 85) / 100.0f;
    }

    public void startPulsing() {
        if (isPulsing)
            return;
        isPulsing = true;

        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize < FFT_SIZE * 2) {
            bufferSize = FFT_SIZE * 2;
        }

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
                executor.execute(this::audioLoop);
            } else {
                Log.e("GlyphAudioVis", "AudioRecord initialization failed.");
                isPulsing = false;
            }
        } catch (SecurityException e) {
            Log.e("GlyphAudioVis", "Missing RECORD_AUDIO permission", e);
            isPulsing = false;
        }
    }

    private void audioLoop() {
        short[] audioBuffer = new short[FFT_SIZE];
        double[] fftReal = new double[FFT_SIZE];
        double[] fftImag = new double[FFT_SIZE];
        
        while (isPulsing && audioRecord != null
                && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            
            int readSize = audioRecord.read(audioBuffer, 0, FFT_SIZE);
            if (readSize < FFT_SIZE) continue;

            // Prepare FFT input
            for (int i = 0; i < FFT_SIZE; i++) {
                fftReal[i] = audioBuffer[i] / 32768.0; // Normalize
                fftImag[i] = 0;
            }

            org.aspends.nglyphs.util.FFT.fft(fftReal, fftImag);

            // Calculate magnitude for each bin
            double[] magnitudes = new double[FFT_SIZE / 2];
            for (int i = 0; i < FFT_SIZE / 2; i++) {
                magnitudes[i] = Math.sqrt(fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]);
            }

            // Map to 5 Zones
            // Bin resolution: 44100 / 512 = ~86Hz
            float[] zonePeaks = new float[5];
            
            // Bass: 0 - 150Hz (Bins 0-2)
            zonePeaks[0] = getPeak(magnitudes, 0, 2);
            // Low Mids: 150 - 500Hz (Bins 2-6)
            zonePeaks[1] = getPeak(magnitudes, 2, 6);
            // Mids: 500 - 2000Hz (Bins 6-23)
            zonePeaks[2] = getPeak(magnitudes, 6, 23);
            // High Mids: 2000 - 6000Hz (Bins 23-70)
            zonePeaks[3] = getPeak(magnitudes, 23, 70);
            // Highs: 6000 - 20000Hz (Bins 70-230)
            zonePeaks[4] = getPeak(magnitudes, 70, 230);

            int[] zoneIntensities = new int[5];
            for (int i = 0; i < 5; i++) {
                float intensity = zonePeaks[i] * 15 * sensitivity; // Scaling factor
                if (intensity > 1.0f) intensity = 1.0f;
                
                // Downward smoothing
                if (intensity > smoothedZones[i]) {
                    smoothedZones[i] = intensity;
                } else {
                    smoothedZones[i] *= decayRate;
                }
                
                zoneIntensities[i] = (int) (smoothedZones[i] * GlyphManagerV2.MAX_BRIGHTNESS);
            }

            AnimationManager.showVisualizer(zoneIntensities);
        }
    }

    private float getPeak(double[] magnitudes, int start, int end) {
        double max = 0;
        for (int i = start; i < end && i < magnitudes.length; i++) {
            if (magnitudes[i] > max) max = magnitudes[i];
        }
        return (float) max;
    }

    private void resetProgressBar() {
        try {
            AnimationManager.cancelPriority(AnimationManager.PRIORITY_VISUALIZER);
        } catch (Exception e) {
            Log.e("GlyphAudioVis", "Error restoring matrix frame", e);
        }
    }

    public void stopPulsing() {
        isPulsing = false;
        if (audioRecord != null) {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
            audioRecord.release();
            audioRecord = null;
        }
        resetProgressBar(); // Release priority
    }

    public void stop() {
        stopPulsing();
    }
}
