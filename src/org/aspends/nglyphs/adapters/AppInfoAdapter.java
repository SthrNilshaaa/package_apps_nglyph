package org.aspends.nglyphs.adapters;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.AppListCache;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.service.notification.StatusBarNotification;

public class AppInfoAdapter extends RecyclerView.Adapter<AppInfoAdapter.AppViewHolder> implements Filterable {

    public static class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public boolean isEnabled;
        public boolean isSystemApp;
        public boolean isExpanded = false;
    }

    private final Context context;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    private List<AppInfo> rawApps = new ArrayList<>();
    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> filteredApps = new ArrayList<>();
    private Set<String> enabledPackages;
    private boolean showSystemApps = false;
    private CharSequence currentQuery = "";

    // Bounds the lazy-loader threads to avoid OutOfMemory or Thread exhaustion
    // resulting in ANRs
    private final ExecutorService iconLoaderExecutor = Executors.newFixedThreadPool(4);

    public AppInfoAdapter(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.prefs = context.getSharedPreferences(context.getString(R.string.pref_file), Context.MODE_PRIVATE);
        this.enabledPackages = new HashSet<>(prefs.getStringSet("essential_apps", new HashSet<>()));
        
        loadApps();
    }

    public void setShowSystemApps(boolean show) {
        this.showSystemApps = show;
        applyFilters();
    }

    private void setCurrentQuery(CharSequence query) {
        this.currentQuery = query;
        applyFilters();
    }

    private void applyFilters() {
        List<AppInfo> level1 = new ArrayList<>();
        for (AppInfo row : rawApps) {
            // Hide system apps if toggle is off, UNLESS the user has already enabled them
            // previously.
            if (!showSystemApps && row.isSystemApp && !row.isEnabled) {
                continue;
            }
            level1.add(row);
        }
        allApps = level1;

        if (currentQuery == null || currentQuery.toString().isEmpty()) {
            filteredApps = allApps;
        } else {
            List<AppInfo> level2 = new ArrayList<>();
            for (AppInfo row : allApps) {
                if (row.name.toLowerCase().contains(currentQuery.toString().toLowerCase())) {
                    level2.add(row);
                }
            }
            filteredApps = level2;
        }
        notifyDataSetChanged();
    }

    private void loadApps() {
        iconLoaderExecutor.execute(() -> {
            List<AppInfo> loadedApps = new ArrayList<>();

            if (AppListCache.cachedApps != null) {
                // Duplicate and append specific user preferences natively
                for (AppInfo cache : AppListCache.cachedApps) {
                    AppInfo info = new AppInfo();
                    info.name = cache.name;
                    info.packageName = cache.packageName;
                    info.icon = cache.icon;
                    info.isSystemApp = cache.isSystemApp;
                    info.isEnabled = enabledPackages.contains(info.packageName);
                    loadedApps.add(info);
                }
            } else {
                List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo packageInfo : packages) {
                    if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
                        AppInfo info = new AppInfo();
                        info.name = packageInfo.loadLabel(pm).toString();
                        info.packageName = packageInfo.packageName;
                        // Do NOT load icon here; it takes too long for hundreds of apps. Lazy load in
                        // onBindViewHolder.
                        info.icon = null;
                        info.isEnabled = enabledPackages.contains(info.packageName);
                        info.isSystemApp = (packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        loadedApps.add(info);
                    }
                }
            }

            Collections.sort(loadedApps, (a1, a2) -> {
                if (a1.isEnabled && !a2.isEnabled)
                    return -1;
                if (!a1.isEnabled && a2.isEnabled)
                    return 1;
                return a1.name.toLowerCase().compareTo(a2.name.toLowerCase());
            });

            ((android.app.Activity) context).runOnUiThread(() -> {
                rawApps = new ArrayList<>(loadedApps);
                applyFilters();
            });
        });
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new AppViewHolder(LayoutInflater.from(context).inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = filteredApps.get(position);
        holder.textName.setText(app.name);

        // Lazy-load the icon onto the UI thread to prevent blocking
        if (app.icon == null) {
            holder.imageIcon.setImageDrawable(null); // Clear recycled image
            iconLoaderExecutor.execute(() -> {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(app.packageName, 0);
                    final android.graphics.drawable.Drawable loadedIcon = pm.getApplicationIcon(info);
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        app.icon = loadedIcon;
                        // Ensure the holder hasn't been recycled for a different app before setting
                        if (holder.getBindingAdapterPosition() == position) {
                            holder.imageIcon.setImageDrawable(app.icon);
                        }
                    });
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            });
        } else {
            holder.imageIcon.setImageDrawable(app.icon);
        }

        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(app.isEnabled);
        holder.iconSettings.setVisibility(app.isEnabled ? View.VISIBLE : View.GONE);

        holder.layoutAppBody.setOnClickListener(v -> {
            app.isExpanded = !app.isExpanded;
            notifyItemChanged(position);
        });

        holder.switchEnabled.setOnCheckedChangeListener((btn, isChecked) -> {
            app.isEnabled = isChecked;
            holder.iconSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);

            if (isChecked) {
                enabledPackages.add(app.packageName);
            } else {
                enabledPackages.remove(app.packageName);
                app.isExpanded = false;
            }
            prefs.edit().putStringSet("essential_apps", enabledPackages).apply();

            // Sync status with original rawApps list so persistence is kept when
            // re-filtering
            for (AppInfo origApp : rawApps) {
                if (origApp.packageName.equals(app.packageName)) {
                    origApp.isEnabled = isChecked;
                    break;
                }
            }
            notifyItemChanged(position);
        });

        holder.iconSettings.setOnClickListener(v -> {
            String[] options = { "Default", "Camera", "Diagonal", "Main", "Line", "Dot", "Red" };
            String[] values = { "DEFAULT", "CAMERA", "DIAGONAL", "MAIN", "LINE", "DOT", "SINGLE_LED" };

            String currentSelection = prefs.getString("glyph_app_" + app.packageName, "DEFAULT");
            int selectedIndex = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(currentSelection)) {
                    selectedIndex = i;
                    break;
                }
            }

            new AlertDialog.Builder(context)
                    .setTitle("Select Glyph for " + app.name)
                    .setSingleChoiceItems(options, selectedIndex, (dialog, which) -> {
                        String selectedValue = values[which];
                        prefs.edit().putString("glyph_app_" + app.packageName, selectedValue).apply();

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
                                context.sendBroadcast(
                                        new android.content.Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL"));
                            }, 1000);
                        }

                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        if (app.isExpanded) {
            holder.layoutChannels.setVisibility(View.VISIBLE);
            holder.layoutChannels.removeAllViews();
            
            // 1. Persistent Cache (Historically detected)
            List<org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory> discoveredChannels_metadata = new ArrayList<>();
            Set<String> cachedIds = org.aspends.nglyphs.util.RootNotificationHelper.getChannelsForPackage(context, app.packageName);
            for (String id : cachedIds) {
                discoveredChannels_metadata.add(new org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory(app.packageName, id, id, 3));
            }

            // 2. Active Session discovery (Live Fallback)
            org.aspends.nglyphs.services.GlyphNotificationListener listener = org.aspends.nglyphs.services.GlyphNotificationListener.instance;
            if (listener != null) {
                try {
                    StatusBarNotification[] active = listener.getActiveNotifications();
                    if (active != null) {
                        for (StatusBarNotification sbn : active) {
                            if (sbn.getPackageName().equals(app.packageName)) {
                                String cid = sbn.getNotification().getChannelId();
                                boolean alreadyHas = false;
                                for (org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory c : discoveredChannels_metadata) {
                                    if (c.channelId.equals(cid)) { alreadyHas = true; break; }
                                }
                                if (!alreadyHas) {
                                    discoveredChannels_metadata.add(new org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory(app.packageName, cid, cid, 3));
                                    org.aspends.nglyphs.util.RootNotificationHelper.persistDiscovery(context, app.packageName, cid);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            // 3. Previously Selected channels (Ensures persistence)
            Set<String> selectedChannels = prefs.getStringSet("essential_channels_" + app.packageName, new HashSet<>());
            Set<String> discoveredChannelsIds = new HashSet<>();
            for (org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory c : discoveredChannels_metadata) {
                discoveredChannelsIds.add(c.channelId);
            }
            discoveredChannelsIds.addAll(selectedChannels);

            // Logic: If app is enabled AND selectedChannels is EMPTY, treat ALL as checked (Default-On).
            // If selectedChannels is NOT empty, only those are checked.
            boolean isDefaultOn = app.isEnabled && selectedChannels.isEmpty();

            if (discoveredChannels_metadata.isEmpty()) {
                TextView tv = new TextView(context);
                tv.setText("No categories detected. Received a notification to discover.");
                tv.setTextSize(12);
                tv.setAlpha(0.6f);
                holder.layoutChannels.addView(tv);
            } else {
                for (org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory cat : discoveredChannels_metadata) {
                    android.widget.CheckBox cb = new android.widget.CheckBox(context);
                    String label = (cat.channelName != null && !cat.channelName.equalsIgnoreCase(cat.channelId)) 
                            ? cat.channelName + " (" + cat.channelId + ")" 
                            : cat.channelId;
                    cb.setText(label);
                    cb.setChecked(isDefaultOn || selectedChannels.contains(cat.channelId));
                    cb.setOnCheckedChangeListener((btn, isChecked) -> {
                        Set<String> current;
                        if (isDefaultOn) {
                            current = new HashSet<>();
                            for (org.aspends.nglyphs.util.RootNotificationHelper.NotificationCategory c : discoveredChannels_metadata) {
                                current.add(c.channelId);
                            }
                            if (isChecked) current.add(cat.channelId);
                            else current.remove(cat.channelId);
                        } else {
                            current = new HashSet<>(prefs.getStringSet("essential_channels_" + app.packageName, new HashSet<>()));
                            if (isChecked) {
                                current.add(cat.channelId);
                                if (!app.isEnabled) {
                                    app.isEnabled = true;
                                    enabledPackages.add(app.packageName);
                                    prefs.edit().putStringSet("essential_apps", enabledPackages).apply();
                                    notifyItemChanged(position);
                                }
                            } else {
                                current.remove(cat.channelId);
                            }
                        }
                        prefs.edit().putStringSet("essential_channels_" + app.packageName, current).apply();
                        context.sendBroadcast(new android.content.Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL"));
                    });
                    holder.layoutChannels.addView(cb);
                }
            }
        } else {
            holder.layoutChannels.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return filteredApps.size();
    }

    @Override
    public Filter getFilter() {
        return new AppFilter();
    }

    private class AppFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            return new FilterResults(); // filtering is handled by publishResults -> setCurrentQuery
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            setCurrentQuery(constraint);
        }
    }

    public static class AppViewHolder extends RecyclerView.ViewHolder {

        public TextView textName;
        public ImageView imageIcon;
        public MaterialSwitch switchEnabled;
        public ImageView iconSettings;
        public View layoutAppBody;
        public ViewGroup layoutChannels;

        public AppViewHolder(View itemView) {
            super(itemView);
            textName = itemView.findViewById(R.id.textAppName);
            imageIcon = itemView.findViewById(R.id.imageAppIcon);
            switchEnabled = itemView.findViewById(R.id.switchAppEnabled);
            iconSettings = itemView.findViewById(R.id.iconSettings);
            layoutAppBody = itemView.findViewById(R.id.layoutAppBody);
            layoutChannels = itemView.findViewById(R.id.layoutChannels);
        }
    }
}
