package org.aspends.nglyphs.adapters;

import org.aspends.nglyphs.R;

import android.content.Context;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.util.RingtoneHelper;
import org.aspends.nglyphs.util.CustomRingtoneManager;
import android.media.RingtoneManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StyleAdapter extends RecyclerView.Adapter<StyleAdapter.ViewHolder> {

    private final Context context;
    private final List<String> names;
    private final List<String> values;
    private final Vibrator vibrator;
    private int selectedPosition;
    private final int audioStreamType;
    private final SharedPreferencesProvider prefsProvider;
    private final String folderName;
    private final boolean isFlipMode;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface SharedPreferencesProvider {
        int getBrightness();
    }

    public interface OnItemClickListener {
        void onItemClick(String styleName);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(StyleAdapter adapter, int position);
    }

    private OnItemClickListener clickListener;
    private OnSelectionChangedListener selectionChangedListener;

    public StyleAdapter(Context context, List<String> names, List<String> values, int selectedPosition,
            Vibrator vibrator, int audioStreamType, SharedPreferencesProvider prefsProvider, String folderName, boolean isFlipMode) {
        this.context = context;
        this.names = names;
        this.values = values;
        this.selectedPosition = selectedPosition;
        this.vibrator = vibrator;
        this.audioStreamType = audioStreamType;
        this.prefsProvider = prefsProvider;
        this.folderName = folderName;
        this.isFlipMode = isFlipMode;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_style_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String name = names.get(position);
        String value = values.get(position);
        holder.textName.setText(name);
        holder.radioButton.setChecked(position == selectedPosition);

        // Show delete button only for custom ringtones (imported ones have 🎵 emoji in
        // name)
        boolean isImportedAudio = name.startsWith("🎵 ");
        boolean isImportedPattern = name.startsWith("🧩 ");
        boolean isCustom = isImportedAudio || isImportedPattern;
        
        holder.btnDelete.setVisibility(isCustom ? View.VISIBLE : View.GONE);

        boolean isSelected = position == selectedPosition;
        holder.card.setStrokeWidth(isSelected ? 3 : 1);
        int primaryColor = 0xFF000000;
        int outlineColor = 0x1F000000;
        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            // Use standard AppCompat attributes which are more reliable
            if (context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)) {
                primaryColor = typedValue.data;
            } else {
                primaryColor = 0xFF000000; // Black default
            }

            if (context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorControlHighlight, typedValue,
                    true)) {
                outlineColor = typedValue.data;
            } else {
                outlineColor = 0x1F000000; // Semi-transparent black default
            }
        } catch (Exception e) {
            primaryColor = 0xFF000000;
            outlineColor = 0x1F000000;
        }

        holder.card.setStrokeColor(isSelected ? primaryColor : outlineColor);
        holder.card.setCardBackgroundColor(isSelected ? (primaryColor & 0x15FFFFFF) : 0);
        holder.radioButton.setChecked(isSelected);

        holder.itemView.setOnClickListener(v -> {
            int oldPos = selectedPosition;
            selectedPosition = holder.getBindingAdapterPosition();
            notifyItemChanged(oldPos);
            notifyItemChanged(selectedPosition);

            // Haptic feedback
            if (vibrator != null) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(15, 80));
            }

            if (clickListener != null) {
                clickListener.onItemClick(name);
            }

            if (selectionChangedListener != null) {
                selectionChangedListener.onSelectionChanged(this, selectedPosition);
            }

            // Preview the effect
            executor.execute(() -> {
                boolean isCustomStyle = value.endsWith(".ogg") || name.startsWith("🎵 ");
                if (isCustomStyle) {
                    GlyphEffects.run(value, prefsProvider.getBrightness(), vibrator, context, audioStreamType, !isFlipMode);
                } else if ("native_flip".equals(value)) {
                    GlyphEffects.run("stock", prefsProvider.getBrightness(), vibrator, context, audioStreamType);
                } else if (folderName != null) {
                    GlyphEffects.play(context, folderName, value, vibrator, prefsProvider.getBrightness());
                } else {
                    GlyphEffects.run(value, prefsProvider.getBrightness(), vibrator, context, audioStreamType, !isFlipMode);
                }
            });
        });

        holder.radioButton.setOnClickListener(v -> holder.itemView.performClick());

        if (isCustom) {
            holder.btnDelete.setOnClickListener(v -> showDeleteConfirmation(position, value));
        }

    }

    private void showDeleteConfirmation(int position, String value) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    if (CustomRingtoneManager.deleteRingtone(context, value)) {
                        names.remove(position);
                        values.remove(position);
                        if (selectedPosition == position) {
                            selectedPosition = 0; // Default to first item
                        } else if (selectedPosition > position) {
                            selectedPosition--;
                        }
                        notifyDataSetChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public int getItemCount() {
        return names.size();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public String getSelectedValue() {
        return values.get(selectedPosition);
    }

    public String getSelectedName() {
        if (selectedPosition < 0 || selectedPosition >= names.size())
            return "None";
        return names.get(selectedPosition);
    }

    public void clearSelection() {
        int oldPos = selectedPosition;
        selectedPosition = -1;
        if (oldPos != -1) {
            notifyItemChanged(oldPos);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        RadioButton radioButton;
        TextView textName;
        ImageButton btnDelete;
        MaterialCardView card;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardStyleItem);
            radioButton = itemView.findViewById(R.id.radioStyle);
            textName = itemView.findViewById(R.id.textStyleName);
            btnDelete = itemView.findViewById(R.id.btnDeleteStyle);
        }
    }
}
