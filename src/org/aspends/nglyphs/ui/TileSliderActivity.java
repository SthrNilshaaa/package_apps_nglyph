package org.aspends.nglyphs.ui;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.services.AutoBrightnessService;
import org.aspends.nglyphs.services.GlyphTileService;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.slider.Slider;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;

/**
 * Activity displayed as a dialog when long-pressing the Quick Settings Glyph
 * Tile.
 * Provides a real-time brightness adjustment slider.
 */
public class TileSliderActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private Vibrator vibrator;
    private int currentBrightness;
    private Slider slider;
    private boolean isMasterAllowed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        
        org.aspends.nglyphs.core.AnimationManager.init(this);
        org.aspends.nglyphs.core.GlyphManagerV2.getInstance().init(this);

        // Android passes the ComponentName of the tile that launched this preference
        // activity.
        // We only want to show the slider for the Glyph light tile, not the Master
        // tile.
        android.content.ComponentName component = getIntent()
                .getParcelableExtra(android.content.Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName.class);
        if (component != null && !component.getClassName().equals(GlyphTileService.class.getName())) {
            finish();
            return;
        }

        setContentView(R.layout.activity_tile_slider);

        if (getWindow() != null) {
            getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.90),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);
        isMasterAllowed = prefs.getBoolean("master_allow", false);
        currentBrightness = prefs.getInt("torch_brightness", 2048);

        slider = findViewById(R.id.sliderTile);

        if (!isMasterAllowed) {
            slider.setEnabled(false);
            return;
        }

        if (!prefs.getBoolean("is_light_on", false)) {
            slider.setValue(slider.getValueFrom()); // Default to minimum non-zero value
            slider.setEnabled(false);
        } else {
            slider.setValue(mapBrightnessToPosition(currentBrightness));
            slider.setEnabled(true);
        }

        com.google.android.material.button.MaterialButton btnToggle = findViewById(R.id.btnToggleTorch);
        btnToggle.setOnClickListener(v -> {
            boolean wasOn = prefs.getBoolean("is_light_on", false);
            boolean newState = !wasOn;
            
            prefs.edit().putBoolean("is_light_on", newState).apply();
            org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();
            
            slider.setEnabled(newState);
            if (newState) {
                slider.setValue(mapBrightnessToPosition(currentBrightness));
            }
            
            TileService.requestListeningState(this, new ComponentName(this, GlyphTileService.class));
            quickTick(20, 100);
        });

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                if (prefs.getBoolean("auto_brightness_enabled", false)) {
                    prefs.edit().putBoolean("auto_brightness_enabled", false).apply();
                    stopService(new android.content.Intent(this, AutoBrightnessService.class));
                }

                int brightness = mapPositionToBrightness(value);
                if (brightness != currentBrightness) {
                    quickTick(10, 50);
                    currentBrightness = brightness;
                    prefs.edit()
                            .putInt("torch_brightness", currentBrightness)
                            .putInt("brightness", currentBrightness)
                            .apply();

                    if (prefs.getBoolean("is_light_on", false)) {
                        org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();
                    }
                }
            }
        });
    }


    private void quickTick(int d, int a) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(d, a));
        }
    }

    private int mapPositionToBrightness(float p) {
        if (p <= 0)
            return 0;
        if (p >= 100)
            return 4095;
        return (int) ((p / 100f) * 4095);
    }

    private float mapBrightnessToPosition(int b) {
        if (b <= 0)
            return 0f;
        if (b >= 4095)
            return 100f;
        return (float) Math.round((b / 4095f) * 100f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
