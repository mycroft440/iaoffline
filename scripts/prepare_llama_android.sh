#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_TAG="${LLAMA_CPP_TAG:-v0.4.0}"
LLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
AAR_DEST="${ROOT_DIR}/app/libs/llama-android.aar"
ENGINE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
AI_CHAT_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"

mkdir -p "${ROOT_DIR}/third_party" "${ROOT_DIR}/app/libs"

if [[ ! -d "${LLAMA_DIR}/.git" ]]; then
  git clone --depth 1 --branch "${LLAMA_TAG}" https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}"
else
  git -C "${LLAMA_DIR}" fetch --depth 1 origin "${LLAMA_TAG}"
  git -C "${LLAMA_DIR}" checkout --force FETCH_HEAD
  git -C "${LLAMA_DIR}" clean -fdx
fi

# v0.4.0 resets State.Error without unloading a model that may already have
# been allocated natively. Track native ownership and unload it on recovery so
# retrying a failed request/model does not leak or overwrite the previous model.
# The upstream binding also maps every native load failure to
# UnsupportedArchitectureException, even when the actual cause is memory or an
# Android backend/load-mode problem. Replace that with a neutral IOException;
# the native patch below performs compatibility fallbacks before giving up and
# exposes the relevant native llama.cpp diagnostics to Kotlin.
python3 - "${ENGINE_FILE}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected llama.cpp v0.4.0 source shape: expected 1 match, got {count}")
    text = text.replace(old, new, 1)

replace_once(
    "    @FastNative\n    private external fun load(modelPath: String): Int\n\n    @FastNative\n    private external fun prepare(): Int",
    "    @FastNative\n    private external fun load(modelPath: String): Int\n\n    private external fun lastError(): String\n\n    @FastNative\n    private external fun prepare(): Int",
)
replace_once(
    "    @Volatile\n    private var _cancelGeneration = false\n",
    "    @Volatile\n    private var _cancelGeneration = false\n    private var _nativeModelLoaded = false\n",
)
replace_once(
    "                load(pathToModel).let {\n                    // TODO-han.yin: find a better way to pass other error codes\n                    if (it != 0) throw UnsupportedArchitectureException()\n                }\n                prepare().let {",
    "                load(pathToModel).let {\n                    if (it != 0) {\n                        val nativeDetail = runCatching { lastError().trim() }.getOrDefault(\"\").take(3000)\n                        throw IOException(buildString {\n                            append(\"O backend nativo não conseguiu carregar o arquivo GGUF após as tentativas de compatibilidade.\")\n                            if (nativeDetail.isNotBlank()) append(\" Detalhe nativo: \" + nativeDetail)\n                        })\n                    }\n                }\n                _nativeModelLoaded = true\n                prepare().let {",
)
replace_once(
    "                prepare().let {\n                    if (it != 0) throw IOException(\"Failed to prepare resources\")\n                }",
    "                prepare().let {\n                    if (it != 0) {\n                        val nativeDetail = runCatching { lastError().trim() }.getOrDefault(\"\").take(3000)\n                        throw IOException(buildString {\n                            append(\"Failed to prepare resources\")\n                            if (nativeDetail.isNotBlank()) append(\". Detalhe nativo: \" + nativeDetail)\n                        })\n                    }\n                }",
)
replace_once(
    "                    unload()\n\n                    _state.value = InferenceEngine.State.Initialized",
    "                    unload()\n                    _nativeModelLoaded = false\n\n                    _state.value = InferenceEngine.State.Initialized",
)
replace_once(
    "                is InferenceEngine.State.Error -> {\n                    Log.i(TAG, \"Resetting error states...\")\n                    _state.value = InferenceEngine.State.Initialized\n                    Log.i(TAG, \"States reset!\")\n                    Unit\n                }",
    "                is InferenceEngine.State.Error -> {\n                    Log.i(TAG, \"Resetting error states...\")\n                    if (_nativeModelLoaded) {\n                        Log.i(TAG, \"Error occurred after native model allocation; unloading it...\")\n                        unload()\n                        _nativeModelLoaded = false\n                    }\n                    _state.value = InferenceEngine.State.Initialized\n                    Log.i(TAG, \"States reset!\")\n                    Unit\n                }",
)
replace_once(
    "                else -> { unload(); shutdown() }",
    "                else -> {\n                    if (_nativeModelLoaded) { unload(); _nativeModelLoaded = false }\n                    shutdown()\n                }",
)

path.write_text(text)
print("Applied IA Offline inference-engine recovery patch")
PY

# Android compatibility patch for model loading:
# 1. Keep model weights on CPU. The packaged Android binding ships CPU variants,
#    and automatic layer offload can select a backend/buffer combination that a
#    specific device cannot allocate.
# 2. Try normal mmap + optimized CPU buffers first.
# 3. If that fails, keep mmap but disable extra/repacked CPU buffers. This is the
#    low-RAM fallback: mapped weights stay file-backed instead of forcing a full
#    ordinary-I/O copy into process memory.
# 4. If that still fails, retry with conservative CPU buffers + regular file I/O.
# 5. Capture warning/error output from llama.cpp and expose it to Kotlin so a
#    device-specific load failure is diagnosable instead of becoming one generic
#    UnsupportedArchitectureException.
# 6. Size the context to the memory the app grants the KV cache
#    (IAOFFLINE_KV_BUDGET_MB), up to IAOFFLINE_CONTEXT_TOKENS and the model's trained
#    context. If it cannot be allocated, retry with half the size down to 2K, which
#    keeps smaller-memory phones usable instead of reporting the model as incompatible.
#    The size in use is published as IAOFFLINE_ACTIVE_CONTEXT_TOKENS for the app.
# 7. When the app sets IAOFFLINE_LOW_MEMORY=1 (model large for the device RAM),
#    skip the repacked CPU buffers from the start.
# 8. Use a Q4_0 KV cache (about a quarter of F16) when the model supports flash
#    attention, falling back to a smaller F16 context otherwise.
python3 - "${AI_CHAT_FILE}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected llama.cpp v0.4.0 native source shape: expected 1 match, got {count}")
    text = text.replace(old, new, 1)

replace_once(
    "#include <string>\n#include <unistd.h>",
    "#include <string>\n#include <mutex>\n#include <cstdlib>\n#include <unistd.h>",
)
replace_once(
    "static common_sampler                   * g_sampler;\n\nextern \"C\"\nJNIEXPORT void JNICALL\nJava_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {\n    // Set llama log handler to Android\n    llama_log_set(aichat_android_log_callback, nullptr);",
    "static common_sampler                   * g_sampler;\n\n"
    "static std::mutex                         g_native_error_mutex;\n"
    "static std::string                        g_last_native_error;\n"
    "constexpr size_t                          MAX_NATIVE_ERROR_CHARS = 6000;\n\n"
    "static void clear_native_error() {\n"
    "    std::lock_guard<std::mutex> lock(g_native_error_mutex);\n"
    "    g_last_native_error.clear();\n"
    "}\n\n"
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n"
    "static bool iaoffline_low_memory_mode() {\n"
    "    const char *value = getenv(\"IAOFFLINE_LOW_MEMORY\");\n"
    "    return value != nullptr && value[0] == '1';\n"
    "}\n\n"
    "constexpr int                             IAOFFLINE_MIN_CONTEXT = 2048;\n"
    "constexpr int                             IAOFFLINE_MAX_F16_CONTEXT = 8192;\n\n"
    "static long iaoffline_env_long(const char *name, long fallback) {\n"
    "    const char *value = getenv(name);\n"
    "    const long parsed = value != nullptr ? atol(value) : 0;\n"
    "    return parsed > 0 ? parsed : fallback;\n"
    "}\n\n"
    "static int iaoffline_meta_int(const llama_model *model, const std::string &suffix) {\n"
    "    char arch[64] = {0};\n"
    "    if (llama_model_meta_val_str(model, \"general.architecture\", arch, sizeof(arch)) <= 0) return 0;\n"
    "    char value[32] = {0};\n"
    "    const std::string key = std::string(arch) + suffix;\n"
    "    if (llama_model_meta_val_str(model, key.c_str(), value, sizeof(value)) <= 0) return 0;\n"
    "    return atoi(value);\n"
    "}\n\n"
    "// Bytes of Q4_0 KV cache per context token (18 bytes per 32 values, K and V). Hybrid and\n"
    "// sliding-window models need less; overestimating only yields a smaller context.\n"
    "static double iaoffline_kv_bytes_per_token(const llama_model *model) {\n"
    "    const int n_layer = std::max(1, llama_model_n_layer(model));\n"
    "    const int n_head = std::max(1, llama_model_n_head(model));\n"
    "    int n_head_kv = llama_model_n_head_kv(model);\n"
    "    if (n_head_kv <= 0) n_head_kv = n_head;\n"
    "    int key_length = iaoffline_meta_int(model, \".attention.key_length\");\n"
    "    int value_length = iaoffline_meta_int(model, \".attention.value_length\");\n"
    "    if (key_length <= 0) key_length = llama_model_n_embd(model) / n_head;\n"
    "    if (value_length <= 0) value_length = key_length;\n"
    "    return (double) n_layer * n_head_kv * (key_length + value_length) * 18.0 / 32.0;\n"
    "}\n\n"
    "// Largest context that fits the KV budget the app granted (IAOFFLINE_KV_BUDGET_MB), capped\n"
    "// by IAOFFLINE_CONTEXT_TOKENS and by the context the model was trained with.\n"
    "static int iaoffline_context_for_memory(const llama_model *model) {\n"
    "    const long budget_mb = iaoffline_env_long(\"IAOFFLINE_KV_BUDGET_MB\", 512);\n"
    "    long context = iaoffline_env_long(\"IAOFFLINE_CONTEXT_TOKENS\", DEFAULT_CONTEXT_SIZE);\n"
    "    const int trained = llama_model_n_ctx_train(model);\n"
    "    if (trained > 0) context = std::min<long>(context, trained);\n"
    "    const double per_token = iaoffline_kv_bytes_per_token(model);\n"
    "    context = std::min<long>(context, (long) (budget_mb * 1024.0 * 1024.0 / per_token));\n"
    "    context = context / 256 * 256;\n"
    "    const int floor = trained > 0 ? std::min(IAOFFLINE_MIN_CONTEXT, trained) : IAOFFLINE_MIN_CONTEXT;\n"
    "    context = std::max<long>(context, floor);\n"
    "    LOGi(\"%s: KV budget %ld MB, %.0f bytes/token, trained %d -> context %ld\",\n"
    "         __func__, budget_mb, per_token, trained, context);\n"
    "    return (int) context;\n"
    "}\n\n"
    "static void append_native_error(const char *text) {\n"
    "    if (text == nullptr || text[0] == '\\0') return;\n"
    "    std::lock_guard<std::mutex> lock(g_native_error_mutex);\n"
    "    g_last_native_error.append(text);\n"
    "    if (g_last_native_error.empty() || g_last_native_error.back() != '\\n') {\n"
    "        g_last_native_error.push_back('\\n');\n"
    "    }\n"
    "    if (g_last_native_error.size() > MAX_NATIVE_ERROR_CHARS) {\n"
    "        g_last_native_error.erase(0, g_last_native_error.size() - MAX_NATIVE_ERROR_CHARS);\n"
    "    }\n"
    "}\n\n"
    "static void capturing_android_log_callback(enum ggml_log_level level, const char *text, void *user) {\n"
    "    aichat_android_log_callback(level, text, user);\n"
    "    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {\n"
    "        append_native_error(text);\n"
    "    }\n"
    "}\n\n"
    "extern \"C\"\n"
    "JNIEXPORT void JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {\n"
    "    // Set llama log handler to Android and preserve warnings/errors for the app.\n"
    "    llama_log_set(capturing_android_log_callback, nullptr);",
)
replace_once(
    "    LOGi(\"Backend initiated; Log handler set.\");\n}\n\nextern \"C\"\nJNIEXPORT jint JNICALL\nJava_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {",
    "    LOGi(\"Backend initiated; Log handler set.\");\n}\n\n"
    "extern \"C\"\n"
    "JNIEXPORT jstring JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_lastError(JNIEnv *env, jobject /*unused*/) {\n"
    "    std::lock_guard<std::mutex> lock(g_native_error_mutex);\n"
    "    return env->NewStringUTF(g_last_native_error.c_str());\n"
    "}\n\n"
    "extern \"C\"\n"
    "JNIEXPORT jint JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {",
)
replace_once(
    "    llama_model_params model_params = llama_model_default_params();\n\n    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);",
    "    clear_native_error();\n"
    "    llama_model_params model_params = llama_model_default_params();\n"
    "    // Android app inference is CPU-first. Avoid accidental device offload\n"
    "    // and keep the first attempt on the portable mmap path.\n"
    "    model_params.n_gpu_layers = 0;\n"
    "    model_params.split_mode = LLAMA_SPLIT_MODE_NONE;\n"
    "    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;\n"
    "    if (iaoffline_low_memory_mode()) {\n"
    "        // Repacked CPU buffers copy every weight into anonymous memory; without them the\n"
    "        // weights stay file-backed and the kernel can page them instead of killing the app.\n"
    "        model_params.use_extra_bufts = false;\n"
    "        LOGi(\"%s: low-memory mode: mmap without extra buffers\", __func__);\n"
    "    }\n\n"
    "    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);",
)
replace_once(
    "    auto *model = llama_model_load_from_file(model_path, model_params);\n"
    "    env->ReleaseStringUTFChars(jmodel_path, model_path);\n"
    "    if (!model) {\n"
    "        return 1;\n"
    "    }",
    "    auto *model = llama_model_load_from_file(model_path, model_params);\n"
    "    if (!model) {\n"
    "        append_native_error(\"[IA Offline] mmap otimizado falhou; tentando mmap sem buffers extras.\");\n"
    "        model_params.use_extra_bufts = false;\n"
    "        model = llama_model_load_from_file(model_path, model_params);\n"
    "    }\n"
    "    if (!model) {\n"
    "        append_native_error(\"[IA Offline] mmap conservador falhou; tentando I/O regular em CPU.\");\n"
    "        model_params.load_mode = LLAMA_LOAD_MODE_NONE;\n"
    "        model = llama_model_load_from_file(model_path, model_params);\n"
    "    }\n"
    "    env->ReleaseStringUTFChars(jmodel_path, model_path);\n"
    "    if (!model) {\n"
    "        append_native_error(\"[IA Offline] o carregamento falhou nos três modos de compatibilidade.\");\n"
    "        LOGe(\"%s: model load failed in all Android compatibility modes\", __func__);\n"
    "        return 1;\n"
    "    }",
)
replace_once(
    "    ctx_params.n_threads_batch = n_threads;\n"
    "    auto *context = llama_init_from_model(g_model, ctx_params);\n"
    "    if (context == nullptr) {\n",
    "    ctx_params.n_threads_batch = n_threads;\n"
    "    // A Q4_0 KV cache takes about a quarter of the memory of F16, so the same RAM holds a\n"
    "    // context about 3.5 times longer. It needs flash attention, which llama.cpp turns on\n"
    "    // automatically when the model supports it; otherwise context creation fails and an\n"
    "    // F16 cache is used, with the context shrunk to the same memory (and at most 8K,\n"
    "    // because attention without flash attention needs a buffer that grows with it).\n"
    "    ctx_params.type_k = GGML_TYPE_Q4_0;\n"
    "    ctx_params.type_v = GGML_TYPE_Q4_0;\n"
    "    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;\n"
    "    auto *context = llama_init_from_model(g_model, ctx_params);\n"
    "    if (context == nullptr) {\n"
    "        ctx_params.type_k = GGML_TYPE_F16;\n"
    "        ctx_params.type_v = GGML_TYPE_F16;\n"
    "        ctx_params.n_ctx = std::max(std::min(n_ctx, IAOFFLINE_MIN_CONTEXT),\n"
    "                                    std::min(IAOFFLINE_MAX_F16_CONTEXT, n_ctx * 9 / 32 / 256 * 256));\n"
    "        LOGw(\"%s: Q4_0 KV cache unavailable for this model; retrying with F16 and %u tokens\",\n"
    "             __func__, ctx_params.n_ctx);\n"
    "        context = llama_init_from_model(g_model, ctx_params);\n"
    "    }\n"
    "    if (context == nullptr) {\n",
)
replace_once(
    "    auto *context = init_context(g_model);\n"
    "    if (!context) { return 1; }",
    "    int n_ctx = iaoffline_context_for_memory(g_model);\n"
    "    auto *context = init_context(g_model, n_ctx);\n"
    "    while (!context && n_ctx > IAOFFLINE_MIN_CONTEXT) {\n"
    "        const int smaller = std::max(IAOFFLINE_MIN_CONTEXT, n_ctx / 2 / 256 * 256);\n"
    "        LOGw(\"%s: %d-token context allocation failed; retrying with %d\", __func__, n_ctx, smaller);\n"
    "        n_ctx = smaller;\n"
    "        context = init_context(g_model, n_ctx);\n"
    "    }\n"
    "    if (!context) {\n"
    "        LOGe(\"%s: failed to allocate even the %d-token context\", __func__, n_ctx);\n"
    "        return 1;\n"
    "    }\n"
    "    // Read back by the app, so its prompt builder uses the context actually allocated.\n"
    "    setenv(\"IAOFFLINE_ACTIVE_CONTEXT_TOKENS\", std::to_string(llama_n_ctx(context)).c_str(), 1);",
)

# When prepare() falls back to a smaller context, all overflow/truncation logic
# must use that actual context size rather than the original 8192-token constant.
replace_once(
    "        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {",
    "        if (start_pos + i + cur_batch_size >= (int) llama_n_ctx(context) - OVERFLOW_HEADROOM) {",
)
replace_once(
    "    // Handle context overflow\n"
    "    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;\n"
    "    if ((int) system_tokens.size() > max_batch_size) {",
    "    // Handle context overflow using the context actually allocated on this device.\n"
    "    const int max_batch_size = (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM;\n"
    "    if ((int) system_tokens.size() > max_batch_size) {",
)
replace_once(
    "    const int user_prompt_size = (int) user_tokens.size();\n"
    "    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;",
    "    const int user_prompt_size = (int) user_tokens.size();\n"
    "    const int max_batch_size = (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM;",
)
replace_once(
    "    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {",
    "    if (current_position >= (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {",
)

path.write_text(text)
print("Applied IA Offline Android model-load compatibility patch")
PY

pushd "${LLAMA_DIR}/examples/llama.android" >/dev/null
chmod +x ./gradlew
./gradlew :lib:assembleRelease --no-daemon
popd >/dev/null

AAR_SOURCE="${LLAMA_DIR}/examples/llama.android/lib/build/outputs/aar/lib-release.aar"
if [[ ! -f "${AAR_SOURCE}" ]]; then
  echo "AAR do llama.cpp não encontrado em ${AAR_SOURCE}" >&2
  exit 1
fi

cp "${AAR_SOURCE}" "${AAR_DEST}"
echo "llama.cpp Android AAR preparado em ${AAR_DEST}"
