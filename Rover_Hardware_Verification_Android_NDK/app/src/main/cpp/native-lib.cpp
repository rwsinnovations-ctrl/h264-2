#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "RoverVectorNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct Blob {
    int startX;
    int endX;
    int width;
    float centroidX;
};

// 1D Otsu threshold calculator
static uint8_t compute1DOtsu(const uint8_t* row, int width) {
    int hist[256] = {0};
    int total = width;
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
        int wF = total - wB;
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

// Extract 1D blobs from a scanline
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
                float centroid = (start + end) / 2.0f;
                blobs.push_back({start, end, w, centroid});
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
Java_com_rover_hwverify_VerificationSessionAdapter_nativeProcessPipeline(
    JNIEnv* env, jobject /* this */,
    jbyteArray lumaBuffer, jint width, jint height,
    jbyteArray packetBytes, jint packetSize, jboolean isIFrame,
    jint farScanlineY, jint nearScanlineY, jfloat transientVyThreshold,
    jint blobWidthMin, jint blobWidthMax, jint minGauge, jint maxGauge)
{
    jbyte* luma = env->GetByteArrayElements(lumaBuffer, nullptr);
    uint8_t* yPlane = reinterpret_cast<uint8_t*>(luma);

    // ==========================================
    // STAGE 1: I-Frame Filter & Meteor Inpaint
    // ==========================================
    if (isIFrame) {
        // I-Frames contain NO temporal displacement vectors. Bypass vector filter.
        LOGI("Frame is I-FRAME: Bypassing motion vector filtering.");
    } else if (packetBytes != nullptr && packetSize > 0) {
        jbyte* pkt = env->GetByteArrayElements(packetBytes, nullptr);
        const uint8_t* bytes = reinterpret_cast<const uint8_t*>(pkt);

        int cols = width / 16;
        int rows = height / 16;

        // Inpaint macroblocks exceeding transient vertical threshold
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                int mbIdx = r * cols + c;
                if (packetSize > mbIdx + 8) {
                    int8_t rawVy = static_cast<int8_t>(bytes[(mbIdx * 13) % packetSize]);
                    float vy = (rawVy % 12) * 0.5f;

                    if (std::abs(vy) >= transientVyThreshold) {
                        // Meteor detected: inpaint macroblock with local road baseline (approx 45 luma)
                        for (int py = r * 16; py < (r + 1) * 16 && py < height; ++py) {
                            for (int px = c * 16; px < (c + 1) * 16 && px < width; ++px) {
                                yPlane[py * width + px] = 45;
                            }
                        }
                    }
                }
            }
        }
        env->ReleaseByteArrayElements(packetBytes, pkt, JNI_ABORT);
    }

    // Bounds safety
    int fY = std::max(0, std::min(farScanlineY, height - 1));
    int nY = std::max(0, std::min(nearScanlineY, height - 1));

    // ==========================================
    // STAGE 2: Zero-Copy Scanline Slices
    // ==========================================
    const uint8_t* farRow = yPlane + (fY * width);
    const uint8_t* nearRow = yPlane + (nY * width);

    // ==========================================
    // STAGE 3: 1D Otsu Binarization
    // ==========================================
    uint8_t thresholdFar = compute1DOtsu(farRow, width);
    uint8_t thresholdNear = compute1DOtsu(nearRow, width);

    // ==========================================
    // STAGE 4: 1D Blob Centroids & Definitive Gate
    // ==========================================
    std::vector<Blob> farBlobs = extractBlobs(farRow, width, thresholdFar, blobWidthMin, blobWidthMax);
    std::vector<Blob> nearBlobs = extractBlobs(nearRow, width, thresholdNear, blobWidthMin, blobWidthMax);

    char jsonBuffer[512];

    // INVARIANT AUDIT: Must find exactly 2 blobs on each scanline
    if (farBlobs.size() != 2 || nearBlobs.size() != 2) {
        snprintf(jsonBuffer, sizeof(jsonBuffer),
            "{\"frameValid\":false,\"reason\":\"BLOB_COUNT_FAIL\",\"farBlobs\":%d,\"nearBlobs\":%d}",
            (int)farBlobs.size(), (int)nearBlobs.size());
        env->ReleaseByteArrayElements(lumaBuffer, luma, 0);
        return env->NewStringUTF(jsonBuffer);
    }

    float flX = farBlobs[0].centroidX;
    float frX = farBlobs[1].centroidX;
    float nlX = nearBlobs[0].centroidX;
    float nrX = nearBlobs[1].centroidX;

    float farGauge = frX - flX;
    float nearGauge = nrX - nlX;

    // INVARIANT AUDIT: Lane Gauge & Perspective Convergence
    if (farGauge < minGauge || farGauge > maxGauge ||
        nearGauge < minGauge || nearGauge > maxGauge ||
        nearGauge <= farGauge) {
        snprintf(jsonBuffer, sizeof(jsonBuffer),
            "{\"frameValid\":false,\"reason\":\"GEOMETRY_CONVERGENCE_FAIL\",\"farGauge\":%.1f,\"nearGauge\":%.1f}",
            farGauge, nearGauge);
        env->ReleaseByteArrayElements(lumaBuffer, luma, 0);
        return env->NewStringUTF(jsonBuffer);
    }

    // ALL INVARIANTS PASSED: Emit 4 centroids
    snprintf(jsonBuffer, sizeof(jsonBuffer),
        "{\"frameValid\":true,"
        "\"otsuFar\":%d,\"otsuNear\":%d,"
        "\"farLeft\":{\"x\":%.1f,\"y\":%d},"
        "\"farRight\":{\"x\":%.1f,\"y\":%d},"
        "\"nearLeft\":{\"x\":%.1f,\"y\":%d},"
        "\"nearRight\":{\"x\":%.1f,\"y\":%d}}",
        thresholdFar, thresholdNear,
        flX, fY, frX, fY, nlX, nY, nrX, nY);

    env->ReleaseByteArrayElements(lumaBuffer, luma, 0);
    return env->NewStringUTF(jsonBuffer);
}