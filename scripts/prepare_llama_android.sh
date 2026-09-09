#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_TAG="${LLAMA_CPP_TAG:-v0.4.0}"
LLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
AAR_DEST="${ROOT_DIR}/app/libs/llama-android.aar"
ENGINE_API_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt"
ENGINE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
NATIVE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
LIB_GRADLE_FILE="${LLAMA_DIR}/examples/llama.android/lib/build.gradle.kts"

mkdir -p "${ROOT_DIR}/third_party" "${ROOT_DIR}/app/libs"

if [[ ! -d "${LLAMA_DIR}/.git" ]]; then
  git clone --depth 1 --branch "${LLAMA_TAG}" https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}"
else
  git -C "${LLAMA_DIR}" fetch --depth 1 origin "${LLAMA_TAG}"
  git -C "${LLAMA_DIR}" checkout --force FETCH_HEAD
  git -C "${LLAMA_DIR}" clean -fdx
fi

# IA Offline intentionally carries a small reproducible patch over the pinned Android binding:
# - Android 10 (API 29) minimum instead of API 33;
# - configurable native context size instead of the hard-coded 8192-token runtime ceiling;
# - configurable sampler temperature per request;
# - cleanup of a native model allocated before a recoverable State.Error.
# Keeping the patch here makes CI rebuild and verify the exact AAR used by the app.
python3 - "${ENGINE_API_FILE}" "${ENGINE_FILE}" "${NATIVE_FILE}" "${LIB_GRADLE_FILE}" <<'PY'
from pathlib import Path
import sys

api_path = Path(sys.argv[1])
engine_path = Path(sys.argv[2])
native_path = Path(sys.argv[3])
gradle_path = Path(sys.argv[4])


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Unexpected llama.cpp v0.4.0 source shape in {label}: expected 1 match, got {count}"
        )
    return text.replace(old, new, 1)


def replace_count(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise SystemExit(
            f"Unexpected llama.cpp v0.4.0 source shape in {label}: expected {expected} matches, got {count}"
        )
    return text.replace(old, new)


api = api_path.read_text()
api = replace_once(
    api,
    "    suspend fun loadModel(pathToModel: String)\n",
    "    suspend fun loadModel(\n"
    "        pathToModel: String,\n"
    "        contextSize: Int = 8192,\n"
    "        temperature: Float = 0.3f,\n"
    "    )\n",
    "InferenceEngine.kt",
)
api_path.write_text(api)

engine = engine_path.read_text()
engine = replace_once(
    engine,
    "    private external fun prepare(): Int\n",
    "    private external fun prepare(contextSize: Int, temperature: Float): Int\n",
    "InferenceEngineImpl.kt",
)
engine = replace_once(
    engine,
    "    @Volatile\n    private var _cancelGeneration = false\n",
    "    @Volatile\n    private var _cancelGeneration = false\n    private var _nativeModelLoaded = false\n",
    "InferenceEngineImpl.kt",
)
engine = replace_once(
    engine,
    "    override suspend fun loadModel(pathToModel: String) =\n"
    "        withContext(llamaDispatcher) {\n"
    "            check(_state.value is InferenceEngine.State.Initialized) {\n"
    "                \"Cannot load model in ${_state.value.javaClass.simpleName}!\"\n"
    "            }\n\n"
    "            try {",
    "    override suspend fun loadModel(\n"
    "        pathToModel: String,\n"
    "        contextSize: Int,\n"
    "        temperature: Float,\n"
    "    ) = withContext(llamaDispatcher) {\n"
    "            check(_state.value is InferenceEngine.State.Initialized) {\n"
    "                \"Cannot load model in ${_state.value.javaClass.simpleName}!\"\n"
    "            }\n"
    "            require(contextSize >= 1024) { \"Context size must be at least 1024 tokens\" }\n"
    "            require(temperature.isFinite() && temperature in 0f..2f) {\n"
    "                \"Temperature must be between 0 and 2\"\n"
    "            }\n\n"
    "            try {",
    "InferenceEngineImpl.kt",
)
engine = replace_once(
    engine,
    "                load(pathToModel).let {\n"
    "                    // TODO-han.yin: find a better way to pass other error codes\n"
    "                    if (it != 0) throw UnsupportedArchitectureException()\n"
    "                }\n"
    "                prepare().let {",
    "                load(pathToModel).let {\n"
    "                    // TODO-han.yin: find a better way to pass other error codes\n"
    "                    if (it != 0) throw UnsupportedArchitectureException()\n"
    "                }\n"
    "                _nativeModelLoaded = true\n"
    "                prepare(contextSize, temperature).let {",
    "InferenceEngineImpl.kt",
)
engine = replace_once(
    engine,
    "                    unload()\n\n                    _state.value = InferenceEngine.State.Initialized",
    "                    unload()\n                    _nativeModelLoaded = false\n\n                    _state.value = InferenceEngine.State.Initialized",
    "InferenceEngineImpl.kt",
)
engine = replace_once(
    engine,
    "                is InferenceEngine.State.Error -> {\n"
    "                    Log.i(TAG, \"Resetting error states...\")\n"
    "                    _state.value = InferenceEngine.State.Initialized\n"
    "                    Log.i(TAG, \"States reset!\")\n"
    "                    Unit\n"
    "                }",
    "                is InferenceEngine.State.Error -> {\n"
    "                    Log.i(TAG, \"Resetting error states...\")\n"
    "                    if (_nativeModelLoaded) {\n"
    "                        Log.i(TAG, \"Error occurred after native model allocation; unloading it...\")\n"
    "                        unload()\n"
    "                        _nativeModelLoaded = false\n"
    "                    }\n"
    "                    _state.value = InferenceEngine.State.Initialized\n"
    "                    Log.i(TAG, \"States reset!\")\n"
    "                    Unit\n"
    "                }",
    "InferenceEngineImpl.kt",
)
engine = replace_once(
    engine,
    "                else -> { unload(); shutdown() }",
    "                else -> {\n"
    "                    if (_nativeModelLoaded) { unload(); _nativeModelLoaded = false }\n"
    "                    shutdown()\n"
    "                }",
    "InferenceEngineImpl.kt",
)
engine_path.write_text(engine)

native = native_path.read_text()
native = replace_once(
    native,
    "Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {\n"
    "    auto *context = init_context(g_model);",
    "Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(\n"
    "        JNIEnv * /*env*/, jobject /*unused*/, jint context_size, jfloat temperature) {\n"
    "    if (context_size < 1024) {\n"
    "        LOGe(\"%s: context size must be at least 1024, got %d\", __func__, context_size);\n"
    "        return 2;\n"
    "    }\n"
    "    if (!std::isfinite(temperature) || temperature < 0.0f || temperature > 2.0f) {\n"
    "        LOGe(\"%s: invalid temperature %f\", __func__, temperature);\n"
    "        return 3;\n"
    "    }\n"
    "    auto *context = init_context(g_model, context_size);",
    "ai_chat.cpp",
)
native = replace_once(
    native,
    "    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);\n",
    "    g_sampler = new_sampler((float) temperature);\n",
    "ai_chat.cpp",
)
# The upstream Android example allocates a requested context but still checks overflow against
# DEFAULT_CONTEXT_SIZE (8192) in four places. Use the actual live context capacity everywhere.
native = replace_once(
    native,
    "        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {",
    "        if (start_pos + i + cur_batch_size >= (int) llama_n_ctx(context) - OVERFLOW_HEADROOM) {",
    "ai_chat.cpp",
)
native = replace_count(
    native,
    "    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;",
    "    const int max_batch_size = (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM;",
    2,
    "ai_chat.cpp",
)
native = replace_once(
    native,
    "    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {",
    "    if (current_position >= (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {",
    "ai_chat.cpp",
)
native_path.write_text(native)

gradle = gradle_path.read_text()
gradle = replace_once(
    gradle,
    "        minSdk = 33\n",
    "        minSdk = 29\n",
    "lib/build.gradle.kts",
)
gradle_path.write_text(gradle)

print("Applied IA Offline Android 10 + dynamic-context + configurable-temperature + cleanup patches")
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
