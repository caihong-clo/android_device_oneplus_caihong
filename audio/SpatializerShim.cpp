/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Wraps the OnePlus spatializer effect library so that it gets the output device.
//
// liboplus_spatializer selects its headphone tuning from the device passed with
// EFFECT_CMD_SET_DEVICE, but its descriptor does not ask for device indication, so audioflinger
// never sends one and the engine stays on the wired headphone tuning. On A2DP it also needs the
// headset type first: 0 selects the OnePlus "Galileo" tuning, any other value the plain
// Bluetooth one. Stock sends both from its vendor audio framework.

#define LOG_TAG "OplusSpatializerShim"

#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <hardware/audio_effect.h>
#include <log/log.h>
#include <system/audio.h>

namespace {

constexpr const char* kLibraryPath = "/odm/lib64/soundfx/liboplus_spatializer.so";
constexpr uint32_t kParamBluetoothType = 0x100100c1;
constexpr int32_t kBluetoothTypeHeadset = 1;

struct ShimContext {
    const struct effect_interface_s* itfe;
    effect_handle_t inner;
};

audio_effect_library_t* gLibrary = nullptr;
pthread_once_t gLoadOnce = PTHREAD_ONCE_INIT;

void loadLibrary() {
    void* handle = dlopen(kLibraryPath, RTLD_NOW);
    if (handle == nullptr) {
        ALOGE("dlopen %s: %s", kLibraryPath, dlerror());
        return;
    }
    auto* library = static_cast<audio_effect_library_t*>(
            dlsym(handle, AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR));
    if (library == nullptr || library->tag != AUDIO_EFFECT_LIBRARY_TAG) {
        ALOGE("%s has no effect library info", kLibraryPath);
        dlclose(handle);
        return;
    }
    gLibrary = library;
}

audio_effect_library_t* library() {
    pthread_once(&gLoadOnce, loadLibrary);
    return gLibrary;
}

void requestDeviceIndication(effect_descriptor_t* desc) {
    desc->flags = (desc->flags & ~EFFECT_FLAG_DEVICE_MASK) | EFFECT_FLAG_DEVICE_IND;
}

ShimContext* context(effect_handle_t self) {
    return reinterpret_cast<ShimContext*>(self);
}

int shimProcess(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    effect_handle_t inner = context(self)->inner;
    return (*inner)->process(inner, in, out);
}

void setBluetoothType(effect_handle_t inner) {
    uint32_t cmd[sizeof(effect_param_t) / sizeof(uint32_t) + 2] = {};
    auto* param = reinterpret_cast<effect_param_t*>(cmd);
    param->psize = sizeof(uint32_t);
    param->vsize = sizeof(int32_t);
    reinterpret_cast<uint32_t*>(param->data)[0] = kParamBluetoothType;
    reinterpret_cast<int32_t*>(param->data)[1] = kBluetoothTypeHeadset;
    int reply = 0;
    uint32_t replySize = sizeof(reply);
    (*inner)->command(inner, EFFECT_CMD_SET_PARAM, sizeof(cmd), cmd, &replySize, &reply);
}

int shimCommand(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize, void* cmdData,
                uint32_t* replySize, void* replyData) {
    effect_handle_t inner = context(self)->inner;
    if (cmdCode == EFFECT_CMD_SET_DEVICE && cmdData != nullptr && cmdSize >= sizeof(uint32_t) &&
        audio_is_a2dp_out_device(*static_cast<audio_devices_t*>(cmdData))) {
        setBluetoothType(inner);
    }
    return (*inner)->command(inner, cmdCode, cmdSize, cmdData, replySize, replyData);
}

int shimGetDescriptor(effect_handle_t self, effect_descriptor_t* desc) {
    effect_handle_t inner = context(self)->inner;
    const int ret = (*inner)->get_descriptor(inner, desc);
    if (ret == 0) requestDeviceIndication(desc);
    return ret;
}

int shimProcessReverse(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    effect_handle_t inner = context(self)->inner;
    if ((*inner)->process_reverse == nullptr) return -ENOSYS;
    return (*inner)->process_reverse(inner, in, out);
}

const struct effect_interface_s kShimInterface = {
        shimProcess,
        shimCommand,
        shimGetDescriptor,
        shimProcessReverse,
};

int createEffect(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                 effect_handle_t* pHandle) {
    audio_effect_library_t* lib = library();
    if (lib == nullptr || pHandle == nullptr) return -EINVAL;
    effect_handle_t inner = nullptr;
    const int ret = lib->create_effect(uuid, sessionId, ioId, &inner);
    if (ret != 0) return ret;
    auto* ctx = static_cast<ShimContext*>(calloc(1, sizeof(ShimContext)));
    if (ctx == nullptr) {
        lib->release_effect(inner);
        return -ENOMEM;
    }
    ctx->itfe = &kShimInterface;
    ctx->inner = inner;
    *pHandle = reinterpret_cast<effect_handle_t>(ctx);
    return 0;
}

int releaseEffect(effect_handle_t handle) {
    audio_effect_library_t* lib = library();
    if (lib == nullptr || handle == nullptr) return -EINVAL;
    ShimContext* ctx = context(handle);
    const int ret = lib->release_effect(ctx->inner);
    free(ctx);
    return ret;
}

int getDescriptor(const effect_uuid_t* uuid, effect_descriptor_t* desc) {
    audio_effect_library_t* lib = library();
    if (lib == nullptr) return -EINVAL;
    const int ret = lib->get_descriptor(uuid, desc);
    if (ret == 0) requestDeviceIndication(desc);
    return ret;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) audio_effect_library_t
        AUDIO_EFFECT_LIBRARY_INFO_SYM = {
                .tag = AUDIO_EFFECT_LIBRARY_TAG,
                .version = EFFECT_LIBRARY_API_VERSION,
                .name = "OnePlus Spatializer Shim",
                .implementor = "PenguinOS",
                .create_effect = createEffect,
                .release_effect = releaseEffect,
                .get_descriptor = getDescriptor,
};
