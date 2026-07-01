#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAB="${PHAOHN_AAB:-$ROOT/releases/PhaoHN-v2.3.34-direct.aab}"

if [[ ! -f "$AAB" ]]; then
  echo "Chưa có AAB. Build: cd $ROOT && ./gradlew bundleDirect"
  exit 1
fi

echo "AAB: $AAB ($(du -h "$AAB" | cut -f1))"
echo "Package: com.phaohn.spyhelper"
echo "Version: 2.3.34-direct (code 65)"
echo ""

if [[ -n "${PLAY_JSON_KEY:-}" && -f "$PLAY_JSON_KEY" ]]; then
  echo "Upload qua fastlane (track=${PLAY_TRACK:-internal})..."
  cd "$ROOT/fastlane"
  PLAY_JSON_KEY="$PLAY_JSON_KEY" PHAOHN_AAB="$AAB" fastlane upload_direct
  exit 0
fi

echo "Chưa có PLAY_JSON_KEY — upload thủ công trên Play Console:"
echo "  1. https://play.google.com/console → Tạo app (hoặc chọn PhaoHN)"
echo "  2. Testing → Internal testing → Create release"
echo "  3. Upload file: $AAB"
echo "  4. Điền Accessibility API declaration + Privacy policy URL"
echo ""
echo "Tự động hóa: đặt PLAY_JSON_KEY=/path/to/play-service-account.json rồi chạy lại script này."