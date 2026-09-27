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

# The app keeps the model loaded between messages: a new system prompt resets the native
# conversation (KV cache, chat history, sampler), so it no longer requires a fresh load.
replace_once(
    "            check(_readyForSystemPrompt) { \"System prompt must be set ** RIGHT AFTER ** model loaded!\" }\n",
    "",
)
# Upstream returned silently when the native side rejected a user prompt, leaving the
# engine in ProcessingUserPrompt for good: every later request and even cleanUp() failed
# until the app restarted. The model is still loaded and usable, so report the rejection
# (the app then rebuilds the conversation with a new system prompt) and stay ModelReady.
replace_once(
    "                if (result != 0) {\n"
    "                    Log.e(TAG, \"Failed to process user prompt: $result\")\n"
    "                    return@flow\n"
    "                }\n",
    "                if (result != 0) {\n"
    "                    Log.e(TAG, \"Failed to process user prompt: $result\")\n"
    "                    throw IOException(\"IAOFFLINE_USER_PROMPT_REJECTED:$result\")\n"
    "                }\n",
)
replace_once(
    "            Log.e(TAG, \"Error during generation!\", e)\n"
    "            _state.value = InferenceEngine.State.Error(e)\n",
    "            Log.e(TAG, \"Error during generation!\", e)\n"
    "            _state.value = if (e.message?.startsWith(\"IAOFFLINE_USER_PROMPT_REJECTED:\") == true) {\n"
    "                InferenceEngine.State.ModelReady\n"
    "            } else {\n"
    "                InferenceEngine.State.Error(e)\n"
    "            }\n",
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
# 9. Format chat turns with llama.cpp's built-in templates and, when a model's
#    template is not one of them (Gemma 4), render the model's own Jinja template
#    instead of letting the exception abort the process; a plain transcript is the
#    last resort, and the format for GGUFs without any template.
# 10. Size the context from the GGUF's per-layer attention layout (hybrid, sliding
#    window and shared-cache layers), not from the first layer alone.
# 11. Reserve logits for one output instead of one per batch token (Qwen3.5 9B's
#    compute buffer: 509 MB -> 125 MB), and keep only the window in sliding-window layers.
# 12. Never shift the context of models that cannot drop part of it (recurrent,
#    hybrid, multi-position RoPE): stop the answer or reject the prompt instead.
# 13. Let the app keep the model loaded: a new system prompt resets the conversation,
#    and a user prompt after a finished answer continues it (see
#    iaoffline_format_continuation). Why generation stopped and how much context is
#    in use are published in IAOFFLINE_LAST_STOP and IAOFFLINE_CONTEXT_USED.
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
    "#include <string>\n#include <mutex>\n#include <cstdlib>\n#include <algorithm>\n#include <vector>\n#include <unistd.h>\n#include <gguf.h>",
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
    "// Q4_0 KV cache of the loaded model (18 bytes per 32 values, K and V), read from its GGUF\n"
    "// header, because llama.cpp does not expose per-layer arrays: bytes per context token for\n"
    "// the layers whose cache grows with the context, and bytes per cell for sliding-window\n"
    "// layers, whose cache stops near the window. Layers without attention (Mamba, DeltaNet,\n"
    "// conv) and layers that reuse an earlier layer's cache (Gemma 4) cost nothing per token.\n"
    "struct iaoffline_kv_cost {\n"
    "    bool   valid = false;\n"
    "    double per_token = 0.0;\n"
    "    double per_swa_cell = 0.0;\n"
    "    long   swa_cells = 0;\n"
    "};\n"
    "static iaoffline_kv_cost g_kv_cost;\n\n"
    "static long iaoffline_gguf_number(enum gguf_type type, const void *data, size_t i) {\n"
    "    switch (type) {\n"
    "        case GGUF_TYPE_UINT8:  return ((const uint8_t  *) data)[i];\n"
    "        case GGUF_TYPE_INT8:   return ((const int8_t   *) data)[i];\n"
    "        case GGUF_TYPE_UINT16: return ((const uint16_t *) data)[i];\n"
    "        case GGUF_TYPE_INT16:  return ((const int16_t  *) data)[i];\n"
    "        case GGUF_TYPE_UINT32: return (long) ((const uint32_t *) data)[i];\n"
    "        case GGUF_TYPE_INT32:  return ((const int32_t  *) data)[i];\n"
    "        case GGUF_TYPE_UINT64: return (long) ((const uint64_t *) data)[i];\n"
    "        case GGUF_TYPE_INT64:  return (long) ((const int64_t  *) data)[i];\n"
    "        case GGUF_TYPE_BOOL:   return ((const int8_t   *) data)[i] != 0;\n"
    "        default:               return -1;\n"
    "    }\n"
    "}\n\n"
    "// One value per layer: an array gives each layer its own value, a scalar applies to all.\n"
    "static std::vector<long> iaoffline_gguf_per_layer(const gguf_context *g, const std::string &key,\n"
    "                                                  int n_layer, long fallback) {\n"
    "    std::vector<long> values(n_layer, fallback);\n"
    "    const int64_t id = gguf_find_key(g, key.c_str());\n"
    "    if (id < 0) return values;\n"
    "    const enum gguf_type type = gguf_get_kv_type(g, id);\n"
    "    if (type == GGUF_TYPE_ARRAY) {\n"
    "        const enum gguf_type item = gguf_get_arr_type(g, id);\n"
    "        if (item == GGUF_TYPE_STRING || item == GGUF_TYPE_ARRAY) return values;\n"
    "        const size_t n = std::min<size_t>(gguf_get_arr_n(g, id), (size_t) n_layer);\n"
    "        const void *data = n > 0 ? gguf_get_arr_data(g, id) : nullptr;\n"
    "        for (size_t i = 0; i < n; i++) values[i] = iaoffline_gguf_number(item, data, i);\n"
    "    } else if (type != GGUF_TYPE_STRING) {\n"
    "        std::fill(values.begin(), values.end(), iaoffline_gguf_number(type, gguf_get_val_data(g, id), 0));\n"
    "    }\n"
    "    return values;\n"
    "}\n\n"
    "static iaoffline_kv_cost iaoffline_read_kv_cost(const char *path) {\n"
    "    iaoffline_kv_cost cost;\n"
    "    gguf_init_params params = { /*.no_alloc =*/ true, /*.ctx =*/ nullptr };\n"
    "    gguf_context *g = gguf_init_from_file(path, params);\n"
    "    if (g == nullptr) return cost;\n"
    "    const int64_t arch_id = gguf_find_key(g, \"general.architecture\");\n"
    "    if (arch_id >= 0 && gguf_get_kv_type(g, arch_id) == GGUF_TYPE_STRING) {\n"
    "        const std::string arch = gguf_get_val_str(g, arch_id);\n"
    "        auto scalar = [&](const char *suffix, long fallback) {\n"
    "            return iaoffline_gguf_per_layer(g, arch + suffix, 1, fallback)[0];\n"
    "        };\n"
    "        const int n_layer = (int) scalar(\".block_count\", 0);\n"
    "        if (n_layer > 0) {\n"
    "            const auto heads = iaoffline_gguf_per_layer(g, arch + \".attention.head_count\", n_layer, 0);\n"
    "            const auto heads_kv = iaoffline_gguf_per_layer(g, arch + \".attention.head_count_kv\", n_layer, -1);\n"
    "            const auto swa_layer = iaoffline_gguf_per_layer(g, arch + \".attention.sliding_window_pattern\", n_layer, 0);\n"
    "            const long n_embd = scalar(\".embedding_length\", 0);\n"
    "            const long key_length = scalar(\".attention.key_length\", 0);\n"
    "            const long value_length = scalar(\".attention.value_length\", 0);\n"
    "            const long key_length_swa = scalar(\".attention.key_length_swa\", 0);\n"
    "            const long value_length_swa = scalar(\".attention.value_length_swa\", 0);\n"
    "            const long n_swa = scalar(\".attention.sliding_window\", 0);\n"
    "            const long shared = scalar(\".attention.shared_kv_layers\", 0);\n"
    "            const long interval = scalar(\".full_attention_interval\", 0);\n"
    "            for (int il = 0; il < n_layer; il++) {\n"
    "                if (il >= n_layer - shared) continue;                    // reuses an earlier cache\n"
    "                if (interval > 1 && (il + 1) % interval != 0) continue;  // recurrent (Qwen3.5/3.6)\n"
    "                const long kv = heads_kv[il] >= 0 ? heads_kv[il] : heads[il];\n"
    "                if (kv <= 0) continue;                                   // no attention in this layer\n"
    "                long k = key_length > 0 ? key_length : n_embd / std::max(1L, heads[il]);\n"
    "                long v = value_length > 0 ? value_length : k;\n"
    "                if (n_swa > 0 && swa_layer[il] > 0) {\n"
    "                    if (key_length_swa > 0) k = key_length_swa;\n"
    "                    if (value_length_swa > 0) v = value_length_swa;\n"
    "                    cost.per_swa_cell += kv * (k + v) * 18.0 / 32.0;\n"
    "                } else {\n"
    "                    cost.per_token += kv * (k + v) * 18.0 / 32.0;\n"
    "                }\n"
    "            }\n"
    "            cost.swa_cells = n_swa + BATCH_SIZE;\n"
    "            cost.valid = cost.per_token > 0.0 || cost.per_swa_cell > 0.0;\n"
    "        }\n"
    "    }\n"
    "    gguf_free(g);\n"
    "    return cost;\n"
    "}\n\n"
    "// Fallback when the GGUF header cannot be read: every layer counted as attention with the\n"
    "// first layer's heads. Hybrids that declare full_attention_interval (Qwen3.5/3.6) keep a\n"
    "// cache only in those layers. Overestimating only yields a smaller context.\n"
    "static double iaoffline_kv_bytes_per_token(const llama_model *model) {\n"
    "    int n_layer = std::max(1, llama_model_n_layer(model));\n"
    "    const int full_attention_interval = iaoffline_meta_int(model, \".full_attention_interval\");\n"
    "    if (full_attention_interval > 1) n_layer = std::max(1, n_layer / full_attention_interval);\n"
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
    "    double per_token = iaoffline_kv_bytes_per_token(model);\n"
    "    double sliding_window_bytes = 0.0;\n"
    "    if (g_kv_cost.valid) {\n"
    "        per_token = g_kv_cost.per_token;\n"
    "        sliding_window_bytes = g_kv_cost.per_swa_cell * g_kv_cost.swa_cells;\n"
    "    }\n"
    "    const double growing_bytes = std::max(0.0, budget_mb * 1024.0 * 1024.0 - sliding_window_bytes);\n"
    "    if (per_token > 0.0) context = std::min<long>(context, (long) (growing_bytes / per_token));\n"
    "    context = context / 256 * 256;\n"
    "    const int floor = trained > 0 ? std::min(IAOFFLINE_MIN_CONTEXT, trained) : IAOFFLINE_MIN_CONTEXT;\n"
    "    context = std::max<long>(context, floor);\n"
    "    LOGi(\"%s: KV budget %ld MB (%.0f MB sliding window), %.0f bytes/token, trained %d -> context %ld\",\n"
    "         __func__, budget_mb, sliding_window_bytes / (1024.0 * 1024.0), per_token, trained, context);\n"
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
    "    // The attention layout, read from the file itself, sizes the context in prepare().\n"
    "    g_kv_cost = model ? iaoffline_read_kv_cost(model_path) : iaoffline_kv_cost();\n"
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
    "    // Only the last token's logits are ever read, so reserve one output row instead of one\n"
    "    // per batch token; with Qwen3.5 9B's 248K-token vocabulary the compute buffer drops\n"
    "    // from 509 MB to 125 MB.\n"
    "    ctx_params.n_outputs_max = 1;\n"
    "    // Sliding-window layers (Gemma 4, OLMo 3, gpt-oss) keep only the window, as the KV budget\n"
    "    // assumes; the llama.cpp default gives them a full-size cache (610 MB more for Gemma 4 E4B\n"
    "    // at 55K tokens). Such a context cannot be shifted, so a full one ends the answer instead.\n"
    "    ctx_params.swa_full = false;\n"
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

# The upstream context shift drops the older half of the conversation. Recurrent and
# hybrid models (Qwen3.5/3.6, Nemotron-H, Granite-H, LFM2) cannot drop part of their
# state, so it used to leave overlapping positions behind, and on multi-position RoPE
# (Qwen3.5/3.6) llama.cpp aborts the whole app. Those models now stop the answer (or
# reject the prompt) when the context is full. The stop reason and the context in use
# are published for the app, which decides from them whether the next message can
# continue the conversation that is already in the context.
replace_once(
    "static void shift_context() {\n"
    "    const int n_discard = (current_position - system_prompt_position) / 2;\n"
    "    LOGi(\"%s: Discarding %d tokens\", __func__, n_discard);\n"
    "    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);\n"
    "    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);\n"
    "    current_position -= n_discard;\n"
    "    LOGi(\"%s: Context shifting done! Current position: %d\", __func__, current_position);\n"
    "}\n",
    "static bool shift_context() {\n"
    "    llama_memory_t mem = llama_get_memory(g_context);\n"
    "    const int n_discard = (current_position - system_prompt_position) / 2;\n"
    "    // seq_rm fails without changing anything when the state cannot lose a range.\n"
    "    if (n_discard <= 0 || !llama_memory_can_shift(mem) ||\n"
    "        !llama_memory_seq_rm(mem, 0, system_prompt_position, system_prompt_position + n_discard)) {\n"
    "        LOGw(\"%s: this model's context cannot be shifted\", __func__);\n"
    "        return false;\n"
    "    }\n"
    "    LOGi(\"%s: Discarding %d tokens\", __func__, n_discard);\n"
    "    llama_memory_seq_add(mem, 0, system_prompt_position + n_discard, current_position, -n_discard);\n"
    "    current_position -= n_discard;\n"
    "    LOGi(\"%s: Context shifting done! Current position: %d\", __func__, current_position);\n"
    "    return true;\n"
    "}\n\n"
    "// Read back by the app: why the last answer stopped (running, eog, limit, context, error)\n"
    "// and how many context positions are in use.\n"
    "static void iaoffline_publish_stop(const char *reason) {\n"
    "    setenv(\"IAOFFLINE_LAST_STOP\", reason, 1);\n"
    "    setenv(\"IAOFFLINE_CONTEXT_USED\", std::to_string(current_position).c_str(), 1);\n"
    "}\n",
)
# Batch positions follow current_position, which a shift moves back; upstream kept
# counting from the old start and left a gap.
replace_once(
    "        // Shift context if current batch cannot fit into the context\n"
    "        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {\n"
    "            LOGw(\"%s: Current batch won't fit into context! Shifting...\", __func__);\n"
    "            shift_context();\n"
    "        }\n",
    "        // Shift context if current batch cannot fit into the context\n"
    "        if (current_position + cur_batch_size >= (int) llama_n_ctx(context) - OVERFLOW_HEADROOM) {\n"
    "            LOGw(\"%s: Current batch won't fit into context! Shifting...\", __func__);\n"
    "            if (!shift_context()) return 3;\n"
    "        }\n",
)
replace_once(
    "            const llama_pos position = start_pos + i + j;\n",
    "            const llama_pos position = current_position + j;\n",
)
replace_once(
    "            LOGe(\"%s: llama_decode failed w/ %d\", __func__, decode_result);\n"
    "            return 1;\n"
    "        }\n"
    "    }\n"
    "    return 0;\n",
    "            LOGe(\"%s: llama_decode failed w/ %d\", __func__, decode_result);\n"
    "            return 1;\n"
    "        }\n"
    "        current_position += cur_batch_size;\n"
    "    }\n"
    "    return 0;\n",
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
# decode_tokens_in_batches now advances current_position itself. The stop position
# counted the prompt twice, letting answers run past n_predict.
replace_once(
    "    // Decode user tokens in batches\n"
    "    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return 2;\n"
    "    }\n"
    "\n"
    "    // Update position\n"
    "    current_position += user_prompt_size;\n"
    "    stop_generation_position = current_position + user_prompt_size + n_predict;\n",
    "    // Decode user tokens in batches; this advances current_position. A full context that\n"
    "    // this model cannot shift is reported as 3.\n"
    "    const int decode_result = decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true);\n"
    "    if (decode_result) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return decode_result == 3 ? 3 : 2;\n"
    "    }\n"
    "\n"
    "    // At most n_predict tokens of answer\n"
    "    stop_generation_position = current_position + n_predict;\n",
)
replace_once(
    "    // Infinite text generation via context shifting\n"
    "    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {\n"
    "        LOGw(\"%s: Context full! Shifting...\", __func__);\n"
    "        shift_context();\n"
    "    }\n"
    "\n"
    "    // Stop if reaching the marked position\n"
    "    if (current_position >= stop_generation_position) {\n"
    "        LOGw(\"%s: STOP: hitting stop position: %d\", __func__, stop_generation_position);\n"
    "        return nullptr;\n"
    "    }\n",
    "    // Infinite text generation via context shifting, where the model allows it\n"
    "    if (current_position >= (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {\n"
    "        LOGw(\"%s: Context full! Shifting...\", __func__);\n"
    "        if (!shift_context()) {\n"
    "            iaoffline_publish_stop(\"context\");\n"
    "            return nullptr;\n"
    "        }\n"
    "    }\n"
    "\n"
    "    // Stop if reaching the marked position\n"
    "    if (current_position >= stop_generation_position) {\n"
    "        LOGw(\"%s: STOP: hitting stop position: %d\", __func__, stop_generation_position);\n"
    "        iaoffline_publish_stop(\"limit\");\n"
    "        return nullptr;\n"
    "    }\n",
)
replace_once(
    "        LOGe(\"%s: llama_decode() failed for generated token\", __func__);\n"
    "        return nullptr;\n",
    "        LOGe(\"%s: llama_decode() failed for generated token\", __func__);\n"
    "        iaoffline_publish_stop(\"error\");\n"
    "        return nullptr;\n",
)
replace_once(
    "        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());\n"
    "        return nullptr;\n",
    "        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());\n"
    "        g_last_eog_token = new_token_id;\n"
    "        iaoffline_publish_stop(\"eog\");\n"
    "        return nullptr;\n",
)

# The app now keeps the model loaded and sends a new system prompt for each conversation
# it rebuilds, so reset the sampler's token history with the rest of the conversation.
# A GGUF without a chat template gets a plain transcript instead of the bare texts glued
# together. The BOS token is added once, at the start of the sequence; upstream also put
# one before every user message of models that use BOS (Llama, Gemma, Mistral).
replace_once(
    "    // Format system prompt if applicable\n"
    "    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());\n"
    "    if (has_chat_template) {\n"
    "        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);\n"
    "    }\n"
    "    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);\n"
    "\n"
    "    // Tokenize system prompt\n"
    "    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,\n"
    "                                               has_chat_template, has_chat_template);\n",
    "    // Format system prompt; without a chat template, as a plain transcript\n"
    "    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());\n"
    "    if (!has_chat_template) g_template_mode = iaoffline_template_mode::PLAIN;\n"
    "    formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);\n"
    "    g_conversation_template_mode = g_template_mode;\n"
    "    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);\n"
    "    common_sampler_reset(g_sampler);\n"
    "\n"
    "    // Tokenize system prompt\n"
    "    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,\n"
    "                                               /* add_special */ true, has_chat_template);\n",
)
# A user prompt after a finished answer continues the conversation already in the
# context (see iaoffline_format_continuation). If it cannot be formatted, nothing is
# changed and 4 tells the app to rebuild the conversation.
replace_once(
    "    // Reset short-term states\n"
    "    reset_short_term_states();\n"
    "\n"
    "    // Obtain and tokenize user prompt\n",
    "    // Reset short-term states\n"
    "    reset_short_term_states();\n"
    "    iaoffline_publish_stop(\"running\");\n"
    "\n"
    "    // Obtain and tokenize user prompt\n",
)
replace_once(
    "    // Format user prompt if applicable\n"
    "    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());\n"
    "    if (has_chat_template) {\n"
    "        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);\n"
    "    }\n"
    "    env->ReleaseStringUTFChars(juser_prompt, user_prompt);\n"
    "\n"
    "    // Decode formatted user prompts\n"
    "    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);\n",
    "    // Format user prompt (a plain transcript without a chat template)\n"
    "    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());\n"
    "    env->ReleaseStringUTFChars(juser_prompt, user_prompt);\n"
    "    const std::string user_text = formatted_user_prompt;\n"
    "    if (!chat_msgs.empty() && chat_msgs.back().role == ROLE_ASSISTANT) {\n"
    "        if (g_template_mode != g_conversation_template_mode ||\n"
    "            !iaoffline_format_continuation(user_text, formatted_user_prompt)) {\n"
    "            LOGw(\"%s: cannot continue this conversation in the context\", __func__);\n"
    "            return 4;\n"
    "        }\n"
    "    } else {\n"
    "        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_text);\n"
    "    }\n"
    "\n"
    "    // Decode formatted user prompts\n"
    "    auto user_tokens = common_tokenize(g_context, formatted_user_prompt,\n"
    "                                       /* add_special */ false, has_chat_template);\n",
)

replace_once(
    "static void reset_long_term_states(const bool clear_kv_cache = true) {\n"
    "    chat_msgs.clear();\n",
    "// How chat turns are formatted for the loaded model; see chat_add_and_format().\n"
    "enum class iaoffline_template_mode { BUILT_IN, JINJA, PLAIN };\n"
    "static iaoffline_template_mode g_template_mode = iaoffline_template_mode::BUILT_IN;\n"
    "// Mode chosen for the system prompt of the conversation in the context.\n"
    "static iaoffline_template_mode g_conversation_template_mode = iaoffline_template_mode::BUILT_IN;\n"
    "// End-of-generation token that closed the last answer, already in the context.\n"
    "static llama_token g_last_eog_token = LLAMA_TOKEN_NULL;\n\n"
    "static void reset_long_term_states(const bool clear_kv_cache = true) {\n"
    "    chat_msgs.clear();\n"
    "    g_template_mode = iaoffline_template_mode::BUILT_IN;\n"
    "    g_last_eog_token = LLAMA_TOKEN_NULL;\n",
)
replace_once(
    "    auto formatted = common_chat_format_single(\n"
    "            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);\n",
    "    // An exception escaping into JNI aborts the app, so an unsupported template falls back to\n"
    "    // the model's own Jinja template and, failing that, to a plain transcript.\n"
    "    std::string formatted;\n"
    "    const bool add_assistant = role == ROLE_USER;\n"
    "    if (g_template_mode == iaoffline_template_mode::BUILT_IN) {\n"
    "        try {\n"
    "            formatted = common_chat_format_single(\n"
    "                    g_chat_templates.get(), chat_msgs, new_msg, add_assistant, /* use_jinja */ false);\n"
    "        } catch (const std::exception &e) {\n"
    "            LOGw(\"%s: built-in chat template unavailable (%s); using the model's Jinja template\",\n"
    "                 __func__, e.what());\n"
    "            g_template_mode = iaoffline_template_mode::JINJA;\n"
    "        }\n"
    "    }\n"
    "    if (g_template_mode == iaoffline_template_mode::JINJA) {\n"
    "        try {\n"
    "            formatted = common_chat_format_single(\n"
    "                    g_chat_templates.get(), chat_msgs, new_msg, add_assistant, /* use_jinja */ true);\n"
    "        } catch (const std::exception &e) {\n"
    "            LOGe(\"%s: Jinja chat template failed (%s); using a plain transcript\", __func__, e.what());\n"
    "            g_template_mode = iaoffline_template_mode::PLAIN;\n"
    "        }\n"
    "    }\n"
    "    if (g_template_mode == iaoffline_template_mode::PLAIN) {\n"
    "        formatted = role == ROLE_USER ? \"\\nUser: \" + content + \"\\nAssistant: \" : content + \"\\n\";\n"
    "    }\n",
)
# Continuing a conversation after an answer that ended with its end-of-generation token:
# append only what the template puts after that answer (the end of its turn, the new
# user turn and the assistant header). Templates may render earlier turns differently
# once another message follows (Nemotron drops the reasoning, Qwen3 older thinking), so
# upstream's "diff of two renderings" is not safe; the answer is found by its last
# characters in the new rendering instead. When it cannot be found the app rebuilds.
replace_once(
    "    chat_msgs.push_back(new_msg);\n"
    "    LOGi(\"%s: Formatted and added %s message: \\n%s\\n\", __func__, role.c_str(), formatted.c_str());\n"
    "    return formatted;\n"
    "}\n",
    "    chat_msgs.push_back(new_msg);\n"
    "    LOGi(\"%s: Formatted and added %s message: \\n%s\\n\", __func__, role.c_str(), formatted.c_str());\n"
    "    return formatted;\n"
    "}\n\n"
    "static bool iaoffline_format_continuation(const std::string &content, std::string &formatted) {\n"
    "    common_chat_msg new_msg;\n"
    "    new_msg.role = ROLE_USER;\n"
    "    new_msg.content = content;\n"
    "    if (g_template_mode == iaoffline_template_mode::PLAIN) {\n"
    "        formatted = \"\\nUser: \" + content + \"\\nAssistant: \";\n"
    "        chat_msgs.push_back(new_msg);\n"
    "        return true;\n"
    "    }\n"
    "    std::string answer = chat_msgs.back().content;\n"
    "    const size_t answer_end = answer.find_last_not_of(\" \\t\\r\\n\");\n"
    "    if (answer_end == std::string::npos) return false;\n"
    "    answer.erase(answer_end + 1);\n"
    "    std::string full;\n"
    "    try {\n"
    "        common_chat_templates_inputs inputs;\n"
    "        inputs.use_jinja = g_template_mode == iaoffline_template_mode::JINJA;\n"
    "        inputs.add_bos = llama_vocab_get_add_bos(llama_model_get_vocab(g_model));\n"
    "        inputs.add_eos = llama_vocab_get_add_eos(llama_model_get_vocab(g_model));\n"
    "        inputs.messages = chat_msgs;\n"
    "        inputs.messages.push_back(new_msg);\n"
    "        inputs.add_generation_prompt = true;\n"
    "        full = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;\n"
    "    } catch (const std::exception &e) {\n"
    "        LOGw(\"%s: chat template failed (%s)\", __func__, e.what());\n"
    "        return false;\n"
    "    }\n"
    "    // Only the end of a short answer survives templates that drop its reasoning (Gemma 4), so\n"
    "    // shorter endings are tried too. After the answer comes only the new turn, so the last match\n"
    "    // is the answer unless the new message itself contains that ending.\n"
    "    size_t end = std::string::npos;\n"
    "    for (const size_t length : {64, 32, 16}) {\n"
    "        const std::string tail = answer.size() > length ? answer.substr(answer.size() - length) : answer;\n"
    "        if (content.find(tail) != std::string::npos) return false;\n"
    "        const size_t at = full.rfind(tail);\n"
    "        if (at != std::string::npos) {\n"
    "            end = at + tail.size();\n"
    "            break;\n"
    "        }\n"
    "    }\n"
    "    if (end == std::string::npos) return false;\n"
    "    formatted = full.substr(end);\n"
    "    // The generated end-of-turn token is already in the context, with whatever whitespace the\n"
    "    // model put before it; drop the template's copy of both.\n"
    "    if (g_last_eog_token != LLAMA_TOKEN_NULL) {\n"
    "        const std::string eog = common_token_to_piece(g_context, g_last_eog_token, true);\n"
    "        const size_t marker = formatted.find_first_not_of(\" \\t\\r\\n\");\n"
    "        if (!eog.empty() && marker != std::string::npos && formatted.compare(marker, eog.size(), eog) == 0) {\n"
    "            formatted.erase(0, marker + eog.size());\n"
    "        }\n"
    "    }\n"
    "    chat_msgs.push_back(new_msg);\n"
    "    LOGi(\"%s: Continuing the conversation with: \\n%s\\n\", __func__, formatted.c_str());\n"
    "    return true;\n"
    "}\n",
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
