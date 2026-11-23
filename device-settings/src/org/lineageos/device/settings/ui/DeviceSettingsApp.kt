/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.lineageos.device.settings.R
import org.lineageos.device.settings.ui.screens.BypassChargingScreen
import org.lineageos.device.settings.ui.screens.MainScreen

enum class DeviceSettingsDest(val route: String) {
    Main("main"),
    Bypass("bypass"),
}

@Composable
fun DeviceSettingsApp(
    start: DeviceSettingsDest,
    onFinish: () -> Unit,
) {
    val navController = rememberNavController()
    val back: () -> Unit = {
        if (!navController.popBackStack()) onFinish()
    }
    BackHandler(onBack = back)
    NavHost(
        navController = navController,
        startDestination = start.route,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(DeviceSettingsDest.Main.route) {
            DeviceSettingsScaffold(title = stringResource(R.string.device_title), onBack = back) {
                MainScreen(
                    contentPadding = it,
                    onOpenBypass = { navController.navigate(DeviceSettingsDest.Bypass.route) },
                )
            }
        }
        composable(DeviceSettingsDest.Bypass.route) {
            DeviceSettingsScaffold(
                title = stringResource(R.string.bypass_charging_title),
                onBack = back,
            ) {
                BypassChargingScreen(contentPadding = it)
            }
        }
    }
}
