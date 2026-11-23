/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Central service for DeviceSettings.
 * - Always running after boot
 * - Handles power connect/disconnect events
 * - Initializes and manages other services/controllers
 */

package org.lineageos.device.settings;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import org.lineageos.device.settings.bypasschrg.BypassChargingController;
import org.lineageos.device.settings.bypasschrg.BypassChargingManager;
import org.lineageos.device.settings.fastcharge.FastChargeController;

public class DeviceSettingsService extends Service {
    private static final String TAG = "DeviceSettingsService";

    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Constants.DEBUG) Log.i(TAG, "Service created");

        // Initialize all subsystems
        initializeSubsystems();

        // Register receivers for power events
        registerReceivers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Constants.DEBUG) Log.i(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (Constants.DEBUG) Log.i(TAG, "Service destroyed");
        unregisterReceivers();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== Initialization =====

    private void initializeSubsystems() {
        initializeBypassCharging();
        initializeFastCharging();
    }

    private void initializeBypassCharging() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing BypassCharging");
        try {
            BypassChargingController controller = BypassChargingController.getInstance(this);
            // The kernel charging vote does not survive a reboot (and a stale vote can
            // survive an app crash), so sync the hardware with the persisted state first
            controller.reconcileHardwareState();
            BypassChargingManager.notifyStateChanged(this, controller.getState());
            if (Constants.DEBUG) Log.i(TAG, "BypassCharging initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BypassCharging", e);
        }
    }

    private void initializeFastCharging() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing FastCharging");
        try {
            // USER_VOTER does not survive a reboot, so the persisted cap has to be
            // written again here
            FastChargeController.getInstance(this).restore();
            if (Constants.DEBUG) Log.i(TAG, "FastCharging initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize FastCharging", e);
        }
    }

    // ===== Receivers =====

    private void registerReceivers() {
        if (mReceiver != null) return;

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                final String action = intent.getAction();
                if (Constants.DEBUG) Log.i(TAG, "Received: " + action);

                switch (action) {
                    case Intent.ACTION_POWER_CONNECTED:
                        handlePowerConnected();
                        break;
                    case Intent.ACTION_POWER_DISCONNECTED:
                        handlePowerDisconnected();
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

        try {
            registerReceiver(mReceiver, filter);
            if (Constants.DEBUG) Log.i(TAG, "Receivers registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receivers", e);
            mReceiver = null;
        }
    }

    private void unregisterReceivers() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
            mReceiver = null;
        }
    }

    // ===== Power Event Handlers =====

    private void handlePowerConnected() {
        if (Constants.DEBUG) Log.i(TAG, "Power CONNECTED");

        // Notify BypassChargingController
        try {
            BypassChargingController.getInstance(this).handlePowerConnected();
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle power connected", e);
        }

        try {
            FastChargeController.getInstance(this).restore();
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore fast charging on connect", e);
        }
    }

    private void handlePowerDisconnected() {
        if (Constants.DEBUG) Log.i(TAG, "Power DISCONNECTED");

        // Re-enable charging in the kernel: the vote survives replug, so a stale
        // "charging disabled" would silently prevent charging on the next connect
        // if this app were not running then. Global prefs are kept for next connect.
        try {
            BypassChargingController.getInstance(this).handlePowerDisconnected();
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle power disconnected", e);
        }

        // USER_VOTER survives unplug, so clear the session boost and re-apply the
        // persisted cap now for the next plug
        try {
            FastChargeController.getInstance(this).restore();
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore fast charging on disconnect", e);
        }
    }
}