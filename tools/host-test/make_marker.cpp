// Writes a printable ArUco scale marker (DICT_4X4_50) as an A4 SVG in millimetres.
//   make_marker <out.svg> [id=0] [edge_mm=60]
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/objdetect/aruco_detector.hpp>

#include <cstdio>
#include <cstdlib>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <out.svg> [id=0] [edge_mm=60]\n", argv[0]);
        return 2;
    }
    const int id = argc > 2 ? std::atoi(argv[2]) : 0;
    const double edge = argc > 3 ? std::atof(argv[3]) : 60.0;
    const auto dict = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
    cv::Mat m;  // 6x6 cells: black border + 4x4 bits
    cv::aruco::generateImageMarker(dict, id, 6, m, 1);
    FILE* f = std::fopen(argv[1], "w");
    if (!f) return 1;
    const double W = 210, H = 297, cell = edge / 6, x0 = (W - edge) / 2, y0 = 60;
    std::fprintf(f,
                 "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%gmm\" height=\"%gmm\" viewBox=\"0 0 %g %g\">\n"
                 "<rect width=\"%g\" height=\"%g\" fill=\"#fff\"/>\n",
                 W, H, W, H, W, H);
    for (int r = 0; r < 6; ++r)
        for (int c = 0; c < 6; ++c)
            if (m.at<uchar>(r, c) < 128)
                std::fprintf(f, "<rect x=\"%.3f\" y=\"%.3f\" width=\"%.3f\" height=\"%.3f\" fill=\"#000\"/>\n",
                             x0 + c * cell, y0 + r * cell, cell + 0.01, cell + 0.01);
    std::fprintf(f,
                 "<g font-family=\"sans-serif\" font-size=\"4.5\" fill=\"#333\" text-anchor=\"middle\">\n"
                 "<text x=\"105\" y=\"%.1f\">Parallax scale marker · ArUco DICT_4X4_50 · id %d · %.0f mm</text>\n"
                 "<text x=\"105\" y=\"%.1f\">Print at 100 %% (no fit to page). Measure the black square's edge and enter that</text>\n"
                 "<text x=\"105\" y=\"%.1f\">value under Scans → ruler. Keep the white margin; lay it flat next to the object.</text>\n"
                 "</g>\n</svg>\n",
                 y0 + edge + 16, id, edge, y0 + edge + 24, y0 + edge + 30);
    std::fclose(f);
    return 0;
}
