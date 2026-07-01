#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.phaohn.spyhelper"
SERVICE="$PKG/com.phaohn.spyhelper.SpyAccessibilityService"
APK="${PHAOHN_APK:-$ROOT/releases/PhaoHN-direct.apk}"

ADB="${ADB:-adb}"
SERIAL="${ADB_SERIAL:-}"
ADB_CMD=("$ADB")
if [[ -n "$SERIAL" ]]; then
  ADB_CMD+=(-s "$SERIAL")
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK"
  echo "Build first: cd $ROOT && ./gradlew assembleDirect"
  exit 1
fi

echo "Installing $APK ..."
"${ADB_CMD[@]}" install -r "$APK"

echo "Granting restricted settings + overlay ..."
"${ADB_CMD[@]}" shell cmd appops set "$PKG" android:access_restricted_settings allow
"${ADB_CMD[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

if [[ "${ENABLE_A11Y:-1}" == "1" ]]; then
  echo "Enabling accessibility service ..."
  "${ADB_CMD[@]}" shell settings put secure accessibility_enabled 1
  "${ADB_CMD[@]}" shell settings put secure enabled_accessibility_services "$SERVICE"
fi

echo "Done. Open PhaoHN — bản direct mở thẳng màn hình Trợ năng."