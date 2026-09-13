#!/bin/sh
set -eu

VERSION=9.6.0
CHECKSUM=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/localai-gradle-$VERSION"
GRADLE_BIN="$BASE/gradle-$VERSION/bin/gradle"
ZIP="$BASE/gradle-$VERSION-bin.zip"
MARKER="$BASE/.verified-$CHECKSUM"
URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"

verify_sha256() {
  file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$file" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
  else
    echo "sha256sum ou shasum é necessário para verificar o Gradle $VERSION." >&2
    return 1
  fi
  if [ "$actual" != "$CHECKSUM" ]; then
    echo "Checksum SHA-256 inválido para o Gradle $VERSION." >&2
    echo "Esperado: $CHECKSUM" >&2
    echo "Recebido: $actual" >&2
    return 1
  fi
}

if [ ! -x "$GRADLE_BIN" ] || [ ! -f "$MARKER" ]; then
  mkdir -p "$BASE"
  rm -rf "$BASE/gradle-$VERSION" "$MARKER"
  TMP="$ZIP.tmp.$$"
  rm -f "$TMP"
  trap 'rm -f "$TMP"' EXIT INT TERM

  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP" "$URL"
  else
    echo "curl ou wget é necessário para baixar o Gradle $VERSION." >&2
    exit 1
  fi

  verify_sha256 "$TMP"
  mv "$TMP" "$ZIP"
  unzip -q -o "$ZIP" -d "$BASE"
  test -x "$GRADLE_BIN"
  touch "$MARKER"
  trap - EXIT INT TERM
fi

exec "$GRADLE_BIN" "$@"
