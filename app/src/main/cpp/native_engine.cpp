#include <jni.h>
#include <llama.h>
#include <gguf.h>

#include <algorithm>
#include <chrono>
#include <sys/resource.h>
#include <sys/stat.h>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
enum Result : jint {
    complete = 0, limit = 1, stopped = 2, invalid_model = 3,
    prompt_too_long = 4, inference_error = 5, no_memory = 6,
};

using Clock = std::chrono::steady_clock;

struct Metrics {
    Clock::time_point started = Clock::now();
    Clock::time_point first{};
    jlong prompt = 0;
    jlong output = 0;

    void write(JNIEnv *env, jlongArray target, Result result) const {
        if (env->ExceptionCheck()) return;
        const auto now = Clock::now();
        const auto ms = [](auto duration) {
            return std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
        };
        rusage memory{};
        const jlong rss = getrusage(RUSAGE_SELF, &memory) == 0 ? memory.ru_maxrss : 0;
        const bool emitted = first != Clock::time_point{};
        const jlong values[] = {prompt, output, ms(now - started),
            emitted ? ms(first - started) : -1, emitted ? ms(now - first) : 0, rss, result};
        env->SetLongArrayRegion(target, 0, 7, values);
    }
};

int profile_from_architecture(const char *architecture) {
    if (std::strcmp(architecture, "qwen35") == 0) return 1;
    if (std::strcmp(architecture, "lfm3") == 0) return 2;
    if (std::strcmp(architecture, "nanbeige") == 0) return 3;
    return 0;
}

struct Callback {
    JNIEnv *env;
    jobject receiver;
    jmethodID active;
    jmethodID piece;

    bool running() const {
        return !env->ExceptionCheck() && env->CallBooleanMethod(receiver, active) == JNI_TRUE;
    }

    bool emit(const char *bytes, int count) const {
        const auto chunk = env->NewByteArray(count);
        if (chunk == nullptr) return false;
        env->SetByteArrayRegion(chunk, 0, count, reinterpret_cast<const jbyte *>(bytes));
        if (!env->ExceptionCheck()) env->CallVoidMethod(receiver, piece, chunk);
        env->DeleteLocalRef(chunk);
        return !env->ExceptionCheck();
    }
};

std::vector<llama_token> select_prompt(
        JNIEnv *env, jobjectArray prompts, const llama_vocab *vocab, int budget,
        const Callback &callback) {
    std::vector<llama_token> tokens(budget);
    const int count = env->GetArrayLength(prompts);
    for (int i = 0; i < count && callback.running(); ++i) {
        auto prompt = static_cast<jbyteArray>(env->GetObjectArrayElement(prompts, i));
        const int size = env->GetArrayLength(prompt);
        std::string text(size, '\0');
        env->GetByteArrayRegion(prompt, 0, size, reinterpret_cast<jbyte *>(text.data()));
        env->DeleteLocalRef(prompt);
        if (env->ExceptionCheck()) break;
        const int length = llama_tokenize(vocab, text.data(), size, tokens.data(), budget, true, true);
        if (length > 0) {
            tokens.resize(length);
            return tokens;
        }
    }
    return {};
}

Result run(JNIEnv *env, jstring path, jobjectArray prompts, int n_ctx, int output,
           int threads, int profile, uint32_t seed, const Callback &callback, Metrics &metrics) {
    static std::once_flag backend;
    std::call_once(backend, llama_backend_init);
    if (!callback.running()) return stopped;

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    // Keep Nanbeige's larger weights mapped without a second repacked CPU buffer.
    model_params.use_extra_bufts = profile != 3;
    model_params.progress_callback = [](float, void *data) {
        return static_cast<const Callback *>(data)->running();
    };
    model_params.progress_callback_user_data = const_cast<Callback *>(&callback);

    const char *file = env->GetStringUTFChars(path, nullptr);
    if (!file) return no_memory;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(file, model_params), llama_model_free);
    env->ReleaseStringUTFChars(path, file);
    if (!callback.running()) return stopped;
    if (!model) return invalid_model;

    char architecture[32] = {};
    llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
    if (profile_from_architecture(architecture) != profile) return invalid_model;
    const auto *vocab = llama_model_get_vocab(model.get());
    auto tokens = select_prompt(env, prompts, vocab, n_ctx - output, callback);
    if (!callback.running()) return stopped;
    if (tokens.empty()) return prompt_too_long;
    metrics.prompt = static_cast<jlong>(tokens.size());

    auto ctx_params = llama_context_default_params();
    constexpr int batch_size = 64;
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = batch_size;
    ctx_params.n_ubatch = batch_size;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.no_perf = true;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
        llama_init_from_model(model.get(), ctx_params), llama_free);
    if (!ctx) return no_memory;

    for (int offset = 0; offset < static_cast<int>(tokens.size()); offset += batch_size) {
        if (!callback.running()) return stopped;
        const int size = std::min(batch_size, static_cast<int>(tokens.size()) - offset);
        if (llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + offset, size)) != 0) {
            return inference_error;
        }
    }

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    // The chosen text profiles explicitly close the thinking block before generation.
    std::vector<llama_logit_bias> closed_thinking;
    for (const char *marker : {"<think>", "</think>"}) {
        llama_token token;
        if (llama_tokenize(vocab, marker, std::strlen(marker), &token, 1, false, true) == 1) {
            closed_thinking.push_back({token, -std::numeric_limits<float>::infinity()});
        }
    }
    if (!closed_thinking.empty()) {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_logit_bias(
            llama_vocab_n_tokens(vocab), closed_thinking.size(), closed_thinking.data()));
    }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.0f, 0.0f, profile == 1 ? 1.5f : 0.0f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(profile == 2 ? 50 : 20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(profile == 1 ? 0.8f : 0.95f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(profile == 2 ? 0.3f : 0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));

    std::vector<char> buffer(256);
    for (int step = 0; step < output; ++step) {
        if (!callback.running()) return stopped;
        llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) return complete;
        int length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
        if (length < 0) {
            buffer.resize(-length);
            length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
        }
        if (length < 0) return inference_error;
        ++metrics.output;
        if (length > 0) {
            if (metrics.first == Clock::time_point{}) metrics.first = Clock::now();
            if (!callback.emit(buffer.data(), length)) return inference_error;
        }
        if (step + 1 < output && llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) {
            return inference_error;
        }
    }
    return limit;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_ialocal_android_inference_NativeBridge_generate(
        JNIEnv *env, jobject, jstring path, jobjectArray prompts, jint context,
        jint output, jint threads, jint profile, jint seed, jlongArray stats, jobject receiver) {
    if (!stats || env->GetArrayLength(stats) < 7 || profile < 1 || profile > 3 || context < 1024 || context > 4096 || output < 1 || output > 1024 ||
        output >= context || threads < 1 || threads > 4) {
        return inference_error;
    }
    jclass type = env->GetObjectClass(receiver);
    const auto active = env->GetMethodID(type, "isActive", "()Z");
    if (!active) return inference_error;
    const auto piece = env->GetMethodID(type, "onBytes", "([B)V");
    if (!piece) return inference_error;
    env->DeleteLocalRef(type);
    Metrics metrics;
    Result result;
    try {
        result = run(env, path, prompts, context, output, threads, profile, static_cast<uint32_t>(seed),
                     {env, receiver, active, piece}, metrics);
    } catch (const std::bad_alloc &) {
        result = no_memory;
    } catch (const std::exception &) {
        result = inference_error;
    }
    metrics.write(env, stats, result);
    return result;
}

// Metadata-only preflight. Keep this gate aligned with the profiles implemented by the app.
extern "C" JNIEXPORT jint JNICALL
Java_com_ialocal_android_inference_NativeBridge_inspectModel(JNIEnv *env, jobject, jstring path) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    if (!file) return 0;
    const auto release = [env, path](const char *chars) { env->ReleaseStringUTFChars(path, chars); };
    std::unique_ptr<const char, decltype(release)> filename(file, release);
    try {
        std::unique_ptr<gguf_context, decltype(&gguf_free)> model(
            gguf_init_from_file(filename.get(), {true, nullptr}), gguf_free);
        if (!model) return 0;
        const auto count = gguf_get_n_tensors(model.get());
        if (count == 0) return 0;
        struct stat file_info{};
        if (stat(filename.get(), &file_info) != 0 || file_info.st_size < 0) return 0;
        auto remaining = static_cast<uint64_t>(file_info.st_size);
        const auto data_offset = gguf_get_data_offset(model.get());
        if (data_offset > remaining) return 0;
        remaining -= data_offset;
        // The GGUF parser checks that tensor offsets are contiguous and aligned.
        const auto last_offset = gguf_get_tensor_offset(model.get(), count - 1);
        if (last_offset > remaining || gguf_get_tensor_size(model.get(), count - 1) > remaining - last_offset) return 0;
        const auto arch = gguf_find_key(model.get(), "general.architecture");
        if (arch < 0 || gguf_get_kv_type(model.get(), arch) != GGUF_TYPE_STRING) return 0;
        const int profile = profile_from_architecture(gguf_get_val_str(model.get(), arch));
        if (profile != 1) return profile;
        const auto mtp = gguf_find_key(model.get(), "qwen35.nextn_predict_layers");
        if (mtp >= 0 && (gguf_get_kv_type(model.get(), mtp) != GGUF_TYPE_UINT32 ||
                         gguf_get_val_u32(model.get(), mtp) != 0)) return 0;
        return 1;
    } catch (const std::exception &) {
        return 0;
    }
}
