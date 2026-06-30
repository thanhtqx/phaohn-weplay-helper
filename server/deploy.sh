#!/usr/bin/env bash
set -euo pipefail

HOST="${PHAOHN_HOST:-root@152.42.223.79}"
REMOTE_DIR="${PHAOHN_REMOTE_DIR:-/opt/phaohn}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="/tmp/phaohn-deploy-$$.tgz"
APK_SRC="${PHAOHN_APK:-$ROOT/releases/PhaoHN-v2.3.7.apk}"
VERSION_NAME="${PHAOHN_VERSION:-2.3.7}"
VERSION_CODE="${PHAOHN_VERSION_CODE:-38}"

cleanup() { rm -f "$TMP"; }
trap cleanup EXIT

echo "==> Đóng gói server..."
tar czf "$TMP" \
  -C "$ROOT/server" \
  app static requirements.txt phaohn.service .env.example

echo "==> Upload $HOST:$REMOTE_DIR"
scp -o StrictHostKeyChecking=no "$TMP" "$HOST:/tmp/phaohn-deploy.tgz"

if [[ -f "$APK_SRC" ]]; then
  echo "==> Upload APK $(basename "$APK_SRC")"
  scp -o StrictHostKeyChecking=no "$APK_SRC" "$HOST:$REMOTE_DIR/releases/PhaoHN-latest.apk"
  printf '{"version":"%s","version_code":%s}\n' "$VERSION_NAME" "$VERSION_CODE" > /tmp/phaohn-version.json
  scp -o StrictHostKeyChecking=no /tmp/phaohn-version.json "$HOST:$REMOTE_DIR/releases/version.json"
  rm -f /tmp/phaohn-version.json
else
  echo "WARN: Không tìm thấy APK tại $APK_SRC — bỏ qua upload APK"
fi

echo "==> Giải nén + restart service"
ssh -o StrictHostKeyChecking=no "$HOST" bash -s <<'REMOTE'
set -euo pipefail
cd /opt/phaohn
mkdir -p releases
tar xzf /tmp/phaohn-deploy.tgz
rm -f /tmp/phaohn-deploy.tgz
if systemctl is-active --quiet phaohn.service; then
  systemctl restart phaohn.service
  echo "phaohn.service restarted"
else
  echo "WARN: phaohn.service không chạy — bỏ qua restart"
fi
REMOTE

echo "==> Deploy xong: http://152.42.223.79"
echo "    APK: http://152.42.223.79/download/apk"
echo "    Version API: http://152.42.223.79/api/app/version"