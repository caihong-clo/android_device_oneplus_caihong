/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.fastcharge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

/**
 * Lowers the charging current as the case heats up, like the stock thermal control: while
 * charging with the screen on, the horae case temperature picks a gear and its cool_down
 * level caps the user's charging setting.
 */
public final class ChargeThermalLimiter {
    private static final String TAG = "ChargeThermalLimiter";
    private static final String THERMAL = "/sys/class/thermal";
    private static final long POLL_MS = 10_000;

    private static ChargeThermalLimiter sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<String> mZones = new ArrayList<>();
    private final Runnable mPoll = this::poll;

    private boolean mCharging;
    private boolean mScreenOn = true;
    private int mGear = ChargeGears.NO_GEAR;

    public static synchronized ChargeThermalLimiter getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ChargeThermalLimiter(context.getApplicationContext());
        }
        return sInstance;
    }

    private ChargeThermalLimiter(Context context) {
        mContext = context;
        File[] zones = new File(THERMAL).listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zones != null) {
            for (File zone : zones) {
                String type = FileUtils.readLineTrimmed(zone.getPath() + "/type");
                for (String shell : Constants.CASE_TEMP_ZONES) {
                    if (shell.equals(type)) mZones.add(zone.getPath() + "/temp");
                }
            }
        }
        if (mZones.isEmpty()) Log.w(TAG, "No horae case temperature zones");
    }

    /** Reads the current charging and screen state; called once the service runs. */
    public void init() {
        Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        mCharging = battery != null
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        mScreenOn = pm == null || pm.isInteractive();
        update();
    }

    public void setCharging(boolean charging) {
        mCharging = charging;
        update();
    }

    public void setScreenOn(boolean on) {
        mScreenOn = on;
        update();
    }

    /** The cool_down level the case temperature allows right now, 0 for no limit. */
    public synchronized int coolDown() {
        return ChargeGears.coolDown(mGear);
    }

    private void update() {
        mHandler.removeCallbacks(mPoll);
        if (mCharging && mScreenOn && !mZones.isEmpty()) {
            poll();
        } else {
            setGear(ChargeGears.NO_GEAR);
        }
    }

    private void poll() {
        int temp = caseTemp();
        if (temp != Integer.MIN_VALUE) {
            int gear;
            synchronized (this) {
                gear = ChargeGears.next(mGear, temp);
            }
            setGear(gear);
        }
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    /** The hottest case temperature horae reports, in millidegrees. */
    private int caseTemp() {
        int max = Integer.MIN_VALUE;
        for (String zone : mZones) {
            String value = FileUtils.readLineTrimmed(zone);
            try {
                if (value != null) max = Math.max(max, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    private void setGear(int gear) {
        int before;
        synchronized (this) {
            if (gear == mGear) return;
            before = ChargeGears.coolDown(mGear);
            mGear = gear;
        }
        int after = ChargeGears.coolDown(gear);
        if (after != before) {
            Log.i(TAG, "Case temperature gear " + gear + ", charging cool_down cap " + after);
            FastChargeController.getInstance(mContext).reapply();
        }
    }
}
