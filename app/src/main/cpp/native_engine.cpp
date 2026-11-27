#include <jni.h>
#include <llama.h>
#include <gguf.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <sys/resource.h>
#include <sys/stat.h>
#if defined(__ANDROID__) && defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif
#include <cstring>
#include <iterator>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
enum Result : jint {
    complete = 0, limit = 1, stopped = 2, invalid_model = 3,
    prompt_too_long = 4, inference_error = 5, no_memory = 6, first_token_timeout = 7,
};

using Clock = std::chrono::steady_clock;
enum Phase : jint { loading_model = 1, creating_context = 2, processing_prompt = 3, generating_text = 4 };

struct Metrics {
    Clock::time_point started = Clock::now();
    Clock::time_point first{};
    jlong prompt = 0;
    jlong output = 0;
    jlong context = 0;
    jlong load_ms = 0;
    jlong context_ms = 0;
    jlong tokenize_ms = 0;
    jlong prefill_ms = 0;
    jlong reused = 0;
    jlong evaluated = 0;
    jlong model_reused = 0;
    jlong context_reused = 0;

    void write(JNIEnv *env, jlongArray target, Result result) const {
        if (env->ExceptionCheck()) return;
        const auto now = Clock::now();
        const auto ms = [](auto duration) {
            return std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
        };
        rusage memory{};
        const jlong rss = getrusage(RUSAGE_SELF, &memory) == 0 ? memory.ru_maxrss : 0;
        const bool emitted = first != Clock::time_point{};
        long current_rss = -1;
        if (auto * status = std::fopen("/proc/self/status", "r")) {
            char line[128];
            while (std::fgets(line, sizeof(line), status)) {
                if (std::sscanf(line, "VmRSS: %ld kB", &current_rss) == 1) break;
            }
            std::fclose(status);
        }
        jlong arm_features = 0;
#if defined(__ANDROID__) && defined(__aarch64__)
        const auto hwcap = getauxval(AT_HWCAP);
        arm_features = 1; // ARM64 baseline
        if (hwcap & HWCAP_ASIMDDP) arm_features |= 2;
        if (hwcap & HWCAP_ASIMDHP) arm_features |= 4;
#endif
        const jlong values[] = {prompt, output, ms(now - started),
            emitted ? ms(first - started) : -1, emitted ? ms(now - first) : 0, rss, result, context,
            load_ms, context_ms, tokenize_ms, prefill_ms, reused, evaluated, model_reused, context_reused,
            current_rss, arm_features};
        env->SetLongArrayRegion(target, 0, std::min<jsize>(env->GetArrayLength(target), std::size(values)), values);
    }
};

struct Timer {
    jlong & output;
    Clock::time_point started = Clock::now();
    ~Timer() { output = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count(); }
};

struct Session {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx{nullptr, llama_free};
    std::string path;
    struct stat file_info{};
    jlong conversation = 0;
    int context_limit = 0;
    int batch_size = 0;
    bool hybrid = false;
    int profile = 0;
    llama_token turn_start = LLAMA_TOKEN_NULL;
    std::vector<llama_logit_bias> closed_thinking;
    std::vector<llama_token> cached;
    std::vector<uint8_t> checkpoint;
    size_t checkpoint_at = 0;

    void release_context() {
        ctx.reset();
        cached.clear();
        std::vector<uint8_t>().swap(checkpoint);
        checkpoint_at = 0;
    }

    void reset_sequence() {
        llama_memory_clear(llama_get_memory(ctx.get()), false);
        cached.clear();
        checkpoint.clear();
        checkpoint_at = 0;
    }

    bool matches(const std::string & filename, const struct stat & info) const {
        return model && path == filename && file_info.st_dev == info.st_dev && file_info.st_ino == info.st_ino &&
            file_info.st_size == info.st_size && file_info.st_mtim.tv_sec == info.st_mtim.tv_sec &&
            file_info.st_mtim.tv_nsec == info.st_mtim.tv_nsec;
    }

    size_t reuse(const std::vector<llama_token> & tokens) {
        auto mem = llama_get_memory(ctx.get());
        const size_t common = std::mismatch(cached.begin(), cached.end(), tokens.begin(), tokens.end()).first - cached.begin();
        // At least one token must be evaluated to produce logits for this turn.
        const size_t prefix = std::min(common, tokens.size() - 1);
        if (prefix > 0 && prefix == cached.size() && llama_memory_seq_pos_max(mem, 0) == static_cast<llama_pos>(prefix - 1)) {
            return prefix;
        }
        if (!hybrid && prefix > 0 && llama_memory_seq_pos_min(mem, 0) == 0 &&
            llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(prefix), -1)) {
            cached.resize(prefix);
            return prefix;
        }
        // A recurrent state cannot generally be rolled back. Restore a complete, bounded snapshot.
        if (checkpoint_at > 0 && checkpoint_at <= prefix) {
            llama_memory_clear(mem, false);
            const size_t restored = llama_state_seq_set_data(ctx.get(), checkpoint.data(), checkpoint.size(), 0);
            if (restored == checkpoint.size() && llama_memory_seq_pos_max(mem, 0) == static_cast<llama_pos>(checkpoint_at - 1)) {
                cached.resize(checkpoint_at);
                return checkpoint_at;
            }
        }
        reset_sequence();
        return 0;
    }

    void save_checkpoint(size_t end) {
        constexpr size_t max_checkpoint = 32 * 1024 * 1024;
        const size_t bytes = llama_state_seq_get_size(ctx.get(), 0);
        if (!bytes || bytes > max_checkpoint) return;
        checkpoint_at = 0;
        checkpoint.resize(bytes);
        if (llama_state_seq_get_data(ctx.get(), checkpoint.data(), bytes, 0) == bytes) checkpoint_at = end;
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
    jmethodID phase;
    Clock::time_point deadline;
    const std::thread::id caller = std::this_thread::get_id();
    Clock::time_point next_poll{};
    bool cancelled = false;

    bool java_running() const {
        return !env->ExceptionCheck() && env->CallBooleanMethod(receiver, active) == JNI_TRUE;
    }

    bool running() const { return Clock::now() < deadline && java_running(); }

    Result stop_reason() const { return java_running() ? first_token_timeout : stopped; }

    bool abort_compute() {
        const auto now = Clock::now();
        if (now >= deadline) return true;
        // The CPU backend calls on worker zero. Never use this JNIEnv on a different thread.
        if (std::this_thread::get_id() == caller && now >= next_poll) {
            next_poll = now + std::chrono::milliseconds(50);
            cancelled = !java_running();
        }
        return std::this_thread::get_id() == caller && cancelled;
    }

    void report(Phase value) const { env->CallVoidMethod(receiver, phase, static_cast<jint>(value)); }

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
    std::string text;
    const int count = env->GetArrayLength(prompts);
    for (int i = 0; i < count && callback.running(); ++i) {
        auto prompt = static_cast<jbyteArray>(env->GetObjectArrayElement(prompts, i));
        const int size = env->GetArrayLength(prompt);
        text.resize(size);
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

Result run(Session &session, const std::string &path, jlong conversation, JNIEnv *env, jobjectArray prompts,
           int n_ctx, int output, int threads, int profile, int batch_size, uint32_t seed,
           Callback &callback, Metrics &metrics, bool keep_checkpoint) {
    static std::once_flag backend;
    std::call_once(backend, llama_backend_init);
    if (!callback.running()) return callback.stop_reason();

    struct stat file_info{};
    if (stat(path.c_str(), &file_info) != 0 || !S_ISREG(file_info.st_mode)) return invalid_model;
    if (session.matches(path, file_info)) {
        if (session.profile != profile) return invalid_model;
        metrics.model_reused = 1;
    } else {
        session.release_context();
        session.model.reset();
        callback.report(loading_model);
        Timer load{metrics.load_ms};
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        // Keep Nanbeige's larger weights mapped without a second repacked CPU buffer.
        params.use_extra_bufts = profile != 3;
        params.progress_callback = [](float, void *data) { return static_cast<const Callback *>(data)->running(); };
        params.progress_callback_user_data = &callback;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> loaded(
            llama_model_load_from_file(path.c_str(), params), llama_model_free);
        if (!callback.running()) return callback.stop_reason();
        if (!loaded) return invalid_model;
        char architecture[32] = {};
        llama_model_meta_val_str(loaded.get(), "general.architecture", architecture, sizeof(architecture));
        if (profile_from_architecture(architecture) != profile) return invalid_model;
        session.path = path;
        session.file_info = file_info;
        session.profile = profile;
        session.hybrid = llama_model_is_recurrent(loaded.get()) || llama_model_is_hybrid(loaded.get());
        session.closed_thinking.clear();
        const auto *vocab = llama_model_get_vocab(loaded.get());
        for (const char *marker : {"<think>", "</think>"}) {
            llama_token token;
            if (llama_tokenize(vocab, marker, std::strlen(marker), &token, 1, false, true) == 1) {
                session.closed_thinking.push_back({token, -std::numeric_limits<float>::infinity()});
            }
        }
        const char *marker = "<|im_start|>";
        if (llama_tokenize(vocab, marker, std::strlen(marker), &session.turn_start, 1, false, true) != 1) {
            session.turn_start = LLAMA_TOKEN_NULL;
        }
        session.model = std::move(loaded);
    }

    const auto *vocab = llama_model_get_vocab(session.model.get());
    std::vector<llama_token> tokens;
    {
        Timer tokenize{metrics.tokenize_ms};
        tokens = select_prompt(env, prompts, vocab, n_ctx - output, callback);
    }
    if (!callback.running()) return callback.stop_reason();
    if (tokens.empty()) return prompt_too_long;
    metrics.prompt = static_cast<jlong>(tokens.size());

    constexpr int context_block = 256;
    const int needed = static_cast<int>(tokens.size()) + output;
    const int target_context = profile == 3
        ? std::max(512, (needed + context_block - 1) / context_block * context_block) : n_ctx;
    if (session.ctx && session.context_limit == n_ctx && session.batch_size == batch_size &&
        llama_n_ctx(session.ctx.get()) >= static_cast<uint32_t>(target_context)) {
        metrics.context_reused = 1;
        llama_set_n_threads(session.ctx.get(), threads, threads);
    } else {
        session.release_context();
        callback.report(creating_context);
        Timer create{metrics.context_ms};
        auto params = llama_context_default_params();
        params.n_ctx = target_context;
        params.n_batch = batch_size;
        params.n_ubatch = batch_size;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        params.no_perf = true;
        session.ctx.reset(llama_init_from_model(session.model.get(), params));
        if (!callback.running()) return callback.stop_reason();
        if (!session.ctx) return no_memory;
        session.context_limit = n_ctx;
        session.batch_size = batch_size;
    }
    auto *ctx = session.ctx.get();
    metrics.context = llama_n_ctx(ctx);
    llama_set_abort_callback(ctx, [](void *data) { return static_cast<Callback *>(data)->abort_compute(); }, &callback);
    struct ClearCallback {
        llama_context *ctx;
        ~ClearCallback() { llama_set_abort_callback(ctx, nullptr, nullptr); }
    } clear_callback{ctx};

    callback.report(processing_prompt);
    {
        Timer prefill{metrics.prefill_ms};
        if (session.conversation != conversation) {
            session.reset_sequence();
            session.conversation = conversation;
        }
        if (!keep_checkpoint && session.checkpoint_at > 0) {
            std::vector<uint8_t>().swap(session.checkpoint);
            session.checkpoint_at = 0;
        }
        size_t checkpoint_at = 0;
        if (keep_checkpoint && session.hybrid && session.turn_start != LLAMA_TOKEN_NULL) {
            const auto marker = std::find(tokens.rbegin(), tokens.rend(), session.turn_start);
            if (marker != tokens.rend()) checkpoint_at = (marker.base() - tokens.begin() - 1) / batch_size * batch_size;
        }
        size_t offset = session.reuse(tokens);
        metrics.reused = offset;
        session.cached.reserve(tokens.size() + output);
        while (offset < tokens.size()) {
            if (!callback.running()) return callback.stop_reason();
            size_t end = std::min(offset + batch_size, tokens.size());
            if (checkpoint_at > offset) end = std::min(end, checkpoint_at);
            const int size = static_cast<int>(end - offset);
            if (llama_decode(ctx, llama_batch_get_one(tokens.data() + offset, size)) != 0) {
                return callback.running() ? inference_error : callback.stop_reason();
            }
            metrics.evaluated += size;
            session.cached.insert(session.cached.end(), tokens.begin() + offset, tokens.begin() + end);
            offset = end;
            if (offset == checkpoint_at) session.save_checkpoint(offset);
        }
    }

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    if (!session.closed_thinking.empty()) {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_logit_bias(
            llama_vocab_n_tokens(vocab), session.closed_thinking.size(), session.closed_thinking.data()));
    }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.0f, 0.0f, profile == 1 ? 1.5f : 0.0f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(profile == 2 ? 50 : 20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(profile == 1 ? 0.8f : 0.95f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(profile == 2 ? 0.3f : 0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));

    std::vector<char> buffer(256);
    for (int step = 0; step < output; ++step) {
        if (!callback.running()) return callback.stop_reason();
        llama_token token = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) return complete;
        int length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
        if (length < 0) {
            buffer.resize(-length);
            length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
        }
        if (length < 0) return inference_error;
        ++metrics.output;
        if (length > 0) {
            if (metrics.first == Clock::time_point{}) {
                if (!callback.running()) return callback.stop_reason();
                metrics.first = Clock::now();
                callback.deadline = Clock::time_point::max();
                callback.report(generating_text);
            }
            if (!callback.emit(buffer.data(), length)) return inference_error;
        }
        if (step + 1 < output) {
            if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) {
                return callback.running() ? inference_error : callback.stop_reason();
            }
            session.cached.push_back(token);
        }
    }
    return limit;
}
// Handles are serialized by their owner. A one-shot caller uses a stack-owned session.
jint generate(Session &session, jlong conversation, jint batch, bool keep_checkpoint,
        JNIEnv *env, jstring path, jobjectArray prompts, jint context,
        jint output, jint threads, jint profile, jint seed, jint first_token_timeout_ms,
        jlongArray stats, jobject receiver) {
    if (!stats || env->GetArrayLength(stats) < 7 || profile < 1 || profile > 3 || context < 1024 || context > 4096 || output < 1 || output > 1024 ||
        output >= context || threads < 1 || threads > 4 || first_token_timeout_ms < 1 ||
        (batch != 32 && batch != 64 && batch != 128)) {
        return inference_error;
    }
    jclass type = env->GetObjectClass(receiver);
    const auto active = env->GetMethodID(type, "isActive", "()Z");
    if (!active) return inference_error;
    const auto piece = env->GetMethodID(type, "onBytes", "([B)V");
    if (!piece) return inference_error;
    const auto phase = env->GetMethodID(type, "onPhase", "(I)V");
    if (!phase) return inference_error;
    env->DeleteLocalRef(type);
    Metrics metrics;
    Result result;
    try {
        const char *file = env->GetStringUTFChars(path, nullptr);
        if (!file) return no_memory;
        const auto release = [env, path](const char *chars) { env->ReleaseStringUTFChars(path, chars); };
        std::unique_ptr<const char, decltype(release)> filename(file, release);
        Callback callback{env, receiver, active, piece, phase,
                          metrics.started + std::chrono::milliseconds(first_token_timeout_ms)};
        result = run(session, filename.get(), conversation, env, prompts, context, output, threads,
                     profile, batch, static_cast<uint32_t>(seed), callback, metrics, keep_checkpoint);
    } catch (const std::bad_alloc &) {
        result = no_memory;
    } catch (const std::exception &) {
        result = inference_error;
    }
    metrics.write(env, stats, result);
    if (result == stopped || result == first_token_timeout || result == inference_error || result == no_memory || env->ExceptionCheck()) {
        session.release_context();
    }
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ialocal_android_inference_NativeBridge_openSession(JNIEnv *, jobject) {
    try {
        return reinterpret_cast<jlong>(new Session());
    } catch (const std::bad_alloc &) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ialocal_android_inference_NativeBridge_closeSession(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ialocal_android_inference_NativeBridge_generateCached(
        JNIEnv *env, jobject, jlong handle, jlong conversation, jint batch, jboolean keep_checkpoint,
        jstring path, jobjectArray prompts, jint context, jint output, jint threads, jint profile,
        jint seed, jint first_token_timeout_ms, jlongArray stats, jobject receiver) {
    if (!handle) return no_memory;
    return generate(*reinterpret_cast<Session *>(handle), conversation, batch, keep_checkpoint, env, path,
                    prompts, context, output, threads, profile, seed, first_token_timeout_ms, stats, receiver);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ialocal_android_inference_NativeBridge_generate(
        JNIEnv *env, jobject, jstring path, jobjectArray prompts, jint context,
        jint output, jint threads, jint profile, jint seed, jint first_token_timeout_ms,
        jlongArray stats, jobject receiver) {
    Session session;
    return generate(session, 0, 64, false, env, path, prompts, context, output, threads,
                    profile, seed, first_token_timeout_ms, stats, receiver);
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
