#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "RoverVectorNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct Blob {
    int startX;
    int endX;
    int width;
    float centroidX;
};

static uint8_t compute1DOtsu(const uint8_t* row, int width) {
    int hist[256] = {0};
    int sum = 0;
    for (int i = 0; i < width; ++i) {
        hist[row[i]]++;
        sum += row[i];
    }

    int sumB = 0;
    int wB = 0;
    double maxVariance = 0.0;
    uint8_t threshold = 128;

    for (int t = 0; t < 256; ++t) {
        wB += hist[t];
        if (wB == 0) continue;
        int wF = width - wB;
        if (wF == 0) break;

        sumB += t * hist[t];
        double mB = (double)sumB / wB;
        double mF = (double)(sum - sumB) / wF;
        double variance = (double)wB * (double)wF * (mB - mF) * (mB - mF);

        if (variance > maxVariance) {
            maxVariance = variance;
            threshold = (uint8_t)t;
        }
    }
    return threshold;
}

static std::vector<Blob> extractBlobs(const uint8_t* row, int width, uint8_t threshold, int minW, int maxW) {
    std::vector<Blob> blobs;
    bool inBlob = false;
    int start = 0;

    for (int x = 0; x < width; ++x) {
        bool isWhite = (row[x] >= threshold);
        if (isWhite && !inBlob) {
            inBlob = true;
            start = x;
        } else if (!isWhite && inBlob) {
            inBlob = false;
            int end = x - 1;
            int w = end - start + 1;
            if (w >= minW && w <= maxW) {
                blobs.push_back({start, end, w, (start + end) / 2.0f});
            }
        }
    }
    if (inBlob) {
        int end = width - 1;
        int w = end - start + 1;
        if (w >= minW && w <= maxW) {
            blobs.push_back({start, end, w, (start + end) / 2.0f});
        }
    }
    return blobs;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_rover_hwverify_VerificationSessionAdapter_nativeProcessDirectScanlines(
    JNIEnv* env, jobject /* this */,
    jobject directLumaBuffer, jint width, jint height, jint rowStride,
    jbyteArray packetBytes, jint packetSize, jboolean isIFrame,
    jint farScanlineY, jint nearScanlineY, jfloat transientVyThreshold,
    jint blobWidthMin, jint blobWidthMax, jint minGauge, jint maxGauge)
{
    // ZERO-COPY: Direct pointer to physical camera DMA buffer
    uint8_t* yPlane = static_cast<uint8_t*>(env->GetDirectBufferAddress(directLumaBuffer));
    if (!yPlane) {
        return env->NewStringUTF("{\"frameValid\":false,\"reason\":\"NULL_BUFFER\"}");
    }

    // STAGE 1: I-Frame Check (only filter before scanline extraction)
    if (isIFrame) {
        // Intra-frame only, bypass motion vector processing
    }

    int fY = std::max(0, std::min(farScanlineY, height - 1));
    int nY = std::max(0, std::min(nearScanlineY, height - 1));

    // STAGE 2: Direct Scanline Slices (CPU reads only 2 rows directly from memory)
    const uint8_t* farRow = yPlane + (fY * rowStride);
    const uint8_t* nearRow = yPlane + (nY * rowStride);

    // STAGE 3: 1D Otsu
    uint8_t thresholdFar = compute1DOtsu(farRow, width);
    uint8_t thresholdNear = compute1DOtsu(nearRow, width);

    // STAGE 4: 1D Blob Extraction & Invariant Audit
    std::vector<Blob> farBlobs = extractBlobs(farRow, width, thresholdFar, blobWidthMin, blobWidthMax);
    std::vector<Blob> nearBlobs = extractBlobs(nearRow, width, thresholdNear, blobWidthMin, blobWidthMax);

    char jsonBuffer[512];

    // Audit 1: Exactly 2 blobs per line
    if (farBlobs.size() != 2 || nearBlobs.size() != 2) {
        snprintf(jsonBuffer, sizeof(jsonBuffer),
            "{\"frameValid\":false,\"reason\":\"BLOB_COUNT_FAIL\","
            "\"otsuFar\":%d,\"otsuNear\":%d,"
            "\"farBlobsCount\":%d,\"nearBlobsCount\":%d,"
            "\"farY\":%d,\"nearY\":%d}",
            thresholdFar, thresholdNear,
            (int)farBlobs.size(), (int)nearBlobs.size(),
            fY, nY);
        return env->NewStringUTF(jsonBuffer);
    }

    float flX = farBlobs[0].centroidX;
    float frX = farBlobs[1].centroidX;
    float nlX = nearBlobs[0].centroidX;
    float nrX = nearBlobs[1].centroidX;

    float farGauge = frX - flX;
    float nearGauge = nrX - nlX;

    // Audit 2: Track Gauge & Convergence Invariants
    if (farGauge < minGauge || farGauge > maxGauge ||
        nearGauge < minGauge || nearGauge > maxGauge ||
        nearGauge <= farGauge) {
        snprintf(jsonBuffer, sizeof(jsonBuffer),
            "{\"frameValid\":false,\"reason\":\"GEOMETRY_FAIL\","
            "\"otsuFar\":%d,\"otsuNear\":%d,"
            "\"farGauge\":%.1f,\"nearGauge\":%.1f,"
            "\"farY\":%d,\"nearY\":%d}",
            thresholdFar, thresholdNear,
            farGauge, nearGauge, fY, nY);
        return env->NewStringUTF(jsonBuffer);
    }

    // ALL CHECKS PASSED: Return verified 4 corners
    snprintf(jsonBuffer, sizeof(jsonBuffer),
        "{\"frameValid\":true,"
        "\"otsuFar\":%d,\"otsuNear\":%d,"
        "\"farLeft\":{\"x\":%.1f,\"y\":%d},"
        "\"farRight\":{\"x\":%.1f,\"y\":%d},"
        "\"nearLeft\":{\"x\":%.1f,\"y\":%d},"
        "\"nearRight\":{\"x\":%.1f,\"y\":%d},"
        "\"farGauge\":%.1f,\"nearGauge\":%.1f}",
        thresholdFar, thresholdNear,
        flX, fY, frX, fY, nlX, nY, nrX, nY,
        farGauge, nearGauge);

    return env->NewStringUTF(jsonBuffer);
}
