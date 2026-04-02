package org.aspends.nglyphs.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.aspends.nglyphs.services.GlyphNotificationListener;
import org.aspends.nglyphs.services.FlipToGlyphService;

/**
 * Universal receiver for device state changes (Screen On/Off, Unlock).
 * Declared in Manifest to ensure reliable operation even if services are restarting.
 */
public class DeviceStateReceiver extends BroadcastReceiver {
    private static final String TAG = "DeviceStateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received action: " + action);

        // Forward the action to our core services to ensure they refresh their state.
        // This is especially important for GlyphNotificationListener to clear essential lights.
        
        // 1. Notify Notification Listener (if running)
        if (GlyphNotificationListener.instance != null) {
            Intent listenerIntent = new Intent(action);
            listenerIntent.setPackage(context.getPackageName());
            context.sendBroadcast(listenerIntent);
        }

        // 2. Refresh internal state for FlipToGlyph and others if needed
        // (Existing services already listen for these, but manifest-declared 
        // receiver provides extra reliability and wakes the process if needed)
    }
}
