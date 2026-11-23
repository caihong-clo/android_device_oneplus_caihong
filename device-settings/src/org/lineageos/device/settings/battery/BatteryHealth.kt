/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.battery

import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import org.lineageos.device.settings.Constants

/** Battery state of health as the charger reports it. */
object BatteryHealth {
    /** Health in percent, or -1 when the charger reports none. */
    fun read(): Int {
        parsePct(firstLine(Constants.NODE_SOH_UI))?.let { return it }
        parsePct(firstLine(Constants.NODE_SOH_BATTERY))?.let { return it }
        val full = parseLong(firstLine(Constants.NODE_CHARGE_FULL))
        val design = parseLong(firstLine(Constants.NODE_CHARGE_FULL_DESIGN))
        if (full != null && design != null && design > 0L) {
            return ((100L * full) / design).toInt().coerceIn(0, 100)
        }
        return -1
    }

    private fun parsePct(raw: String?): Int? =
        raw?.trim()?.toFloatOrNull()?.toInt()?.takeIf { it in 1..100 }

    private fun parseLong(raw: String?): Long? = raw?.trim()?.toLongOrNull()

    private fun firstLine(path: String): String? = try {
        BufferedReader(FileReader(path)).use { it.readLine() }
    } catch (_: IOException) {
        null
    }
}
