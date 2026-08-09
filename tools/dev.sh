#!/bin/bash
# Dev helpers for the Galaxy Z Flip 7. Source or call with a subcommand.
set -euo pipefail

PKG=com.scrtrans
SVC="$PKG/$PKG.TranslatorService"
TARGET=jp.hotpepper.android.beauty.hair
# Z Flip has two displays; screencap needs an explicit one or it prepends a
# warning to the PNG bytes and you get a corrupt file.
DISPLAY_ID=4633128672291735937

here="$(cd "$(dirname "$0")/.." && pwd)"

case "${1:-}" in
  install)
    "$here/gradlew" -p "$here" installDebug
    # Reinstalling always clears the accessibility grant, so re-enable it.
    "$0" enable
    ;;
  enable)
    adb shell settings put secure enabled_accessibility_services "$SVC"
    adb shell settings put secure accessibility_enabled 1
    sleep 1
    echo "enabled: $(adb shell settings get secure enabled_accessibility_services)"
    ;;
  disable)
    adb shell settings put secure enabled_accessibility_services ""
    adb shell settings put secure accessibility_enabled 0
    ;;
  shot)
    out="${2:-shot.png}"
    adb exec-out screencap -p -d "$DISPLAY_ID" > "$out"
    file "$out"
    ;;
  app)
    adb shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    ;;
  home)
    adb shell input keyevent KEYCODE_HOME
    ;;
  log)
    adb logcat -s ScrTrans
    ;;
  clearlog)
    adb logcat -c
    ;;
  *)
    echo "usage: $0 {install|enable|disable|shot [file]|app|home|log|clearlog}"
    exit 1
    ;;
esac
