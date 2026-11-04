// Parallax host test: renders a textured synthetic scene from a two-camera rig orbiting it,
// runs the full reconstruction core, and checks the result against ground truth.
//   synth_test <work-dir> [views=36] [--single] [--no-dense]
// Exit code 0 when every check passes.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/calib3d.hpp>
#include <opencv2/core/utility.hpp>
#include <opencv2/imgcodecs.hpp>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <random>
#include <string>

#include "core.hpp"

#if __has_include(<opencv2/objdetect/aruco_detector.hpp>)
#include <opencv2/objdetect/aruco_detector.hpp>
#define SYNTH_HAVE_ARUCO 1
#endif

namespace fs = std::filesystem;
using namespace parallax;

namespace {

// ---- Procedural texture: multi-octave 3-D value noise -----------------------------------
uint32_t hash3(int x, int y, int z, uint32_t seed) {
    uint32_t h = seed ^ (uint32_t(x) * 73856093u) ^ (uint32_t(y) * 19349663u) ^ (uint32_t(z) * 83492791u);
    h ^= h >> 13; h *= 0x5bd1e995u; h ^= h >> 15;
    return h;
}
double vnoise(const cv::Vec3d& p, uint32_t seed) {
    const int x = int(std::floor(p[0])), y = int(std::floor(p[1])), z = int(std::floor(p[2]));
    const double fx = p[0] - x, fy = p[1] - y, fz = p[2] - z;
    auto s = [](double t) { return t * t * (3 - 2 * t); };
    const double u = s(fx), v = s(fy), w = s(fz);
    double acc = 0;
    for (int dz = 0; dz < 2; ++dz)
        for (int dy = 0; dy < 2; ++dy)
            for (int dx = 0; dx < 2; ++dx) {
                const double val = (hash3(x + dx, y + dy, z + dz, seed) & 0xFFFF) / 65535.0;
                acc += val * (dx ? u : 1 - u) * (dy ? v : 1 - v) * (dz ? w : 1 - w);
            }
    return acc;
}
cv::Vec3d texture(const cv::Vec3d& X, uint32_t material) {
    cv::Vec3d c(0, 0, 0);
    for (int ch = 0; ch < 3; ++ch) {
        double a = 0, amp = 0.5, freq = 18.0;
        for (int o = 0; o < 5; ++o) {
            a += amp * vnoise(X * freq, material * 31 + ch * 7 + o);
            amp *= 0.55;
            freq *= 2.3;
        }
        c[ch] = a;
    }
    return c;
}

// ---- Optional printed scale marker on the ground ------------------------------------------
// ArUco DICT_4X4_50 id 7, 60 mm black square centred at (0.20, -0.20), with a one-cell white
// quiet zone (8 x 8 cells of 10 mm).
cv::Mat gMarker;  // 8 x 8 cells, 0 = black, 255 = white; empty when off
const cv::Vec2d kMarkerCentre(0.20, -0.20);
constexpr double kMarkerSize = 0.060;

bool markerColour(const cv::Vec3d& X, double& value) {
    if (gMarker.empty()) return false;
    const double cell = kMarkerSize / 6.0;
    const double u = (X[0] - kMarkerCentre[0]) / cell + 4.0, v = (kMarkerCentre[1] - X[1]) / cell + 4.0;
    if (u < 0 || v < 0 || u >= 8 || v >= 8) return false;
    value = gMarker.at<uchar>(int(v), int(u)) > 127 ? 0.95 : 0.05;
    return true;
}

// ---- Scene: ground plane z = 0, a box and a sphere ----------------------------------------
const cv::Vec3d kBoxMin(-0.10, -0.07, 0.0), kBoxMax(0.06, 0.07, 0.14);
const cv::Vec3d kSphere(0.10, 0.09, 0.07);
const double kSphereR = 0.07;

double sceneDistance(const cv::Vec3d& p) {
    const double plane = std::abs(p[2]);
    const cv::Vec3d c = 0.5 * (kBoxMin + kBoxMax), h = 0.5 * (kBoxMax - kBoxMin);
    cv::Vec3d q;
    for (int i = 0; i < 3; ++i) q[i] = std::abs(p[i] - c[i]) - h[i];
    cv::Vec3d qp(std::max(q[0], 0.0), std::max(q[1], 0.0), std::max(q[2], 0.0));
    const double box = cv::norm(qp) + std::min(std::max(q[0], std::max(q[1], q[2])), 0.0);
    const double sphere = std::abs(cv::norm(p - kSphere) - kSphereR);
    return std::min(plane, std::min(std::abs(box), sphere));
}

bool intersect(const cv::Vec3d& o, const cv::Vec3d& d, double& tHit, cv::Vec3d& n, uint32_t& mat) {
    tHit = 1e30;
    if (d[2] < -1e-9) {
        const double t = -o[2] / d[2];
        const cv::Vec3d p = o + t * d;
        if (t > 0 && std::abs(p[0]) < 1.5 && std::abs(p[1]) < 1.5) { tHit = t; n = {0, 0, 1}; mat = 1; }
    }
    double t0 = -1e30, t1 = 1e30;
    int axis = -1;
    bool ok = true;
    for (int i = 0; i < 3 && ok; ++i) {
        if (std::abs(d[i]) < 1e-12) { ok = o[i] >= kBoxMin[i] && o[i] <= kBoxMax[i]; continue; }
        double a = (kBoxMin[i] - o[i]) / d[i], b = (kBoxMax[i] - o[i]) / d[i];
        if (a > b) std::swap(a, b);
        if (a > t0) { t0 = a; axis = i; }
        t1 = std::min(t1, b);
        ok = t0 <= t1;
    }
    if (ok && t0 > 0 && t0 < tHit && axis >= 0) {
        tHit = t0;
        n = {0, 0, 0};
        n[axis] = d[axis] > 0 ? -1 : 1;
        mat = 2;
    }
    const cv::Vec3d oc = o - kSphere;
    const double b = oc.dot(d), c = oc.dot(oc) - kSphereR * kSphereR, disc = b * b - c;
    if (disc > 0) {
        const double t = -b - std::sqrt(disc);
        if (t > 0 && t < tHit) { tHit = t; n = cv::normalize(o + t * d - kSphere); mat = 3; }
    }
    return tHit < 1e29;
}

struct Pose {
    cv::Matx33d R;  // world -> camera
    cv::Vec3d C;    // centre
};

// Renders with the camera pose of each image row, so a moving rolling-shutter camera is modelled.
cv::Mat render(const CameraModel& m, const std::function<Pose(int row)>& poseAtRow) {
    std::vector<cv::Point2f> px;
    px.reserve(size_t(m.width) * m.height);
    for (int y = 0; y < m.height; ++y)
        for (int x = 0; x < m.width; ++x) px.emplace_back(float(x), float(y));
    std::vector<cv::Point2f> nrm;
    const cv::Matx33d K(m.fx, 0, m.cx, 0, m.fy, m.cy, 0, 0, 1);
    const cv::Mat D(cv::Matx<double, 1, 5>(m.k1, m.k2, m.p1, m.p2, m.k3));
    cv::undistortPoints(px, nrm, K, D, cv::noArray(), cv::noArray(),
                        cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 1e-10));
    cv::Mat img(m.height, m.width, CV_8UC3);
    const cv::Vec3d light = cv::normalize(cv::Vec3d(0.4, -0.3, 1.0));
    cv::parallel_for_(cv::Range(0, m.height), [&](const cv::Range& r) {
        for (int y = r.start; y < r.end; ++y) {
            const Pose pose = poseAtRow(y);
            const cv::Matx33d Rt = pose.R.t();
            const cv::Vec3d& C = pose.C;
            for (int x = 0; x < m.width; ++x) {
                const cv::Point2f& q = nrm[size_t(y) * m.width + x];
                const cv::Vec3d d = cv::normalize(Rt * cv::Vec3d(q.x, q.y, 1.0));
                double th;
                cv::Vec3d n;
                uint32_t mat;
                cv::Vec3d col(40, 45, 55);  // background
                if (intersect(C, d, th, n, mat)) {
                    const cv::Vec3d X = C + th * d;
                    const double shade = 0.45 + 0.55 * std::max(0.0, n.dot(light));
                    double mv;
                    if (mat == 1 && markerColour(X, mv)) col = 255.0 * shade * cv::Vec3d(mv, mv, mv);
                    else col = 255.0 * shade * texture(X, mat);
                }
                img.at<cv::Vec3b>(y, x) = cv::Vec3b(cv::saturate_cast<uchar>(col[2]), cv::saturate_cast<uchar>(col[1]),
                                                    cv::saturate_cast<uchar>(col[0]));
            }
        }
    });
    cv::Mat noise(img.size(), CV_16SC3);
    cv::randn(noise, 0, 2.0);
    cv::Mat out;
    img.convertTo(out, CV_16SC3);
    out += noise;
    out.convertTo(img, CV_8UC3);
    return img;
}

cv::Matx33d lookAt(const cv::Vec3d& C, const cv::Vec3d& target) {
    const cv::Vec3d z = cv::normalize(target - C);
    const cv::Vec3d x = cv::normalize(z.cross(cv::Vec3d(0, 0, 1)));  // camera x: right
    const cv::Vec3d y = z.cross(x);                                  // camera y: down
    return {x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
}

constexpr double kSecondsPerView = 0.8;

// Orbit around the scene; with `wobble`, the aim also sways as a hand-held phone does
// (about 0.25 rad/s peak), which a rolling shutter turns into row-dependent distortion.
Pose trajectory(double t, int views, bool wobble) {
    const double a = 2 * 3.14159265358979 * t / (views * kSecondsPerView);
    const double radius = 0.55 + 0.05 * std::sin(3 * a), height = 0.32 + 0.04 * std::cos(2 * a);
    const cv::Vec3d C(radius * std::cos(a), radius * std::sin(a), height);
    cv::Vec3d target(0, 0, 0.06);
    if (wobble) target += 0.04 * cv::Vec3d(std::sin(2 * 3.14159265358979 * 0.5 * t), std::cos(2 * 3.14159265358979 * 0.37 * t), 0);
    return {lookAt(C, target), C};
}

// Angular rate in camera coordinates, as a gyroscope reports it: R(t+h) R(t)^T = exp(-[w h]x).
cv::Vec3d gyroAt(double t, int views, bool wobble) {
    const double h = 1e-4;
    cv::Vec3d rv;
    cv::Rodrigues(cv::Mat(trajectory(t + h, views, wobble).R * trajectory(t, views, wobble).R.t()), rv);
    return -rv * (1.0 / h);
}

// Umeyama similarity: dst ~ s R src + t.
void umeyama(const std::vector<cv::Vec3d>& src, const std::vector<cv::Vec3d>& dst, double& s, cv::Matx33d& R, cv::Vec3d& t) {
    cv::Vec3d ms(0, 0, 0), md(0, 0, 0);
    for (size_t i = 0; i < src.size(); ++i) { ms += src[i]; md += dst[i]; }
    ms *= 1.0 / src.size();
    md *= 1.0 / dst.size();
    cv::Matx33d S = cv::Matx33d::zeros();
    double vs = 0;
    for (size_t i = 0; i < src.size(); ++i) {
        const cv::Vec3d a = src[i] - ms, b = dst[i] - md;
        S += cv::Matx33d(b[0] * a[0], b[0] * a[1], b[0] * a[2], b[1] * a[0], b[1] * a[1], b[1] * a[2], b[2] * a[0],
                         b[2] * a[1], b[2] * a[2]);
        vs += a.dot(a);
    }
    S *= 1.0 / src.size();
    vs /= src.size();
    cv::Mat w, u, vt;
    cv::SVD::compute(cv::Mat(S), w, u, vt);
    cv::Mat E = cv::Mat::eye(3, 3, CV_64F);
    if (cv::determinant(u) * cv::determinant(vt) < 0) E.at<double>(2, 2) = -1;
    R = cv::Matx33d(cv::Mat(u * E * vt));
    s = (w.at<double>(0) + w.at<double>(1) + E.at<double>(2, 2) * w.at<double>(2)) / vs;
    t = md - s * (R * ms);
}

std::vector<cv::Vec3f> readPly(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    std::string line;
    size_t n = 0;
    while (std::getline(f, line)) {
        if (line.rfind("element vertex", 0) == 0) n = std::stoul(line.substr(15));
        if (line == "end_header") break;
    }
    std::vector<cv::Vec3f> pts(n);
    for (size_t i = 0; i < n; ++i) {
        char rec[27];
        f.read(rec, 27);
        std::memcpy(&pts[i][0], rec, 12);
    }
    return pts;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <work-dir> [views] [--single] [--no-dense] [--rs] [--no-rs-model]\n", argv[0]);
        return 2;
    }
    const fs::path work = argv[1];
    int views = 36;
    bool rig = true, dense = true, rs = false, rsModel = true, marker = false;
    for (int a = 2; a < argc; ++a) {
        if (!std::strcmp(argv[a], "--single")) rig = false;
        else if (!std::strcmp(argv[a], "--no-dense")) dense = false;
        else if (!std::strcmp(argv[a], "--rs")) rs = true;  // rolling-shutter images, hand-held sway
        else if (!std::strcmp(argv[a], "--no-rs-model")) rsModel = false;
        else if (!std::strcmp(argv[a], "--marker")) marker = true;  // printed 60 mm ArUco on the ground
        else views = std::atoi(argv[a]);
    }
    fs::create_directories(work / "images");
    fs::create_directories(work / "out");

    CameraModel wide;
    wide.id = 0; wide.width = 1280; wide.height = 960;
    wide.fx = wide.fy = 1050; wide.cx = 641.3; wide.cy = 478.2;
    wide.k1 = 0.04; wide.k2 = -0.06;
    wide.calibrated = true; wide.hasLensPosition = true;
    CameraModel ultra = wide;
    ultra.id = 2; ultra.fx = ultra.fy = 560; ultra.cx = 638.9; ultra.cy = 481.0;
    ultra.k1 = -0.12; ultra.k2 = 0.03;
    // 12 mm along the device x axis (which maps to camera -y in portrait for a 90 degree sensor).
    ultra.lensX = 0.012;

    // The pipeline starts from slightly wrong intrinsics, as a phone without a factory
    // calibration would provide: focal 3 % off, no distortion.
    CameraModel wideGuess = wide, ultraGuess = ultra;
    wideGuess.fx = wideGuess.fy = wide.fx * 1.03; wideGuess.k1 = wideGuess.k2 = 0; wideGuess.calibrated = false;
    ultraGuess.fx = ultraGuess.fy = ultra.fx * 0.97; ultraGuess.k1 = ultraGuess.k2 = 0; ultraGuess.calibrated = false;

    Options opt;
    opt.dense = dense;
    opt.rollingShutter = rsModel;
    if (marker) {
#ifdef SYNTH_HAVE_ARUCO
        cv::Mat m;
        cv::aruco::generateImageMarker(cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50), 7, 6, m, 1);
        gMarker = cv::Mat(8, 8, CV_8U, cv::Scalar(255));
        m.copyTo(gMarker(cv::Rect(1, 1, 6, 6)));
        opt.markerSizeMm = kMarkerSize * 1000;
#else
        std::fprintf(stderr, "--marker needs OpenCV with objdetect/ArUco\n");
        return 2;
#endif
    }
    const double skew = rs ? 0.030 : 0.0;  // 30 ms readout, typical of phone sensors
    Reconstructor rec(opt);
    rec.addCamera(wideGuess);
    if (rig) rec.addCamera(ultraGuess);
    std::vector<cv::Vec3d> gtCentres;
    std::vector<cv::Vec3d> gtUp;
    const auto t0 = std::chrono::steady_clock::now();
    for (int v = 0; v < views; ++v) {
        const double tv = v * kSecondsPerView;  // capture time of the middle row
        const Pose mid = trajectory(tv, views, rs);
        auto rowTime = [&](int row, const CameraModel& m) { return tv + skew * (double(row) / (m.height - 1) - 0.5); };
        const char* tag = rs ? (marker ? "_rs_m" : "_rs") : (marker ? "_m" : "");
        char name[64];
        std::snprintf(name, sizeof name, "w_%03d_of_%03d%s.jpg", v, views, tag);  // cached renders must match
        const std::string pw = (work / "images" / name).string();
        if (!fs::exists(pw))
            cv::imwrite(pw, render(wide, [&](int row) { return trajectory(rowTime(row, wide), views, rs); }),
                        {cv::IMWRITE_JPEG_QUALITY, 95});
        ImageInput iw;
        iw.path = pw;
        iw.cameraId = 0;
        iw.rigGroup = rig ? v : -1;
        const cv::Vec3d up = mid.R * cv::Vec3d(0, 0, 1);
        iw.upX = up[0]; iw.upY = up[1]; iw.upZ = up[2];
        iw.time = 10.0 + tv;
        iw.rollingShutterSkew = skew;
        const cv::Vec3d g = gyroAt(tv, views, rs);
        iw.gyroX = g[0]; iw.gyroY = g[1]; iw.gyroZ = g[2];
        iw.hasGyro = true;
        rec.addImage(iw);
        gtCentres.push_back(mid.C);
        if (rig) {
            // Second lens 12 mm along camera -y (upwards in the image), parallel optical axes.
            auto offset = [](const Pose& p) { return Pose{p.R, p.C + p.R.t() * cv::Vec3d(0, -0.012, 0)}; };
            std::snprintf(name, sizeof name, "u_%03d_of_%03d%s.jpg", v, views, tag);
            const std::string pu = (work / "images" / name).string();
            if (!fs::exists(pu))
                cv::imwrite(pu, render(ultra, [&](int row) { return offset(trajectory(rowTime(row, ultra), views, rs)); }),
                            {cv::IMWRITE_JPEG_QUALITY, 95});
            ImageInput iu = iw;
            iu.path = pu;
            iu.cameraId = 2;
            rec.addImage(iu);
            gtCentres.push_back(offset(mid).C);
        }
    }
    const auto t1 = std::chrono::steady_clock::now();
    std::printf("rendered %zu images in %.1f s\n", gtCentres.size(), std::chrono::duration<double>(t1 - t0).count());

    Result r = rec.run((work / "out").string(), work.string(), [](const std::string& stage, float f) {
        static std::string last;
        static auto since = std::chrono::steady_clock::now();
        if (stage != last) {
            const auto now = std::chrono::steady_clock::now();
            // Elapsed time belongs to the stage that just ended.
            if (!last.empty()) std::printf("  %-22s %6.1f s\n", last.c_str(), std::chrono::duration<double>(now - since).count());
            last = stage;
            since = now;
        }
        return true;
    });
    const auto t2 = std::chrono::steady_clock::now();
    std::printf("reconstruction %.1f s\n%s\n", std::chrono::duration<double>(t2 - t1).count(), resultToJson(r).c_str());
    if (!r.ok) return 1;

    // Pose accuracy after a similarity alignment.
    std::vector<cv::Vec3d> est, gt;
    for (size_t k = 0; k < r.cameraImages.size(); ++k) {
        est.emplace_back(r.cameraCentres[3 * k], r.cameraCentres[3 * k + 1], r.cameraCentres[3 * k + 2]);
        gt.push_back(gtCentres[r.cameraImages[k]]);
    }
    double s;
    cv::Matx33d R;
    cv::Vec3d t;
    umeyama(est, gt, s, R, t);
    double se = 0;
    for (size_t k = 0; k < est.size(); ++k) se += std::pow(cv::norm(s * (R * est[k]) + t - gt[k]), 2);
    const double rmsPos = std::sqrt(se / est.size());
    // Gravity: the exported +Z should map to the true +Z.
    const cv::Vec3d zMapped = R * cv::Vec3d(0, 0, 1);
    const double upErrDeg = std::acos(std::min(1.0, zMapped[2])) * 180 / 3.14159265358979;
    std::printf("registered %d/%d, pose RMS %.2f mm, alignment scale %.4f, up error %.2f deg\n", r.imagesRegistered,
                r.imagesTotal, rmsPos * 1000, s, upErrDeg);

    bool pass = r.imagesRegistered >= int(0.9 * r.imagesTotal) && rmsPos < 0.005 && upErrDeg < 2.0;
    if (marker) {
        // Single camera, so the scale can only come from the marker: exported units are metres.
        const bool ok = r.scaleSource == "marker" && std::abs(s - 1.0) < 0.01;
        std::printf("marker scale %s (source %s, alignment scale %.4f should be 1)\n", ok ? "OK" : "FAIL",
                    r.scaleSource.c_str(), s);
        pass = pass && ok;
    }
    if (rs) {
        // Uncorrected (--no-rs-model) this scene gives about 1.7 mm and must fail; corrected, it
        // reaches the global-shutter level (~0.4 mm).
        const bool rsOk = rmsPos < 0.001;
        std::printf("rolling-shutter correction %s (pose RMS must be under 1 mm)\n", rsOk ? "OK" : "FAIL");
        pass = pass && rsOk;
    }
    if (rig) {
        const bool scaleOk = r.metresPerUnit > 0 && std::abs(s - 1.0) < 0.05;
        std::printf("metric scale %s (alignment scale %.4f should be 1)\n", scaleOk ? "OK" : "FAIL", s);
        pass = pass && scaleOk;
    }

    if (dense) {
        auto pts = readPly((work / "out" / "dense.ply").string());
        std::vector<double> err;
        for (const auto& p : pts) err.push_back(sceneDistance(s * (R * cv::Vec3d(p[0], p[1], p[2])) + t));
        std::sort(err.begin(), err.end());
        const double med = err.empty() ? 1e9 : err[err.size() / 2], p90 = err.empty() ? 1e9 : err[err.size() * 9 / 10];
        std::printf("dense: %zu points, surface error median %.2f mm, 90th pct %.2f mm\n", pts.size(), med * 1000, p90 * 1000);
        pass = pass && pts.size() > 20000 && med < 0.002 && p90 < 0.006;
    }
    std::printf("%s\n", pass ? "PASS" : "FAIL");
    return pass ? 0 : 1;
}
