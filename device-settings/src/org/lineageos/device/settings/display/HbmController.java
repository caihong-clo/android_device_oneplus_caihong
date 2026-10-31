/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

public class HbmController {
    private static final String TAG = "HbmController";
    private static HbmController sInstance;
    private final Context mContext;
    private final SharedPreferences mSharedPrefs;

    private static final float MAX = 120.0f;
    // HBM (hbm_max, the sunlight boost) needs the panel at one stable rate. Dynamic
    // RR lets SF timing-switch 60<->120, and each switch reprograms the panel drive
    // registers + re-latches, dropping it out of HBM (visible flash); auto would also
    // let the DDIC self-refresh down-clock beneath the mode. So pin BOTH halves - SF
    // MIN=PEAK=120 (no switches) and adfr_min_fps=120 (no down-clock). The system
    // settings own MIN/PEAK, so back them up once per pin (a second backup would
    // save the pinned 120, which is what used to weld auto->120 when pins
    // overlapped) and put them back on release exactly as they were, unset
    // included; adfr_min_fps goes back to 0 (LTPO).
    private static final float HBM_FRAMERATE = MAX;
    private static final String KEY_BACKUP_AUTO_BRIGHTNESS = "hbm_backup_auto_brightness";
    // Marker plus raw MIN/PEAK strings; a missing string means the setting was unset
    private static final String KEY_BACKUP_REFRESH_RATE = "hbm_backup_refresh_rate";
    private static final String KEY_BACKUP_MIN_REFRESH_RATE = "hbm_backup_min_rate";
    private static final String KEY_BACKUP_PEAK_REFRESH_RATE = "hbm_backup_peak_rate";

    private HbmController(Context context) {
        mContext = context.getApplicationContext();
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public static synchronized HbmController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HbmController(context);
        }
        return sInstance;
    }

    public boolean isHbmEnabled() {
        // The panel always boots with HBM off while the preference may still say
        // enabled (e.g. reboot with HBM on), so the node is the source of truth
        String value = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        if (value != null) {
            return "1".equals(value);
        }
        return mSharedPrefs.getBoolean(Constants.KEY_HBM, false);
    }

    /**
     * Reconcile our persisted state with the actual node, which is
     * authoritative: hbm_max boots off, so after a reboot our preference/tile
     * may be a stale ON. Call at boot and on screen-on: if the node disagrees
     * with the stored preference, adopt the node value. Whenever the node reads
     * off, a pin still held (e.g. the process died between taking it and the
     * release) is released too. Returns the live state.
     */
    public boolean syncState() {
        boolean nodeState = isHbmEnabled();
        boolean prefState = mSharedPrefs.getBoolean(Constants.KEY_HBM, false);
        if (prefState != nodeState) {
            mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, nodeState).commit();
            Log.i(TAG, "HBM state synced to node: " + nodeState);
        }
        if (!nodeState && (mSharedPrefs.getBoolean(KEY_BACKUP_REFRESH_RATE, false)
                || mSharedPrefs.contains(KEY_BACKUP_AUTO_BRIGHTNESS))) {
            // HBM was released underneath us (e.g. a reboot) while auto-brightness
            // was parked and our RR was pinned to 120Hz. Undo both as
            // disableHbmInternal() would, so the user's refresh rate is restored
            // instead of staying welded at 120.
            restoreAutoBrightness();
            releaseRefreshRate();
        }
        return nodeState;
    }

    public boolean enableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        // Already on (e.g. the switch on top of a live sunlight boost): taking the
        // pin again would back up the parked auto-brightness as "off"
        if ("1".equals(FileUtils.readLineTrimmed(Constants.NODE_HBM))) {
            mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, true).commit();
            return true;
        }

        // Check if PWM is enabled (PWM has priority)
        PwmController pwmController = PwmController.getInstance(mContext);
        if (pwmController.isPwmEnabled()) {
            Log.w(TAG, "Cannot enable HBM while PWM is active");
            return false;
        }

        // Wait out a recent PWM/HBM mode change (two-tap PWM-off → HBM-on)
        PanelModeSettle.awaitIfNeeded("before HBM on");
        if (!enableHbmInternal()) {
            return false;
        }
        PanelModeSettle.mark();
        return true;
    }

    public boolean disableHbm() {
        if (!FileUtils.isFileWritable(Constants.NODE_HBM)) {
            Log.w(TAG, "HBM node is not writable");
            return false;
        }

        if (!disableHbmInternal()) {
            return false;
        }
        // Mark so a following PWM on (even via a separate tile) waits out EXIT.
        PanelModeSettle.mark();
        return true;
    }

    private boolean enableHbmInternal() {
        // 1. Backup and disable auto-brightness
        boolean autoBrightnessEnabled = isAutoBrightnessEnabled();
        mSharedPrefs.edit()
                .putBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, autoBrightnessEnabled)
                .apply();

        if (autoBrightnessEnabled) {
            setAutoBrightness(false);
            Log.i(TAG, "Auto-brightness disabled for HBM");
        }

        // 2. Back up and pin the refresh rate
        pinRefreshRate();

        // 3. Write HBM sysfs node; only persist pref when the node matches
        if (!writeHbmNode(true)) {
            // The kernel refused (e.g. panel not fully on): undo steps 1 and 2 so
            // a failed enable does not leave auto-brightness off and RR pinned.
            restoreAutoBrightness();
            releaseRefreshRate();
            Log.i(TAG, "HBM enable failed; refresh rate restored");
            return false;
        }
        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, true).commit();
        Log.i(TAG, "HBM sysfs node enabled");
        return true;
    }

    private boolean disableHbmInternal() {
        // 1. Disable HBM sysfs node first, so the panel leaves HBM before the
        // refresh rate is allowed to switch again.
        if (!writeHbmNode(false)) {
            return false;
        }

        // 2. Restore auto-brightness if it was enabled before
        restoreAutoBrightness();

        mSharedPrefs.edit().putBoolean(Constants.KEY_HBM, false).commit();

        // 3. Restore the refresh rate pinned by enableHbmInternal()
        releaseRefreshRate();
        Log.i(TAG, "HBM sysfs node disabled; refresh rate restored");
        return true;
    }

    private boolean writeHbmNode(boolean enable) {
        String want = enable ? "1" : "0";
        if (!FileUtils.writeLine(Constants.NODE_HBM, want)) {
            Log.w(TAG, "HBM sysfs write failed (enable=" + enable + ")");
            return false;
        }
        String got = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        if (got == null || !want.equals(got)) {
            Log.w(TAG, "HBM node mismatch after write: want=" + want + " got=" + got);
            return false;
        }
        return true;
    }

    /** Re-enable auto-brightness if HBM parked it, then drop the backup. */
    private void restoreAutoBrightness() {
        if (mSharedPrefs.getBoolean(KEY_BACKUP_AUTO_BRIGHTNESS, false)) {
            setAutoBrightness(true);
            Log.i(TAG, "Auto-brightness restored");
        }
        mSharedPrefs.edit().remove(KEY_BACKUP_AUTO_BRIGHTNESS).commit();
    }

    /**
     * Pin the refresh rate for HBM: adfr_min_fps first (kernel self-refresh floor),
     * then SF MIN=PEAK so SF stops timing-switching. The user's MIN/PEAK are backed
     * up only if no backup is held yet: while one is held the settings already
     * read the pinned 120, and saving them again would make it stick.
     */
    private synchronized void pinRefreshRate() {
        if (!mSharedPrefs.getBoolean(KEY_BACKUP_REFRESH_RATE, false)) {
            String min = getRefreshRateSetting(Settings.System.MIN_REFRESH_RATE);
            String peak = getRefreshRateSetting(Settings.System.PEAK_REFRESH_RATE);
            // putString(key, null) removes the key, which records an unset setting
            mSharedPrefs.edit()
                    .putBoolean(KEY_BACKUP_REFRESH_RATE, true)
                    .putString(KEY_BACKUP_MIN_REFRESH_RATE, min)
                    .putString(KEY_BACKUP_PEAK_REFRESH_RATE, peak)
                    .commit();
            Log.i(TAG, "Refresh rate backed up (MIN: " + min + ", PEAK: " + peak + ")");
        }

        FileUtils.writeLine(Constants.NODE_ADFR_MIN_FPS,
                String.valueOf((int) HBM_FRAMERATE));
        String pinned = Float.toString(HBM_FRAMERATE);
        setRefreshRateSetting(Settings.System.MIN_REFRESH_RATE, pinned);
        setRefreshRateSetting(Settings.System.PEAK_REFRESH_RATE, pinned);
        Log.i(TAG, "HBM: pinned refresh rate to " + HBM_FRAMERATE);
    }

    /**
     * Undo pinRefreshRate(): adfr_min_fps back to 0 (LTPO) and MIN/PEAK back to
     * the backup, then drop it. A setting changed while pinned (e.g. from the
     * display settings) is the user's newer choice and is left alone.
     */
    private synchronized void releaseRefreshRate() {
        FileUtils.writeLine(Constants.NODE_ADFR_MIN_FPS, "0");

        if (!mSharedPrefs.getBoolean(KEY_BACKUP_REFRESH_RATE, false)) {
            return;
        }
        restoreRefreshRateSetting(Settings.System.MIN_REFRESH_RATE,
                mSharedPrefs.getString(KEY_BACKUP_MIN_REFRESH_RATE, null));
        restoreRefreshRateSetting(Settings.System.PEAK_REFRESH_RATE,
                mSharedPrefs.getString(KEY_BACKUP_PEAK_REFRESH_RATE, null));
        mSharedPrefs.edit()
                .remove(KEY_BACKUP_REFRESH_RATE)
                .remove(KEY_BACKUP_MIN_REFRESH_RATE)
                .remove(KEY_BACKUP_PEAK_REFRESH_RATE)
                .commit();
    }

    private void restoreRefreshRateSetting(String name, String backup) {
        float current = Settings.System.getFloatForUser(mContext.getContentResolver(),
                name, -1f, UserHandle.USER_CURRENT);
        if (current != HBM_FRAMERATE) {
            Log.i(TAG, name + " changed while pinned, keeping it");
            return;
        }
        // A null backup writes a null value, which reads back as unset (default)
        setRefreshRateSetting(name, backup);
        Log.i(TAG, name + " restored to " + backup);
    }

    private String getRefreshRateSetting(String name) {
        return Settings.System.getStringForUser(mContext.getContentResolver(),
                name, UserHandle.USER_CURRENT);
    }

    private void setRefreshRateSetting(String name, String value) {
        Settings.System.putStringForUser(mContext.getContentResolver(),
                name, value, UserHandle.USER_CURRENT);
    }

    private boolean isAutoBrightnessEnabled() {
        try {
            int mode = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get auto-brightness state", e);
            return false;
        }
    }

    private void setAutoBrightness(boolean enabled) {
        Settings.System.putIntForUser(
                mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                enabled ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT);
    }
}
