#!/usr/bin/env bash
# Builds the release APK the head unit can actually use: the two offline-mfi identity files
# MUST be inside assets/. A plain assembleRelease is source-only and silently omits them.
#
#   DIPLAY_AUTH_ASSETS_DIR=/path/to/runtime-assets scripts/build_release.sh
#
# Provision the assets once with:
#   curl -fSL -o /tmp/official.apk \
#     https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14.apk
#   python3 scripts/extract_official_identity.py /tmp/official.apk "$DIPLAY_AUTH_ASSETS_DIR" \
#     62b31f79db32bc7c85013ae830460b697a5952fad571ed0030b97341dde0b2e3
set -euo pipefail

: "${DIPLAY_AUTH_ASSETS_DIR:?set DIPLAY_AUTH_ASSETS_DIR; a source-only build cannot authenticate}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export DIPLAY_AUTH_ASSETS_DIR

./gradlew :mobile:assembleStandaloneRelease "$@"

APK="mobile/build/outputs/apk/release/mobile-release.apk"
python3 scripts/verify_apk_identity.py "$APK"
echo "Built a standalone APK with runtime identity: $APK"
