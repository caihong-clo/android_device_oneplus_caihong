/*
 * SPDX-FileCopyrightText: 2024-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * OnePlus Pad 2 (caihong) power HAL mode extension, built into the QTI
 * power HAL through TARGET_POWERHAL_MODE_EXT.
 * Battery Saver (Mode::LOW_POWER) caps CPU and GPU max frequency, and game
 * loading screens (Mode::GAME_LOADING) get a short CPU boost.
 *
 * thermal-engine limits the CPUs to the stock boost ceilings. Like stock, the
 * stricter stock limits listed in thermal-engine.conf apply while the user is
 * not touching the screen or launching an app.
 */

#include <aidl/android/hardware/power/BnPower.h>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/strings.h>

#include <algorithm>
#include <chrono>
#include <climits>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using ::aidl::android::hardware::power::Boost;
using ::aidl::android::hardware::power::Mode;

namespace {

using Clock = std::chrono::steady_clock;
// One value per CPU cluster in kHz, in cpufreq policy order. On pineapple:
// little = cpu0-1, big = cpu2-4, titanium = cpu5-6, prime = cpu7.
using Freqs = std::vector<int>;

// Battery Saver CPU limits, stock max is 2265600/3148800/2956800/3302400
const Freqs kLowPowerMax = {1804800, 2035200, 2035200, 2035200};
// GPU max power level for Battery Saver. Higher index means lower frequency;
// level 6 is about 580-630 MHz depending on the chip bin (top level is
// 870-903 MHz).
constexpr int kLowPowerGpuLevel = 6;

// Game loading screens (GameManager Mode::GAME_LOADING): raise the CPU floors
// so level loads finish faster. Capped by kGameLoadingMax in case the
// framework never clears the mode.
const Freqs kGameLoadingMin = {1574400, 2035200, 2035200, 2035200};
constexpr auto kGameLoadingMax = std::chrono::milliseconds(10000);

constexpr const char* kGpuMaxPwrlevel = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel";

int clusterValue(const Freqs& freqs, size_t cluster) {
    return cluster < freqs.size() ? freqs[cluster] : 0;
}

// cpufreq policy directories, ordered by their first CPU
std::vector<std::string> cpufreqPolicies() {
    std::vector<std::string> policies;
    if (DIR* dir = opendir("/sys/devices/system/cpu/cpufreq")) {
        while (dirent* entry = readdir(dir)) {
            if (android::base::StartsWith(entry->d_name, "policy")) {
                policies.push_back(entry->d_name);
            }
        }
        closedir(dir);
    }
    std::sort(policies.begin(), policies.end(), [](const auto& a, const auto& b) {
        return std::stoi(a.substr(6)) < std::stoi(b.substr(6));
    });
    for (auto& policy : policies) policy = "/sys/devices/system/cpu/cpufreq/" + policy;
    return policies;
}

// The limits are written to the kernel nodes directly rather than taken as
// perf HAL locks. scaling_min_freq and scaling_max_freq are frequency QoS
// requests of their own, and kgsl applies thermal limits separately from
// max_pwrlevel, so thermal-engine still wins. Reading the cpufreq nodes
// returns the combined policy limits, so releasing a limit writes the cpuinfo
// bounds, the kernel defaults of these requests.
class FreqLimits {
  public:
    static FreqLimits& get() {
        static FreqLimits instance;
        return instance;
    }

    // Empty or zero means no idle limit for that cluster
    void setIdleMax(const Freqs& max) {
        std::lock_guard<std::mutex> lock(mLock);
        mIdleMax = max;
        applyLocked();
    }

    void setLowPower(bool enabled) {
        std::lock_guard<std::mutex> lock(mLock);
        if (enabled == mLowPower) return;
        mLowPower = enabled;
        // Battery Saver wins over the loading boost
        if (enabled) mLoading = false;
        applyLocked();
        writeGpuMaxLevel(enabled ? kLowPowerGpuLevel : 0);
        LOG(INFO) << "Battery saver CPU/GPU cap " << (enabled ? "on" : "off");
    }

    void setGameLoading(bool enabled) {
        std::lock_guard<std::mutex> lock(mLock);
        const uint64_t generation = ++mLoadingGeneration;
        mLoading = enabled && !mLowPower;
        applyLocked();
        if (!mLoading) return;
        std::thread([this, generation] {
            std::this_thread::sleep_for(kGameLoadingMax);
            std::lock_guard<std::mutex> lock(mLock);
            if (generation != mLoadingGeneration || !mLoading) return;
            mLoading = false;
            applyLocked();
        }).detach();
    }

  private:
    struct Policy {
        std::string minFreq;
        std::string maxFreq;
        int bottom = 0;
        int top = 0;
        int appliedMin = 0;
        int appliedMax = 0;
    };

    FreqLimits() {
        for (const auto& dir : cpufreqPolicies()) {
            std::string bottom, top;
            if (!android::base::ReadFileToString(dir + "/cpuinfo_min_freq", &bottom) ||
                !android::base::ReadFileToString(dir + "/cpuinfo_max_freq", &top)) {
                continue;
            }
            Policy policy;
            policy.minFreq = dir + "/scaling_min_freq";
            policy.maxFreq = dir + "/scaling_max_freq";
            policy.bottom = std::atoi(bottom.c_str());
            policy.top = std::atoi(top.c_str());
            mPolicies.push_back(policy);
        }
        // Drop any limits left by a previous instance
        std::lock_guard<std::mutex> lock(mLock);
        applyLocked();
        writeGpuMaxLevel(0);
    }

    static bool write(const std::string& path, int value) {
        if (android::base::WriteStringToFile(std::to_string(value), path)) return true;
        PLOG(WARNING) << "Cannot write " << value << " to " << path;
        return false;
    }

    static void writeGpuMaxLevel(int level) { write(kGpuMaxPwrlevel, level); }

    void applyLocked() {
        for (size_t c = 0; c < mPolicies.size(); c++) {
            Policy& policy = mPolicies[c];

            int max = policy.top;
            if (mLowPower) max = std::min(max, clusterValue(kLowPowerMax, c));
            // A loading screen also lifts the idle limit
            const int idleMax = clusterValue(mIdleMax, c);
            if (!mLoading && idleMax > 0) max = std::min(max, idleMax);
            if (max > 0 && max != policy.appliedMax && write(policy.maxFreq, max)) {
                policy.appliedMax = max;
            }

            int min = policy.bottom;
            if (mLoading) min = std::max(min, clusterValue(kGameLoadingMin, c));
            if (min > 0 && min != policy.appliedMin && write(policy.minFreq, min)) {
                policy.appliedMin = min;
            }
        }
    }

    std::mutex mLock;
    std::vector<Policy> mPolicies;
    Freqs mIdleMax;
    bool mLowPower = false;
    bool mLoading = false;
    uint64_t mLoadingGeneration = 0;
};

// Idle CPU limits, see the file comment.
constexpr const char* kThermalEngineConfig = "/vendor/etc/thermal-engine.conf";
constexpr const char* kIdleLimitsTag = "# idle_limits";
// Keep the boost ceilings this long after the last input event, so scrolls,
// flings and the animations that follow a tap run unlimited.
constexpr auto kInteractionHold = std::chrono::milliseconds(2000);
constexpr auto kLaunchMax = std::chrono::milliseconds(5000);
// thermal-engine samples the shell temperature once a second.
constexpr auto kPollInterval = std::chrono::milliseconds(1000);

struct Cluster {
    std::string coolingState;
    std::vector<int> freqsDescending;

    // The cooling device frequency a thermal-engine limit ends up at
    int snap(int freq) const {
        for (int f : freqsDescending) {
            if (f <= freq) return f;
        }
        return freqsDescending.back();
    }
};

std::string formatMhz(const Freqs& freqs) {
    std::string out;
    for (int freq : freqs) {
        if (!out.empty()) out += "/";
        out += std::to_string(freq / 1000);
    }
    return out;
}

class IdleLimits {
  public:
    static IdleLimits& get() {
        static IdleLimits instance;
        return instance;
    }

    bool active() const { return mActive; }

    void interaction(Clock::duration hold) {
        if (!mActive) return;
        std::lock_guard<std::mutex> lock(mLock);
        mInteractUntil = std::max(mInteractUntil, Clock::now() + hold);
        // Lift the limit right away, the worker restores it later
        if (!mApplied.empty()) applyLocked(Clock::now());
        mCv.notify_one();
    }

    void setLaunching(bool launching) {
        if (!mActive) return;
        std::lock_guard<std::mutex> lock(mLock);
        mLaunchUntil = launching ? Clock::now() + kLaunchMax : Clock::time_point();
        if (!launching) mInteractUntil = std::max(mInteractUntil, Clock::now() + kInteractionHold);
        applyLocked(Clock::now());
        mCv.notify_one();
    }

    void setScreenOn(bool on) {
        if (!mActive) return;
        std::lock_guard<std::mutex> lock(mLock);
        mScreenOn = on;
        if (!on) {
            mInteractUntil = Clock::time_point();
            mLaunchUntil = Clock::time_point();
        }
        mCv.notify_one();
    }

  private:
    IdleLimits() {
        mActive = findClusters() && loadLimits();
        if (mActive) {
            LOG(INFO) << "Idle CPU limits for " << mLimits.size() << " thermal levels";
            std::thread([this] { loop(); }).detach();
        }
    }

    // <limit>+<limit>+... with one limit per cluster, snapped to the
    // frequencies the cooling devices report
    bool parseFreqs(const std::string& field, Freqs* freqs) {
        freqs->clear();
        for (const auto& value : android::base::Split(field, "+")) {
            char* end;
            long freq = std::strtol(value.c_str(), &end, 10);
            if (value.empty() || *end != '\0' || freq <= 0 || freq > INT_MAX) return false;
            freqs->push_back(static_cast<int>(freq));
        }
        if (freqs->size() != mClusters.size()) return false;
        for (size_t c = 0; c < mClusters.size(); c++) {
            (*freqs)[c] = mClusters[c].snap((*freqs)[c]);
        }
        return true;
    }

    bool loadLimits() {
        std::string config;
        if (!android::base::ReadFileToString(kThermalEngineConfig, &config)) return false;
        for (const auto& line : android::base::Split(config, "\n")) {
            if (!android::base::StartsWith(line, kIdleLimitsTag)) continue;
            std::istringstream fields(line.substr(strlen(kIdleLimitsTag)));
            std::string limitsField, idleField;
            Freqs limits, idle;
            if (!(fields >> limitsField >> idleField) || !parseFreqs(limitsField, &limits) ||
                !parseFreqs(idleField, &idle)) {
                LOG(WARNING) << "Ignoring malformed idle limits: " << line;
                continue;
            }
            mLimits[limits] = idle;
        }
        return !mLimits.empty();
    }

    // cpu-clusterN cooling devices step through policy N's frequencies from the top
    bool findClusters() {
        const std::vector<std::string> policies = cpufreqPolicies();
        if (policies.empty()) return false;
        mClusters.assign(policies.size(), {});
        if (DIR* dir = opendir("/sys/class/thermal")) {
            while (dirent* entry = readdir(dir)) {
                std::string path = std::string("/sys/class/thermal/") + entry->d_name;
                std::string type;
                if (!android::base::ReadFileToString(path + "/type", &type)) continue;
                type = android::base::Trim(type);
                for (size_t c = 0; c < mClusters.size(); c++) {
                    if (type == "cpu-cluster" + std::to_string(c)) {
                        mClusters[c].coolingState = path + "/cur_state";
                    }
                }
            }
            closedir(dir);
        }
        for (size_t c = 0; c < mClusters.size(); c++) {
            std::string freqs;
            if (mClusters[c].coolingState.empty() ||
                !android::base::ReadFileToString(policies[c] + "/scaling_available_frequencies",
                                                 &freqs)) {
                return false;
            }
            std::istringstream input(freqs);
            int freq;
            while (input >> freq) mClusters[c].freqsDescending.push_back(freq);
            if (mClusters[c].freqsDescending.empty()) return false;
            std::sort(mClusters[c].freqsDescending.rbegin(), mClusters[c].freqsDescending.rend());
        }
        return true;
    }

    bool currentLimits(Freqs* limits) {
        limits->clear();
        for (const auto& cluster : mClusters) {
            std::string state;
            if (!android::base::ReadFileToString(cluster.coolingState, &state)) return false;
            const auto& table = cluster.freqsDescending;
            size_t index = std::min<size_t>(std::strtoul(state.c_str(), nullptr, 10),
                                            table.size() - 1);
            limits->push_back(table[index]);
        }
        return true;
    }

    void applyLocked(Clock::time_point now) {
        Freqs target;
        Freqs limits;
        if (now >= mInteractUntil && now >= mLaunchUntil && currentLimits(&limits)) {
            auto it = mLimits.find(limits);
            if (it != mLimits.end()) target = it->second;
        }
        if (target == mApplied) return;

        FreqLimits::get().setIdleMax(target);
        mApplied = target;
        if (!target.empty() && target != mLogged) {
            LOG(INFO) << "Idle CPU limit " << formatMhz(target) << " MHz";
            mLogged = target;
        }
    }

    void loop() {
        std::unique_lock<std::mutex> lock(mLock);
        while (true) {
            auto now = Clock::now();
            applyLocked(now);
            if (!mScreenOn) {
                // The limits stay as they are until the screen is back on
                mCv.wait(lock);
                continue;
            }
            auto next = now + kPollInterval;
            if (mInteractUntil > now) next = std::min(next, mInteractUntil);
            if (mLaunchUntil > now) next = std::min(next, mLaunchUntil);
            mCv.wait_until(lock, next);
        }
    }

    bool mActive = false;
    std::map<Freqs, Freqs> mLimits;
    std::vector<Cluster> mClusters;

    std::mutex mLock;
    std::condition_variable mCv;
    Clock::time_point mInteractUntil;
    Clock::time_point mLaunchUntil;
    bool mScreenOn = true;
    Freqs mApplied;
    Freqs mLogged;
};

}  // namespace

bool isDeviceSpecificModeSupported(Mode type, bool* _aidl_return) {
    switch (type) {
        case Mode::LOW_POWER:
        case Mode::GAME_LOADING:
            *_aidl_return = true;
            return true;
        case Mode::LAUNCH:
            if (!IdleLimits::get().active()) return false;
            *_aidl_return = true;
            return true;
        default:
            return false;
    }
}

bool setDeviceSpecificBoost(Boost type, int32_t durationMs) {
    if (type != Boost::INTERACTION) return false;
    IdleLimits::get().interaction(
            std::max<Clock::duration>(kInteractionHold, std::chrono::milliseconds(durationMs)));
    return true;
}

bool setDeviceSpecificMode(Mode type, bool enabled) {
    switch (type) {
        case Mode::LAUNCH:
            if (!IdleLimits::get().active()) return false;
            IdleLimits::get().setLaunching(enabled);
            return true;
        case Mode::INTERACTIVE:
            // Also handled by the common power HAL
            IdleLimits::get().setScreenOn(enabled);
            return false;
        case Mode::LOW_POWER:
            FreqLimits::get().setLowPower(enabled);
            return true;
        case Mode::GAME_LOADING:
            FreqLimits::get().setGameLoading(enabled);
            return true;
        default:
            return false;
    }
}
