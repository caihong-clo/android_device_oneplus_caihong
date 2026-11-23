/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.fastcharge;

/**
 * Stock charging limits by case temperature: the global policy of the ColorOS
 * sys_thermal_control_config.xml, on horae's thermal_status_model gears. Stock applies them
 * while the screen is on; its screen off policy has no charging limit.
 */
final class ChargeGears {
    /** Gear g starts at ENTER_C[g] C and ends below LEAVE_C[g] C (horae temp_threshold). */
    private static final int[] ENTER_C =
            {27, 29, 31, 33, 35, 37, 39, 40, 41, 43, 45, 47, 49, 51, 53, 55, 57, 59, 61, 63};
    private static final int[] LEAVE_C =
            {25, 27, 29, 31, 33, 35, 37, 39, 40, 41, 43, 45, 47, 49, 51, 53, 55, 57, 59, 61};

    /** cool_down level of each gear, 0 for no limit. Lower levels charge slower. */
    private static final int[] COOL_DOWN =
            {0, 0, 0, 0, 13, 12, 11, 10, 8, 7, 6, 6, 5, 3, 3, 2, 2, 2, 2, 2};

    static final int NO_GEAR = -1;

    private ChargeGears() {}

    /** The gear for a case temperature in millidegrees, moving from {@code gear}. */
    static int next(int gear, int milliC) {
        int g = gear;
        while (g + 1 < ENTER_C.length && milliC >= ENTER_C[g + 1] * 1000) g++;
        while (g >= 0 && milliC < LEAVE_C[g] * 1000) g--;
        return g;
    }

    /** The cool_down level stock applies at {@code gear}, 0 for none. */
    static int coolDown(int gear) {
        return gear < 0 ? 0 : COOL_DOWN[Math.min(gear, COOL_DOWN.length - 1)];
    }

    /** The stricter of two cool_down levels, where 0 means no limit. */
    static int stricter(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }
}
