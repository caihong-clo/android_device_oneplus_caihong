/*
 * Copyright (C) 2018-2024 crDroid Android Project
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

package org.lineageos.device.settings;

public class Constants {

    /* Debug flag */
    public static final boolean DEBUG = true;

    /* Bypass Charging */
    public static final String NODE_BYPASS_CHARGING = "/sys/class/oplus_chg/battery/mmi_charging_enable";
    public static final String KEY_BYPASS_CHARGING = "bypass_charging";
    public static final String KEY_BYPASS_CHARGING_TARGET = "bypass_charging_target";
    public static final String KEY_BYPASS_CHARGING_APPS = "bypass_charging_apps";

    public static final int BYPASS_OFF = 0;
    public static final int BYPASS_WAITING = 1;
    public static final int BYPASS_ON = 2;

    public static final int BYPASS_TARGET_MIN = 1;
    public static final int BYPASS_TARGET_MAX = 99;
    public static final int BYPASS_TARGET_DEFAULT = BYPASS_TARGET_MIN;

    /* Fast charging current cap */
    public static final String NODE_FAST_CHARGING = "/sys/class/oplus_chg/battery/cool_down";
    public static final String KEY_FAST_CHARGING = "fast_charging";
    public static final String KEY_NIGHT_CHARGING = "night_charging";

    /** cool_down steps on cp_curr_table (battery mA), DT vooc_curr_table_type =
     *  VOOC_CP_CURR_TABLE. 0 unvotes USER_VOTER entirely: the 67 W charger class. */
    public static final String COOL_DOWN_UNLIMITED = "0";
    public static final String COOL_DOWN_NIGHT = "1";      // 2000 mA
    public static final String COOL_DOWN_STANDARD = "5";   // 3400 mA

    /** Mirrored to Settings.System for the SystemUI charging HUD, which must not
     *  read oplus_chg itself (platform_app is denied search on that dir). */
    public static final String SETTINGS_CHARGE_HUD_MODE = "device_settings_charge_hud_mode";
    public static final String SETTINGS_CHARGE_BOOST_AVAILABLE =
            "device_settings_charge_boost_available";

    public static final int HUD_MODE_UNLIMITED = 0;
    public static final int HUD_MODE_STANDARD = 1;
    public static final int HUD_MODE_NIGHT = 2;

    /** Sent by SystemUI on long-press inside the charging ring. */
    public static final String ACTION_BOOST_CHARGING =
            "org.lineageos.device.settings.action.BOOST_CHARGING";

    /* Battery health */
    public static final String NODE_SOH_UI = "/sys/class/oplus_chg/battery/ui_soh";
    public static final String NODE_SOH_BATTERY = "/sys/class/oplus_chg/battery/battery_soh";
    public static final String NODE_CHARGE_FULL = "/sys/class/power_supply/battery/charge_full";
    public static final String NODE_CHARGE_FULL_DESIGN =
            "/sys/class/power_supply/battery/charge_full_design";
}
