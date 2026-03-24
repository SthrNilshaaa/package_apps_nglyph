package org.aspends.nglyphs.services;

import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class GlyphTileService extends TileService {

    /**
     * Called when the Quick Settings tile becomes visible to the user.
     * Updates the tile's visual state based on whether it is allowed to operate.
     */
    @Override
    public void onStartListening() {
        super.onStartListening();
        org.aspends.nglyphs.core.AnimationManager.init(this);
        org.aspends.nglyphs.core.GlyphManagerV2.getInstance().init(this);
        syncTile();
    }

    /**
     * Called when the user clicks the Quick Settings tile.
     * Toggles the glyphs on or off if the master setting allows it.
     */
    @Override
    public void onClick() {
        super.onClick();
        org.aspends.nglyphs.core.AnimationManager.init(this);
        org.aspends.nglyphs.core.GlyphManagerV2.getInstance().init(this);
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        if (!prefs.getBoolean("master_allow", false)) {
            syncTile();
            return;
        }

        boolean isLightOn = prefs.getBoolean("is_light_on", false);
        boolean newState = !isLightOn;

        prefs.edit().putBoolean("is_light_on", newState).apply();
        org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();
        syncTile();
    }

    /**
     * Synchronizes the tile's visual state with the master allowance preference.
     * Makes the tile UNAVAILABLE if master is off, otherwise sets it based on
     * `is_light_on`.
     */
    private void syncTile() {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        boolean isMasterAllowed = prefs.getBoolean("master_allow", false);
        boolean isLightOn = prefs.getBoolean("is_light_on", false);
        Tile tile = getQsTile();

        if (tile != null) {
            tile.setLabel(getString(R.string.tile_light_label));

            if (!isMasterAllowed) {
                tile.setState(Tile.STATE_UNAVAILABLE);
                prefs.edit().putBoolean("is_light_on", false).apply();
            } else {
                tile.setState(isLightOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            }

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