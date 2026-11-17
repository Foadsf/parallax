// Parallax: SIFT feature extraction (RootSIFT) and mutual ratio-test matching.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/features2d.hpp>
#include <opencv2/flann.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#if __has_include(<opencv2/objdetect/aruco_detector.hpp>)
#include <opencv2/objdetect/aruco_detector.hpp>
#define PARALLAX_HAVE_ARUCO 1
#endif

#include <algorithm>
#include <cstring>
#include <map>
#include <mutex>
#if defined(__ANDROID__)
#include <dlfcn.h>
#endif

#include "internal.hpp"

namespace parallax {

bool extractFeatures(State& s, int imageIndex) {
    Image& im = s.images[imageIndex];
    // One read: colour for the sparse points, grey for detection.
    const cv::Mat bgr = cv::imread(im.input.path, cv::IMREAD_COLOR);
    if (bgr.empty()) return false;
    cv::Mat gray;
    cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
#ifdef PARALLAX_HAVE_ARUCO
    if (s.opt.markerSizeMm > 0) {
        // Printed scale markers, sub-pixel corners (Garrido-Jurado et al. 2014).
        cv::aruco::DetectorParameters params;
        params.cornerRefinementMethod = cv::aruco::CORNER_REFINE_SUBPIX;
        cv::aruco::ArucoDetector detector(cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50), params);
        std::vector<int> ids;
        std::vector<std::vector<cv::Point2f>> corners;
        detector.detectMarkers(gray, corners, ids);
        std::map<int, int> counts;
        for (size_t k = 0; k < ids.size(); ++k)
            if (corners[k].size() == 4) counts[ids[k]]++;
        for (size_t k = 0; k < ids.size(); ++k)
            if (corners[k].size() == 4 && counts[ids[k]] == 1)
                im.markers[ids[k]] = {corners[k][0], corners[k][1], corners[k][2], corners[k][3]};
    }
#endif

    const double scale = std::min(1.0, s.opt.featureMaxSide / double(std::max(gray.cols, gray.rows)));
    cv::Mat work;
    if (scale < 1.0) cv::resize(gray, work, cv::Size(), scale, scale, cv::INTER_AREA);
    else work = gray;

    // Lowe's SIFT, the de-facto standard detector/descriptor for SfM (patent expired 2020).
    auto sift = cv::SIFT::create(s.opt.maxFeatures, 3, 0.02, 10.0, 1.6);
    std::vector<cv::KeyPoint> kps;
    cv::Mat desc;
    sift->detectAndCompute(work, cv::noArray(), kps, desc);

    // RootSIFT (Arandjelovic & Zisserman 2012): L1-normalise, then square root.
    for (int r = 0; r < desc.rows; ++r) {
        float* d = desc.ptr<float>(r);
        double l1 = 0;
        for (int c = 0; c < desc.cols; ++c) l1 += d[c];
        const float inv = l1 > 0 ? float(1.0 / l1) : 0.f;
        for (int c = 0; c < desc.cols; ++c) d[c] = std::sqrt(d[c] * inv);
    }

    im.pixels.resize(kps.size());
    const float inv = float(1.0 / scale);
    for (size_t i = 0; i < kps.size(); ++i) {
        // Keypoint centres follow OpenCV's pixel-centre convention, so a plain rescale suffices
        // up to a sub-pixel shift: (x + 0.5) / s - 0.5.
        im.pixels[i] = {(kps[i].pt.x + 0.5f) * inv - 0.5f, (kps[i].pt.y + 0.5f) * inv - 0.5f};
    }
    im.colors.resize(im.pixels.size());
    for (size_t i = 0; i < im.pixels.size(); ++i) {
        const int x = std::clamp(int(im.pixels[i].x + 0.5f), 0, bgr.cols - 1);
        const int y = std::clamp(int(im.pixels[i].y + 0.5f), 0, bgr.rows - 1);
        const cv::Vec3b c = bgr.at<cv::Vec3b>(y, x);
        im.colors[i] = {c[2], c[1], c[0]};
    }
    im.descriptors = desc;
    im.track.assign(kps.size(), -1);
    undistortKeypoints(s.cameras[im.camera], im.pixels, im.normalized);
    return true;
}

#if defined(__ANDROID__)
namespace {
class GlesMatcher {
public:
    static GlesMatcher& instance() { static GlesMatcher g; return g; }
    bool match(const cv::Mat& A, const cv::Mat& B, float r2, std::vector<int>& outBest) {
        std::lock_guard<std::mutex> lk(mtx_);
        if (!init()) return false;
        const int M = A.rows, N = B.rows;
        if (!eglMakeCurrent_(dpy_, surf_, surf_, ctx_)) return false;
        constexpr uint32_t kSSBO = 0x90D2, kDyn = 0x88EA;
        glBindBuffer_(kSSBO, bufs_[0]); glBufferData_(kSSBO, ptrdiff_t(M * 128 * 4), A.data, kDyn); glBindBufferBase_(kSSBO, 0, bufs_[0]);
        glBindBuffer_(kSSBO, bufs_[1]); glBufferData_(kSSBO, ptrdiff_t(N * 128 * 4), B.data, kDyn); glBindBufferBase_(kSSBO, 1, bufs_[1]);
        glBindBuffer_(kSSBO, bufs_[2]); glBufferData_(kSSBO, ptrdiff_t(M * 4), nullptr, kDyn); glBindBufferBase_(kSSBO, 2, bufs_[2]);
        glUseProgram_(prog_);
        glUniform1ui_(uM_, uint32_t(M)); glUniform1ui_(uN_, uint32_t(N)); glUniform1f_(uR2_, r2);
        glDispatchCompute_(uint32_t((M + 63) / 64), 1, 1);
        glMemoryBarrier_(0x00002200);
        glBindBuffer_(kSSBO, bufs_[2]);
        void* ptr = glMapBufferRange_(kSSBO, 0, ptrdiff_t(M * 4), 1);
        if (ptr) { outBest.resize(M); std::memcpy(outBest.data(), ptr, M * 4); glUnmapBuffer_(kSSBO); }
        eglMakeCurrent_(dpy_, nullptr, nullptr, nullptr);
        return ptr != nullptr;
    }
private:
    bool init() {
        if (tried_) return ok_;
        tried_ = true;
        egl_ = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
        gles_ = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
        if (!egl_ || !gles_) return false;
        auto getDisp = reinterpret_cast<void* (*)(void*)>(dlsym(egl_, "eglGetDisplay"));
        auto eglInit = reinterpret_cast<uint32_t (*)(void*, int32_t*, int32_t*)>(dlsym(egl_, "eglInitialize"));
        auto choose = reinterpret_cast<uint32_t (*)(void*, const int32_t*, void**, int32_t, int32_t*)>(dlsym(egl_, "eglChooseConfig"));
        auto createCtx = reinterpret_cast<void* (*)(void*, void*, void*, const int32_t*)>(dlsym(egl_, "eglCreateContext"));
        auto createPb = reinterpret_cast<void* (*)(void*, void*, const int32_t*)>(dlsym(egl_, "eglCreatePbufferSurface"));
        eglMakeCurrent_ = reinterpret_cast<uint32_t (*)(void*, void*, void*, void*)>(dlsym(egl_, "eglMakeCurrent"));
        if (!getDisp || !eglInit || !choose || !createCtx || !createPb || !eglMakeCurrent_) return false;
        dpy_ = getDisp(nullptr);
        if (!dpy_ || !eglInit(dpy_, nullptr, nullptr)) return false;
        const int32_t attr[] = {0x3040, 0x0040, 0x3033, 1, 0x3038}, cattr[] = {0x3098, 3, 0x30FB, 1, 0x3038}, pattr[] = {0x3057, 1, 0x3056, 1, 0x3038};
        void* cfg = nullptr; int32_t n = 0;
        if (!choose(dpy_, attr, &cfg, 1, &n) || n == 0) return false;
        ctx_ = createCtx(dpy_, cfg, nullptr, cattr); surf_ = createPb(dpy_, cfg, pattr);
        if (!ctx_ || !surf_ || !eglMakeCurrent_(dpy_, surf_, surf_, ctx_)) return false;
        auto createSh = reinterpret_cast<uint32_t (*)(uint32_t)>(dlsym(gles_, "glCreateShader"));
        auto shSrc = reinterpret_cast<void (*)(uint32_t, int32_t, const char* const*, const int32_t*)>(dlsym(gles_, "glShaderSource"));
        auto compile = reinterpret_cast<void (*)(uint32_t)>(dlsym(gles_, "glCompileShader"));
        auto getShIv = reinterpret_cast<void (*)(uint32_t, uint32_t, int32_t*)>(dlsym(gles_, "glGetShaderiv"));
        auto createProg = reinterpret_cast<uint32_t (*)()>(dlsym(gles_, "glCreateProgram"));
        auto attach = reinterpret_cast<void (*)(uint32_t, uint32_t)>(dlsym(gles_, "glAttachShader"));
        auto link = reinterpret_cast<void (*)(uint32_t)>(dlsym(gles_, "glLinkProgram"));
        auto getProgIv = reinterpret_cast<void (*)(uint32_t, uint32_t, int32_t*)>(dlsym(gles_, "glGetProgramiv"));
        auto getLoc = reinterpret_cast<int32_t (*)(uint32_t, const char*)>(dlsym(gles_, "glGetUniformLocation"));
        auto genBufs = reinterpret_cast<void (*)(int32_t, uint32_t*)>(dlsym(gles_, "glGenBuffers"));
        glUseProgram_ = reinterpret_cast<void (*)(uint32_t)>(dlsym(gles_, "glUseProgram"));
        glUniform1ui_ = reinterpret_cast<void (*)(int32_t, uint32_t)>(dlsym(gles_, "glUniform1ui"));
        glUniform1f_ = reinterpret_cast<void (*)(int32_t, float)>(dlsym(gles_, "glUniform1f"));
        glBindBuffer_ = reinterpret_cast<void (*)(uint32_t, uint32_t)>(dlsym(gles_, "glBindBuffer"));
        glBufferData_ = reinterpret_cast<void (*)(uint32_t, ptrdiff_t, const void*, uint32_t)>(dlsym(gles_, "glBufferData"));
        glBindBufferBase_ = reinterpret_cast<void (*)(uint32_t, uint32_t, uint32_t)>(dlsym(gles_, "glBindBufferBase"));
        glDispatchCompute_ = reinterpret_cast<void (*)(uint32_t, uint32_t, uint32_t)>(dlsym(gles_, "glDispatchCompute"));
        glMemoryBarrier_ = reinterpret_cast<void (*)(uint32_t)>(dlsym(gles_, "glMemoryBarrier"));
        glMapBufferRange_ = reinterpret_cast<void* (*)(uint32_t, ptrdiff_t, ptrdiff_t, uint32_t)>(dlsym(gles_, "glMapBufferRange"));
        glUnmapBuffer_ = reinterpret_cast<uint8_t (*)(uint32_t)>(dlsym(gles_, "glUnmapBuffer"));
        if (!createSh || !glDispatchCompute_ || !glMapBufferRange_) { eglMakeCurrent_(dpy_, nullptr, nullptr, nullptr); return false; }
        const char* src =
            "#version 310 es\nlayout(local_size_x = 64) in;\n"
            "layout(std430, binding = 0) readonly buffer BA { vec4 A[]; };\n"
            "layout(std430, binding = 1) readonly buffer BB { vec4 B[]; };\n"
            "layout(std430, binding = 2) writeonly buffer BO { int best[]; };\n"
            "uniform uint uM; uniform uint uN; uniform float uR2;\n"
            "void main() {\n"
            "  uint i = gl_GlobalInvocationID.x; if (i >= uM) return;\n"
            "  vec4 a[32]; uint bA = i * 32u; for (uint k = 0u; k < 32u; ++k) a[k] = A[bA + k];\n"
            "  float s1 = -1.0, s2 = -1.0; int j1 = -1;\n"
            "  for (uint j = 0u; j < uN; ++j) {\n"
            "    uint bB = j * 32u; float s = 0.0;\n"
            "    for (uint k = 0u; k < 32u; ++k) s += dot(a[k], B[bB + k]);\n"
            "    if (s > s1) { s2 = s1; s1 = s; j1 = int(j); } else if (s > s2) { s2 = s; }\n"
            "  }\n"
            "  best[i] = (j1 >= 0 && max(0.0, 1.0 - s1) < uR2 * max(0.0, 1.0 - s2)) ? j1 : -1;\n"
            "}\n";
        uint32_t sh = createSh(0x91B9); shSrc(sh, 1, &src, nullptr); compile(sh);
        int32_t st = 0, lst = 0; getShIv(sh, 0x8B81, &st);
        prog_ = createProg(); attach(prog_, sh); link(prog_); getProgIv(prog_, 0x8B82, &lst);
        if (st && lst) { uM_ = getLoc(prog_, "uM"); uN_ = getLoc(prog_, "uN"); uR2_ = getLoc(prog_, "uR2"); genBufs(3, bufs_); ok_ = true; }
        eglMakeCurrent_(dpy_, nullptr, nullptr, nullptr);
        return ok_;
    }
    std::mutex mtx_; bool tried_ = false, ok_ = false;
    void *egl_ = nullptr, *gles_ = nullptr, *dpy_ = nullptr, *ctx_ = nullptr, *surf_ = nullptr;
    uint32_t prog_ = 0, bufs_[3] = {0, 0, 0}; int32_t uM_ = -1, uN_ = -1, uR2_ = -1;
    uint32_t (*eglMakeCurrent_)(void*, void*, void*, void*) = nullptr;
    void (*glUseProgram_)(uint32_t) = nullptr;
    void (*glUniform1ui_)(int32_t, uint32_t) = nullptr;
    void (*glUniform1f_)(int32_t, float) = nullptr;
    void (*glBindBuffer_)(uint32_t, uint32_t) = nullptr;
    void (*glBufferData_)(uint32_t, ptrdiff_t, const void*, uint32_t) = nullptr;
    void (*glBindBufferBase_)(uint32_t, uint32_t, uint32_t) = nullptr;
    void (*glDispatchCompute_)(uint32_t, uint32_t, uint32_t) = nullptr;
    void (*glMemoryBarrier_)(uint32_t) = nullptr;
    void* (*glMapBufferRange_)(uint32_t, ptrdiff_t, ptrdiff_t, uint32_t) = nullptr;
    uint8_t (*glUnmapBuffer_)(uint32_t) = nullptr;
};
}  // namespace
#endif

std::vector<std::pair<int, int>> matchDescriptors(const Image& a, const Image& b, float ratio) {
    std::vector<std::pair<int, int>> matches;
    const int M = a.descriptors.rows, N = b.descriptors.rows;
    if (M < 8 || N < 8) return matches;
    const float r2 = ratio * ratio;
#if defined(__ANDROID__)
    if (M * N >= 500000) {
        std::vector<int> fwd, bwd;
        if (GlesMatcher::instance().match(a.descriptors, b.descriptors, r2, fwd) &&
            GlesMatcher::instance().match(b.descriptors, a.descriptors, r2, bwd)) {
            for (int i = 0; i < M; ++i)
                if (fwd[i] >= 0 && bwd[fwd[i]] == i) matches.emplace_back(i, fwd[i]);
            return matches;
        }
    }
#endif
    cv::Mat sim;
    cv::gemm(a.descriptors, b.descriptors, 1.0, cv::Mat(), 0.0, sim, cv::GEMM_2_T);
    std::vector<int> fwdBest(M, -1), bwdBest(N, -1);
    std::vector<float> bwdS1(N, -1.f), bwdS2(N, -1.f);
    for (int i = 0; i < M; ++i) {
        const float* row = sim.ptr<float>(i);
        float s1 = -1.f, s2 = -1.f; int j1 = -1;
        for (int j = 0; j < N; ++j) {
            const float s = row[j];
            if (s > s1) { s2 = s1; s1 = s; j1 = j; } else if (s > s2) { s2 = s; }
            if (s > bwdS1[j]) { bwdS2[j] = bwdS1[j]; bwdS1[j] = s; bwdBest[j] = i; } else if (s > bwdS2[j]) { bwdS2[j] = s; }
        }
        if (j1 >= 0 && std::max(0.f, 1.f - s1) < r2 * std::max(0.f, 1.f - s2)) fwdBest[i] = j1;
    }
    for (int i = 0; i < M; ++i) {
        const int j = fwdBest[i];
        if (j >= 0 && bwdBest[j] == i && std::max(0.f, 1.f - bwdS1[j]) < r2 * std::max(0.f, 1.f - bwdS2[j]))
            matches.emplace_back(i, j);
    }
    return matches;
}

}  // namespace parallax
