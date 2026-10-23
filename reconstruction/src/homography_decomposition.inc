/*M///////////////////////////////////////////////////////////////////////////////////////
//
// This is a homography decomposition implementation contributed to OpenCV
// by Samson Yilma. It implements the homography decomposition algorithm
// described in the research report:
// Malis, E and Vargas, M, "Deeper understanding of the homography decomposition
// for vision-based control", Research Report 6303, INRIA (2007)
//
//  IMPORTANT: READ BEFORE DOWNLOADING, COPYING, INSTALLING OR USING.
//
//  By downloading, copying, installing or using the software you agree to this license.
//  If you do not agree to this license, do not download, install,
//  copy or use the software.
//
//
//                           License Agreement
//                For Open Source Computer Vision Library
//
// Copyright (C) 2014, Samson Yilma (samson_yilma@yahoo.com), all rights reserved.
// Copyright (C) 2018, Intel Corporation, all rights reserved.
//
// Third party copyrights are property of their respective owners.
//
// Redistribution and use in source and binary forms, with or without modification,
// are permitted provided that the following conditions are met:
//
//   * Redistribution's of source code must retain the above copyright notice,
//     this list of conditions and the following disclaimer.
//
//   * Redistribution's in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//
//   * The name of the copyright holders may not be used to endorse or promote products
//     derived from this software without specific prior written permission.
//
// This software is provided by the copyright holders and contributors "as is" and
// any express or implied warranties, including, but not limited to, the implied
// warranties of merchantability and fitness for a particular purpose are disclaimed.
// In no event shall the Intel Corporation or contributors be liable for any direct,
// indirect, incidental, special, exemplary, or consequential damages
// (including, but not limited to, procurement of substitute goods or services;
// loss of use, data, or profits; or business interruption) however caused
// and on any theory of liability, whether in contract, strict liability,
// or tort (including negligence or otherwise) arising in any way out of
// the use of this software, even if advised of the possibility of such damage.
//
//M*/

// Adapted from official OpenCV 4.12.0 homography_decomp.cpp, INRIA method.
// Replaced cv types with frozen rf fixed-size math; added numerical guards.
struct Motion {
    Mat3 r;
    Vec3 t, n;
};
bool finite(const Mat3 &h) {
    for (double x : h)
        if (!std::isfinite(x))
            return false;
    return true;
}
double root(double x) {
    if (!std::isfinite(x) || x < -1e-10)
        throw Error("Invalid decomposition radicand");
    return std::sqrt(std::max(0., x));
}
double minorOpp(const Mat3 &s, int row, int col) {
    int x1 = col == 0 ? 1 : 0, x2 = col == 2 ? 1 : 2, y1 = row == 0 ? 1 : 0, y2 = row == 2 ? 1 : 2;
    return s[y1 * 3 + x2] * s[y2 * 3 + x1] - s[y1 * 3 + x1] * s[y2 * 3 + x2];
}
int sign(double x) { return x >= 0 ? 1 : -1; }
std::vector<Motion> decompose(Mat3 h) {
    if (!finite(h))
        throw Error("Nonfinite H");
    double magnitude = 0;
    for (double x : h)
        magnitude = std::max(magnitude, std::abs(x));
    if (magnitude < 1e-300)
        throw Error("Zero H");
    for (double &x : h)
        x /= magnitude;
    auto ev = eigen<3>(multiply(transpose(h), h));
    double mid = root(ev.values[1]);
    if (mid < 1e-12 || ev.values[0] <= 1e-12 * ev.values[2])
        throw Error("Singular H");
    for (double &x : h)
        x /= mid;
    if (determinant(h) < 0)
        for (double &x : h)
            x = -x;
    Mat3 s = multiply(transpose(h), h);
    for (int i : {0, 4, 8})
        s[i] -= 1;
    double max = 0;
    for (double x : s)
        max = std::max(max, std::abs(x));
    if (max < .001)
        return {{h, {0, 0, 0}, {0, 0, 0}}};
    double m00 = minorOpp(s, 0, 0), m11 = minorOpp(s, 1, 1), m22 = minorOpp(s, 2, 2);
    double a = root(m00), b = root(m11), c = root(m22);
    int e12 = sign(minorOpp(s, 1, 2)), e02 = sign(minorOpp(s, 0, 2)), e01 = sign(minorOpp(s, 0, 1));
    int ix = 0;
    if (std::abs(s[4]) > std::abs(s[ix * 4]))
        ix = 1;
    if (std::abs(s[8]) > std::abs(s[ix * 4]))
        ix = 2;
    Vec3 na, nb;
    if (ix == 0) {
        na = {s[0], s[1] + c, s[2] + e12 * b};
        nb = {s[0], s[1] - c, s[2] - e12 * b};
    } else if (ix == 1) {
        na = {s[1] + c, s[4], s[5] - e02 * a};
        nb = {s[1] - c, s[4], s[5] + e02 * a};
    } else {
        na = {s[2] + e01 * b, s[5] + a, s[8]};
        nb = {s[2] - e01 * b, s[5] - a, s[8]};
    }
    double trace = s[0] + s[4] + s[8], v = 2 * root(1 + trace - m00 - m11 - m22);
    if (v < 1e-12)
        throw Error("Degenerate decomposition");
    double r = root(2 + trace + v), nt = root(2 + trace - v);
    na = unit(na);
    nb = unit(nb);
    Vec3 ta = scale(sub(scale(nb, sign(s[ix * 4]) * r), scale(na, nt)), .5 * nt);
    Vec3 tb = scale(sub(scale(na, sign(s[ix * 4]) * r), scale(nb, nt)), .5 * nt);
    std::vector<Motion> out;
    for (auto pair : {std::pair{ta, na}, std::pair{tb, nb}}) {
        auto ts = pair.first, n = pair.second;
        Mat3 q = identity();
        double tv[3]{ts.x, ts.y, ts.z}, nv[3]{n.x, n.y, n.z};
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                q[i * 3 + j] -= 2 / v * tv[i] * nv[j];
        auto rot = multiply(h, q);
        if (determinant(rot) < 0)
            for (double &x : rot)
                x = -x;
        auto t = multiply(rot, ts);
        out.push_back({rot, t, n});
        out.push_back({rot, scale(t, -1), scale(n, -1)});
    }
    return out;
}
