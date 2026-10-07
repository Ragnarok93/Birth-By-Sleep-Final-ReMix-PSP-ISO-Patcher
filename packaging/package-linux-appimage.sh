#!/usr/bin/env bash
set -euo pipefail

CONFIGURATION="${1:-Release}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_PARENT="$ROOT_DIR/composeApp/build/compose/binaries/main/app"
DIST_ROOT="$(find "$DIST_PARENT" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

if [[ -z "$DIST_ROOT" ]]; then
  echo "Compose desktop app image was not found under $DIST_PARENT" >&2
  exit 1
fi

OUT_DIR="$ROOT_DIR/dist"
APPDIR="$ROOT_DIR/build/AppDir-$CONFIGURATION"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr" "$OUT_DIR"
cp -a "$DIST_ROOT"/. "$APPDIR/usr/"

LAUNCHER="$(find "$APPDIR/usr/bin" -maxdepth 1 -type f -perm -u+x | head -n 1)"
if [[ -z "$LAUNCHER" ]]; then
  echo "Desktop launcher was not found in $APPDIR/usr/bin" >&2
  exit 1
fi
LAUNCHER_NAME="$(basename "$LAUNCHER")"

cat > "$APPDIR/AppRun" <<EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/usr/bin/$LAUNCHER_NAME" "\$@"
EOF
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/bbs-final-remix.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Birth By Sleep - Final ReMix PSP ISO Patcher
Exec=AppRun
Icon=bbs-final-remix
Categories=Game;Utility;
Terminal=false
EOF

cat > "$APPDIR/bbs-final-remix.svg" <<'EOF'
<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
  <rect width="256" height="256" rx="56" fill="#06111f"/>
  <path d="M128 205c-10-23-28-40-50-56-26-19-36-45-25-70 8-18 25-29 45-29 13 0 24 5 30 14 7-9 18-14 31-14 20 0 37 11 45 29 11 25 1 51-25 70-22 16-40 33-51 56z" fill="none" stroke="#1e8fff" stroke-width="14"/>
  <circle cx="128" cy="55" r="12" fill="#6aaeff"/>
</svg>
EOF

TOOL="$ROOT_DIR/build/appimagetool-x86_64.AppImage"
mkdir -p "$(dirname "$TOOL")"
if [[ ! -x "$TOOL" ]]; then
  curl -L --fail --retry 3     -o "$TOOL"     "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$TOOL"
fi

OUT_FILE="$OUT_DIR/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher-Linux-x86_64-$CONFIGURATION.AppImage"
ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$APPDIR" "$OUT_FILE"
chmod +x "$OUT_FILE"
echo "Created $OUT_FILE"
