package org.aspends.nglyphs.ui;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.services.*;
import org.aspends.nglyphs.util.*;
import org.aspends.nglyphs.util.ShellUtils;
import org.aspends.nglyphs.util.RootNotificationHelper;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log; // Added for Log.i

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private Vibrator vibrator;
    private boolean isMasterAllowed;
    private int currentBrightness;

    private MaterialCardView cardNotifications, cardRingtones, cardFlipStyle, cardSleepTime, cardBrightness,
            cardEssentialLights, cardRingNotifHaptics, cardVolumeBar, cardShakeToGlyph,
            cardImport, cardBattery, cardTurnOff, cardGlyphProgress, cardGlyphConverter, cardTorchBrightness;

    private TextView textCurrentCallStyle, textCurrentNotifStyle, textCurrentFlipStyle, textSleepTime,
            textImportWarning;
    private MaterialSwitch switchMaster, switchFlip, switchLockscreenOnly, switchSleepMode,
            switchBattery, switchShake, switchVolumeBar, switchVolumeFlipOnly, switchRingNotifHaptics,
            switchShakeWhileOn, switchAutoBrightness, switchAssistantMic,
            switchGlyphProgress, switchGlyphProgressFlippedOnly, switchBluetoothBattery, switchBatteryWhileOn,
            switchBluetoothBatteryCombine, switchNotifCooldown;
    private com.google.android.material.button.MaterialButton btnInfo;
    private LinearLayout layoutRingNotifHapticStrength, layoutVolumeFlipOnly, layoutGlyphProgressFlippedOnly, layoutGlyphProgressBrightness;
    private Slider slider, sliderShakeSensitivity, sliderHapticStrength, sliderRingNotifHapticStrength, sliderProgressBrightness, sliderTorch;

    private RadioGroup rgShakeCount;
    private ImageView spacewar;
    private ActivityResultLauncher<Intent> importRingtoneLauncher;
    private final android.os.Handler previewHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable previewRunnable;
    private android.content.BroadcastReceiver brightnessReceiver;

    public static final String PREF_BLINK_STYLE = "glyph_blink_style";

    // if u gonna add extra styles, begin from here
    private String[] notifStyleValues;
    private String[] callStyleValues;
    private String[] flipStyleValues;

    private static final String NATIVE_FLIP_VALUE = "native_flip";

    /**
     * Called when the activity is starting.
     * Initializes the views, retrieves shared preferences, and sets up root shell
     * access.
     * Also requests necessary permissions if root access is granted.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *                           previously being
     *                           shut down then this Bundle contains the data it
     *                           most recently
     *                           supplied in onSaveInstanceState(Bundle).
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                File logFile = new File(getExternalFilesDir(null), "crash_log.txt");
                FileWriter writer = new FileWriter(logFile, true);
                writer.append("\n\n--- Crash Report ---\n");
                writer.append("Time: ").append(new java.util.Date().toString()).append("\n");
                writer.append("Thread: ").append(thread.getName()).append("\n");
                writer.append("Stack Trace:\n").append(stackTrace);
                writer.close();
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to log crash", e);
            }
            // Let the app crash normally after logging
            System.exit(1);
        });

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        importRingtoneLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        try {
                            android.net.Uri uri = result.getData().getData();
                            if (uri != null) {
                                String originalName = org.aspends.nglyphs.util.CustomRingtoneManager.getFileNameFromUri(this, uri);
                                // Strip extension from originalName if present
                                if (originalName.toLowerCase().endsWith(".ogg")) originalName = originalName.substring(0, originalName.length() - 4);
                                if (originalName.toLowerCase().endsWith(".csv")) originalName = originalName.substring(0, originalName.length() - 4);

                                String mime = getContentResolver().getType(uri);
                                boolean isCsv = (mime != null && mime.contains("text/csv")) || 
                                              (uri.getPath() != null && uri.getPath().endsWith(".csv"));
                                
                                String ext = isCsv ? ".csv" : ".ogg";
                                String fileName = "imported_" + System.currentTimeMillis() + "_" + originalName + ext;
                                
                                java.io.File destFile = org.aspends.nglyphs.util.CustomRingtoneManager.importFile(this, uri, fileName);
                                if (destFile == null) throw new Exception("Import failed");
                                
                                Toast.makeText(this, "Imported " + (isCsv ? "Pattern" : "Audio"), Toast.LENGTH_SHORT).show();
                                if (!isCsv && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.System.canWrite(this)) {
                                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                        .setTitle("Permission Required")
                                        .setMessage("To set this audio as a ringtone, dGlyphs needs permission to modify system settings. Grant it now?")
                                        .setPositiveButton("Grant", (d, w) -> org.aspends.nglyphs.util.RingtoneHelper.checkAndRequestPermissions(this))
                                        .setNegativeButton("Later", null)
                                        .show();
                                }
                                
                                // Refresh lists
                                notifStyleValues = loadStyleNames("notification");
                                callStyleValues = loadStyleNames("call");
                                updateStyleLabels();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        setContentView(R.layout.activity_main);
        GlyphManagerV2.getInstance().init(this);
        AnimationManager.init(this);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nestedScroll), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);

        notifStyleValues = loadStyleNames("notification");
        callStyleValues = loadStyleNames("call");

        // Include Native Flip as an option for Flip to Glyph
        String[] loadedFlipValues = loadStyleNames("notification");
        flipStyleValues = new String[loadedFlipValues.length + 1];
        flipStyleValues[0] = NATIVE_FLIP_VALUE;
        System.arraycopy(loadedFlipValues, 0, flipStyleValues, 1, loadedFlipValues.length);

        initViews();

        AppListCache.loadAsync(MainActivity.this);
        isMasterAllowed = prefs.getBoolean("master_allow", false);
        currentBrightness = prefs.getInt("brightness", 2048);
        checkRootAccess();
        setupUI();
        setupListeners();
        checkAllPermissions();
        setupSmoothCollapse();


        brightnessReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if ("org.aspends.nglyphs.ACTION_BRIGHTNESS_UPDATED".equals(intent.getAction())) {
                    int newBrightness = prefs.getInt("brightness", 2048);
                    float pos = mapBrightnessToPosition(newBrightness);
                    slider.setValue(pos);
                    updateOutlineAlpha(pos);
                }
            }
        };
        registerReceiver(brightnessReceiver,
                new android.content.IntentFilter("org.aspends.nglyphs.ACTION_BRIGHTNESS_UPDATED"),
                android.content.Context.RECEIVER_NOT_EXPORTED);

        // UI Refresh Loop for Sleep Mode transitions
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshUIState();
                handler.postDelayed(this, 60000); // Pulse every 1 minute
            }
        }, 60000);
    }

    private String[] loadStyleNames(String folder) {
        try {
            String[] files = getAssets().list(folder);
            if (files == null || files.length == 0)
                return new String[] {};
            List<String> names = new ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".csv")) {
                    names.add(f.replace(".csv", ""));
                }
            }
            java.util.Collections.sort(names);
            return names.toArray(new String[0]);
        } catch (Exception e) {
            return new String[] {};
        }
    }

    /**
     * Initializes all UI component references by finding them by their ID.
     */
    private void initViews() {
        cardNotifications = findViewById(R.id.cardNotifications);
        cardRingtones = findViewById(R.id.cardRingtones);
        cardFlipStyle = findViewById(R.id.cardFlipStyle);
        cardSleepTime = findViewById(R.id.cardSleepTime);
        cardBrightness = findViewById(R.id.cardBrightness);
        cardEssentialLights = findViewById(R.id.cardEssentialLights);
        cardImport = findViewById(R.id.cardImport);
        cardBattery = findViewById(R.id.cardBattery);
        cardTurnOff = findViewById(R.id.cardTurnOff);
        cardShakeToGlyph = findViewById(R.id.cardShakeToGlyph);
        cardVolumeBar = findViewById(R.id.cardVolumeBar);
        cardRingNotifHaptics = findViewById(R.id.cardRingNotifHaptics);
        cardGlyphProgress = findViewById(R.id.cardGlyphProgress);
        cardGlyphConverter = findViewById(R.id.cardGlyphConverter);
        cardTorchBrightness = findViewById(R.id.cardTorchBrightness);
        sliderTorch = findViewById(R.id.sliderTorch);

        layoutGlyphProgressBrightness = findViewById(R.id.layoutGlyphProgressBrightness);
        sliderProgressBrightness = findViewById(R.id.sliderProgressBrightness);

        textCurrentCallStyle = findViewById(R.id.textCurrentCallStyle);
        textCurrentNotifStyle = findViewById(R.id.textCurrentNotifStyle);
        textCurrentFlipStyle = findViewById(R.id.textCurrentFlipStyle);
        textSleepTime = findViewById(R.id.textSleepTime);

        slider = findViewById(R.id.sliderMain);
        switchMaster = findViewById(R.id.switchAll);
        switchAutoBrightness = findViewById(R.id.switchAutoBrightness);
        switchFlip = findViewById(R.id.switchFlip);
        switchLockscreenOnly = findViewById(R.id.switchLockscreenOnly);
        switchSleepMode = findViewById(R.id.switchSleepMode);
        switchBattery = findViewById(R.id.switchBattery);
        switchBatteryWhileOn = findViewById(R.id.switchBatteryWhileOn);
        switchShake = findViewById(R.id.switchShake);
        switchVolumeBar = findViewById(R.id.switchVolumeBar);
        switchVolumeFlipOnly = findViewById(R.id.switchVolumeFlipOnly);
        switchRingNotifHaptics = findViewById(R.id.switchRingNotifHaptics);

        sliderShakeSensitivity = findViewById(R.id.seekBar_sensitivity);
        switchShakeWhileOn = findViewById(R.id.switchShakeWhileOn);
        rgShakeCount = findViewById(R.id.rg_shake_count);
        sliderRingNotifHapticStrength = findViewById(R.id.sliderRingNotifHapticStrength);
        layoutVolumeFlipOnly = findViewById(R.id.layoutVolumeFlipOnly);
        layoutRingNotifHapticStrength = findViewById(R.id.layoutRingNotifHapticStrength);
        layoutGlyphProgressFlippedOnly = findViewById(R.id.layoutGlyphProgressFlippedOnly);
        switchGlyphProgress = findViewById(R.id.switchGlyphProgress);
        switchGlyphProgressFlippedOnly = findViewById(R.id.switchGlyphProgressFlippedOnly);
        switchBluetoothBattery = findViewById(R.id.switchBluetoothBattery);
        switchBluetoothBatteryCombine = findViewById(R.id.switchBluetoothBatteryCombine);
        btnInfo = findViewById(R.id.btnInfo);
        spacewar = findViewById(R.id.spacewar);
        switchNotifCooldown = findViewById(R.id.switchNotifCooldown);
    }

    private void startStopBatteryService() {
        Intent intent = new Intent(this, BatteryGlyphService.class);
        if (isMasterAllowed && prefs.getBoolean("battery_glyph_enabled", false)) {
            startService(intent);
        } else {
            stopService(intent);
        }
    }

    /**
     * Sets the initial state of the UI components based on stored preferences
     * and master toggle permission.
     */
    private void checkRootAccess() {
        new Thread(() -> {
            boolean hasRoot = ShellUtils.isRootAvailable();
            if (!hasRoot) {
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(this, "Root access required for Glyph control!", android.widget.Toast.LENGTH_LONG).show();
                });
            } else {
                Log.i("MainActivity", "Root access granted.");
            }
        }).start();
    }

    private void setupUI() {
        switchMaster.setChecked(isMasterAllowed);
        slider.setValue(mapBrightnessToPosition(prefs.getInt("brightness", 2048)));
        if (sliderTorch != null) {
            sliderTorch.setValue(mapBrightnessToPosition(prefs.getInt("torch_brightness", 2048)));
        }

        updateOutlineAlpha(slider.getValue());


        switchSleepMode.setChecked(prefs.getBoolean("sleep_mode_enabled", false));
        switchLockscreenOnly.setChecked(prefs.getBoolean("screen_off_only", false));
        switchBattery.setChecked(prefs.getBoolean("battery_glyph_enabled", false));
        switchBatteryWhileOn.setChecked(prefs.getBoolean("battery_screen_on_enabled", false));
        switchShake.setChecked(prefs.getBoolean("shake_enabled", false));
        switchVolumeBar.setChecked(prefs.getBoolean("volume_bar_enabled", true));
        switchVolumeFlipOnly.setChecked(prefs.getBoolean("volume_flip_only", false));
        switchRingNotifHaptics.setChecked(prefs.getBoolean("ring_notif_haptics_enabled", true));
        switchAutoBrightness.setChecked(prefs.getBoolean("auto_brightness_enabled", false));
        switchShakeWhileOn.setChecked(prefs.getBoolean("shake_while_screen_on", false));
        switchGlyphProgress.setChecked(prefs.getBoolean("glyph_progress_enabled", false));
        switchGlyphProgressFlippedOnly.setChecked(prefs.getBoolean("glyph_progress_flipped_only", false));
        switchBluetoothBattery.setChecked(prefs.getBoolean("bluetooth_battery_glyph_enabled", false));
        switchBluetoothBatteryCombine.setChecked(prefs.getBoolean("bluetooth_battery_combine_enabled", false));
        switchNotifCooldown.setChecked(prefs.getBoolean("notif_cooldown_enabled", false));
        sliderRingNotifHapticStrength.setValue(prefs.getInt("ring_notif_haptic_strength", 100));
        sliderProgressBrightness.setValue(prefs.getInt("glyph_progress_brightness_factor", 70));
        sliderShakeSensitivity.setValue(prefs.getInt("shake_sensitivity", 50));

        // Initialize default styles if missing to ensure immediate functionality on
        // fresh install
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains("glyph_blink_style")) {
            editor.putString("glyph_blink_style", "static");
        }
        if (!prefs.contains("call_style_value")) {
            editor.putString("call_style_value", "static");
        }
        if (!prefs.contains("flip_style_value")) {
            editor.putString("flip_style_value", NATIVE_FLIP_VALUE);
        }
        if (!prefs.contains("shake_count")) {
            editor.putInt("shake_count", 3);
        }
        if (!prefs.contains("shake_sensitivity")) {
            editor.putInt("shake_sensitivity", 50);
        }
        if (!prefs.contains("torch_brightness")) {
            editor.putInt("torch_brightness", prefs.getInt("brightness", 2048));
        }
        editor.apply();
        int shakes = prefs.getInt("shake_count", 3);
        if (shakes == 1)
            rgShakeCount.check(R.id.rb_one);
        else if (shakes == 2)
            rgShakeCount.check(R.id.rb_two);
        else
            rgShakeCount.check(R.id.rb_three);

        updateStyleLabels();
        updateSleepTimeLabel();
        layoutVolumeFlipOnly.setVisibility(switchVolumeBar.isChecked() ? View.VISIBLE : View.GONE);
        layoutRingNotifHapticStrength.setVisibility(switchRingNotifHaptics.isChecked() ? View.VISIBLE : View.GONE);
        layoutGlyphProgressFlippedOnly.setVisibility(switchGlyphProgress.isChecked() ? View.VISIBLE : View.GONE);
        layoutGlyphProgressBrightness.setVisibility(switchGlyphProgress.isChecked() ? View.VISIBLE : View.GONE);

        refreshUIState();

        // Start services if enabled
        if (isMasterAllowed) {
            Intent flipIntent = new Intent(this, FlipToGlyphService.class);
            Intent batteryIntent = new Intent(this, BatteryGlyphService.class);
            Intent volumeIntent = new Intent(this, VolumeObserverService.class);
            Intent shakeIntent = new Intent(this, ShakeToGlyphService.class);
            Intent autoBrightIntent = new Intent(this, AutoBrightnessService.class);

            startService(flipIntent);

            if (prefs.getBoolean("battery_glyph_enabled", false)) {
                startService(batteryIntent);
            }
            if (prefs.getBoolean("volume_bar_enabled", true)) {
                startService(volumeIntent);
            }
            if (prefs.getBoolean("shake_enabled", false)) {
                startService(shakeIntent);
            }
            if (prefs.getBoolean("auto_brightness_enabled", false)) {
                startService(autoBrightIntent);
            }
            if (prefs.getBoolean("assistant_mic_pulsing", false) || 
                prefs.getBoolean("music_visualizer_enabled", false)) {
                startService(new Intent(this, AudioVisualizerService.class));
            }
        }
    }

    private void setupListeners() {
        cardNotifications.setOnClickListener(v -> showStyleDialog(R.string.card_notifications, "glyph_blink_style_idx",
                PREF_BLINK_STYLE, "notification", notifStyleValues));
        cardRingtones.setOnClickListener(v -> showStyleDialog(R.string.card_ringtones, "call_style_idx",
                "call_style_value", "call", callStyleValues));
        cardFlipStyle.setOnClickListener(v -> showStyleDialog(R.string.flip_to_glyph_label, "flip_style_idx",
                "flip_style_value", "notification", flipStyleValues));

        cardSleepTime.setOnClickListener(v -> startActivity(new Intent(this, SleepModeActivity.class)));
        cardEssentialLights.setOnClickListener(v -> startActivity(new Intent(this, EssentialLightsActivity.class)));
        cardImport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/ogg");
            importRingtoneLauncher.launch(intent);
        });
        cardGlyphConverter.setOnClickListener(v -> startActivity(new Intent(this, GlyphConverterActivity.class)));

        switchMaster.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(25, 120);
            isMasterAllowed = isChecked;
            prefs.edit().putBoolean("master_allow", isChecked).apply();

            Intent flipIntent = new Intent(this, FlipToGlyphService.class);
            Intent batteryIntent = new Intent(this, BatteryGlyphService.class);
            Intent volumeIntent = new Intent(this, VolumeObserverService.class);
            Intent shakeIntent = new Intent(this, ShakeToGlyphService.class);
            Intent autoBrightIntent = new Intent(this, AutoBrightnessService.class);

            if (isChecked) {
                startService(flipIntent);
                if (prefs.getBoolean("battery_glyph_enabled", false))
                    startService(batteryIntent);
                if (prefs.getBoolean("volume_bar_enabled", true))
                    startService(volumeIntent);
                if (prefs.getBoolean("auto_brightness_enabled", false))
                    startService(autoBrightIntent);
                if (prefs.getBoolean("assistant_mic_pulsing", false) || 
                    prefs.getBoolean("music_visualizer_enabled", false))
                    startService(new Intent(this, AudioVisualizerService.class));
            } else {
                stopService(flipIntent);
                stopService(shakeIntent);
                stopService(autoBrightIntent);
                stopService(new Intent(this, AudioVisualizerService.class));
            }
            sendBroadcast(new Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL").setPackage(getPackageName()));
            refreshUIState();
        });

        switchFlip.setChecked(prefs.getBoolean("flip_to_glyph_enabled", false));
        switchFlip.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(20, 100);
            prefs.edit().putBoolean("flip_to_glyph_enabled", isChecked).apply();
            // Do not stop FlipToGlyphService here! It also processes notifications and call
            // ringtones!
            // The service now handles ignoring flip sensor events internally when this is
            // false.
        });

        switchAutoBrightness.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("auto_brightness_enabled", ic).apply();
            Intent intent = new Intent(this, AutoBrightnessService.class);
            if (ic && isMasterAllowed)
                startService(intent);
            else
                stopService(intent);
        });

        switchShakeWhileOn.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(25, 120);
            prefs.edit().putBoolean("shake_while_screen_on", isChecked).apply();
        });

        switchLockscreenOnly.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("screen_off_only", isChecked).apply();
        });

        switchSleepMode.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(20, 100);
            prefs.edit().putBoolean("sleep_mode_enabled", isChecked).apply();
            refreshUIState();
        });

        switchBattery.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("battery_glyph_enabled", isChecked).apply();
            startStopBatteryService();
        });

        switchBatteryWhileOn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("battery_screen_on_enabled", isChecked).apply();
            startStopBatteryService();
        });

        switchShake.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("shake_enabled", ic).apply();
            Intent intent = new Intent(this, ShakeToGlyphService.class);
            if (ic && isMasterAllowed)
                startService(intent);
            else
                stopService(intent);
        });

        switchVolumeBar.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("volume_bar_enabled", ic).apply();
            layoutVolumeFlipOnly.setVisibility(ic ? View.VISIBLE : View.GONE);
            Intent intent = new Intent(this, VolumeObserverService.class);
            if (ic && isMasterAllowed)
                startService(intent);
            else
                stopService(intent);
        });

        switchVolumeFlipOnly
                .setOnCheckedChangeListener((v, ic) -> prefs.edit().putBoolean("volume_flip_only", ic).apply());

        switchRingNotifHaptics.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("ring_notif_haptics_enabled", ic).apply();
            layoutRingNotifHapticStrength.setVisibility(ic ? View.VISIBLE : View.GONE);
        });

        switchGlyphProgress.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("glyph_progress_enabled", ic).apply();
            layoutGlyphProgressFlippedOnly.setVisibility(ic ? View.VISIBLE : View.GONE);
            layoutGlyphProgressBrightness.setVisibility(ic ? View.VISIBLE : View.GONE);
        });

        switchGlyphProgressFlippedOnly.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("glyph_progress_flipped_only", ic).apply();
        });

        switchBluetoothBattery.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("bluetooth_battery_glyph_enabled", ic).apply();
            refreshUIState();
        });

        switchBluetoothBatteryCombine.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("bluetooth_battery_combine_enabled", ic).apply();
        });

        switchNotifCooldown.setChecked(prefs.getBoolean("notif_cooldown_enabled", false));
        switchNotifCooldown.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("notif_cooldown_enabled", ic).apply();
            refreshUIState();
        });

        btnInfo.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.changelog_title)
                    .setMessage(android.text.Html.fromHtml(getString(R.string.changelog_content), android.text.Html.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                if (switchAutoBrightness.isChecked()) {
                    switchAutoBrightness.setChecked(false); // Manually sliding overrides auto
                }

                // Haptic feedback tick for slider
                quickTick(10, VibrationEffect.DEFAULT_AMPLITUDE);

                int brightness = mapPositionToBrightness(value);
                prefs.edit()
                        .putInt("brightness", brightness)
                        .apply();
                updateOutlineAlpha(value);

                if (isMasterAllowed) {
                    updateHardware(brightness);
                    previewHandler.removeCallbacksAndMessages(null);
                    previewHandler.postDelayed(() -> updateHardware(0), 1000);
                }
            }
        });

        if (sliderTorch != null) {
            sliderTorch.addOnChangeListener((s, value, fromUser) -> {
                if (fromUser) {
                    quickTick(10, VibrationEffect.DEFAULT_AMPLITUDE);
                    int torchBrightness = mapPositionToBrightness(value);
                    prefs.edit().putInt("torch_brightness", torchBrightness).apply();
                    if (isMasterAllowed && prefs.getBoolean("is_light_on", false)) {
                        updateHardware(torchBrightness);
                    }
                }
            });
        }


        sliderShakeSensitivity.addOnChangeListener((s, value, fromUser) -> {

            if (fromUser) {
                int strength = (int) value;
                quickTick(20, strength);
                prefs.edit().putInt("shake_sensitivity", (int) value).apply();
            }
        });

        sliderRingNotifHapticStrength.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                int strength = (int) value;
                quickTick(20, strength);
                prefs.edit().putInt("ring_notif_haptic_strength", strength).apply();
            }
        });

        sliderProgressBrightness.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                quickTick(10, 50);
                prefs.edit().putInt("glyph_progress_brightness_factor", (int) value).apply();
            }
        });

        rgShakeCount.setOnCheckedChangeListener((group, checkedId) -> {
            int shakes = 2;
            if (checkedId == R.id.rb_one)
                shakes = 1;
            else if (checkedId == R.id.rb_three)
                shakes = 3;
            prefs.edit().putInt("shake_count", shakes).apply();
        });
    }

    private void showStyleDialog(int titleRes, String idxKey, String valKey, String folderName, String[] values) {
        if (values == null || values.length == 0)
            return;

        Intent intent = new Intent(this, StyleSelectionActivity.class);
        intent.putExtra("titleRes", titleRes);
        intent.putExtra("idxKey", idxKey);
        intent.putExtra("valKey", valKey);
        intent.putExtra("folderName", folderName);
        intent.putExtra("values", values);
        startActivity(intent);
    }

    private void updateStyleLabels() {
        int nIdx = prefs.getInt("glyph_blink_style_idx", 0);
        int cIdx = prefs.getInt("call_style_idx", 0);
        int fIdx = prefs.getInt("flip_style_idx", 0);

        List<java.io.File> customs = CustomRingtoneManager.getImportedRingtones(this);

        if (notifStyleValues != null && nIdx < notifStyleValues.length) {
            textCurrentNotifStyle.setText(notifStyleValues[nIdx]);
        } else if (notifStyleValues != null && nIdx - notifStyleValues.length < customs.size()) {
            textCurrentNotifStyle.setText(CustomRingtoneManager.cleanStyleName(customs.get(nIdx - notifStyleValues.length).getName()));
        }

        if (callStyleValues != null && cIdx < callStyleValues.length) {
            textCurrentCallStyle.setText(callStyleValues[cIdx]);
        } else if (callStyleValues != null && cIdx - callStyleValues.length < customs.size()) {
            textCurrentCallStyle.setText(CustomRingtoneManager.cleanStyleName(customs.get(cIdx - callStyleValues.length).getName()));
        }

        if (flipStyleValues != null && fIdx < flipStyleValues.length) {
            String val = flipStyleValues[fIdx];
            if (NATIVE_FLIP_VALUE.equals(val)) {
                textCurrentFlipStyle.setText("Stock");
            } else {
                textCurrentFlipStyle.setText(val);
            }
        } else if (flipStyleValues != null && fIdx - flipStyleValues.length < customs.size()) {
            textCurrentFlipStyle.setText(CustomRingtoneManager.cleanStyleName(customs.get(fIdx - flipStyleValues.length).getName()));
        }

        checkCustomAudioWarning((notifStyleValues != null && nIdx >= notifStyleValues.length) ||
                (callStyleValues != null && cIdx >= callStyleValues.length) ||
                (flipStyleValues != null && fIdx >= flipStyleValues.length));
    }

    private void checkCustomAudioWarning(boolean hasCustom) {
        if (textImportWarning != null) {
            textImportWarning.setVisibility(hasCustom ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }
    private void handleImportResult(Intent data) {
        if (data != null && data.getData() != null) {
            Uri uri = data.getData();
            String originalName = CustomRingtoneManager.getFileNameFromUri(this, uri);
            if (originalName.toLowerCase().endsWith(".ogg")) originalName = originalName.substring(0, originalName.length() - 4);
            String fileName = "imported_" + System.currentTimeMillis() + "_" + originalName + ".ogg";

            java.io.File oggFile = CustomRingtoneManager.importFile(this, uri, fileName);
            if (oggFile != null) {
                String timeline = null;
                java.io.File customCsv = new java.io.File(getFilesDir(), "custom_ringtones/" + oggFile.getName().replace(".ogg", ".csv"));
                if (customCsv.exists()) {
                    timeline = CustomRingtoneManager.loadCSV(customCsv);
                } else {
                    timeline = OggMetadataParser.extractGlyphTimeline(oggFile);
                }
                if (timeline != null) {
                    Toast.makeText(this, "Success: Extracted Timeline & Saved!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Audio saved. You can now set it as system ringtone from the style picker.",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Failed to import ringtone.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Checks if the required permissions (Notification Listener, Phone State)
     * are granted. If not, prompts the user or shows a settings dialog.
     */
    private void checkAllPermissions() {
        if (!isNotificationServiceEnabled()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_permission_notif_title)
                    .setMessage(R.string.dialog_permission_notif_message)
                    .setPositiveButton(R.string.btn_settings,
                            (d, w) -> startActivity(
                                    new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")))
                    .show();
        }

        List<String> permissionsToRequest = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (prefs.getBoolean("assistant_mic_visualizer", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissionsToRequest.isEmpty()) {
            requestPermissions(permissionsToRequest.toArray(new String[0]), 101);
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Battery Optimization")
                    .setMessage(
                            "To ensure Shake to Glyph and Assistant Visualizer run reliably in the background, please disable battery optimization for dGlyphs.")
                    .setPositiveButton("Allow", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    /**
     * Checks if the app currently has permission to read notifications.
     *
     * @return True if the notification listener service is enabled for this app.
     */
    private boolean isNotificationServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    /**
     * Refreshes the visual state (enabled/alpha) of the UI elements based on
     * sleep mode and master toggle permissions.
     */
    private void refreshUIState() {
        boolean sleepActive = isSleepTimeActive();
        boolean generalEnabled = isMasterAllowed && !sleepActive;

        // Master switch and Sleep card are special
        switchMaster.setEnabled(!sleepActive);
        cardSleepTime.setEnabled(isMasterAllowed); // Always allow adjusting sleep time if master is on
        switchSleepMode.setEnabled(isMasterAllowed); // Always allow toggling sleep mode if master is on

        float alpha = generalEnabled ? 1.0f : 0.4f;
        MaterialCardView[] cards = { cardNotifications, cardRingtones, cardBrightness, cardFlipStyle,
                cardEssentialLights, cardVolumeBar, cardRingNotifHaptics, cardBattery, cardTurnOff,
                cardShakeToGlyph, cardImport, cardGlyphProgress, cardSleepTime };

        for (MaterialCardView c : cards) {
            if (c != null) {
                c.setEnabled(generalEnabled || c == cardSleepTime || c == cardTurnOff); // Keep some enabled for interaction if needed
                if (c == cardTurnOff && !isMasterAllowed) {
                    c.setEnabled(false);
                    c.setAlpha(0.4f);
                } else if (c == cardSleepTime && !isMasterAllowed) {
                     c.setEnabled(false);
                     c.setAlpha(0.4f);
                } else {
                    c.setEnabled(generalEnabled);
                    c.setAlpha(alpha);
                }
            }
        }

        // Specifically handle all switches/sliders/pickers
        MaterialSwitch[] switches = { switchFlip, switchLockscreenOnly, switchBattery, switchShake,
                switchVolumeBar, switchVolumeFlipOnly, switchRingNotifHaptics,
                switchShakeWhileOn, switchAutoBrightness, switchAssistantMic,
                switchGlyphProgress, switchGlyphProgressFlippedOnly, switchBluetoothBattery, switchBluetoothBatteryCombine };

        for (MaterialSwitch s : switches) {
            if (s != null)
                s.setEnabled(generalEnabled);
        }

        if (switchBluetoothBatteryCombine != null) {
            switchBluetoothBatteryCombine.setEnabled(generalEnabled && switchBluetoothBattery.isChecked());
        }

        Slider[] sliders = { slider, sliderShakeSensitivity, sliderRingNotifHapticStrength, sliderTorch };

        for (Slider sl : sliders) {
            if (sl != null)
                sl.setEnabled(generalEnabled);
        }

        if (rgShakeCount != null) {
            for (int i = 0; i < rgShakeCount.getChildCount(); i++) {
                rgShakeCount.getChildAt(i).setEnabled(generalEnabled);
            }
        }
    }

    /**
     * Triggers a brief, quick haptic vibration for UI feedback.
     *
     * @param d Duration in milliseconds.
     * @param a Amplitude (1-255).
     */
    private void quickTick(int d, int a) {
        if (!prefs.getBoolean("ring_notif_haptics_enabled", true))
            return;
        
        // Scale 0-100 to 1-255 to prevent crash and follow 0-100 slider
        int scaledA = (a * 255) / 100;
        scaledA = Math.max(1, Math.min(scaledA, 255));
        
        if (vibrator != null && vibrator.hasVibrator()) {
            android.os.VibrationAttributes attrs = new android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build();
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(d, scaledA), attrs);
        }
    }


    /**
     * Directly updates the brightness of all glyph LEDs via the GlyphManagerV2.
     *
     * @param val The brightness level to apply.
     */
    private void updateHardware(int val) {
        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs())
            GlyphManagerV2.getInstance().setBrightness(g, val);

        if (val == 0) {
            GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.SINGLE_LED, 0); // Explicit 0 allows natural
                                                                                            // toggles off
            sendBroadcast(new Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL").setPackage(getPackageName()));
        }
    }

    /**
     * Requests the system to update the state of the Quick Settings tile
     * associated with the master toggle.
     */
    private void updateTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, MasterTileService.class));
        } catch (Exception ignored) {
        }
    }

    /**
     * Maps the UI slider's position (1 to 4) to actual brightness values.
     *
     * @param p The slider position.
     * @return The corresponding brightness level.
     */
    private int mapPositionToBrightness(float p) {
        // Steps of ~570 gap: 100, 671, 1242, 1813, 2384, 2955, 3526, 4095
        if (p <= 1) return 100;
        if (p <= 2) return 671;
        if (p <= 3) return 1242;
        if (p <= 4) return 1813;
        if (p <= 5) return 2384;
        if (p <= 6) return 2955;
        if (p <= 7) return 3526;
        return 4095;
    }


    /**
     * Maps actual brightness values back to the UI slider's position (1 to 8).
     *
     * @param b The brightness level.
     * @return The corresponding slider position.
     */
    private float mapBrightnessToPosition(int b) {
        if (b <= 100) return 1f;
        if (b <= 671) return 2f;
        if (b <= 1242) return 3f;
        if (b <= 1813) return 4f;
        if (b <= 2384) return 5f;
        if (b <= 2955) return 6f;
        if (b <= 3526) return 7f;
        return 8f;
    }



    /**
     * Updates the label showing the start and end times for Sleep Mode.
     */
    private void updateSleepTimeLabel() {
        textSleepTime.setText(prefs.getString("sleep_start", "23:00") + " - " + prefs.getString("sleep_end", "07:00"));
    }

    private boolean isSleepTimeActive() {
        if (!prefs.getBoolean("sleep_mode_enabled", false))
            return false;
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

    /**
     * Called when the activity resumes from a paused state.
     * Re-initializes the UI to ensure changes from other activities
     * (like SleepModeActivity) are reflected.
     */
    @Override
    protected void onResume() {
        super.onResume();
        setupUI();
    }

    private void setupSmoothCollapse() {
        AppBarLayout appBar = findViewById(R.id.appBarLayout);
        CollapsingToolbarLayout ctl = findViewById(R.id.collapsingToolbar);
        if (appBar == null || ctl == null)
            return;

        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true);
        int colorOnSurface = typedValue.data;

        appBar.addOnOffsetChangedListener((bar, verticalOffset) -> {
            int totalScrollRange = bar.getTotalScrollRange();
            if (totalScrollRange == 0)
                return;

            float fraction = Math.abs((float) verticalOffset / totalScrollRange);
            float smooth = fraction * fraction * (3f - 2f * fraction);
            int alpha = (int) (255 * (1f - smooth));
            int color = (alpha << 24) | (colorOnSurface & 0x00FFFFFF);
            ctl.setExpandedTitleColor(color);
        });
    }

    private void updateOutlineAlpha(float sliderValue) {
        if (spacewar != null) {
            float alpha = 0.2f + ((sliderValue - 1) / 4f) * 0.8f;
            spacewar.setAlpha(alpha);
        }
    }
}