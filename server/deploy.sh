#!/usr/bin/env bash
set -euo pipefail

HOST="${PHAOHN_HOST:-root@152.42.223.79}"
REMOTE_DIR="${PHAOHN_REMOTE_DIR:-/opt/phaohn}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="/tmp/phaohn-deploy-$$.tgz"
APK_SRC="${PHAOHN_APK:-$ROOT/releases/PhaoHN-latest.apk}"
VERSION_NAME="${PHAOHN_VERSION:-2.3.72}"
VERSION_CODE="${PHAOHN_VERSION_CODE:-103}"
APP_NAME="${PHAOHN_APP_NAME:-Pháo™}"

cleanup() { rm -f "$TMP"; }
trap cleanup EXIT

echo "==> Đóng gói server..."
tar czf "$TMP" \
  -C "$ROOT/server" \
  app static requirements.txt phaohn.service .env.example

echo "==> Upload $HOST:$REMOTE_DIR"
scp -o StrictHostKeyChecking=no "$TMP" "$HOST:/tmp/phaohn-deploy.tgz"

if [[ -f "$APK_SRC" ]]; then
  APK_BASENAME="PhaoHN-v${VERSION_NAME}.apk"
  echo "==> Upload APK $(basename "$APK_SRC") → $APK_BASENAME + PhaoHN-latest.apk"
  scp -o StrictHostKeyChecking=no "$APK_SRC" "$HOST:$REMOTE_DIR/releases/$APK_BASENAME"
  scp -o StrictHostKeyChecking=no "$APK_SRC" "$HOST:$REMOTE_DIR/releases/PhaoHN-latest.apk"
  DEPLOYED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  cat > /tmp/phaohn-version.json <<EOF
{
  "name": "$APP_NAME",
  "version": "$VERSION_NAME",
  "version_code": $VERSION_CODE,
  "apk_filename": "$APK_BASENAME",
  "apk_latest": "PhaoHN-latest.apk",
  "download_url": "/download/apk",
  "updated_at": "$DEPLOYED_AT"
}
EOF
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
echo "    App: ${APP_NAME} v${VERSION_NAME} (code ${VERSION_CODE})"
echo "    APK: http://152.42.223.79/download/apk"
echo "    Version API: http://152.42.223.79/api/app/version"