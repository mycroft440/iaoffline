#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Exact commit behind the v0.4.0 release tag. Pinning the commit avoids trusting a mutable tag.
LLAMA_COMMIT="${LLAMA_CPP_COMMIT:-5266f24da75dc449bd56cbed7addb9c8e4a6a73e}"
LLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
AAR_DEST="${ROOT_DIR}/app/libs/llama-android.aar"
ENGINE_FILE="${LLAMA_DIR}/examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"

mkdir -p "${ROOT_DIR}/third_party" "${ROOT_DIR}/app/libs"

if [[ ! -d "${LLAMA_DIR}/.git" ]]; then
  rm -rf "${LLAMA_DIR}"
  git init "${LLAMA_DIR}"
  git -C "${LLAMA_DIR}" remote add origin https://github.com/ggml-org/llama.cpp.git
fi

git -C "${LLAMA_DIR}" fetch --depth 1 origin "${LLAMA_COMMIT}"
ACTUAL_COMMIT="$(git -C "${LLAMA_DIR}" rev-parse FETCH_HEAD)"
if [[ "${ACTUAL_COMMIT}" != "${LLAMA_COMMIT}" ]]; then
  echo "Commit inesperado do llama.cpp. Esperado ${LLAMA_COMMIT}, recebido ${ACTUAL_COMMIT}." >&2
  exit 1
fi
git -C "${LLAMA_DIR}" checkout --detach --force "${LLAMA_COMMIT}"
git -C "${LLAMA_DIR}" clean -fdx

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
