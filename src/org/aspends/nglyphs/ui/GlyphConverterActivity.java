package org.aspends.nglyphs.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.util.OggGlyphEncoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class GlyphConverterActivity extends AppCompatActivity {

    private MaterialButton btnSelectFile, btnConvert;
    private TextView textSelectedFile, textStatus;
    private RadioGroup rgZoneMapping;
    private ProgressBar progressLoading;
    private Uri selectedFileUri;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        textSelectedFile.setVisibility(View.VISIBLE);
                        textSelectedFile.setText(selectedFileUri.getLastPathSegment());
                        btnConvert.setEnabled(true);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_glyph_converter);

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nestedScroll), (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnConvert = findViewById(R.id.btnConvert);
        textSelectedFile = findViewById(R.id.textSelectedFile);
        textStatus = findViewById(R.id.textStatus);
        rgZoneMapping = findViewById(R.id.rgZoneMapping);
        progressLoading = findViewById(R.id.progressLoading);

        btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            filePickerLauncher.launch(intent);
        });

        btnConvert.setOnClickListener(v -> startConversion());
    }

    private void startConversion() {
        if (selectedFileUri == null) return;

        boolean isExtended = rgZoneMapping.getCheckedRadioButtonId() == R.id.rbZones15;
        btnConvert.setEnabled(false);
        progressLoading.setVisibility(View.VISIBLE);
        textStatus.setText("Converting...");

        new Thread(() -> {
            try {
                // Copy to temp file for processing
                InputStream is = getContentResolver().openInputStream(selectedFileUri);
                File tempIn = new File(getCacheDir(), "input_convert" + System.currentTimeMillis());
                FileOutputStream os = new FileOutputStream(tempIn);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.close();
                is.close();

                File outputDir = new File(getFilesDir(), "custom_ringtones");
                if (!outputDir.exists()) outputDir.mkdirs();
                
                String baseName = "glyph_" + System.currentTimeMillis();
                String outName = baseName + ".ogg";
                File tempOut = new File(outputDir, outName);

                org.aspends.nglyphs.util.OggGlyphEncoder encoder = new org.aspends.nglyphs.util.OggGlyphEncoder(this);
                encoder.convert(tempIn, tempOut, isExtended, new org.aspends.nglyphs.util.OggGlyphEncoder.ProgressListener() {
                    @Override
                    public void onProgress(String status) {
                        runOnUiThread(() -> textStatus.setText(status));
                    }

                    @Override
                    public void onFinished(File result) {
                        runOnUiThread(() -> {
                            progressLoading.setVisibility(View.GONE);
                            textStatus.setText("Finished! Saved as " + result.getName());
                            Toast.makeText(GlyphConverterActivity.this, "Saved to Custom Ringtones", Toast.LENGTH_LONG).show();
                            btnConvert.setEnabled(true);
                        });
                        tempIn.delete();
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            progressLoading.setVisibility(View.GONE);
                            textStatus.setText("Error: " + error);
                            btnConvert.setEnabled(true);
                        });
                        tempIn.delete();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    textStatus.setText("System Error: " + e.getMessage());
                    btnConvert.setEnabled(true);
                });
            }
        }).start();
    }
}
