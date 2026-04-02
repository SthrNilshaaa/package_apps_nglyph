package org.aspends.nglyphs.util;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.adapters.AppInfoAdapter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppListCache {
    public static List<AppInfoAdapter.AppInfo> cachedApps = null;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void loadAsync(Context context) {
        if (cachedApps != null)
            return;
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfoAdapter.AppInfo> loadedApps = new ArrayList<>();

            for (ApplicationInfo packageInfo : packages) {
                if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
                    AppInfoAdapter.AppInfo info = new AppInfoAdapter.AppInfo();
                    info.name = packageInfo.loadLabel(pm).toString();
                    info.packageName = packageInfo.packageName;
                    info.icon = null;
                    info.isSystemApp = (packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    loadedApps.add(info);
                }
            }

            Collections.sort(loadedApps, (a1, a2) -> a1.name.toLowerCase().compareTo(a2.name.toLowerCase()));
            cachedApps = loadedApps;
        });
    }
}
