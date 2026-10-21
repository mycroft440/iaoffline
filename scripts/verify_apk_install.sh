#!/usr/bin/env bash
set -euo pipefail

APK="${1:?Informe o caminho do APK}"
EXPECTED_PAGE_SIZE="${2:?Informe o tamanho esperado da pagina de memoria}"
PACKAGE_NAME="com.example.ialocal"
test -f "$APK"
: "${RUNNER_TEMP:?RUNNER_TEMP deve apontar para o diretorio temporario do CI}"

# android-emulator-runner executes each line of its script input in a separate shell.
# Keep the complete check here so variables and failure handling share one Bash process.
echo "Android version: $(adb shell getprop ro.build.version.release)"
PAGE_SIZE=$(adb shell getconf PAGESIZE | tr -d '\r')
echo "Emulator page size: $PAGE_SIZE"
if [[ "$PAGE_SIZE" != "$EXPECTED_PAGE_SIZE" ]]; then
  echo "::error::Expected $EXPECTED_PAGE_SIZE-byte pages, got $PAGE_SIZE."
  exit 1
fi

# Require a fresh installation: -r could silently turn this into an update test.
INSTALLED_PACKAGES=$(adb shell pm list packages "$PACKAGE_NAME" | tr -d '\r')
if grep -Fxq "package:$PACKAGE_NAME" <<< "$INSTALLED_PACKAGES"; then
  echo "::error::$PACKAGE_NAME is already installed; this test requires a clean emulator."
  exit 1
fi

echo "Installing the exact signed APK that will be uploaded to GitHub..."
adb install "$APK"
adb shell pm path "$PACKAGE_NAME" | grep -q '^package:'
adb shell dumpsys package "$PACKAGE_NAME" > "$RUNNER_TEMP/emulator-installed-package.txt"
grep -m 1 'versionCode=' "$RUNNER_TEMP/emulator-installed-package.txt"
echo "APK installed successfully in a clean Android environment ($PAGE_SIZE-byte pages)."
