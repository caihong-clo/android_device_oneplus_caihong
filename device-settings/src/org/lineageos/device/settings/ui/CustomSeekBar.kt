/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.android.axion.compose.preferences.SliderPreference
import kotlin.math.roundToInt

/**
 * Integer slider built on the kit [SliderPreference], which only takes floats.
 *
 * The value is committed through [onValueChange] when the drag ends, or on every
 * step when [continuousUpdates] is set. A non-null [defaultValue] adds the kit
 * long-press reset button.
 */
@Composable
internal fun CustomSeekBar(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int = 0,
    max: Int = 100,
    modifier: Modifier = Modifier,
    summary: String? = null,
    interval: Int = 1,
    defaultValue: Int? = null,
    enabled: Boolean = true,
    continuousUpdates: Boolean = false,
    formatValue: ((Int) -> String)? = null,
    customIcon: @Composable (() -> Unit)? = null,
) {
    val upper = max.coerceAtLeast(min)
    val step = interval.coerceAtLeast(1)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    var current by remember { mutableIntStateOf(value.coerceIn(min, upper)) }

    LaunchedEffect(value, min, upper) {
        current = value.coerceIn(min, upper)
    }

    SliderPreference(
        title = title,
        summary = summary.orEmpty(),
        value = current.toFloat(),
        onValueChange = { raw ->
            val stepped = (min + ((raw - min) / step).roundToInt() * step).coerceIn(min, upper)
            if (stepped != current) {
                current = stepped
                if (continuousUpdates) latestOnValueChange(stepped)
            }
        },
        onValueChangeFinished = {
            if (!continuousUpdates && current != latestValue) {
                latestOnValueChange(current)
            }
        },
        valueRange = min.toFloat()..upper.toFloat(),
        displayValue = formatValue?.invoke(current) ?: current.toString(),
        modifier = modifier,
        enabled = enabled,
        onReset = defaultValue?.let { resetValue ->
            {
                val target = resetValue.coerceIn(min, upper)
                current = target
                latestOnValueChange(target)
            }
        },
    )
}
