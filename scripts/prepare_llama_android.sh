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
# the native patch below performs compatibility fallbacks before giving up.
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
    "    @Volatile\n    private var _cancelGeneration = false\n",
    "    @Volatile\n    private var _cancelGeneration = false\n    private var _nativeModelLoaded = false\n",
)
replace_once(
    "                load(pathToModel).let {\n                    // TODO-han.yin: find a better way to pass other error codes\n                    if (it != 0) throw UnsupportedArchitectureException()\n                }\n                prepare().let {",
    "                load(pathToModel).let {\n                    if (it != 0) {\n                        throw IOException(\"O backend nativo não conseguiu carregar o arquivo GGUF após as tentativas de compatibilidade.\")\n                    }\n                }\n                _nativeModelLoaded = true\n                prepare().let {",
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
# 2. Try the normal mmap + optimized CPU buffers first.
# 3. If that fails, retry using ordinary CPU buffers and regular file reads.
# 4. If model loading succeeds but an 8K context cannot be allocated, retry with
#    4K and 2K contexts. This keeps smaller-memory phones usable instead of
#    reporting the model itself as incompatible.
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
    "    llama_model_params model_params = llama_model_default_params();\n\n    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);",
    "    llama_model_params model_params = llama_model_default_params();\n"
    "    // Android app inference is CPU-first. Avoid accidental device offload\n"
    "    // and keep the first attempt on the portable mmap path.\n"
    "    model_params.n_gpu_layers = 0;\n"
    "    model_params.split_mode = LLAMA_SPLIT_MODE_NONE;\n"
    "    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;\n\n"
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
    "        LOGw(\"%s: optimized mmap load failed; retrying with conservative CPU buffers and regular I/O\", __func__);\n"
    "        model_params.use_extra_bufts = false;\n"
    "        model_params.load_mode = LLAMA_LOAD_MODE_NONE;\n"
    "        model = llama_model_load_from_file(model_path, model_params);\n"
    "    }\n"
    "    env->ReleaseStringUTFChars(jmodel_path, model_path);\n"
    "    if (!model) {\n"
    "        LOGe(\"%s: model load failed in both Android compatibility modes\", __func__);\n"
    "        return 1;\n"
    "    }",
)
replace_once(
    "    auto *context = init_context(g_model);\n"
    "    if (!context) { return 1; }",
    "    auto *context = init_context(g_model, DEFAULT_CONTEXT_SIZE);\n"
    "    if (!context) {\n"
    "        LOGw(\"%s: 8192-token context allocation failed; retrying with 4096\", __func__);\n"
    "        context = init_context(g_model, 4096);\n"
    "    }\n"
    "    if (!context) {\n"
    "        LOGw(\"%s: 4096-token context allocation failed; retrying with 2048\", __func__);\n"
    "        context = init_context(g_model, 2048);\n"
    "    }\n"
    "    if (!context) {\n"
    "        LOGe(\"%s: failed to allocate even the 2048-token context\", __func__);\n"
    "        return 1;\n"
    "    }",
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
