// Copyright (c) 2026 YEZVI contributors.
// SPDX-License-Identifier: Apache-2.0
// Experimental metadata bridge. Call only while the OfflineStream is alive,
// from its single owner thread after decoding and before native release.

#include <cmath>

#include "sherpa-onnx/csrc/offline-stream.h"
#include "sherpa-onnx/jni/common.h"

SHERPA_ONNX_EXTERN_C
JNIEXPORT jfloatArray JNICALL
Java_com_yezvi_assistant_data_speech_SherpaTokenScores_readTokenLogProbabilities(
    JNIEnv *env, jobject /*obj*/, jlong stream_ptr) {
  if (stream_ptr == 0 || env->ExceptionCheck()) {
    return nullptr;
  }
  try {
    const auto *stream =
        reinterpret_cast<const sherpa_onnx::OfflineStream *>(stream_ptr);
    const auto &result = stream->GetResult();
    const auto &scores = result.ys_log_probs;
    constexpr size_t kMaxScores = 2048;
    constexpr float kPositiveRoundingTolerance = 1.0e-5f;
    if (scores.empty() || scores.size() > kMaxScores ||
        scores.size() != result.tokens.size()) {
      return nullptr;
    }
    for (const float score : scores) {
      if (!std::isfinite(score) || score > kPositiveRoundingTolerance) {
        return nullptr;
      }
    }
    auto values = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (values == nullptr) {
      return nullptr;
    }
    env->SetFloatArrayRegion(values, 0, static_cast<jsize>(scores.size()),
                             scores.data());
    return values;
  } catch (...) {
    // Metadata must not abort an otherwise successful transcription. No logs.
    return nullptr;
  }
}
