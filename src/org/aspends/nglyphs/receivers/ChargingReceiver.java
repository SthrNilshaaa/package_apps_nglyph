package org.aspends.nglyphs.receivers;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.BatteryGlyphService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

/**
 * Listens to power connected/disconnected states and battery percentage
 * changes,
 * delegating the events back to the active BatteryMonitorService controller.
 */
public class ChargingReceiver extends BroadcastReceiver {

    public interface BatteryListener {
        void onBatteryChanged(int level);

        void onPowerConnected();

        void onPowerDisconnected();
    }

    private final BatteryListener listener;

    public ChargingReceiver(BatteryListener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null || listener == null)
            return;

        switch (intent.getAction()) {
            case Intent.ACTION_POWER_CONNECTED:
                listener.onPowerConnected();
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                listener.onPowerDisconnected();
                break;
            case Intent.ACTION_BATTERY_CHANGED:
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level != -1 && scale != -1) {
                    int batteryPct = (int) ((level / (float) scale) * 100);
                    listener.onBatteryChanged(batteryPct);
                }
                break;
        }
    }
}
