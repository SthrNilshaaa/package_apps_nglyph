package org.aspends.nglyphs.util;

import org.aspends.nglyphs.R;

import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Set;

public class SleepGuard {

    public static boolean isBlocked(SharedPreferences prefs) {
        if (!prefs.getBoolean("sleep_mode_enabled", false)) return false;

        try {
            Set<String> activeDays = prefs.getStringSet("sleep_days", null);
            if (activeDays != null && !activeDays.isEmpty()) {
                int calDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
                int isoDay = (calDay == Calendar.SUNDAY) ? 7 : calDay - 1;
                if (!activeDays.contains(String.valueOf(isoDay))) return false;
            }

            String[] s = prefs.getString("sleep_start", "23:00").split(":");
            String[] e = prefs.getString("sleep_end", "07:00").split(":");
            int startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
            int endMin = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);
            Calendar now = Calendar.getInstance();
            int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

            return startMin < endMin
                    ? (nowMin >= startMin && nowMin <= endMin)
                    : (nowMin >= startMin || nowMin <= endMin);

        } catch (Exception e) {
            return false;
        }
    }
}
