#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_TAG="${LLAMA_CPP_TAG:-v0.4.0}"
LLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
AAR_DEST="${ROOT_DIR}/app/libs/llama-android.aar"
INTERFACE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt"
ENGINE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
NATIVE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"

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
    "                load(pathToModel).let {\n                    // TODO-han.yin: find a better way to pass other error codes\n                    if (it != 0) throw UnsupportedArchitectureException()\n                }\n                _nativeModelLoaded = true\n                prepare().let {",
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
print("Applied IA Offline native-model cleanup patch")
PY

# The upstream v0.4.0 Android wrapper hard-codes sampler temperature to 0.3 even
# though llama.cpp supports configurable sampling. Expose a per-request temperature
# through the public Kotlin interface and rebuild the sampler before generation.
python3 - "${INTERFACE_FILE}" "${ENGINE_FILE}" "${NATIVE_FILE}" <<'PY'
from pathlib import Path
import sys

interface_path = Path(sys.argv[1])
engine_path = Path(sys.argv[2])
native_path = Path(sys.argv[3])


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected llama.cpp v0.4.0 {label} shape: expected 1 match, got {count}")
    return text.replace(old, new, 1)

interface = interface_path.read_text()
interface = replace_once(
    interface,
    "    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>\n",
    "    fun sendUserPrompt(\n"
    "        message: String,\n"
    "        predictLength: Int = DEFAULT_PREDICT_LENGTH,\n"
    "        temperature: Float = DEFAULT_TEMPERATURE,\n"
    "    ): Flow<String>\n",
    "InferenceEngine",
)
interface = replace_once(
    interface,
    "    companion object {\n        const val DEFAULT_PREDICT_LENGTH = 1024\n    }\n",
    "    companion object {\n"
    "        const val DEFAULT_PREDICT_LENGTH = 1024\n"
    "        const val DEFAULT_TEMPERATURE = 0.3f\n"
    "    }\n",
    "InferenceEngine companion",
)
interface_path.write_text(interface)

engine = engine_path.read_text()
engine = replace_once(
    engine,
    "    @FastNative\n    private external fun prepare(): Int\n\n    @FastNative\n    private external fun systemInfo(): String\n",
    "    @FastNative\n"
    "    private external fun prepare(): Int\n\n"
    "    @FastNative\n"
    "    private external fun setTemperature(temperature: Float): Int\n\n"
    "    @FastNative\n"
    "    private external fun systemInfo(): String\n",
    "InferenceEngineImpl native declarations",
)
engine = replace_once(
    engine,
    "    override fun sendUserPrompt(\n        message: String,\n        predictLength: Int,\n    ): Flow<String> = flow {",
    "    override fun sendUserPrompt(\n"
    "        message: String,\n"
    "        predictLength: Int,\n"
    "        temperature: Float,\n"
    "    ): Flow<String> = flow {",
    "InferenceEngineImpl sendUserPrompt signature",
)
engine = replace_once(
    engine,
    "            processUserPrompt(message, predictLength).let { result ->\n",
    "            require(temperature in 0f..2f) { \"Temperature must be between 0.0 and 2.0\" }\n"
    "            setTemperature(temperature).let { result ->\n"
    "                if (result != 0) {\n"
    "                    throw IOException(\"Failed to configure sampler temperature: $result\")\n"
    "                }\n"
    "            }\n\n"
    "            processUserPrompt(message, predictLength).let { result ->\n",
    "InferenceEngineImpl temperature application",
)
engine_path.write_text(engine)

native = native_path.read_text()
native = replace_once(
    native,
    "    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);\n    return 0;\n}\n\nstatic std::string get_backend() {",
    "    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);\n"
    "    return 0;\n"
    "}\n\n"
    "extern \"C\"\n"
    "JNIEXPORT jint JNICALL\n"
    "Java_com_arm_aichat_internal_InferenceEngineImpl_setTemperature(\n"
    "        JNIEnv * /*env*/, jobject /*unused*/, jfloat temperature) {\n"
    "    if (!std::isfinite(temperature) || temperature < 0.0f || temperature > 2.0f) {\n"
    "        return 1;\n"
    "    }\n"
    "    if (!g_model || !g_sampler) {\n"
    "        return 2;\n"
    "    }\n"
    "    common_sampler_free(g_sampler);\n"
    "    g_sampler = new_sampler((float) temperature);\n"
    "    return g_sampler ? 0 : 3;\n"
    "}\n\n"
    "static std::string get_backend() {",
    "ai_chat sampler",
)
native_path.write_text(native)
print("Applied IA Offline configurable-temperature patch")
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
