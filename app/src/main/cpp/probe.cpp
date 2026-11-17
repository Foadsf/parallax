// Parallax: CPU feature and OpenCL discovery for the device report.
// SPDX-License-Identifier: GPL-3.0-or-later
#include "probe.hpp"

#include <dlfcn.h>
#include <sched.h>
#include <sys/auxv.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include <opencv2/core/utility.hpp>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

namespace parallax {

std::string cpuFeatures() {
#if defined(__aarch64__)
    // Kernel-reported ISA extensions (getauxval), the canonical runtime check on Linux/Android.
    const unsigned long hw = getauxval(AT_HWCAP);
    struct Bit { unsigned long mask; const char* name; };
    const Bit bits[] = {
        {HWCAP_ASIMD, "neon"},       {HWCAP_FPHP, "fp16"},          {HWCAP_ASIMDHP, "neon-fp16"},
        {HWCAP_ASIMDDP, "dotprod"},  {HWCAP_ATOMICS, "lse-atomics"}, {HWCAP_ASIMDRDM, "rdm"},
        {HWCAP_SVE, "sve"},          {HWCAP_AES, "aes"},            {HWCAP_CRC32, "crc32"},
    };
    std::string out = "arm64";
    for (const auto& b : bits)
        if (hw & b.mask) out += std::string(" ") + b.name;
    // ARMv8.2-A is the first level with dot product; without it the CPU is ARMv8.0/8.1.
    out += (hw & HWCAP_ASIMDDP) ? " (ARMv8.2+)" : " (ARMv8.0/8.1)";
    return out;
#elif defined(__x86_64__)
    return "x86-64";
#else
    return "unknown";
#endif
}

namespace {
using cl_int = int32_t;
using cl_uint = uint32_t;
using cl_ulong = uint64_t;
using PlatformIDs = cl_int (*)(cl_uint, void**, cl_uint*);
using DeviceIDs = cl_int (*)(void*, cl_ulong, cl_uint, void**, cl_uint*);
using DeviceInfo = cl_int (*)(void*, cl_uint, size_t, void*, size_t*);
constexpr cl_uint kDeviceName = 0x102B, kDeviceVersion = 0x102F, kComputeUnits = 0x1002, kGlobalMem = 0x101F;
constexpr cl_ulong kDeviceTypeGpu = 1 << 2;

std::string infoString(DeviceInfo f, void* dev, cl_uint key) {
    char buf[256] = {0};
    if (f(dev, key, sizeof buf - 1, buf, nullptr) != 0) return "?";
    return buf;
}
}  // namespace

std::string openclSummary() {
    // Android exposes vendor OpenCL only if the ROM lists libOpenCL.so in public.libraries.txt
    // and (targetSdk >= 31) the app declares <uses-native-library>. Either may be missing.
    void* h = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) h = dlopen("libOpenCL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!h) return "not available to apps";
    auto getPlatforms = reinterpret_cast<PlatformIDs>(dlsym(h, "clGetPlatformIDs"));
    auto getDevices = reinterpret_cast<DeviceIDs>(dlsym(h, "clGetDeviceIDs"));
    auto getDeviceInfo = reinterpret_cast<DeviceInfo>(dlsym(h, "clGetDeviceInfo"));
    std::string out;
    if (!getPlatforms || !getDevices || !getDeviceInfo) {
        out = "library present, API incomplete";
    } else {
        cl_uint np = 0;
        void* platforms[4] = {nullptr};
        if (getPlatforms(4, platforms, &np) != 0 || np == 0) {
            out = "library present, no platform";
        } else {
            for (cl_uint p = 0; p < np && p < 4; ++p) {
                void* devs[4] = {nullptr};
                cl_uint nd = 0;
                if (getDevices(platforms[p], kDeviceTypeGpu, 4, devs, &nd) != 0) continue;
                for (cl_uint d = 0; d < nd && d < 4; ++d) {
                    cl_uint cu = 0;
                    cl_ulong mem = 0;
                    getDeviceInfo(devs[d], kComputeUnits, sizeof cu, &cu, nullptr);
                    getDeviceInfo(devs[d], kGlobalMem, sizeof mem, &mem, nullptr);
                    char line[512];
                    std::snprintf(line, sizeof line, "%s%s, %s, %u compute units, %llu MB", out.empty() ? "" : "; ",
                                  infoString(getDeviceInfo, devs[d], kDeviceName).c_str(),
                                  infoString(getDeviceInfo, devs[d], kDeviceVersion).c_str(), cu,
                                  static_cast<unsigned long long>(mem >> 20));
                    out += line;
                }
            }
            if (out.empty()) out = "library present, no GPU device";
        }
    }
    dlclose(h);
    return out;
}

std::string vulkanSummary() {
    void* h = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) h = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!h) return "not available";
    auto getProc = reinterpret_cast<void* (*)(void*, const char*)>(dlsym(h, "vkGetInstanceProcAddr"));
    if (!getProc) { dlclose(h); return "loader incomplete"; }
    struct AppInfo { uint32_t sType; const void* pNext; const char* app; uint32_t av; const char* eng; uint32_t ev; uint32_t api; };
    struct InstInfo { uint32_t sType; const void* pNext; uint32_t flags; const AppInfo* app; uint32_t lc; const char* const* l; uint32_t ec; const char* const* e; };
    struct Props { uint32_t api, drv, vendor, devId, devType; char name[256]; uint8_t pad[656]; };
    auto createInst = reinterpret_cast<int32_t (*)(const InstInfo*, const void*, void**)>(getProc(nullptr, "vkCreateInstance"));
    if (!createInst) { dlclose(h); return "no vkCreateInstance"; }
    AppInfo ai{0, nullptr, "Parallax", 1, "Parallax", 1, (1u << 22)};
    InstInfo ii{1, nullptr, 0, &ai, 0, nullptr, 0, nullptr};
    void* inst = nullptr;
    if (createInst(&ii, nullptr, &inst) != 0 || !inst) { dlclose(h); return "instance init failed"; }
    auto destroyInst = reinterpret_cast<void (*)(void*, const void*)>(getProc(inst, "vkDestroyInstance"));
    auto enumDevs = reinterpret_cast<int32_t (*)(void*, uint32_t*, void**)>(getProc(inst, "vkEnumeratePhysicalDevices"));
    auto getProps = reinterpret_cast<void (*)(void*, Props*)>(getProc(inst, "vkGetPhysicalDeviceProperties"));
    std::string out = "no Vulkan GPU";
    uint32_t n = 0;
    if (enumDevs && getProps && enumDevs(inst, &n, nullptr) == 0 && n > 0) {
        std::vector<void*> devs(std::min(n, 4u));
        n = uint32_t(devs.size());
        if (enumDevs(inst, &n, devs.data()) == 0 && n > 0) {
            Props p{}; getProps(devs[0], &p);
            char buf[320];
            std::snprintf(buf, sizeof buf, "%s (Vulkan %u.%u.%u)", p.name, (p.api >> 22) & 0x3FF, (p.api >> 12) & 0x3FF, p.api & 0xFFF);
            out = buf;
        }
    }
    if (destroyInst) destroyInst(inst, nullptr);
    dlclose(h);
    return out;
}

std::string npuSummary() {
    void* h = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) return "not exposed via NNAPI";
    auto getCount = reinterpret_cast<int (*)(uint32_t*)>(dlsym(h, "ANeuralNetworks_getDeviceCount"));
    auto getDev = reinterpret_cast<int (*)(uint32_t, void**)>(dlsym(h, "ANeuralNetworks_getDevice"));
    auto getName = reinterpret_cast<int (*)(const void*, const char**)>(dlsym(h, "ANeuralNetworksDevice_getName"));
    auto getType = reinterpret_cast<int (*)(const void*, int32_t*)>(dlsym(h, "ANeuralNetworksDevice_getType"));
    std::string out; uint32_t n = 0;
    if (getCount && getDev && getName && getType && getCount(&n) == 0) {
        for (uint32_t i = 0; i < n; ++i) {
            void* d = nullptr; int32_t t = 0; const char* name = nullptr;
            if (getDev(i, &d) == 0 && getType(d, &t) == 0 && getName(d, &name) == 0 && t >= 2 && name) {
                if (!out.empty()) out += ", ";
                out += std::string(name) + (t == 4 ? " (NPU/DSP)" : (t == 3 ? " (GPU)" : ""));
            }
        }
    }
    dlclose(h);
    return out.empty() ? "CPU fallback only" : out;
}

int configureOptimalThreads() {
    const int total = std::max(1, cv::getNumberOfCPUs());
    std::vector<long> freqs(total, 0);
    long maxFreq = 0, minFreq = (1L << 30);
    for (int i = 0; i < total; ++i) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
        if (f >> freqs[i] && freqs[i] > 100000) { maxFreq = std::max(maxFreq, freqs[i]); minFreq = std::min(minFreq, freqs[i]); }
    }
    std::vector<int> perfIds;
    if (maxFreq > 0 && minFreq < maxFreq * 65 / 100)
        for (int i = 0; i < total; ++i) if (freqs[i] >= maxFreq * 65 / 100) perfIds.push_back(i);
    int target = int(perfIds.size()) >= 4 ? int(perfIds.size()) : total;
#if defined(__linux__) || defined(__ANDROID__)
    if (int(perfIds.size()) >= 4) {
        cpu_set_t mask; CPU_ZERO(&mask);
        for (int id : perfIds) CPU_SET(id, &mask);
        sched_setaffinity(0, sizeof(mask), &mask);
    }
#endif
    if (void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL)) {
        auto acq = reinterpret_cast<void* (*)()>(dlsym(lib, "AThermal_acquireManager"));
        auto status = reinterpret_cast<int (*)(void*)>(dlsym(lib, "AThermal_getCurrentThermalStatus"));
        auto rel = reinterpret_cast<void (*)(void*)>(dlsym(lib, "AThermal_releaseManager"));
        if (acq && status && rel) {
            if (void* mgr = acq()) { if (status(mgr) >= 3) target = std::max(2, target / 2); rel(mgr); }
        }
        dlclose(lib);
    }
    cv::setNumThreads(target);
    return target;
}

}  // namespace parallax
