#!/bin/sh
set -eu
VERSION=9.6.0
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/localai-gradle-$VERSION"
GRADLE_BIN="$BASE/gradle-$VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "curl ou wget é necessário para baixar o Gradle $VERSION." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$GRADLE_BIN" "$@"
