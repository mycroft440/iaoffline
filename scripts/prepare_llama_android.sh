#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# v0.7.0 (b11669) is the first release with the Hadamard activation runtime that the ternary
# Bonsai 2 models need (b11555+); older builds load their Q2_0 files and output gibberish.
LLAMA_TAG="${LLAMA_CPP_TAG:-v0.7.0}"
LLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
AAR_DEST="${ROOT_DIR}/app/libs/llama-android.aar"
ENGINE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
INTERFACE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt"
AI_CHAT_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
MODEL_FILE="${LLAMA_DIR}/src/llama-model.cpp"
MODEL_LOADER_FILE="${LLAMA_DIR}/src/llama-model-loader.cpp"

mkdir -p "${ROOT_DIR}/third_party" "${ROOT_DIR}/app/libs"

if [[ ! -d "${LLAMA_DIR}/.git" ]]; then
  git clone --depth 1 --branch "${LLAMA_TAG}" https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}"
else
  git -C "${LLAMA_DIR}" fetch --depth 1 origin "${LLAMA_TAG}"
  git -C "${LLAMA_DIR}" checkout --force FETCH_HEAD
  git -C "${LLAMA_DIR}" clean -fdx
fi

# The binding resets State.Error without unloading a model that may already have
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
        raise SystemExit(f"Unexpected llama.cpp Android binding source shape: expected 1 match, got {count}")
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

# Conversations continue on the loaded model instead of reloading it for every message:
# the native side already resets its state when it gets a new system prompt, so the
# "right after load" restriction only forced a reload (and a full re-read of the history).
replace_once(
    "            check(_readyForSystemPrompt) { \"System prompt must be set ** RIGHT AFTER ** model loaded!\" }\n",
    "",
)
# Earlier turns are fed as real chat turns, formatted by the model's own template.
replace_once(
    "    @FastNative\n    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int\n",
    "    @FastNative\n    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int\n\n"
    "    private external fun processHistoryMessage(role: String, content: String): Int\n",
)
replace_once(
    "    /**\n     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]\n     */\n",
    "    /**\n"
    "     * Adds an earlier turn of the conversation after the system prompt, without generating.\n"
    "     */\n"
    "    override suspend fun addHistoryMessage(role: String, content: String) =\n"
    "        withContext(llamaDispatcher) {\n"
    "            check(_state.value is InferenceEngine.State.ModelReady) {\n"
    "                \"Cannot add history in ${_state.value.javaClass.simpleName}!\"\n"
    "            }\n"
    "            _state.value = InferenceEngine.State.ProcessingSystemPrompt\n"
    "            val result = processHistoryMessage(role, content)\n"
    "            _state.value = InferenceEngine.State.ModelReady\n"
    "            if (result != 0) throw IOException(\"Failed to process history message: $result\")\n"
    "        }\n\n"
    "    /**\n     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]\n     */\n",
)
# A failed user prompt used to end the flow silently and leave the engine stuck in
# ProcessingUserPrompt, so the next request could not even unload the model.
replace_once(
    "                if (result != 0) {\n"
    "                    Log.e(TAG, \"Failed to process user prompt: $result\")\n"
    "                    return@flow\n"
    "                }\n",
    "                if (result != 0) {\n"
    "                    Log.e(TAG, \"Failed to process user prompt: $result\")\n"
    "                    throw IOException(\"Failed to process user prompt: $result\")\n"
    "                }\n",
)

# Every native call runs on one thread of its own. limitedParallelism(1) only runs one call at a
# time, on whichever IO thread is free: each thread that started a computation became the root of
# its own OpenMP team (the CPU threads of llama.cpp on arm64), so the next call, on another thread,
# created a new team while the previous one still spun for 200 ms on the same cores.
replace_once(
    "    @OptIn(ExperimentalCoroutinesApi::class)\n"
    "    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)\n",
    "    private val llamaDispatcher = Executors.newSingleThreadExecutor { task ->\n"
    "        Thread(task, \"llama-native\").apply { isDaemon = true }\n"
    "    }.asCoroutineDispatcher()\n",
)
replace_once(
    "import kotlinx.coroutines.Dispatchers\n"
    "import kotlinx.coroutines.ExperimentalCoroutinesApi\n",
    "",
)
replace_once(
    "import kotlinx.coroutines.cancel\n",
    "import kotlinx.coroutines.asCoroutineDispatcher\nimport kotlinx.coroutines.cancel\n",
)
replace_once(
    "import java.io.IOException\n",
    "import java.io.IOException\nimport java.util.concurrent.Executors\n",
)
# @FastNative keeps the thread runnable for the garbage collector, which then waits for the call
# to return before it can pause the process: loading a model, reading a long prompt or writing a
# token froze the AI process's other threads (the answer's progress, a request to stop) meanwhile.
for name in ("load", "prepare", "benchModel", "processSystemPrompt", "processUserPrompt", "generateNextToken", "unload"):
    replace_once(
        f"    @FastNative\n    private external fun {name}(",
        f"    private external fun {name}(",
    )

path.write_text(text)
print("Applied IA Offline inference-engine recovery patch")
PY

python3 - "${INTERFACE_FILE}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = "    suspend fun setSystemPrompt(systemPrompt: String)\n"
if text.count(old) != 1:
    raise SystemExit("Unexpected llama.cpp Android binding interface shape")
text = text.replace(
    old,
    old + "\n"
    "    /**\n"
    "     * Adds an earlier turn (\"user\" or \"assistant\") after the system prompt, without generating.\n"
    "     */\n"
    "    suspend fun addHistoryMessage(role: String, content: String)\n",
    1,
)
path.write_text(text)
print("Applied IA Offline inference-engine interface patch")
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
#    skip the repacked CPU buffers from the start. Tensors the architecture marks for
#    on-demand reading (per-layer embeddings) are always read from the file as needed.
# 8. Use a Q4_0 KV cache (about a quarter of F16) when the model supports flash
#    attention, falling back to a smaller F16 context otherwise.
# 9. Format chat turns with llama.cpp's built-in templates and, when a model's
#    template is not one of them (Gemma 4), render the model's own Jinja template
#    instead of letting the exception abort the process; a plain transcript is the
#    last resort.
python3 - "${AI_CHAT_FILE}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected llama.cpp Android binding native source shape: expected 1 match, got {count}")
    text = text.replace(old, new, 1)

# 10. Sample with the settings the model's authors recommend in the GGUF
#     (general.sampling.*) instead of the binding's fixed 0.3 temperature, plus the presence
#     penalty the app passes in IAOFFLINE_PRESENCE_PENALTY for models whose cards ask for one
#     (there is no GGUF key for it). Reasoning and low-bit models loop or degrade at a low fixed
#     temperature; the recommended values cost no memory or compute.
replace_once(
    "static common_sampler *new_sampler(float temp) {\n"
    "    common_params_sampling sparams;\n"
    "    sparams.temp = temp;\n"
    "    return common_sampler_init(g_model, sparams);\n"
    "}",
    "static bool iaoffline_meta_float(llama_model_meta_key key, float &dst, float min_value, float max_value) {\n"
    "    char buf[64] = {0};\n"
    "    if (llama_model_meta_val_str(g_model, llama_model_meta_key_str(key), buf, sizeof(buf)) <= 0) return false;\n"
    "    char *end = nullptr;\n"
    "    const float value = strtof(buf, &end);\n"
    "    if (end == buf || !(value >= min_value && value <= max_value)) return false;\n"
    "    dst = value;\n"
    "    return true;\n"
    "}\n\n"
    "static common_sampler *new_sampler(float temp) {\n"
    "    common_params_sampling sparams;\n"
    "    sparams.temp = temp;\n"
    "    float top_k = (float) sparams.top_k;\n"
    "    float last_n = (float) sparams.penalty_last_n;\n"
    "    const bool recommended = iaoffline_meta_float(LLAMA_MODEL_META_KEY_SAMPLING_TEMP, sparams.temp, 0.0f, 2.0f);\n"
    "    if (iaoffline_meta_float(LLAMA_MODEL_META_KEY_SAMPLING_TOP_K, top_k, 0.0f, 1000.0f)) sparams.top_k = (int32_t) top_k;\n"
    "    iaoffline_meta_float(LLAMA_MODEL_META_KEY_SAMPLING_TOP_P, sparams.top_p, 0.01f, 1.0f);\n"
    "    iaoffline_meta_float(LLAMA_MODEL_META_KEY_SAMPLING_MIN_P, sparams.min_p, 0.0f, 0.99f);\n"
    "    iaoffline_meta_float(LLAMA_MODEL_META_KEY_SAMPLING_PENALTY_REPEAT, sparams.penalty_repeat, 1.0f, 1.5f);\n"
    "    if (iaoffline_meta_float(LLAMA_MODEL_META_KEY_SAMPLING_PENALTY_LAST_N, last_n, 0.0f, 4096.0f)) sparams.penalty_last_n = (int32_t) last_n;\n"
    "    const char *presence = getenv(\"IAOFFLINE_PRESENCE_PENALTY\");\n"
    "    if (presence != nullptr) {\n"
    "        const float value = strtof(presence, nullptr);\n"
    "        if (value > 0.0f && value <= 2.0f) sparams.penalty_present = value;\n"
    "    }\n"
    "    LOGi(\"%s: %s sampling: temp %.2f, top_k %d, top_p %.2f, min_p %.2f, repeat %.2f, presence %.2f\",\n"
    "         __func__, recommended ? \"model-recommended\" : \"default\", sparams.temp, sparams.top_k,\n"
    "         sparams.top_p, sparams.min_p, sparams.penalty_repeat, sparams.penalty_present);\n"
    "    return common_sampler_init(g_model, sparams);\n"
    "}",
)

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
    "// Bytes of Q4_0 KV cache per context token (18 bytes per 32 values, K and V). Hybrids\n"
    "// that declare full_attention_interval (Qwen3.5/3.6) keep a cache only in those layers.\n"
    "// Other hybrid and sliding-window models need less than estimated; overestimating only\n"
    "// yields a smaller context.\n"
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
    "    // Per-layer embedding tables (Gemma 4 E2B/E4B: about 1.8 GB of a 3.3 GB E2B file) stay in\n"
    "    // the file and only the rows of the tokens in use are read, a few KB per token. Upstream\n"
    "    // does this only above 4 GiB; on a phone it cuts the model's RAM by about half with the\n"
    "    // same output and speed.\n"
    "    model_params.lazy_mode = LLAMA_LAZY_MODE_ON;\n"
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
# The compute buffer grows with the micro-batch; for models with large vocabularies (Gemma,
# Qwen) 512 tokens take about 570 MB of it. 256 halves that, and measured on the same CPU the
# prompt is read at least as fast. Calls still pass up to 512 tokens; llama.cpp splits them.
replace_once(
    "    ctx_params.n_ubatch = BATCH_SIZE;\n",
    "    ctx_params.n_ubatch = BATCH_SIZE / 2;\n",
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

replace_once(
    "static void reset_long_term_states(const bool clear_kv_cache = true) {\n"
    "    chat_msgs.clear();\n",
    "// How chat turns are formatted for the loaded model; see chat_add_and_format().\n"
    "enum class iaoffline_template_mode { BUILT_IN, JINJA, PLAIN };\n"
    "static iaoffline_template_mode g_template_mode = iaoffline_template_mode::BUILT_IN;\n\n"
    "static void reset_long_term_states(const bool clear_kv_cache = true) {\n"
    "    chat_msgs.clear();\n"
    "    g_template_mode = iaoffline_template_mode::BUILT_IN;\n",
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

# 11. Multi-turn conversations without reloading. Earlier turns are added as real chat turns
#     (processHistoryMessage, formatted by the model's template without the assistant
#     generation prompt) instead of being pasted into the system prompt; a new system prompt
#     also resets the sampler. After each answer the app reads IAOFFLINE_SESSION_CLEAN
#     (1 when the answer ended normally, so the next message can continue the same KV cache)
#     and IAOFFLINE_SESSION_TOKENS (context in use).
replace_once(
    "static std::string chat_add_and_format(const std::string &role, const std::string &content) {",
    "// common_chat_format_single with the reasoning switch the app sets: IAOFFLINE_ENABLE_THINKING=0\n"
    "// renders templates that read enable_thinking (Gemma 4's) with reasoning off; unset keeps it on.\n"
    "static std::string iaoffline_format_single(const common_chat_msg &new_msg, const bool add_ass, const bool use_jinja) {\n"
    "    const llama_vocab *vocab = llama_model_get_vocab(g_model);\n"
    "    common_chat_templates_inputs inputs;\n"
    "    inputs.use_jinja = use_jinja;\n"
    "    inputs.add_bos = llama_vocab_get_add_bos(vocab);\n"
    "    inputs.add_eos = llama_vocab_get_add_eos(vocab);\n"
    "    const char *thinking = getenv(\"IAOFFLINE_ENABLE_THINKING\");\n"
    "    inputs.enable_thinking = thinking == nullptr || thinking[0] != '0';\n"
    "    std::string fmt_past;\n"
    "    if (!chat_msgs.empty()) {\n"
    "        inputs.messages = chat_msgs;\n"
    "        inputs.add_generation_prompt = false;\n"
    "        fmt_past = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt.str();\n"
    "    }\n"
    "    std::ostringstream ss;\n"
    "    if (add_ass && !fmt_past.empty() && fmt_past.back() == '\\n') ss << \"\\n\";\n"
    "    inputs.messages.push_back(new_msg);\n"
    "    inputs.add_generation_prompt = add_ass;\n"
    "    const std::string fmt_new = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt.str();\n"
    "    ss << fmt_new.substr(fmt_past.size(), fmt_new.size() - fmt_past.size());\n"
    "    return ss.str();\n"
    "}\n\n"
    "static std::string chat_add_and_format(const std::string &role, const std::string &content,\n"
    "                                       const bool generation_prompt = true) {",
)
replace_once(
    "            formatted = common_chat_format_single(\n"
    "                    g_chat_templates.get(), chat_msgs, new_msg, add_assistant, /* use_jinja */ false);\n",
    "            formatted = iaoffline_format_single(new_msg, add_assistant, /* use_jinja */ false);\n",
)
replace_once(
    "            formatted = common_chat_format_single(\n"
    "                    g_chat_templates.get(), chat_msgs, new_msg, add_assistant, /* use_jinja */ true);\n",
    "            formatted = iaoffline_format_single(new_msg, add_assistant, /* use_jinja */ true);\n",
)
replace_once(
    "    const bool add_assistant = role == ROLE_USER;\n",
    "    // Earlier turns (generation_prompt = false) are formatted without the assistant prompt.\n"
    "    const bool add_assistant = generation_prompt && role == ROLE_USER;\n",
)
replace_once(
    "        formatted = role == ROLE_USER ? \"\\nUser: \" + content + \"\\nAssistant: \" : content + \"\\n\";\n",
    "        if (role == ROLE_USER) {\n"
    "            formatted = \"\\nUser: \" + content + (add_assistant ? \"\\nAssistant: \" : \"\");\n"
    "        } else {\n"
    "            formatted = (generation_prompt ? \"\" : \"\\nAssistant: \") + content + \"\\n\";\n"
    "        }\n",
)
replace_once(
    "    // Reset long-term & short-term states\n"
    "    reset_long_term_states();\n"
    "    reset_short_term_states();\n\n"
    "    // Obtain system prompt from JEnv",
    "    // Reset long-term & short-term states\n"
    "    reset_long_term_states();\n"
    "    reset_short_term_states();\n"
    "    // Repetition penalties must not carry over from another conversation.\n"
    "    common_sampler_reset(g_sampler);\n"
    "    setenv(\"IAOFFLINE_SESSION_CLEAN\", \"0\", 1);\n\n"
    "    // Obtain system prompt from JEnv",
)
# 12. Answer prefill (IAOFFLINE_ASSISTANT_PREFIX), the way chat templates switch reasoning:
#     "<think>\n\n</think>\n\n" turns it off for Qwen3.5-style models, "<think>\n" asks for it.
#     The user turn is also tokenized without a second BOS when it follows earlier text.
replace_once(
    "    // Reset short-term states\n"
    "    reset_short_term_states();\n\n"
    "    // Obtain and tokenize user prompt",
    "    // Reset short-term states\n"
    "    reset_short_term_states();\n"
    "    setenv(\"IAOFFLINE_SESSION_CLEAN\", \"0\", 1);\n\n"
    "    // Obtain and tokenize user prompt",
)
replace_once(
    "    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);\n",
    "    const char *assistant_prefix = getenv(\"IAOFFLINE_ASSISTANT_PREFIX\");\n"
    "    if (has_chat_template && g_template_mode != iaoffline_template_mode::PLAIN &&\n"
    "        assistant_prefix != nullptr && assistant_prefix[0] != '\\0') {\n"
    "        formatted_user_prompt += assistant_prefix;\n"
    "    }\n"
    "    // The template repeats the newline that follows an earlier turn; drop it when that turn\n"
    "    // was decoded with its newline (system prompt and history) so the text matches training.\n"
    "    if (g_kv_ends_with_newline && !formatted_user_prompt.empty() && formatted_user_prompt[0] == '\\n') {\n"
    "        formatted_user_prompt.erase(0, 1);\n"
    "    }\n"
    "    g_kv_ends_with_newline = false;\n"
    "    const bool add_bos = has_chat_template && current_position == 0;\n"
    "    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, add_bos, has_chat_template);\n",
)
replace_once(
    "static iaoffline_template_mode g_template_mode = iaoffline_template_mode::BUILT_IN;\n",
    "static iaoffline_template_mode g_template_mode = iaoffline_template_mode::BUILT_IN;\n"
    "// Whether the last text decoded into the KV cache ended with a newline.\n"
    "static bool g_kv_ends_with_newline = false;\n",
)
replace_once(
    "    g_template_mode = iaoffline_template_mode::BUILT_IN;\n",
    "    g_template_mode = iaoffline_template_mode::BUILT_IN;\n"
    "    g_kv_ends_with_newline = false;\n",
)
replace_once(
    "    // Update position\n"
    "    system_prompt_position = current_position = (int) system_tokens.size();\n"
    "    return 0;\n",
    "    // Update position\n"
    "    system_prompt_position = current_position = (int) system_tokens.size();\n"
    "    g_kv_ends_with_newline = !formatted_system_prompt.empty() && formatted_system_prompt.back() == '\\n';\n"
    "    return 0;\n",
)
replace_once(
    "    stop_generation_position = current_position + user_prompt_size + n_predict;",
    "    stop_generation_position = current_position + n_predict;",
)
replace_once(
    "        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());\n        return nullptr;\n",
    "        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());\n"
    "        setenv(\"IAOFFLINE_SESSION_CLEAN\", \"1\", 1);\n"
    "        setenv(\"IAOFFLINE_SESSION_TOKENS\", std::to_string(current_position).c_str(), 1);\n"
    "        return nullptr;\n",
)
replace_once(
    "static bool is_valid_utf8(const char *string) {",
    "extern \"C\"\n"
    "JNIEXPORT jint JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_processHistoryMessage(\n"
    "        JNIEnv *env,\n"
    "        jobject /*unused*/,\n"
    "        jstring jrole,\n"
    "        jstring jcontent\n"
    ") {\n"
    "    const auto *role_chars = env->GetStringUTFChars(jrole, nullptr);\n"
    "    const auto *content_chars = env->GetStringUTFChars(jcontent, nullptr);\n"
    "    const std::string role = std::string(role_chars) == ROLE_ASSISTANT ? ROLE_ASSISTANT : ROLE_USER;\n"
    "    const std::string content(content_chars);\n"
    "    env->ReleaseStringUTFChars(jrole, role_chars);\n"
    "    env->ReleaseStringUTFChars(jcontent, content_chars);\n\n"
    "    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());\n"
    "    std::string formatted;\n"
    "    if (has_chat_template) {\n"
    "        formatted = chat_add_and_format(role, content, /* generation_prompt */ false);\n"
    "    } else {\n"
    "        formatted = (role == ROLE_USER ? \"\\nUser: \" : \"\\nAssistant: \") + content + \"\\n\";\n"
    "    }\n"
    "    const auto tokens = common_tokenize(g_context, formatted, current_position == 0, has_chat_template);\n"
    "    if (decode_tokens_in_batches(g_context, g_batch, tokens, current_position)) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return 2;\n"
    "    }\n"
    "    current_position += (int) tokens.size();\n"
    "    g_kv_ends_with_newline = !formatted.empty() && formatted.back() == '\\n';\n"
    "    return 0;\n"
    "}\n\n"
    "static bool is_valid_utf8(const char *string) {",
)

# 13. System prompt cache. Decoding the system prompt (instructions, tools, date) is most of the
#     wait before the first answer: about a thousand tokens at 5-20 tokens/s on a phone CPU. Its
#     KV state is saved to the file the app names in IAOFFLINE_PROMPT_CACHE (one per model), up to
#     the text the app marks as changing (IAOFFLINE_SYSTEM_PROMPT_TAIL, the date at the end), so the
#     saved state stays a prefix of the next day's prompt; recurrent and sliding-window caches
#     cannot be cut back to a shorter prefix. The next load restores it and decodes only the rest.
#     llama.cpp rejects a state that does not fit the context (another cache type or model), and
#     the fallback is always the full decode.
replace_once(
    "#include <mutex>\n",
    "#include <mutex>\n#include <cstdio>\n",
)
replace_once(
    "extern \"C\"\n"
    "JNIEXPORT jint JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(",
    "// Restores the longest prefix of [tokens] that the saved state holds; returns its length.\n"
    "static int iaoffline_restore_prompt_cache(const llama_tokens &tokens) {\n"
    "    const char *path = getenv(\"IAOFFLINE_PROMPT_CACHE\");\n"
    "    if (path == nullptr || path[0] == '\\0' || access(path, R_OK) != 0) return 0;\n"
    "    llama_memory_t mem = llama_get_memory(g_context);\n"
    "    size_t n_saved = 0;\n"
    "    if (llama_state_seq_load_file(g_context, path, 0, nullptr, 0, &n_saved) == 0 || n_saved == 0) return 0;\n"
    "    llama_tokens saved(n_saved);\n"
    "    size_t n_loaded = 0;\n"
    "    if (llama_state_seq_load_file(g_context, path, 0, saved.data(), saved.size(), &n_loaded) == 0) {\n"
    "        LOGw(\"%s: the saved system prompt state does not fit this context; decoding the prompt\", __func__);\n"
    "        llama_memory_clear(mem, false);\n"
    "        return 0;\n"
    "    }\n"
    "    saved.resize(n_loaded);\n"
    "    size_t common = 0;\n"
    "    while (common < saved.size() && common < tokens.size() && saved[common] == tokens[common]) common++;\n"
    "    if (common < saved.size()) {\n"
    "        // Drop the saved tokens that differ. Recurrent layers cannot go back, and a sliding-window\n"
    "        // cache must still hold the whole window before the first token decoded next.\n"
    "        const int n_swa = llama_model_n_swa(g_model);\n"
    "        const llama_pos needed = n_swa > 0 ? std::max<llama_pos>(0, (llama_pos) common - n_swa + 1) : 0;\n"
    "        const bool removed = common > 0 && llama_memory_seq_rm(mem, 0, (llama_pos) common, -1);\n"
    "        const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);\n"
    "        if (!removed || pos_min < 0 || pos_min > needed) {\n"
    "            LOGi(\"%s: the saved system prompt shares %zu of %zu tokens but cannot be cut there; decoding the prompt\",\n"
    "                 __func__, common, tokens.size());\n"
    "            llama_memory_clear(mem, false);\n"
    "            return 0;\n"
    "        }\n"
    "    }\n"
    "    LOGi(\"%s: restored %zu of %zu system prompt tokens\", __func__, common, tokens.size());\n"
    "    return (int) common;\n"
    "}\n\n"
    "// Tokens before the changing end of the system prompt (IAOFFLINE_SYSTEM_PROMPT_TAIL): the part\n"
    "// that is saved. Tokenizing that text alone may merge its last characters differently, so only\n"
    "// the tokens both tokenizations share count.\n"
    "static size_t iaoffline_stable_prompt_tokens(const std::string &formatted, const llama_tokens &tokens, const bool special) {\n"
    "    const char *tail = getenv(\"IAOFFLINE_SYSTEM_PROMPT_TAIL\");\n"
    "    if (tail == nullptr || tail[0] == '\\0') return tokens.size();\n"
    "    const size_t at = formatted.rfind(tail);\n"
    "    if (at == std::string::npos || at == 0) return tokens.size();\n"
    "    const auto head = common_tokenize(g_context, formatted.substr(0, at), special, special);\n"
    "    size_t common = 0;\n"
    "    while (common < head.size() && common < tokens.size() && head[common] == tokens[common]) common++;\n"
    "    return common;\n"
    "}\n\n"
    "static void iaoffline_save_prompt_cache(const llama_tokens &tokens) {\n"
    "    const char *path = getenv(\"IAOFFLINE_PROMPT_CACHE\");\n"
    "    if (path == nullptr || path[0] == '\\0') return;\n"
    "    // Written aside and renamed, so a write cut short never leaves a damaged state behind.\n"
    "    const std::string partial = std::string(path) + \".partial\";\n"
    "    if (llama_state_seq_save_file(g_context, partial.c_str(), 0, tokens.data(), tokens.size()) == 0 ||\n"
    "        rename(partial.c_str(), path) != 0) {\n"
    "        unlink(partial.c_str());\n"
    "        LOGw(\"%s: could not save the system prompt state\", __func__);\n"
    "        return;\n"
    "    }\n"
    "    LOGi(\"%s: saved %zu system prompt tokens\", __func__, tokens.size());\n"
    "}\n\n"
    "extern \"C\"\n"
    "JNIEXPORT jint JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(",
)
replace_once(
    "    // Decode system tokens in batches\n"
    "    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return 2;\n"
    "    }\n",
    "    // Restore what an earlier load saved of this prompt, decode the rest of its unchanging part\n"
    "    // and save that, then decode the changing end.\n"
    "    const int stable = (int) iaoffline_stable_prompt_tokens(formatted_system_prompt, system_tokens, has_chat_template);\n"
    "    int position = iaoffline_restore_prompt_cache(system_tokens);\n"
    "    if (position < stable) {\n"
    "        const llama_tokens head(system_tokens.begin() + position, system_tokens.begin() + stable);\n"
    "        if (decode_tokens_in_batches(g_context, g_batch, head, position)) {\n"
    "            LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "            return 2;\n"
    "        }\n"
    "        position = stable;\n"
    "        iaoffline_save_prompt_cache(llama_tokens(system_tokens.begin(), system_tokens.begin() + stable));\n"
    "    }\n"
    "    const llama_tokens rest(system_tokens.begin() + position, system_tokens.end());\n"
    "    if (!rest.empty() && decode_tokens_in_batches(g_context, g_batch, rest, position)) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return 2;\n"
    "    }\n",
)

# 14. Speed of each answer. The time and tokens of reading the user's message and of the answer
#     are published as IAOFFLINE_PERF ("prompt_tokens prompt_ms answer_tokens answer_ms") when the
#     answer ends, for the app's log.
replace_once(
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n",
    "// Speed of the answer in progress, published as IAOFFLINE_PERF when it ends.\n"
    "static int64_t g_perf_prompt_us = 0;\n"
    "static int g_perf_prompt_tokens = 0;\n"
    "static int64_t g_perf_answer_us = 0;\n"
    "static int g_perf_answer_tokens = 0;\n\n"
    "static void iaoffline_publish_perf() {\n"
    "    char value[96];\n"
    "    snprintf(value, sizeof(value), \"%d %lld %d %lld\", g_perf_prompt_tokens, (long long) (g_perf_prompt_us / 1000),\n"
    "             g_perf_answer_tokens, (long long) (g_perf_answer_us / 1000));\n"
    "    setenv(\"IAOFFLINE_PERF\", value, 1);\n"
    "    LOGi(\"%s: prompt %d tokens in %lld ms, answer %d tokens in %lld ms\", __func__, g_perf_prompt_tokens,\n"
    "         (long long) (g_perf_prompt_us / 1000), g_perf_answer_tokens, (long long) (g_perf_answer_us / 1000));\n"
    "}\n\n"
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n",
)
replace_once(
    "    // Decode user tokens in batches\n"
    "    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return 2;\n"
    "    }\n",
    "    // Decode user tokens in batches\n"
    "    const int64_t prompt_start_us = ggml_time_us();\n"
    "    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {\n"
    "        LOGe(\"%s: llama_decode() failed!\", __func__);\n"
    "        return 2;\n"
    "    }\n"
    "    g_perf_prompt_us = ggml_time_us() - prompt_start_us;\n"
    "    g_perf_prompt_tokens = (int) user_tokens.size();\n"
    "    g_perf_answer_us = 0;\n"
    "    g_perf_answer_tokens = 0;\n",
)
replace_once(
    "        LOGw(\"%s: STOP: hitting stop position: %d\", __func__, stop_generation_position);\n"
    "        return nullptr;\n",
    "        LOGw(\"%s: STOP: hitting stop position: %d\", __func__, stop_generation_position);\n"
    "        iaoffline_publish_perf();\n"
    "        return nullptr;\n",
)
replace_once(
    "    // Sample next token\n"
    "    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);\n",
    "    // Sample next token\n"
    "    const int64_t token_start_us = ggml_time_us();\n"
    "    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);\n",
)
replace_once(
    "    // Update position\n"
    "    current_position++;\n",
    "    g_perf_answer_us += ggml_time_us() - token_start_us;\n"
    "    g_perf_answer_tokens++;\n\n"
    "    // Update position\n"
    "    current_position++;\n",
)
replace_once(
    "        setenv(\"IAOFFLINE_SESSION_TOKENS\", std::to_string(current_position).c_str(), 1);\n"
    "        return nullptr;\n",
    "        setenv(\"IAOFFLINE_SESSION_TOKENS\", std::to_string(current_position).c_str(), 1);\n"
    "        iaoffline_publish_perf();\n"
    "        return nullptr;\n",
)

# 15. Mixture-of-experts models larger than the RAM the app may use (IAOFFLINE_MOE_STREAM=1, set
#     by the app from the GGUF): each token uses a few experts per layer (8 of 128 in Gemma 4
#     26B-A4B, about 21 MB per layer), which stay in the file and are read as the router picks
#     them. Unpatched, the CPU threads read them through page faults, a small read at a time while
#     the matrix product waits: about a word per second on a 12 GB Galaxy S22 Ultra.
#     a) As soon as a layer's router has picked its experts, their slices are requested from the
#        kernel all at once (MADV_WILLNEED) from a thread of their own, so the storage reads them
#        in parallel while the CPU starts on them. An eval callback watches the router output
#        (ffn_moe_topk-<layer>), which splits the graph there: a few pauses per token.
#     b) The expert tensors are kept mapped from the file; the rest (attention, shared expert,
#        output; about 2.4 GB in Gemma 4 26B-A4B, read for every token) may then be repacked into
#        RAM, where it is faster and is never dropped and read again, unless IAOFFLINE_LOW_MEMORY=1.
#     c) Loading without mmap would copy the whole model into RAM, so that fallback is skipped.
replace_once(
    "#include <cstdlib>\n",
    "#include <cstdlib>\n"
    "#include <cstring>\n"
    "#include <algorithm>\n"
    "#include <condition_variable>\n"
    "#include <deque>\n"
    "#include <thread>\n"
    "#include <vector>\n"
    "#include <sys/mman.h>\n",
)
replace_once(
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n",
    "// Set by the app (IAOFFLINE_MOE_STREAM=1) for a mixture-of-experts model larger than the RAM it\n"
    "// may use: its experts stay in the file and are read as the router picks them.\n"
    "static bool iaoffline_moe_stream_mode() {\n"
    "    const char *value = getenv(\"IAOFFLINE_MOE_STREAM\");\n"
    "    return value != nullptr && value[0] == '1';\n"
    "}\n\n"
    "struct iaoffline_prefetch_range {\n"
    "    const char *addr;\n"
    "    size_t size;\n"
    "};\n\n"
    "constexpr size_t                          IAOFFLINE_MAX_PREFETCH_QUEUE = 16384;\n\n"
    "struct iaoffline_prefetch_state {\n"
    "    std::mutex mutex;\n"
    "    std::condition_variable cv;\n"
    "    std::deque<iaoffline_prefetch_range> queue;\n"
    "    bool started = false;\n"
    "};\n\n"
    "// Never destroyed: its thread still waits on it while the process exits.\n"
    "static iaoffline_prefetch_state &iaoffline_prefetch() {\n"
    "    static auto *state = new iaoffline_prefetch_state();\n"
    "    return *state;\n"
    "}\n\n"
    "// Mapped expert weights of each layer, in the order the graph uses them (gate/up, then down),\n"
    "// learned from the graph as it runs. Only the inference thread touches it.\n"
    "static std::vector<std::vector<const ggml_tensor *>> g_moe_layer_weights;\n\n"
    "// Requests the queued slices from the kernel. MADV_WILLNEED starts the reads and returns, so the\n"
    "// storage gets many requests at once; a slice already in memory costs nothing.\n"
    "static void iaoffline_prefetch_worker() {\n"
    "    const uintptr_t page_mask = (uintptr_t) sysconf(_SC_PAGESIZE) - 1;\n"
    "    auto &prefetch = iaoffline_prefetch();\n"
    "    while (true) {\n"
    "        iaoffline_prefetch_range range{};\n"
    "        {\n"
    "            std::unique_lock<std::mutex> lock(prefetch.mutex);\n"
    "            prefetch.cv.wait(lock, [&prefetch] { return !prefetch.queue.empty(); });\n"
    "            range = prefetch.queue.front();\n"
    "            prefetch.queue.pop_front();\n"
    "        }\n"
    "        const uintptr_t begin = (uintptr_t) range.addr & ~page_mask;\n"
    "        const uintptr_t end = (uintptr_t) range.addr + range.size;\n"
    "        madvise((void *) begin, end - begin, MADV_WILLNEED);\n"
    "    }\n"
    "}\n\n"
    "// Forgets the weights of the model being unloaded; queued requests for it are dropped too.\n"
    "static void iaoffline_reset_prefetch() {\n"
    "    auto &prefetch = iaoffline_prefetch();\n"
    "    std::lock_guard<std::mutex> lock(prefetch.mutex);\n"
    "    prefetch.queue.clear();\n"
    "    g_moe_layer_weights.clear();\n"
    "}\n\n"
    "// \"blk.12.ffn_down_exps.weight\" -> 12\n"
    "static int iaoffline_layer_of(const char *name) {\n"
    "    int layer = -1;\n"
    "    return sscanf(name, \"blk.%d.\", &layer) == 1 ? layer : -1;\n"
    "}\n\n"
    "constexpr char                            IAOFFLINE_TOPK_PREFIX[] = \"ffn_moe_topk-\";\n\n"
    "static bool iaoffline_moe_eval_callback(struct ggml_tensor *t, bool ask, void * /*user_data*/) {\n"
    "    if (ask) {\n"
    "        // Learns which mapped expert tensors each layer multiplies by.\n"
    "        if (t->op == GGML_OP_MUL_MAT_ID) {\n"
    "            const ggml_tensor *weights = t->src[0];\n"
    "            const int layer = iaoffline_layer_of(weights->name);\n"
    "            if (layer >= 0 && weights->ne[2] > 1 && weights->buffer != nullptr &&\n"
    "                strcmp(ggml_backend_buffer_name(weights->buffer), \"CPU_Mapped\") == 0) {\n"
    "                if ((int) g_moe_layer_weights.size() <= layer) g_moe_layer_weights.resize(layer + 1);\n"
    "                auto &known = g_moe_layer_weights[layer];\n"
    "                if (std::find(known.begin(), known.end(), weights) == known.end()) known.push_back(weights);\n"
    "            }\n"
    "            return false;\n"
    "        }\n"
    "        // Stops the graph right after a router has picked its experts.\n"
    "        return strncmp(t->name, IAOFFLINE_TOPK_PREFIX, sizeof(IAOFFLINE_TOPK_PREFIX) - 1) == 0;\n"
    "    }\n"
    "    // The picked experts: ids [n_expert_used, n_tokens], possibly a view of the sorted ids.\n"
    "    const int layer = atoi(t->name + sizeof(IAOFFLINE_TOPK_PREFIX) - 1);\n"
    "    if (t->type != GGML_TYPE_I32 || t->data == nullptr || layer < 0 || layer >= (int) g_moe_layer_weights.size() ||\n"
    "        g_moe_layer_weights[layer].empty()) {\n"
    "        return true;\n"
    "    }\n"
    "    const auto &weights = g_moe_layer_weights[layer];\n"
    "    std::vector<bool> used(weights[0]->ne[2], false);\n"
    "    for (int64_t token = 0; token < t->ne[1]; token++) {\n"
    "        for (int64_t k = 0; k < t->ne[0]; k++) {\n"
    "            const int32_t id = *(const int32_t *) ((const char *) t->data + token * t->nb[1] + k * t->nb[0]);\n"
    "            if (id >= 0 && id < (int64_t) used.size()) used[id] = true;\n"
    "        }\n"
    "    }\n"
    "    auto &prefetch = iaoffline_prefetch();\n"
    "    {\n"
    "        std::lock_guard<std::mutex> lock(prefetch.mutex);\n"
    "        for (const ggml_tensor *w : weights) {\n"
    "            for (int64_t expert = 0; expert < std::min<int64_t>(w->ne[2], (int64_t) used.size()); expert++) {\n"
    "                if (!used[expert]) continue;\n"
    "                if (prefetch.queue.size() >= IAOFFLINE_MAX_PREFETCH_QUEUE) prefetch.queue.pop_front();\n"
    "                prefetch.queue.push_back({(const char *) w->data + expert * w->nb[2], (size_t) w->nb[2]});\n"
    "            }\n"
    "        }\n"
    "    }\n"
    "    prefetch.cv.notify_one();\n"
    "    return true;\n"
    "}\n\n"
    "static void iaoffline_start_prefetch() {\n"
    "    auto &prefetch = iaoffline_prefetch();\n"
    "    std::lock_guard<std::mutex> lock(prefetch.mutex);\n"
    "    if (prefetch.started) return;\n"
    "    std::thread(iaoffline_prefetch_worker).detach();\n"
    "    prefetch.started = true;\n"
    "}\n\n"
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n",
)
replace_once(
    "    if (iaoffline_low_memory_mode()) {\n"
    "        // Repacked CPU buffers copy every weight into anonymous memory; without them the\n",
    "    if (iaoffline_moe_stream_mode()) {\n"
    "        // The experts stay mapped from the file, where they are read as the router picks them;\n"
    "        // repacking them would copy the whole model into RAM.\n"
    "        static const llama_model_tensor_buft_override expert_overrides[] = {\n"
    "            {\"\\\\.ffn_(up|down|gate|gate_up)_(ch|)exps\", ggml_backend_cpu_buffer_type()},\n"
    "            {nullptr, nullptr},\n"
    "        };\n"
    "        model_params.tensor_buft_overrides = expert_overrides;\n"
    "        LOGi(\"%s: mixture of experts read from storage: experts mapped from the file\", __func__);\n"
    "    }\n"
    "    if (iaoffline_low_memory_mode()) {\n"
    "        // Repacked CPU buffers copy every weight into anonymous memory; without them the\n",
)
replace_once(
    "    if (!model) {\n"
    "        append_native_error(\"[IA Offline] mmap conservador falhou; tentando I/O regular em CPU.\");\n",
    "    if (!model && !iaoffline_moe_stream_mode()) {\n"
    "        append_native_error(\"[IA Offline] mmap conservador falhou; tentando I/O regular em CPU.\");\n",
)
replace_once(
    "    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;\n",
    "    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;\n"
    "    if (iaoffline_moe_stream_mode()) {\n"
    "        ctx_params.cb_eval = iaoffline_moe_eval_callback;\n"
    "        ctx_params.cb_eval_user_data = nullptr;\n"
    "    }\n",
)
replace_once(
    "    int n_ctx = iaoffline_context_for_memory(g_model);\n",
    "    iaoffline_reset_prefetch();\n"
    "    if (iaoffline_moe_stream_mode()) iaoffline_start_prefetch();\n"
    "    int n_ctx = iaoffline_context_for_memory(g_model);\n",
)
replace_once(
    "    // Free up resources\n"
    "    common_sampler_free(g_sampler);\n",
    "    // Free up resources\n"
    "    iaoffline_reset_prefetch();\n"
    "    common_sampler_free(g_sampler);\n",
)

# 17. Threads for the fast CPU cores the AI process may use now. The upstream binding used all cores
#     but 2, at most 4, which leaves idle a third to half of the fast cores of recent phones (6 on a
#     Snapdragon 8 Gen 3, 8 on a Snapdragon 8 Elite or a Dimensity 9300/9400); reading a prompt is
#     limited by the cores, so it gets up to twice the cores there. Each operation is split among the
#     threads and waits for the last one, so efficiency cores (Cortex-A53/A55/A510/A520, which the app
#     tells apart by their part numbers in /proc/cpuinfo and lists in IAOFFLINE_EFFICIENCY_CPUS) only
#     fill in up to the 4 threads of before, and a phone with 4 fast cores or fewer keeps what it had.
#     The count is checked again before each batch and each token: Android may leave the process
#     fewer cores (in the background, or parking cores), and a thread without a core of its own would
#     make every operation wait for it.
replace_once(
    "#include <android/log.h>\n",
    "// sched_getaffinity() and the CPU_* macros; clang already defines it for C++ on Android.\n"
    "#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n#include <android/log.h>\n",
)
replace_once(
    "#include <sys/mman.h>\n",
    "#include <sys/mman.h>\n#include <sched.h>\n",
)
replace_once(
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n",
    "// Efficiency cores by CPU number, from IAOFFLINE_EFFICIENCY_CPUS (\"0,1,2,3\"; empty when the CPU\n"
    "// has none). Unset, the core types are unknown (x86_64). Read when a context is created.\n"
    "static std::vector<bool> g_efficiency_cpus;\n"
    "static bool g_cpu_types_known = false;\n"
    "constexpr int                             IAOFFLINE_MAX_THREADS = 8;\n\n"
    "static void iaoffline_read_cpu_types() {\n"
    "    g_efficiency_cpus.clear();\n"
    "    const char *value = getenv(\"IAOFFLINE_EFFICIENCY_CPUS\");\n"
    "    g_cpu_types_known = value != nullptr;\n"
    "    if (value == nullptr) return;\n"
    "    for (const char *p = value; *p != '\\0';) {\n"
    "        char *end = nullptr;\n"
    "        const long cpu = strtol(p, &end, 10);\n"
    "        if (end == p) {\n"
    "            p++;\n"
    "            continue;\n"
    "        }\n"
    "        if (cpu >= 0 && cpu < CPU_SETSIZE) {\n"
    "            if ((long) g_efficiency_cpus.size() <= cpu) g_efficiency_cpus.resize(cpu + 1, false);\n"
    "            g_efficiency_cpus[cpu] = true;\n"
    "        }\n"
    "        p = end;\n"
    "    }\n"
    "}\n\n"
    "// Threads for the cores this process may use now (its affinity, which follows the cpuset Android\n"
    "// gives it): one per fast core, filled in with efficiency cores up to the upstream 4, at most 8.\n"
    "static int iaoffline_thread_count(int *allowed_out = nullptr, int *fast_out = nullptr) {\n"
    "    cpu_set_t allowed;\n"
    "    CPU_ZERO(&allowed);\n"
    "    int n_allowed = 0;\n"
    "    int n_fast = 0;\n"
    "    if (sched_getaffinity(0, sizeof(allowed), &allowed) == 0) {\n"
    "        const int total = CPU_COUNT(&allowed);\n"
    "        for (int cpu = 0; cpu < CPU_SETSIZE && n_allowed < total; cpu++) {\n"
    "            if (!CPU_ISSET(cpu, &allowed)) continue;\n"
    "            n_allowed++;\n"
    "            // A core missing from the list (offline when the app read /proc/cpuinfo) counts as fast.\n"
    "            if (cpu >= (int) g_efficiency_cpus.size() || !g_efficiency_cpus[cpu]) n_fast++;\n"
    "        }\n"
    "    }\n"
    "    if (n_allowed == 0) n_allowed = n_fast = std::max(1, (int) sysconf(_SC_NPROCESSORS_ONLN));\n"
    "    if (allowed_out != nullptr) *allowed_out = n_allowed;\n"
    "    if (fast_out != nullptr) *fast_out = n_fast;\n"
    "    if (!g_cpu_types_known) {\n"
    "        // Core types unknown: the upstream choice, all cores but 2, between 2 and 4.\n"
    "        return std::min(n_allowed, std::max(N_THREADS_MIN, std::min(N_THREADS_MAX, n_allowed - N_THREADS_HEADROOM)));\n"
    "    }\n"
    "    return std::min({n_allowed, IAOFFLINE_MAX_THREADS, std::max(n_fast, std::min(N_THREADS_MAX, n_allowed))});\n"
    "}\n\n"
    "static void iaoffline_update_threads(llama_context *context) {\n"
    "    int n_allowed = 0;\n"
    "    int n_fast = 0;\n"
    "    const int threads = iaoffline_thread_count(&n_allowed, &n_fast);\n"
    "    if (threads == llama_n_threads(context) && threads == llama_n_threads_batch(context)) return;\n"
    "    LOGi(\"%s: %d threads (%d cores available now, %d of them fast)\", __func__, threads, n_allowed, n_fast);\n"
    "    llama_set_n_threads(context, threads, threads);\n"
    "}\n\n"
    "// Set by the app (IAOFFLINE_LOW_MEMORY=1) when the model is large for this device's RAM.\n",
)
replace_once(
    "    // Multi-threading setup\n"
    "    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,\n"
    "                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -\n"
    "                                                     N_THREADS_HEADROOM));\n"
    "    LOGi(\"%s: Using %d threads\", __func__, n_threads);\n",
    "    // Multi-threading setup: the fast cores this process may use (iaoffline_thread_count).\n"
    "    iaoffline_read_cpu_types();\n"
    "    int n_allowed_cores = 0;\n"
    "    int n_fast_cores = 0;\n"
    "    const int n_threads = iaoffline_thread_count(&n_allowed_cores, &n_fast_cores);\n"
    "    LOGi(\"%s: Using %d threads (%d cores available, %d of them fast)\", __func__, n_threads,\n"
    "         n_allowed_cores, n_fast_cores);\n",
)
replace_once(
    "    setenv(\"IAOFFLINE_ACTIVE_CONTEXT_TOKENS\", std::to_string(llama_n_ctx(context)).c_str(), 1);",
    "    setenv(\"IAOFFLINE_ACTIVE_CONTEXT_TOKENS\", std::to_string(llama_n_ctx(context)).c_str(), 1);\n"
    "    setenv(\"IAOFFLINE_ACTIVE_THREADS\", std::to_string(llama_n_threads(context)).c_str(), 1);",
)
replace_once(
    "        // Shift context if current batch cannot fit into the context\n",
    "        iaoffline_update_threads(context);\n\n"
    "        // Shift context if current batch cannot fit into the context\n",
)
replace_once(
    "    // Infinite text generation via context shifting\n",
    "    iaoffline_update_threads(g_context);\n\n"
    "    // Infinite text generation via context shifting\n",
)

path.write_text(text)
print("Applied IA Offline Android model-load compatibility patch")
PY

# 16. Loading a mixture-of-experts model larger than the RAM (IAOFFLINE_MOE_STREAM=1):
#     a) llama.cpp maps the model file and asks the kernel to read all of it right away
#        (MAP_POPULATE and MADV_WILLNEED). That reads gigabytes the page cache cannot keep, slows
#        the load and leaves the cache holding the end of the file; the experts are read as the
#        router picks them instead.
#     b) A tensor overridden to the CPU buffer may still be repacked (IQ4_NL experts are, on ARM
#        and x86), which copies it into RAM. The experts must stay mapped from the file.
python3 - "${MODEL_FILE}" "${MODEL_LOADER_FILE}" <<'PY'
from pathlib import Path
import sys

path = None
text = ""

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected llama.cpp model loader source shape in {path.name}: expected 1 match, got {count}")
    text = text.replace(old, new, 1)

path = Path(sys.argv[1])
text = path.read_text()
replace_once(
    "    ml.init_mappings(true, use_mlock ? &pimpl->mlock_mmaps : nullptr);\n",
    "    // IA Offline: a mixture of experts read from storage does not fit the RAM; reading all of its\n"
    "    // file up front only fills the page cache with the end of it.\n"
    "    const char * iaoffline_moe_stream = getenv(\"IAOFFLINE_MOE_STREAM\");\n"
    "    const bool iaoffline_prefetch = iaoffline_moe_stream == nullptr || iaoffline_moe_stream[0] != '1';\n"
    "    ml.init_mappings(iaoffline_prefetch, use_mlock ? &pimpl->mlock_mmaps : nullptr);\n",
)
path.write_text(text)

path = Path(sys.argv[2])
text = path.read_text()
replace_once(
    "                    if (overrides->buft == ggml_backend_cpu_buffer_type()) {\n"
    "                        // when overriding to a CPU buffer, consider the extra buffer types\n",
    "                    // IA Offline: the experts of a mixture of experts read from storage stay mapped\n"
    "                    // from the file; repacking them would copy them into RAM.\n"
    "                    const char * iaoffline_moe_stream = getenv(\"IAOFFLINE_MOE_STREAM\");\n"
    "                    const bool iaoffline_keep_mapped = iaoffline_moe_stream != nullptr && iaoffline_moe_stream[0] == '1';\n"
    "                    if (overrides->buft == ggml_backend_cpu_buffer_type() && !iaoffline_keep_mapped) {\n"
    "                        // when overriding to a CPU buffer, consider the extra buffer types\n",
)
path.write_text(text)
print("Applied IA Offline model-loader patch")
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
