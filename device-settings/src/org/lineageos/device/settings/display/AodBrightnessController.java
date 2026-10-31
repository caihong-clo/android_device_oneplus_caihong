/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

/**
 * AOD brightness on dodge (AA569). The kernel LP1 hook latches DBV from
 * {@link Constants#NODE_AOD_LIGHT_MODE} (0 = high, 1 = low) before enter_idle.
 * Auto (default) writes nothing and leaves the node to the sensors HAL, which
 * sets it from lux_aod like stock ColorOS. High / low pin the level with
 * "force". Do not poke write_panel_reg from here: DSI clocks in idle flash
 * the panel and then idle re-dims it.
 */
public class AodBrightnessController {
    private static final String TAG = "AodBrightnessController";
    /** Boolean pref of the former high brightness switch, migrated on first use. */
    private static final String KEY_AOD_HIGH_BRIGHTNESS_LEGACY = "aod_high_brightness";

    private static AodBrightnessController sInstance;

    private final SharedPreferences mSharedPrefs;

    private AodBrightnessController(Context context) {
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        migrateLegacyPref();
    }

    public static synchronized AodBrightnessController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AodBrightnessController(context);
        }
        return sInstance;
    }

    /** Old switch on maps to high; off (the old low default) becomes auto. */
    private void migrateLegacyPref() {
        if (!mSharedPrefs.contains(KEY_AOD_HIGH_BRIGHTNESS_LEGACY)) {
            return;
        }
        final SharedPreferences.Editor editor = mSharedPrefs.edit()
                .remove(KEY_AOD_HIGH_BRIGHTNESS_LEGACY);
        if (mSharedPrefs.getBoolean(KEY_AOD_HIGH_BRIGHTNESS_LEGACY, false)
                && !mSharedPrefs.contains(Constants.KEY_AOD_BRIGHTNESS_MODE)) {
            editor.putString(Constants.KEY_AOD_BRIGHTNESS_MODE, Constants.AOD_BRIGHTNESS_HIGH);
        }
        editor.commit();
    }

    /** Current mode: auto, high or low. */
    public String getMode() {
        return sanitize(mSharedPrefs.getString(Constants.KEY_AOD_BRIGHTNESS_MODE,
                Constants.AOD_BRIGHTNESS_DEFAULT));
    }

    public boolean setMode(String mode) {
        mode = sanitize(mode);
        if (!FileUtils.isFileWritable(Constants.NODE_AOD_LIGHT_MODE)) {
            Log.w(TAG, "Node is not writable: " + Constants.NODE_AOD_LIGHT_MODE);
            // Persist anyway so restoreAodBrightness() applies it once the node is ready
            persist(mode);
            return false;
        }
        if (Constants.AOD_BRIGHTNESS_AUTO.equals(mode)) {
            if (Constants.AOD_BRIGHTNESS_AUTO.equals(getMode())) {
                return true;
            }
            // Release the force lock taken earlier this boot; the next lux_aod
            // write from the sensors HAL sets the level again.
            if (!write("0 auto")) {
                return false;
            }
        } else if (!write(forcedValue(mode))) {
            return false;
        }
        persist(mode);
        return true;
    }

    /**
     * Re-assert a forced level at boot and on screen-off, before LP1, so the
     * HAL's lux_aod write cannot win the race. Auto writes nothing: the kernel
     * boots unlocked and the lux_aod write that follows AOD entry must stand.
     */
    public void restoreAodBrightness() {
        final String mode = getMode();
        if (Constants.AOD_BRIGHTNESS_AUTO.equals(mode)) {
            return;
        }
        if (!FileUtils.isFileWritable(Constants.NODE_AOD_LIGHT_MODE)) {
            Log.w(TAG, "Cannot restore AOD brightness: node not writable");
            return;
        }
        if (write(forcedValue(mode))) {
            Log.i(TAG, "Restored AOD brightness: " + mode);
        }
    }

    private static String sanitize(String mode) {
        if (Constants.AOD_BRIGHTNESS_HIGH.equals(mode)
                || Constants.AOD_BRIGHTNESS_LOW.equals(mode)
                || Constants.AOD_BRIGHTNESS_AUTO.equals(mode)) {
            return mode;
        }
        return Constants.AOD_BRIGHTNESS_DEFAULT;
    }

    private static String forcedValue(String mode) {
        // "force" tells OFP to ignore lux_aod overwrites from the sensors HAL.
        return Constants.AOD_BRIGHTNESS_HIGH.equals(mode) ? "0 force" : "1 force";
    }

    private void persist(String mode) {
        mSharedPrefs.edit()
                .putString(Constants.KEY_AOD_BRIGHTNESS_MODE, mode)
                .commit();
    }

    private boolean write(String nodeValue) {
        if (!FileUtils.writeLine(Constants.NODE_AOD_LIGHT_MODE, nodeValue)) {
            Log.e(TAG, "Failed to write AOD light mode " + nodeValue);
            return false;
        }
        Log.i(TAG, "AOD light mode set to " + nodeValue);
        return true;
    }
}
