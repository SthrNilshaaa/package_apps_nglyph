package org.aspends.nglyphs.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RootNotificationHelper {

    private static final String TAG = "RootNotificationHelper";
    private static final String PREF_DISCOVERED = "discovered_channels_cache";
    private static final Map<String, List<NotificationCategory>> fullCategoryCache = new ConcurrentHashMap<>();

    public static class NotificationCategory {
        public String packageName;
        public String channelId;
        public String channelName;
        public int importance;
        public boolean blocked;

        public NotificationCategory(String pkg, String id, String name, int importance) {
            this.packageName = pkg;
            this.channelId = id;
            this.channelName = name;
            this.importance = importance;
            this.blocked = (importance == 0); // IMPORTANCE_NONE
        }
    }

    // --- Persistence & Discovery ---

    /**
     * Gets discovered channels for a package from cache (memory -> disk).
     */
    public static Set<String> getChannelsForPackage(Context context, String packageName) {
        Set<String> results = new java.util.TreeSet<>();
        
        // Check persistent disk cache
        SharedPreferences prefs = context.getSharedPreferences(PREF_DISCOVERED, Context.MODE_PRIVATE);
        String saved = prefs.getString(packageName, "");
        if (!saved.isEmpty()) {
            results.addAll(Arrays.asList(saved.split(",")));
        }

        return results;
    }

    /**
     * Adds an ad-hoc discovered channel (e.g. from active notification) to the persistent cache.
     */
    public static void persistDiscovery(Context context, String packageName, String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_DISCOVERED, Context.MODE_PRIVATE);
        String savedStr = prefs.getString(packageName, "");
        Set<String> existing = new HashSet<>();
        if (!savedStr.isEmpty()) {
            existing.addAll(Arrays.asList(savedStr.split(",")));
        }
        
        if (existing.add(channelId)) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String id : existing) {
                if (id.isEmpty()) continue;
                if (!first) sb.append(",");
                sb.append(id);
                first = false;
            }
            prefs.edit().putString(packageName, sb.toString()).apply();
            Log.d(TAG, "Persisted new channel discovery: " + packageName + "/" + channelId);
        }
    }
}
