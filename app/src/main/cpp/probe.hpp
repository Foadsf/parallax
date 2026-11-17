// Parallax: hardware discovery for the device report.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace parallax {

// ISA extensions the kernel reports, e.g. "arm64 neon fp16 dotprod ... (ARMv8.2+)".
std::string cpuFeatures();
// The GPU behind the vendor OpenCL driver, or why it is not reachable.
std::string openclSummary();
// Vulkan 1.1+ physical-device summary via dynamic libvulkan probing.
std::string vulkanSummary();
// NNAPI NPU/DSP device summary via dynamic libneuralnetworks.so probing.
std::string npuSummary();
// Size/pin worker threads to big.LITTLE performance cores, honour AThermal throttling.
int configureOptimalThreads();

}  // namespace parallax
