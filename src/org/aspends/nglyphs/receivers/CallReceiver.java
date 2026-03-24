package org.aspends.nglyphs.receivers;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.FlipToGlyphService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;

public class CallReceiver extends BroadcastReceiver {

    /**
     * Called when the BroadcastReceiver receives an Intent broadcast.
     * Listens for changes in the phone state (ringing, off-hook, idle) 
     * and triggers the appropriate glyph effects via FlipToGlyphService 
     * if the master toggle is enabled.
     *
     * @param context The Context in which the receiver is running.
     * @param intent  The Intent being received.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(context.getString(R.string.pref_file), Context.MODE_PRIVATE);
        if (!prefs.getBoolean("master_allow", false)) return;

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            Intent i = new Intent(context, FlipToGlyphService.class);
            i.setAction(FlipToGlyphService.ACTION_CALL_GLYPH);
            context.startService(i);
        }
        else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) ||
                TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            Intent i = new Intent(context, FlipToGlyphService.class);
            i.setAction(FlipToGlyphService.ACTION_STOP_CALL_GLYPH);
            context.startService(i);
        }
    }
}