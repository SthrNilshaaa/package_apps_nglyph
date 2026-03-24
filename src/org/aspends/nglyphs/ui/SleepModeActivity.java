package org.aspends.nglyphs.ui;

import org.aspends.nglyphs.R;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import java.util.HashSet;
import java.util.Set;

public class SleepModeActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private TextView tvStart, tvEnd;
    private Set<String> selectedDays;

    /**
     * Called when the activity is starting.
     * Initializes the view layout, retrieves shared preferences, and loads
     * the previously selected active days for Sleep Mode.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *                           previously being
     *                           shut down then this Bundle contains the data.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sleep_mode);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nestedScroll), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);
        selectedDays = new HashSet<>(prefs.getStringSet("sleep_days", new HashSet<>()));

        initViews();
    }

    /**
     * Initializes UI components, sets up listeners for switches and buttons,
     * and populates initial data for the sleep schedule (times and days).
     */
    private void initViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvStart = findViewById(R.id.tvStartTime);
        tvEnd = findViewById(R.id.tvEndTime);

        MaterialSwitch sw = findViewById(R.id.switchSleepInternal);
        sw.setChecked(prefs.getBoolean("sleep_mode_enabled", false));
        sw.setOnCheckedChangeListener((v, chk) -> {
            quickTick(20, 100);
            prefs.edit().putBoolean("sleep_mode_enabled", chk).apply();
        });

        MaterialSwitch swShake = findViewById(R.id.switchAllowShake);
        swShake.setChecked(prefs.getBoolean("shake_allow_in_sleep", false));
        swShake.setOnCheckedChangeListener((v, chk) -> {
            quickTick(20, 100);
            prefs.edit().putBoolean("shake_allow_in_sleep", chk).apply();
        });

        tvStart.setText(prefs.getString("sleep_start", "23:00"));
        tvEnd.setText(prefs.getString("sleep_end", "07:00"));

        findViewById(R.id.btnStartTime).setOnClickListener(v -> showPicker(true));
        findViewById(R.id.btnEndTime).setOnClickListener(v -> showPicker(false));

        int[] ids = { R.id.day_1, R.id.day_2, R.id.day_3, R.id.day_4, R.id.day_5, R.id.day_6, R.id.day_7 };
        for (int i = 0; i < ids.length; i++) {
            MaterialButton btn = findViewById(ids[i]);
            String dId = String.valueOf(i + 1);
            updateDayUI(btn, selectedDays.contains(dId));
            btn.setOnClickListener(v -> {
                quickTick(10, 80);
                if (!selectedDays.remove(dId))
                    selectedDays.add(dId);
                updateDayUI(btn, selectedDays.contains(dId));
                prefs.edit().putStringSet("sleep_days", selectedDays).apply();
            });
        }
    }

    /**
     * Updates the visual appearance of a day selection button based on
     * whether it is currently selected or not.
     *
     * @param btn The MaterialButton representing a day.
     * @param sel True if the day is selected, false otherwise.
     */
    private void updateDayUI(MaterialButton btn, boolean sel) {
        int primaryColor = 0xFFFFFFFF;
        int surfaceVariant = 0xFF333333;
        int onPrimary = 0xFF000000;
        int onSurfaceVariant = 0xFFCCCCCC;
        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();

            // Primary color lookup
            int primaryAttr = getResources().getIdentifier("colorPrimary", "attr", getPackageName());
            if (primaryAttr != 0 && getTheme().resolveAttribute(primaryAttr, typedValue, true)) {
                primaryColor = typedValue.data;
            } else if (getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)) {
                primaryColor = typedValue.data;
            }

            // OnPrimary color lookup
            int onPrimaryAttr = getResources().getIdentifier("colorOnPrimary", "attr", getPackageName());
            if (onPrimaryAttr != 0 && getTheme().resolveAttribute(onPrimaryAttr, typedValue, true)) {
                onPrimary = typedValue.data;
            }

            // colorSurfaceContainer and colorOnSurfaceVariant lookups
            int surfaceContainerAttr = getResources().getIdentifier("colorSurfaceContainer", "attr", getPackageName());
            if (surfaceContainerAttr != 0 && getTheme().resolveAttribute(surfaceContainerAttr, typedValue, true)) {
                surfaceVariant = typedValue.data;
            }
            int onSurfaceVariantAttr = getResources().getIdentifier("colorOnSurfaceVariant", "attr", getPackageName());
            if (onSurfaceVariantAttr != 0 && getTheme().resolveAttribute(onSurfaceVariantAttr, typedValue, true)) {
                onSurfaceVariant = typedValue.data;
            }
        } catch (Exception e) {
        }
        btn.setBackgroundTintList(ColorStateList.valueOf(sel ? primaryColor : surfaceVariant));
        btn.setTextColor(sel ? onPrimary : onSurfaceVariant);
    }

    /**
     * Displays a MaterialTimePicker dialog to allow the user to select
     * either the start time or the end time for Sleep Mode.
     *
     * @param isStart True to edit the start time, false to edit the end time.
     */
    private void showPicker(boolean isStart) {
        TextView target = isStart ? tvStart : tvEnd;
        String[] time = target.getText().toString().split(":");

        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(Integer.parseInt(time[0]))
                .setMinute(Integer.parseInt(time[1]))
                .setTitleText(isStart ? R.string.sleep_picker_start : R.string.sleep_picker_end)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            String val = String.format("%02d:%02d", picker.getHour(), picker.getMinute());
            target.setText(val);
            prefs.edit().putString(isStart ? "sleep_start" : "sleep_end", val).apply();
            quickTick(15, 80);
        });
        picker.show(getSupportFragmentManager(), "picker");
    }

    /**
     * Triggers a brief, quick haptic vibration for UI feedback.
     *
     * @param d Duration in milliseconds.
     * @param a Amplitude (1-255).
     */
    private void quickTick(int d, int a) {
        if (vibrator != null && vibrator.hasVibrator())
            vibrator.vibrate(VibrationEffect.createOneShot(d, a));
    }
}