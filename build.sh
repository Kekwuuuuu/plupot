#!/usr/bin/env bash
# build.sh — macOS build for Plupot.
#   chmod +x build.sh && ./build.sh            -> dist/Plupot.app
#   ./build.sh --dmg                           -> also dist/Plupot-<ver>.dmg
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="Plupot"
APP_VERSION="1.0.0"
PKG_ID="com.plupot"
WANT_DMG=0
[[ "${1:-}" == "--dmg" ]] && WANT_DMG=1

# ------------------------------------------------------------------ find JDK 17+
find_jdk() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" && -x "$JAVA_HOME/bin/jpackage" ]]; then
    echo "$JAVA_HOME"; return 0
  fi
  if [[ -x /usr/libexec/java_home ]]; then
    local home
    if home="$(/usr/libexec/java_home -v 17+ 2>/dev/null)"; then
      [[ -x "$home/bin/jpackage" ]] && { echo "$home"; return 0; }
    fi
  fi
  if command -v javac >/dev/null 2>&1; then
    local bin_dir; bin_dir="$(dirname "$(command -v javac)")"
    [[ -x "$bin_dir/jpackage" ]] && { echo "$(dirname "$bin_dir")"; return 0; }
  fi
  return 1
}

if ! JDK_HOME="$(find_jdk)"; then
  cat <<'EOF'
ERROR: no JDK 17+ (with jpackage) found.

  Install one, then re-run:
      brew install --cask temurin@21

  Or point at an existing JDK:
      export JAVA_HOME=$(/usr/libexec/java_home -v 21)
EOF
  exit 1
fi
JDKBIN="$JDK_HOME/bin"
echo "Using JDK: $JDK_HOME"
echo "Arch:      $(uname -m)"

# ------------------------------------------------------------------ 1. master icon
mkdir -p assets
if [[ ! -f assets/icon.png ]]; then
  echo "Missing master icon: $(pwd)/assets/icon.png"
  echo "Drop a square PNG (1024x1024 recommended) there and re-run."
  exit 1
fi

# ------------------------------------------------------------------ 2. compile
rm -rf out
mkdir -p out
find src -name '*.java' > sources.txt
"$JDKBIN/javac" -d out -encoding UTF-8 @sources.txt

# ------------------------------------------------------------------ 3. icons
echo "Building icons..."
"$JDKBIN/java" -cp out com.plupot.build.IconBuilder

# ------------------------------------------------------------------ 4. resources
rm -rf out/com/plupot/res
mkdir -p out/com/plupot
cp -R src/com/plupot/res out/com/plupot/res

# ------------------------------------------------------------------ 5. jar
"$JDKBIN/jar" --create --file Plupot.jar --main-class com.plupot.Main -C out .
echo "Wrote Plupot.jar"

# ------------------------------------------------------------------ 6. reset save
"$JDKBIN/java" -cp out com.plupot.build.ResetSave

# ------------------------------------------------------------------ 7. app bundle
rm -rf app dist
mkdir -p app
cp Plupot.jar app/Plupot.jar

"$JDKBIN/jpackage" \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input app \
  --main-jar Plupot.jar \
  --main-class com.plupot.Main \
  --add-modules java.desktop \
  --icon icon/icon.icns \
  --mac-package-identifier "$PKG_ID" \
  --mac-package-name "$APP_NAME" \
  --dest dist

# Ad-hoc sign so Gatekeeper is less cranky on the local machine.
codesign --force --deep --sign - "dist/$APP_NAME.app" 2>/dev/null \
  && echo "Ad-hoc signed dist/$APP_NAME.app"

echo "Built: dist/$APP_NAME.app"

# ------------------------------------------------------------------ 8. dmg
if [[ "$WANT_DMG" == "1" ]]; then
  "$JDKBIN/jpackage" \
    --type dmg \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --input app \
    --main-jar Plupot.jar \
    --main-class com.plupot.Main \
    --add-modules java.desktop \
    --icon icon/icon.icns \
    --mac-package-identifier "$PKG_ID" \
    --mac-package-name "$APP_NAME" \
    --dest dist
  echo "Built: $(ls dist/*.dmg | head -n1)"
fi