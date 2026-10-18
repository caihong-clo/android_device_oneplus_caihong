/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * OnePlus Pad 2 (caihong) power HAL mode extension.
 * Double tap to wake is the same as hardware/oplus/power.
 * Battery Saver (Mode::LOW_POWER) caps CPU and GPU max frequency, and game
 * loading screens (Mode::GAME_LOADING) get a short CPU boost. Both use perf
 * HAL locks, so they work together with thermal-engine and other boosts.
 */

#include <aidl/android/hardware/power/BnPower.h>
#include <aidl/vendor/oplus/hardware/touch/IOplusTouch.h>

#include <android-base/logging.h>
#include <android/binder_manager.h>

#include <OplusTouchConstants.h>

#include <mutex>

#include "utils.h"

using aidl::android::hardware::power::Mode;
using aidl::vendor::oplus::hardware::touch::IOplusTouch;

namespace {

// Private hint id used to track the Battery Saver perf lock.
constexpr int kLowPowerHintId = 0x00C41001;

// Perf HAL opcodes. Cluster order in the opcode is big, little, prime,
// titanium. On pineapple: little = cpu0-1, big = cpu2-4,
// titanium = cpu5-6, prime = cpu7. Frequency values are in MHz.
constexpr int kMaxFreqBig = 0x40804000;
constexpr int kMaxFreqLittle = 0x40804100;
constexpr int kMaxFreqPrime = 0x40804200;
constexpr int kMaxFreqTitanium = 0x40804300;
// GPU max power level. Higher index means lower frequency; level 6 is
// about 580-630 MHz depending on the chip bin (top level is 870-903 MHz).
constexpr int kGpuMaxPwrlevel = 0x42808000;

int gLowPowerResources[] = {
        kMaxFreqLittle,   1804,  // stock max 2265
        kMaxFreqBig,      2035,  // stock max 3148
        kMaxFreqTitanium, 2035,  // stock max 2956
        kMaxFreqPrime,    2035,  // stock max 3302
        kGpuMaxPwrlevel,  6,
};

// Min frequency opcodes, same cluster order as above.
constexpr int kMinFreqBig = 0x40800000;
constexpr int kMinFreqLittle = 0x40800100;
constexpr int kMinFreqPrime = 0x40800200;
constexpr int kMinFreqTitanium = 0x40800300;

// Game loading screens (GameManager Mode::GAME_LOADING): raise CPU floors so
// level loads finish faster. Capped by kGameLoadingMaxMs in case the
// framework never clears the mode.
constexpr int kGameLoadingMaxMs = 10000;
int gGameLoadingResources[] = {
        kMinFreqLittle,   1574,
        kMinFreqBig,      2035,
        kMinFreqTitanium, 2035,
        kMinFreqPrime,    2035,
};

std::mutex gLowPowerLock;
bool gLowPowerActive = false;
int gGameLoadingHandle = 0;

void setGameLoading(bool enabled) {
    std::lock_guard<std::mutex> lock(gLowPowerLock);
    if (gGameLoadingHandle > 0) {
        release_request(gGameLoadingHandle);
        gGameLoadingHandle = 0;
    }
    // Battery Saver wins over the loading boost.
    if (!enabled || gLowPowerActive) return;

    int handle = interaction_with_handle(
            0, kGameLoadingMaxMs,
            sizeof(gGameLoadingResources) / sizeof(gGameLoadingResources[0]),
            gGameLoadingResources);
    if (handle > 0) gGameLoadingHandle = handle;
}

void setLowPower(bool enabled) {
    std::lock_guard<std::mutex> lock(gLowPowerLock);
    if (enabled == gLowPowerActive) return;

    // Drop any running game-loading boost before capping.
    if (enabled && gGameLoadingHandle > 0) {
        release_request(gGameLoadingHandle);
        gGameLoadingHandle = 0;
    }

    if (enabled) {
        int ret = perform_hint_action(kLowPowerHintId, gLowPowerResources,
                                      sizeof(gLowPowerResources) / sizeof(gLowPowerResources[0]));
        if (ret != 0) {
            LOG(ERROR) << "Battery saver perf lock failed: " << ret;
            return;
        }
    } else {
        undo_hint_action(kLowPowerHintId);
    }
    gLowPowerActive = enabled;
    LOG(INFO) << "Battery saver CPU/GPU cap " << (enabled ? "on" : "off");
}

void setDoubleTapToWake(bool enabled) {
    std::string tmp;
    int contents = 0;

    const std::string instance = std::string() + IOplusTouch::descriptor + "/default";
    std::shared_ptr<IOplusTouch> oplusTouch = IOplusTouch::fromBinder(
            ndk::SpAIBinder(AServiceManager_waitForService(instance.c_str())));
    LOG(INFO) << "Power mode: DOUBLE_TAP_TO_WAKE isDoubleTapEnabled: " << enabled;

    oplusTouch->touchReadNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID,
                                  OplusTouchConstants::DOUBLE_TAP_INDEP_NODE, &tmp);
    contents = std::stoi(tmp, nullptr, 16);

    if (enabled) {
        contents |= OplusTouchConstants::DOUBLE_TAP_GESTURE;
    } else {
        contents &= ~OplusTouchConstants::DOUBLE_TAP_GESTURE;
    }

    int aidl_return = 0;
    oplusTouch->touchWriteNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID,
                                   OplusTouchConstants::DOUBLE_TAP_ENABLE_NODE, "1",
                                   &aidl_return);
    oplusTouch->touchWriteNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID,
                                   OplusTouchConstants::DOUBLE_TAP_INDEP_NODE,
                                   std::to_string(contents), &aidl_return);
}

}  // namespace

namespace aidl::android::hardware::power::impl {

bool isDeviceSpecificModeSupported(Mode type, bool* _aidl_return) {
    switch (type) {
        case Mode::DOUBLE_TAP_TO_WAKE:
        case Mode::LOW_POWER:
        case Mode::GAME_LOADING:
            *_aidl_return = true;
            return true;
        default:
            return false;
    }
}

bool setDeviceSpecificMode(Mode type, bool enabled) {
    switch (type) {
        case Mode::DOUBLE_TAP_TO_WAKE:
            setDoubleTapToWake(enabled);
            return true;
        case Mode::LOW_POWER:
            setLowPower(enabled);
            return true;
        case Mode::GAME_LOADING:
            setGameLoading(enabled);
            return true;
        default:
            return false;
    }
}

}  // namespace aidl::android::hardware::power::impl
