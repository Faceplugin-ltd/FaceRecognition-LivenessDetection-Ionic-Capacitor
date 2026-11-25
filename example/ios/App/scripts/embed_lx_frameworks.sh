#!/bin/bash
# Copy the LX Face Recognition runtimes (same files as Face-Recognition-LivenessDetection-iOS)
# into the app Frameworks folder and sign them. Device arm64 only.
set -euo pipefail

if [ "${PLATFORM_NAME:-}" = "iphonesimulator" ]; then
  echo "note: skipping Face Recognition frameworks on simulator (device-only)"
  exit 0
fi

# Xcode SRCROOT is example/ios/App. The plugin runtimes live at ios/Frameworks.
if [ -n "${SRCROOT:-}" ]; then
  SRC_DIR="${SRCROOT}/../../../ios/Frameworks"
else
  SRC_DIR="$(cd "$(dirname "$0")/../../../../ios/Frameworks" && pwd)"
fi
DEST="${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}"
mkdir -p "$DEST"

copy_fw() {
  local from="$1"
  local to="$DEST/$(basename "$from")"
  if [ ! -d "$from" ]; then
    echo "error: missing $from" >&2
    exit 1
  fi
  rm -rf "$to"
  /usr/bin/ditto "$from" "$to"
}

copy_xc() {
  local xc="$1"
  local slice="$xc/ios-arm64"
  local from
  from="$(/bin/ls -d "$slice"/*.framework 2>/dev/null | /usr/bin/head -1 || true)"
  if [ -z "$from" ] || [ ! -d "$from" ]; then
    echo "error: missing $slice/*.framework" >&2
    exit 1
  fi
  copy_fw "$from"
}

copy_fw "$SRC_DIR/facerecognitionsdk.framework"
if [ -d "$SRC_DIR/FacePluginSDK.xcframework" ]; then
  copy_xc "$SRC_DIR/FacePluginSDK.xcframework"
else
  copy_fw "$SRC_DIR/FacePluginSDK.framework"
fi
if [ -d "$SRC_DIR/FacePlugin.xcframework" ]; then
  copy_xc "$SRC_DIR/FacePlugin.xcframework"
else
  copy_fw "$SRC_DIR/FacePPlugin.framework"
fi
if [ -d "$SRC_DIR/FaceDetect.xcframework" ]; then
  copy_xc "$SRC_DIR/FaceDetect.xcframework"
else
  copy_fw "$SRC_DIR/FaceDetect.framework"
fi

SIGN="${SRCROOT}/scripts/sign_nested_facerecognitionsdk.sh"
if [ -f "$SIGN" ]; then
  /bin/sh "$SIGN"
fi
echo "Embedded LX Face Recognition frameworks into $DEST"
