/*
 * SPDX-FileCopyrightText: 2020-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * OnePlus Pad 2 (caihong) vendor.aospa.power feature extension.
 * The HBP touch stack has no sysfs tap-to-wake node, so double tap to wake
 * is toggled through the Oplus touch HAL, like hardware/oplus on LineageOS.
 * The panel then reports KEY_WAKEUP (scan code 143) for the gesture.
 */

#include <aidl/vendor/aospa/power/BnPowerFeature.h>
#include <aidl/vendor/oplus/hardware/touch/IOplusTouch.h>

#include <android-base/logging.h>
#include <android/binder_manager.h>

#include <cstdlib>
#include <string>

using aidl::vendor::oplus::hardware::touch::IOplusTouch;

namespace {

// Node and gesture ids of vendor.oplus.hardware.touch.
constexpr int kDefaultTpIcId = 0;
constexpr int kDoubleTapEnableNode = 1;
constexpr int kDoubleTapIndepNode = 21;
constexpr int kDoubleTapGesture = 1 << 1;

bool setDoubleTapToWake(bool enabled) {
    const std::string instance = std::string() + IOplusTouch::descriptor + "/default";
    std::shared_ptr<IOplusTouch> oplusTouch = IOplusTouch::fromBinder(
            ndk::SpAIBinder(AServiceManager_waitForService(instance.c_str())));
    if (!oplusTouch) {
        LOG(ERROR) << "DT2W: " << instance << " is not available";
        return false;
    }

    std::string tmp;
    int contents = 0;
    if (oplusTouch->touchReadNodeFile(kDefaultTpIcId, kDoubleTapIndepNode, &tmp).isOk()) {
        char* end = nullptr;
        long value = strtol(tmp.c_str(), &end, 16);
        if (end != tmp.c_str()) {
            contents = static_cast<int>(value);
        } else {
            LOG(WARNING) << "DT2W: unexpected gesture state '" << tmp << "'";
        }
    }

    if (enabled) {
        contents |= kDoubleTapGesture;
    } else {
        contents &= ~kDoubleTapGesture;
    }

    int ret = 0;
    oplusTouch->touchWriteNodeFile(kDefaultTpIcId, kDoubleTapEnableNode, "1", &ret);
    oplusTouch->touchWriteNodeFile(kDefaultTpIcId, kDoubleTapIndepNode,
                                   std::to_string(contents), &ret);
    LOG(INFO) << "DT2W " << (enabled ? "on" : "off") << ", gestures " << contents;
    return true;
}

}  // anonymous namespace

namespace aidl {
namespace vendor {
namespace aospa {
namespace power {

bool setDeviceSpecificFeature(Feature feature, bool enabled) {
    switch (feature) {
        case Feature::DOUBLE_TAP:
            return setDoubleTapToWake(enabled);
        default:
            return false;
    }
}

}  // namespace power
}  // namespace aospa
}  // namespace vendor
}  // namespace aidl
