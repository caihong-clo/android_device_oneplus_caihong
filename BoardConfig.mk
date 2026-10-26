#
# Copyright (C) 2021-2025 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Partitions
BOARD_SUPER_PARTITION_SIZE := 15032385536

# Include the common OEM chipset BoardConfig.
include device/oneplus/sm8650-common/BoardConfigCommon.mk

DEVICE_PATH := device/oneplus/caihong

# Assert
TARGET_OTA_ASSERT_DEVICE := OP5DAAL1

# Display
TARGET_SCREEN_DENSITY := 420

# Kernel modules
BOARD_VENDOR_KERNEL_MODULES_LOAD += \
    msm_lmh_dcvs.ko \
    oplus_bsp_kshrink_slabd.ko

# Power: caihong mode extension (Battery Saver CPU/GPU cap, game loading boost).
TARGET_POWERHAL_MODE_EXT := $(DEVICE_PATH)/power/power-mode.cpp
# Power feature: double tap to wake through the Oplus touch HAL.
TARGET_POWER_FEATURE_EXT_LIB := //$(DEVICE_PATH):libpowerfeature_ext_caihong

# Properties
TARGET_ODM_PROP += $(DEVICE_PATH)/properties/odm.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/properties/vendor.prop

# Recovery
TARGET_RECOVERY_UI_MARGIN_HEIGHT := 103
TARGET_RECOVERY_DEFAULT_TOUCH_ROTATION := ROTATION_RIGHT

# SEPolicy
include $(DEVICE_PATH)/sepolicy/SEPolicy.mk

# Include the proprietary files BoardConfig.
include vendor/oneplus/caihong/BoardConfigVendor.mk
