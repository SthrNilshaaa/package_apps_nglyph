package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class MasterTileService extends TileService {

    /**
     * Called when the Quick Settings tile becomes visible to the user.
     * Updates the tile's visual state based on the current master allowance
     * setting.
     */
    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTile();
    }

    /**
     * Called when the user clicks the Quick Settings tile.
     * Toggles the master allowance state (on/off), updates the LEDs if disabled,
     * syncs this tile's UI, and requests an update for the GlyphTileService.
     */
    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        boolean currentState = prefs.getBoolean("master_allow", false);
        boolean newState = !currentState;

        prefs.edit().putBoolean("master_allow", newState).apply();
        syncTile();
        TileService.requestListeningState(this,
                new ComponentName(this, GlyphTileService.class));
    }

    /**
     * Synchronizes the tile's visual state (Active/Inactive) with the
     * underlying master allowance preference.
     */
    private void syncTile() {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        boolean isAllowed = prefs.getBoolean("master_allow", false);
        Tile tile = getQsTile();

        if (tile != null) {
            tile.setState(isAllowed ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_master_label));
            tile.updateTile();
        }
    }

    /**
     * Directly updates the brightness of all glyph LEDs.
     *
     * @param val The brightness level to apply.
     */
    private void updateHardware(int val) {
        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
            GlyphManagerV2.getInstance().setBrightness(g, val);
        }
        if (val == 0) {
            GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.SINGLE_LED, 0);
        }
    }
}