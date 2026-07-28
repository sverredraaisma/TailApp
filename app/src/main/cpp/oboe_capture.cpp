/*
 * Low-latency mic capture for the BeatLight analysis pipeline.
 *
 * Oboe picks AAudio or OpenSL ES per device and gives us the lowest input
 * latency the platform can manage. The audio callback does exactly one thing —
 * copy its burst into a lock-free ring buffer and return — because anything
 * else (a JNI call, an allocation, a lock) risks an underrun and an audible
 * glitch. The JVM side drains the buffer at its own pace over JNI.
 */

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include <oboe/Oboe.h>

#include "ring_buffer.h"

#define LOG_TAG "OboeCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

class CaptureEngine : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    CaptureEngine(int32_t requested_sample_rate, int32_t ring_capacity)
        : requested_sample_rate_(requested_sample_rate), ring_(ring_capacity) {}

    ~CaptureEngine() override { stop(); }

    oboe::Result start() {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        if (stream_ != nullptr) return oboe::Result::OK;

        ring_.clear();

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(requested_sample_rate_)
            // Ask Oboe to resample for us when the device will not run at our
            // analysis rate; the JVM side still checks the rate it actually got.
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            // Unprocessed keeps AGC/noise suppression/echo cancellation out of the
            // signal — they wreck the dynamics beat and drop detection rely on.
            ->setInputPreset(oboe::InputPreset::Unprocessed)
            ->setDataCallback(this)
            ->setErrorCallback(this);

        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            // Not every device exposes an Unprocessed input; VoiceRecognition is
            // the next-least-processed preset and is universally available.
            LOGW("Unprocessed input failed (%s), retrying with VoiceRecognition",
                 oboe::convertToText(result));
            builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
            result = builder.openStream(stream_);
        }
        if (result != oboe::Result::OK) {
            LOGE("openStream failed: %s", oboe::convertToText(result));
            stream_.reset();
            return result;
        }

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            return result;
        }

        LOGI("capture started: %d Hz, %d ch, burst %d frames, %s",
             stream_->getSampleRate(), stream_->getChannelCount(),
             stream_->getFramesPerBurst(),
             stream_->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared");
        return oboe::Result::OK;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        if (stream_ == nullptr) return;
        stream_->stop();
        stream_->close();
        stream_.reset();
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audio_data,
                                          int32_t num_frames) override {
        // Mono float in, so frames == samples. Nothing here may allocate or block.
        ring_.write(static_cast<const float *>(audio_data), num_frames);
        (void)stream;
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override {
        (void)stream;
        // Disconnects happen when the route changes (headset in/out). Flag it and
        // let the JVM side restart: reopening from the error thread races stop().
        LOGW("stream error after close: %s", oboe::convertToText(error));
        disconnected_.store(true, std::memory_order_release);
    }

    int32_t read(float *dest, int32_t count) { return ring_.read(dest, count); }

    /**
     * Consumer-side scratch for the JNI hand-off, grown once and then reused.
     * The analysis loop calls in at ~200 Hz with a constant count; a fresh
     * `std::vector` per call was ~8 KB malloc'd *and* zero-filled every time,
     * for a buffer whose contents are overwritten immediately.
     *
     * Single-consumer, like `read` itself.
     */
    int32_t readIntoScratch(int32_t count) {
        if (static_cast<int32_t>(read_scratch_.size()) < count) {
            read_scratch_.resize(static_cast<size_t>(count));
        }
        return ring_.read(read_scratch_.data(), count);
    }

    const float *scratchData() const { return read_scratch_.data(); }

    int64_t overruns() const { return ring_.overruns(); }

    bool consumeDisconnected() { return disconnected_.exchange(false, std::memory_order_acq_rel); }

    int32_t sampleRate() {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        return stream_ != nullptr ? stream_->getSampleRate() : requested_sample_rate_;
    }

    /** Best-effort input latency: the stream's own estimate plus its buffer depth. */
    float latencyMillis() {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        if (stream_ == nullptr) return 0.0f;
        auto latency = stream_->calculateLatencyMillis();
        if (!latency) return 0.0f;
        return static_cast<float>(latency.value());
    }

private:
    const int32_t requested_sample_rate_;
    FloatRingBuffer ring_;
    std::vector<float> read_scratch_;

    std::mutex stream_mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> disconnected_{false};
};

inline CaptureEngine *engine_of(jlong handle) {
    return reinterpret_cast<CaptureEngine *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeCreate(JNIEnv *, jobject, jint sample_rate,
                                                    jint ring_capacity) {
    auto *engine = new CaptureEngine(sample_rate, ring_capacity);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete engine_of(handle);
}

/** Returns 0 on success, or the negated oboe::Result so Kotlin can log it. */
JNIEXPORT jint JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeStart(JNIEnv *, jobject, jlong handle) {
    oboe::Result result = engine_of(handle)->start();
    return result == oboe::Result::OK ? 0 : static_cast<jint>(result);
}

JNIEXPORT void JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeStop(JNIEnv *, jobject, jlong handle) {
    engine_of(handle)->stop();
}

JNIEXPORT jint JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeRead(JNIEnv *env, jobject, jlong handle,
                                                  jfloatArray out, jint count) {
    if (count <= 0) return 0;
    auto *engine = engine_of(handle);

    // Copy into a reusable scratch and hand it over in one SetFloatArrayRegion:
    // GetFloatArrayElements could pin or copy the whole array on every call.
    const int32_t read = engine->readIntoScratch(count);
    if (read > 0) {
        env->SetFloatArrayRegion(out, 0, read, engine->scratchData());
    }
    return read;
}

JNIEXPORT jint JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeSampleRate(JNIEnv *, jobject, jlong handle) {
    return engine_of(handle)->sampleRate();
}

JNIEXPORT jfloat JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeLatencyMillis(JNIEnv *, jobject, jlong handle) {
    return engine_of(handle)->latencyMillis();
}

JNIEXPORT jlong JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeOverruns(JNIEnv *, jobject, jlong handle) {
    return engine_of(handle)->overruns();
}

JNIEXPORT jboolean JNICALL
Java_com_tailapp_audio_OboeAudioSource_nativeConsumeDisconnected(JNIEnv *, jobject, jlong handle) {
    return engine_of(handle)->consumeDisconnected() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
