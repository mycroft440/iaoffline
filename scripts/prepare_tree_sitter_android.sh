#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TSLP_TAG="${TREE_SITTER_LANGUAGE_PACK_TAG:-v1.15.12}"
TSLP_VERSION="${TSLP_TAG#v}"
TSLP_DIR="${ROOT_DIR}/third_party/tree-sitter-language-pack"
AAR_DEST="${ROOT_DIR}/app/libs/tree-sitter-language-pack-android-max.aar"
PARSER_ARCHIVE="${ROOT_DIR}/third_party/parser-sources-${TSLP_VERSION}.tar.zst"
LANGUAGES="${TREE_SITTER_LANGUAGES:-all}"
PARSER_URL="https://github.com/xberg-io/tree-sitter-language-pack/releases/download/${TSLP_TAG}/parser-sources-${TSLP_VERSION}.tar.zst"
PARSER_SHA256_URL="${PARSER_URL}.sha256"

mkdir -p "${ROOT_DIR}/third_party" "${ROOT_DIR}/app/libs"

if ! command -v cargo >/dev/null 2>&1; then
  echo "Rust/cargo é necessário para compilar o backend Tree-sitter Android." >&2
  exit 1
fi
if ! command -v cargo-ndk >/dev/null 2>&1 && ! cargo ndk --version >/dev/null 2>&1; then
  echo "cargo-ndk é necessário. Instale com: cargo install cargo-ndk --locked" >&2
  exit 1
fi
if ! command -v zstd >/dev/null 2>&1; then
  echo "zstd é necessário para extrair o pacote oficial de parsers." >&2
  exit 1
fi
if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "Defina ANDROID_NDK_HOME para um Android NDK instalado." >&2
  exit 1
fi

if [[ ! -d "${TSLP_DIR}/.git" ]]; then
  git clone --depth 1 --branch "${TSLP_TAG}" https://github.com/xberg-io/tree-sitter-language-pack.git "${TSLP_DIR}"
else
  git -C "${TSLP_DIR}" fetch --depth 1 origin "${TSLP_TAG}"
  git -C "${TSLP_DIR}" checkout --force FETCH_HEAD
  git -C "${TSLP_DIR}" clean -fdx
fi

# Use the release's already-generated parser sources. This avoids executing hundreds of
# third-party grammar.js files during the app build and keeps the build pinned to the exact
# sources shipped by the selected tree-sitter-language-pack release.
if [[ ! -f "${PARSER_ARCHIVE}" ]]; then
  curl --fail --location --retry 3 --output "${PARSER_ARCHIVE}" "${PARSER_URL}"
fi

EXPECTED_SHA256="$(curl --fail --location --retry 3 --silent --show-error "${PARSER_SHA256_URL}" | awk '{print $1}')"
if [[ -z "${EXPECTED_SHA256}" ]]; then
  echo "Não foi possível ler o SHA-256 oficial de ${PARSER_ARCHIVE}." >&2
  exit 1
fi
printf '%s  %s\n' "${EXPECTED_SHA256}" "${PARSER_ARCHIVE}" | sha256sum --check --status || {
  echo "Falha de integridade no pacote oficial de parsers Tree-sitter." >&2
  rm -f "${PARSER_ARCHIVE}"
  exit 1
}

rm -rf "${TSLP_DIR}/parsers"
tar --zstd -xf "${PARSER_ARCHIVE}" -C "${TSLP_DIR}"

if [[ ! -d "${TSLP_DIR}/parsers" ]]; then
  echo "O bundle oficial não produziu ${TSLP_DIR}/parsers." >&2
  exit 1
fi

# 'all' asks the upstream build to statically compile every grammar present in the release.
# A comma-separated TREE_SITTER_LANGUAGES can be supplied for a smaller custom APK.
export PROJECT_ROOT="${TSLP_DIR}"
export TSLP_LINK_MODE="static"
export TSLP_LANGUAGES="${LANGUAGES}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT}}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME}}"

rm -rf "${TSLP_DIR}/packages/kotlin-android/src/main/jniLibs"
pushd "${TSLP_DIR}/packages/kotlin-android" >/dev/null
chmod +x ./gradlew
./gradlew clean assembleRelease --no-daemon --stacktrace -Palef.skipHostJni=true
popd >/dev/null

mapfile -t RELEASE_AARS < <(find "${TSLP_DIR}/packages/kotlin-android/build/outputs/aar" -maxdepth 1 -type f -name '*release.aar' -print)
if [[ "${#RELEASE_AARS[@]}" -ne 1 ]]; then
  echo "Esperava exatamente um AAR release do Tree-sitter; encontrei ${#RELEASE_AARS[@]}." >&2
  printf '  %s\n' "${RELEASE_AARS[@]:-}" >&2
  exit 1
fi

cp "${RELEASE_AARS[0]}" "${AAR_DEST}"
echo "Tree-sitter Android preparado em ${AAR_DEST} com TSLP_LANGUAGES=${LANGUAGES}"
