package org.aspends.nglyphs.ui;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.adapters.AppInfoAdapter;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.services.FlipToGlyphService;
import org.aspends.nglyphs.util.AppListCache;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

public class EssentialLightsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private AppInfoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_essential_lights);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nestedScroll), (v, windowInsets) -> {
            boolean isKeyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            View cardOptions = findViewById(R.id.cardOptions);
            
            if (cardOptions != null) {
                if (isKeyboardVisible && cardOptions.getVisibility() == View.VISIBLE) {
                    TransitionManager.beginDelayedTransition((ViewGroup) v);
                    cardOptions.setVisibility(View.GONE);
                } else if (!isKeyboardVisible && cardOptions.getVisibility() == View.GONE) {
                    TransitionManager.beginDelayedTransition((ViewGroup) v);
                    cardOptions.setVisibility(View.VISIBLE);
                }
            }
            
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom);
            return windowInsets;
        });

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        // Setup Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Setup Unlock Switch
        MaterialSwitch switchUnlock = findViewById(R.id.switchUnlock);
        switchUnlock.setChecked(prefs.getBoolean("essential_lights_unlock", false));
        switchUnlock.setOnCheckedChangeListener((bw, ic) -> {
            prefs.edit().putBoolean("essential_lights_unlock", ic).apply();
            if (!ic) {
                // When "Turn off when unlocked" is disabled, clear any currently ignored notifications
                // so they reappear as essential lights immediately.
                prefs.edit().remove("ignored_essential_keys").apply();
                if (org.aspends.nglyphs.services.GlyphNotificationListener.instance != null) {
                    // Force refresh by notifying the service to reload keys and update state
                    sendBroadcast(new android.content.Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL"));
                }
            }
        });

        // Setup System Apps Switch
        MaterialSwitch switchSystemApps = findViewById(R.id.switchSystemApps);
        switchSystemApps.setChecked(prefs.getBoolean("show_system_apps", false));
        switchSystemApps.setOnCheckedChangeListener((bw, ic) -> {
            prefs.edit().putBoolean("show_system_apps", ic).apply();
            if (adapter != null)
                adapter.setShowSystemApps(ic);
        });

        // Setup Red Indicator Switch
        MaterialSwitch switchRedIndicator = findViewById(R.id.switchRedIndicator);
        switchRedIndicator.setChecked(prefs.getBoolean("essential_lights_red", false));
        switchRedIndicator
                .setOnCheckedChangeListener((bw, ic) -> prefs.edit().putBoolean("essential_lights_red", ic).apply());

        // Setup Battery Saver Switch
        MaterialSwitch switchBatterySaver = findViewById(R.id.switchBatterySaver);
        switchBatterySaver.setChecked(prefs.getBoolean("essential_battery_saver", false));
        switchBatterySaver.setOnCheckedChangeListener((bw, ic) -> {
            prefs.edit().putBoolean("essential_battery_saver", ic).apply();
            // Refresh essential lights state immediately
            sendBroadcast(new android.content.Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL"));
        });

        // Setup Missed Call Switch
        MaterialSwitch switchMissedCall = findViewById(R.id.switchMissedCall);
        android.widget.ImageView iconMissedCallSettings = findViewById(R.id.iconMissedCallSettings);
        boolean isMissedCallEnabled = prefs.getBoolean("essential_missed_call", false);
        switchMissedCall.setChecked(isMissedCallEnabled);
        iconMissedCallSettings.setVisibility(isMissedCallEnabled ? View.VISIBLE : View.GONE);
        
        switchMissedCall.setOnCheckedChangeListener((bw, ic) -> {
            prefs.edit().putBoolean("essential_missed_call", ic).apply();
            iconMissedCallSettings.setVisibility(ic ? View.VISIBLE : View.GONE);
        });

        iconMissedCallSettings.setOnClickListener(v -> {
            String[] options = { "Default", "Camera", "Diagonal", "Main", "Line", "Dot", "Red" };
            String[] values = { "DEFAULT", "CAMERA", "DIAGONAL", "MAIN", "LINE", "DOT", "SINGLE_LED" };

            String currentSelection = prefs.getString("glyph_missed_call", "DEFAULT");
            int selectedIndex = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(currentSelection)) {
                    selectedIndex = i;
                    break;
                }
            }

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Select Glyph for Missed Calls")
                    .setSingleChoiceItems(options, selectedIndex, (dialog, which) -> {
                        String selectedValue = values[which];
                        prefs.edit().putString("glyph_missed_call", selectedValue).apply();

                        // Trigger Preview
                        int brightness = prefs.getInt("brightness", 2048);
                        GlyphManagerV2.Glyph previewGlyph = null;

                        if ("DEFAULT".equals(selectedValue)) {
                            boolean useRed = prefs.getBoolean("essential_lights_red", false);
                            previewGlyph = useRed ? GlyphManagerV2.Glyph.SINGLE_LED : GlyphManagerV2.Glyph.DIAGONAL;
                        } else {
                            try {
                                previewGlyph = GlyphManagerV2.Glyph.valueOf(selectedValue);
                            } catch (Exception ignored) {
                            }
                        }

                        if (previewGlyph != null) {
                            final GlyphManagerV2.Glyph finalG = previewGlyph;
                            GlyphManagerV2.getInstance().setBrightness(finalG, brightness);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                GlyphManagerV2.getInstance().setBrightness(finalG, 0);
                                sendBroadcast(
                                        new android.content.Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL"));
                            }, 1000);
                        }

                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Setup Preview Button
        com.google.android.material.button.MaterialButton btnPreview = findViewById(R.id.btnPreview);
        btnPreview.setOnClickListener(v -> {
            boolean useRed = prefs.getBoolean("essential_lights_red", false);
            GlyphManagerV2.Glyph target = useRed ? GlyphManagerV2.Glyph.SINGLE_LED : GlyphManagerV2.Glyph.DIAGONAL;
            int brightness = prefs.getInt("brightness", 2048);

            // Turn on preview
            GlyphManagerV2.getInstance().setBrightness(target, brightness);

            // Turn off after 3 seconds and restore true essential state
            v.postDelayed(() -> {
                GlyphManagerV2.getInstance().setBrightness(target, 0);
                sendBroadcast(new android.content.Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL));
            }, 3000);
        });

        // Setup RecyclerView
        RecyclerView recyclerApps = findViewById(R.id.recyclerApps);
        recyclerApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppInfoAdapter(this);
        adapter.setShowSystemApps(prefs.getBoolean("show_system_apps", false));
        recyclerApps.setAdapter(adapter);

        // Setup Search filtering
        EditText editSearch = findViewById(R.id.editSearch);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        refreshUIState();
    }

    private void refreshUIState() {
        boolean sleepActive = isSleepTimeActive();
        float alpha = sleepActive ? 0.4f : 1.0f;
        boolean enabled = !sleepActive;

        findViewById(R.id.switchUnlock).setEnabled(enabled);
        findViewById(R.id.switchSystemApps).setEnabled(enabled);
        findViewById(R.id.switchRedIndicator).setEnabled(enabled);
        findViewById(R.id.switchBatterySaver).setEnabled(enabled);
        findViewById(R.id.switchMissedCall).setEnabled(enabled);
        findViewById(R.id.btnPreview).setEnabled(enabled);
        findViewById(R.id.recyclerApps).setEnabled(enabled);
        findViewById(R.id.recyclerApps).setAlpha(alpha);
        findViewById(R.id.editSearch).setEnabled(enabled);
        findViewById(R.id.editSearch).setAlpha(alpha);

        // Root layout alpha
        findViewById(R.id.nestedScroll).setAlpha(alpha);
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

    @Override
    protected void onResume() {
        super.onResume();
        refreshUIState();
    }
}
