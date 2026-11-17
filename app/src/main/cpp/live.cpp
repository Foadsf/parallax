// Parallax: live capture support - YUV conversion, keyframe selection by KLT tracking, preview.
// SPDX-License-Identifier: GPL-3.0-or-later
#include "live.hpp"

#include <opencv2/core/utility.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

#include <algorithm>

namespace parallax {

namespace {
inline uint8_t clamp8(int v) { return uint8_t(v < 0 ? 0 : (v > 255 ? 255 : v)); }

// Camera2 YUV_420_888 is full-range BT.601 (JFIF), so convert explicitly rather than with
// OpenCV's video-range YUV conversions. Fixed point, 16-bit fractions.
inline void yuvToRgb(int Y, int U, int V, uint8_t& r, uint8_t& g, uint8_t& b) {
    const int u = U - 128, v = V - 128;
    r = clamp8(Y + ((91881 * v) >> 16));
    g = clamp8(Y - ((22554 * u + 46802 * v) >> 16));
    b = clamp8(Y + ((116130 * u) >> 16));
}
}  // namespace

cv::Mat yuvToBgr(const YuvFrame& f) {
    cv::Mat out(f.height, f.width, CV_8UC3);
    cv::parallel_for_(cv::Range(0, f.height), [&](const cv::Range& r) {
        for (int y = r.start; y < r.end; ++y) {
            const uint8_t* yr = f.y + size_t(y) * f.yStride;
            const uint8_t* ur = f.u + size_t(y / 2) * f.uvStride;
            const uint8_t* vr = f.v + size_t(y / 2) * f.uvStride;
            uint8_t* o = out.ptr<uint8_t>(y);
            int x = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
            if (f.uvPixelStride == 2 || f.uvPixelStride == 1) {
                const int16x8_t v128 = vdupq_n_s16(128);
                const int16x8_t c_rv = vdupq_n_s16(90), c_gu = vdupq_n_s16(-22), c_gv = vdupq_n_s16(-46), c_bu = vdupq_n_s16(113);
                for (; x + 8 <= f.width; x += 8) {
                    uint8_t u_tmp[8], v_tmp[8];
                    for (int k = 0; k < 4; ++k) {
                        const int c = (x / 2 + k) * f.uvPixelStride;
                        u_tmp[2 * k] = u_tmp[2 * k + 1] = ur[c];
                        v_tmp[2 * k] = v_tmp[2 * k + 1] = vr[c];
                    }
                    const int16x8_t Y = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(yr + x)));
                    const int16x8_t U = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vld1_u8(u_tmp))), v128);
                    const int16x8_t V = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vld1_u8(v_tmp))), v128);
                    uint8x8x3_t bgr;
                    bgr.val[2] = vqmovun_s16(vaddq_s16(Y, vshrq_n_s16(vmulq_s16(V, c_rv), 6)));
                    bgr.val[1] = vqmovun_s16(vaddq_s16(Y, vshrq_n_s16(vmlaq_s16(vmulq_s16(U, c_gu), V, c_gv), 6)));
                    bgr.val[0] = vqmovun_s16(vaddq_s16(Y, vshrq_n_s16(vmulq_s16(U, c_bu), 6)));
                    vst3_u8(o + 3 * x, bgr);
                }
            }
#endif
            for (; x < f.width; ++x) {
                const int c = (x / 2) * f.uvPixelStride;
                yuvToRgb(yr[x], ur[c], vr[c], o[3 * x + 2], o[3 * x + 1], o[3 * x]);
            }
        }
    });
    return out;
}

bool saveJpeg(const YuvFrame& f, const std::string& path, int quality) {
    const cv::Mat bgr = yuvToBgr(f);
    return cv::imwrite(path, bgr, {cv::IMWRITE_JPEG_QUALITY, quality});
}

void renderPreview(const YuvFrame& f, int rotation, uint8_t* rgba, int outW, int outH, int outStride) {
    // Nearest-neighbour sampling straight into the (rotated) bitmap.
    const bool swap = rotation == 90 || rotation == 270;
    const int srcW = swap ? outH : outW, srcH = swap ? outW : outH;
    cv::parallel_for_(cv::Range(0, outH), [&](const cv::Range& r) {
        for (int oy = r.start; oy < r.end; ++oy) {
            uint8_t* row = rgba + size_t(oy) * outStride;
            for (int ox = 0; ox < outW; ++ox) {
                int sx, sy;  // in the small, unrotated grid
                switch (rotation) {
                    case 90: sx = oy; sy = srcH - 1 - ox; break;
                    case 180: sx = srcW - 1 - ox; sy = srcH - 1 - oy; break;
                    case 270: sx = srcW - 1 - oy; sy = ox; break;
                    default: sx = ox; sy = oy; break;
                }
                const int x = sx * f.width / srcW, y = sy * f.height / srcH;
                const int c = (x / 2) * f.uvPixelStride;
                uint8_t* p = row + 4 * ox;
                yuvToRgb(f.y[size_t(y) * f.yStride + x], f.u[size_t(y / 2) * f.uvStride + c],
                         f.v[size_t(y / 2) * f.uvStride + c], p[0], p[1], p[2]);
                p[3] = 255;
            }
        }
    });
}

void Tracker::topUpLocked(const cv::Mat& gray, bool asKeyframeAnchor) {
    if (gray.empty()) return;
    cv::Mat mask(gray.size(), CV_8UC1, cv::Scalar(255));
    for (const auto& p : pts_) cv::circle(mask, p, 10, cv::Scalar(0), -1);
    std::vector<cv::Point2f> fresh;
    const int want = 250 - int(pts_.size());
    if (want > 0) cv::goodFeaturesToTrack(gray, fresh, want, 0.01, 10, mask, 5);
    for (const auto& p : fresh) { pts_.push_back(p); anchors_.push_back(p); fromKf_.push_back(asKeyframeAnchor ? 1 : 0); }
}

void Tracker::reset() {
    std::lock_guard<std::mutex> lock(mtx_);
    prev_.release();
    pts_.clear();
    anchors_.clear();
    fromKf_.clear();
    anchorsAtKeyframe_ = 0;
    sharpRef_ = 0;
    wantFirst_ = true;
    topUp_ = true;
}

void Tracker::markKeyframe() {
    std::lock_guard<std::mutex> lock(mtx_);
    if (!prev_.empty()) topUpLocked(prev_, true);
    anchors_ = pts_;
    fromKf_.assign(pts_.size(), 1);
    anchorsAtKeyframe_ = int(pts_.size());
    wantFirst_ = false;
    topUp_ = false;
}

TrackResult Tracker::process(const YuvFrame& f) {
    std::lock_guard<std::mutex> lock(mtx_);
    TrackResult res;
    // Work at about 640 px wide.
    const int factor = std::max(1, (f.width + 320) / 640);
    const cv::Mat yfull(f.height, f.width, CV_8UC1, const_cast<uint8_t*>(f.y), size_t(f.yStride));
    cv::Mat gray;
    cv::resize(yfull, gray, cv::Size(f.width / factor, f.height / factor), 0, 0, cv::INTER_AREA);

    // Exposure statistics.
    int dark = 0, clip = 0;
    double sum = 0;
    for (int y = 0; y < gray.rows; y += 2) {
        const uint8_t* r = gray.ptr<uint8_t>(y);
        for (int x = 0; x < gray.cols; x += 2) {
            sum += r[x];
            dark += r[x] < 30;
            clip += r[x] > 250;
        }
    }
    const double n = double((gray.rows + 1) / 2) * ((gray.cols + 1) / 2);
    res.meanY = float(sum / n);
    res.darkRatio = float(dark / n);
    res.clipRatio = float(clip / n);

    // Sharpness: variance of the Laplacian (Pech-Pacheco et al. 2000).
    cv::Mat lap;
    cv::Laplacian(gray, lap, CV_16S, 3);
    cv::Scalar mu, sd;
    cv::meanStdDev(lap, mu, sd);
    res.sharpness = float(sd[0] * sd[0]);
    sharpRef_ = sharpRef_ == 0 ? res.sharpness : std::max(0.98f * sharpRef_, res.sharpness);
    const bool sharp = res.sharpness >= 0.55f * sharpRef_;
    res.sharp = sharp;

    // Pyramidal Lucas-Kanade with a forward-backward consistency check (Kalal et al. 2010).
    if (!prev_.empty() && !pts_.empty()) {
        std::vector<cv::Point2f> fwd, bwd;
        std::vector<uchar> st1, st2;
        std::vector<float> err;
        const cv::TermCriteria tc(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 20, 0.03);
        cv::calcOpticalFlowPyrLK(prev_, gray, pts_, fwd, st1, err, cv::Size(21, 21), 3, tc);
        cv::calcOpticalFlowPyrLK(gray, prev_, fwd, bwd, st2, err, cv::Size(21, 21), 3, tc);
        std::vector<cv::Point2f> keepP, keepA;
        std::vector<uint8_t> keepKf;
        for (size_t i = 0; i < pts_.size(); ++i) {
            if (!st1[i] || !st2[i] || cv::norm(bwd[i] - pts_[i]) > 1.0) continue;
            if (fwd[i].x < 0 || fwd[i].y < 0 || fwd[i].x >= gray.cols || fwd[i].y >= gray.rows) continue;
            keepP.push_back(fwd[i]);
            keepA.push_back(anchors_[i]);
            keepKf.push_back(fromKf_[i]);
        }
        pts_.swap(keepP);
        anchors_.swap(keepA);
        fromKf_.swap(keepKf);
    }

    // Parallax progress: median image motion since the last keyframe, as a fraction of 8 % of
    // the image width (about 3-5 degrees of orbit at typical scanning distances).
    std::vector<float> d;
    int kfSurvived = 0;
    for (size_t i = 0; i < pts_.size(); ++i)
        if (fromKf_[i]) { d.push_back(float(cv::norm(pts_[i] - anchors_[i]))); ++kfSurvived; }
    if (d.empty()) for (size_t i = 0; i < pts_.size(); ++i) d.push_back(float(cv::norm(pts_[i] - anchors_[i])));
    float median = 0;
    if (!d.empty()) {
        std::nth_element(d.begin(), d.begin() + d.size() / 2, d.end());
        median = d[d.size() / 2];
    }
    res.progress = std::min(1.f, median / (0.08f * gray.cols));
    const float survival = anchorsAtKeyframe_ > 0 ? float(kfSurvived) / anchorsAtKeyframe_ : 1.f;

    // Keep about 250 well-spread corners (Shi-Tomasi).
    if (topUp_ || pts_.size() < 120) {
        topUpLocked(gray, wantFirst_);
        topUp_ = false;
    }
    res.tracked = int(pts_.size());
    res.keyframe = sharp && (wantFirst_ || res.progress >= 1.f || (anchorsAtKeyframe_ > 0 && survival < 0.4f));
    prev_ = gray;
    res.points.reserve(pts_.size());
    res.anchors.reserve(pts_.size());
    for (size_t i = 0; i < pts_.size(); ++i) {
        res.points.emplace_back(pts_[i].x / gray.cols, pts_[i].y / gray.rows);
        res.anchors.emplace_back(anchors_[i].x / gray.cols, anchors_[i].y / gray.rows);
    }
    return res;
}

}  // namespace parallax
