#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
enum Result : jint {
    complete = 0, limit = 1, stopped = 2, invalid_model = 3,
    prompt_too_long = 4, inference_error = 5, no_memory = 6,
};

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
           int threads, const Callback &callback) {
    static std::once_flag backend;
    std::call_once(backend, llama_backend_init);
    if (!callback.running()) return stopped;

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    model_params.use_mlock = false;
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
    if (std::strcmp(architecture, "qwen35") != 0) return invalid_model;
    const auto *vocab = llama_model_get_vocab(model.get());
    auto tokens = select_prompt(env, prompts, vocab, n_ctx - output, callback);
    if (!callback.running()) return stopped;
    if (tokens.empty()) return prompt_too_long;

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
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(64, 1.0f, 0.0f, 1.5f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

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
        if (length > 0 && !callback.emit(buffer.data(), length)) return inference_error;
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
        jint output, jint threads, jobject receiver) {
    if (context < 1024 || context > 4096 || output < 1 || output > 1024 ||
        output >= context || threads < 1 || threads > 4) {
        return inference_error;
    }
    jclass type = env->GetObjectClass(receiver);
    const auto active = env->GetMethodID(type, "isActive", "()Z");
    if (!active) return inference_error;
    const auto piece = env->GetMethodID(type, "onBytes", "([B)V");
    if (!piece) return inference_error;
    env->DeleteLocalRef(type);
    try {
        return run(env, path, prompts, context, output, threads, {env, receiver, active, piece});
    } catch (const std::bad_alloc &) {
        return no_memory;
    } catch (const std::exception &) {
        return inference_error;
    }
}
