/*
 * Copyright (C) 2018-2024 crDroid Android Project
 * Copyright (C) 2025 AlphaDroid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.device.settings.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.axion.compose.preferences.BasePreference
import com.android.axion.compose.preferences.ClickablePreference
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.SwitchPreference
import org.lineageos.device.settings.R
import org.lineageos.device.settings.battery.BatteryHealth
import org.lineageos.device.settings.fastcharge.FastChargeController
import org.lineageos.device.settings.ui.OnResume
import org.lineageos.device.settings.ui.PrefIcon
import org.lineageos.device.settings.ui.SettingsScroll

private const val TAG = "DeviceSettings"

@Composable
fun MainScreen(
    contentPadding: PaddingValues,
    onOpenBypass: () -> Unit,
) {
    val context = LocalContext.current
    val fastCharge = remember { FastChargeController.getInstance(context) }
    val fastSupported = fastCharge.isSupported

    var fastOn by remember { mutableStateOf(fastCharge.isFastChargingEnabled) }
    var nightOn by remember { mutableStateOf(fastCharge.isNightModeEnabled) }
    var health by remember { mutableIntStateOf(BatteryHealth.read()) }

    OnResume {
        if (fastSupported) {
            fastOn = fastCharge.isFastChargingEnabled
            nightOn = fastCharge.isNightModeEnabled
        }
        health = BatteryHealth.read()
    }

    SettingsScroll(contentPadding) {
        PreferenceGroup(title = stringResource(R.string.battery_category_title)) {
            item {
                ClickablePreference(
                    title = stringResource(R.string.bypass_charging_title),
                    summary = stringResource(R.string.bypass_charging_summary),
                    customIcon = { PrefIcon(R.drawable.ic_bypass_charging) },
                    onClick = onOpenBypass,
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.fast_charging_title),
                    summary = stringResource(R.string.fast_charging_summary),
                    checked = fastOn,
                    enabled = fastSupported,
                    customIcon = { PrefIcon(R.drawable.ic_fast_charging) },
                    onCheckedChange = { enable ->
                        fastCharge.setFastChargingEnabled(enable)
                        fastOn = enable
                        Log.i(TAG, "Fast charging " + if (enable) "enabled" else "disabled")
                    },
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.night_charging_title),
                    summary = stringResource(R.string.night_charging_summary),
                    checked = nightOn,
                    enabled = fastSupported && !fastOn,
                    customIcon = { PrefIcon(R.drawable.ic_night_charging) },
                    onCheckedChange = { enable ->
                        fastCharge.setNightModeEnabled(enable)
                        nightOn = enable
                        Log.i(TAG, "Night charging " + if (enable) "enabled" else "disabled")
                    },
                )
            }
            item {
                BasePreference(
                    title = stringResource(R.string.battery_health_title),
                    summary = if (health < 0) {
                        stringResource(R.string.battery_health_unknown)
                    } else {
                        stringResource(R.string.battery_health_summary, health)
                    },
                    customIcon = { PrefIcon(R.drawable.ic_battery_health) },
                )
            }
        }
    }
}
