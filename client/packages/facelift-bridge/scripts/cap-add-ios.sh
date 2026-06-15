#!/usr/bin/env bash
# cap-add-ios.sh — generate (or regenerate) the iOS Xcode project for
# the Facelift wrapper and patch every Capacitor default we need to
# tweak. Idempotent for fresh setups; re-runs after `rm -rf ios` give
# the same result.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# `cap add ios` writes the project skeleton, then calls `pod install`.
# The pod install step fails when the Podfile's iOS deployment target
# is below the plugin pods' minimum (17.0) — that's expected on a
# fresh add. We swallow that failure here and re-run pod install
# after patching the Podfile.
npx --no-install cap add ios || true

# ── iOS 17 deployment target ──────────────────────────────────────
sed -i '' \
  "s/platform :ios, '[0-9.]*'/platform :ios, '17.0'/" \
  ios/App/Podfile
sed -i '' \
  "s/IPHONEOS_DEPLOYMENT_TARGET = [0-9.]*;/IPHONEOS_DEPLOYMENT_TARGET = 17.0;/g" \
  ios/App/App.xcodeproj/project.pbxproj

# ── Info.plist patches ────────────────────────────────────────────
# Custom URL scheme so the website can call `vance-facelift://*`.
plutil -remove CFBundleURLTypes ios/App/App/Info.plist 2>/dev/null || true
plutil -insert CFBundleURLTypes \
  -json '[{"CFBundleURLSchemes":["vance-facelift"]}]' \
  ios/App/App/Info.plist

# Camera + Photo Library usage descriptions (iOS rejects access
# without these strings).
plutil -replace NSCameraUsageDescription \
  -string "Vance uses the camera so you can attach photos to documents and chat messages." \
  ios/App/App/Info.plist
plutil -replace NSPhotoLibraryUsageDescription \
  -string "Vance uses your photo library so you can attach pictures to documents and chat messages." \
  ios/App/App/Info.plist

# Face-ID — iOS aborts the biometric call without this description.
plutil -replace NSFaceIDUsageDescription \
  -string "Vance uses Face ID to unlock the app." \
  ios/App/App/Info.plist

# Microphone — required for the website's web-based speech-to-text
# (the chat editor uses the SpeechRecognition API). The WKUIDelegate
# in the Swift plugin grants the actual per-call permission; this
# string is shown by iOS in the system permission prompt.
plutil -replace NSMicrophoneUsageDescription \
  -string "Vance uses the microphone for voice input (speech-to-text) in the chat editor." \
  ios/App/App/Info.plist

# ── App-Group entitlement ─────────────────────────────────────────
# Copy our committed template (allows the Share Extension to access
# accounts.json / projects.json / credentials.json on disk and via
# the shared keychain access group).
cp ios-template/App.entitlements ios/App/App/App.entitlements

# Wire the entitlements file into both Debug + Release build configs
# of the App target. Idempotent — only inserts when not already
# present.
if ! grep -q "CODE_SIGN_ENTITLEMENTS = App/App.entitlements" \
        ios/App/App.xcodeproj/project.pbxproj; then
    sed -i '' \
      $'s/CODE_SIGN_STYLE = Automatic;/CODE_SIGN_STYLE = Automatic;\\\n\t\t\t\tCODE_SIGN_ENTITLEMENTS = App\\/App.entitlements;/g' \
      ios/App/App.xcodeproj/project.pbxproj
fi

# ── Share-Extension target ────────────────────────────────────────
# Capacitor does not scaffold app extensions. Run our Ruby helper
# (uses the xcodeproj gem that ships with CocoaPods) to add the
# VanceShareExtension target + wire up its build settings.
# Resolve the gem path from the `pod` wrapper script so the Ruby we
# invoke can `require 'xcodeproj'`.
COCOAPODS_GEM_HOME="$(sed -nE 's/^GEM_HOME="([^"]+)".*/\1/p' "$(command -v pod)" 2>/dev/null | head -1)"
if [ -n "$COCOAPODS_GEM_HOME" ]; then
    GEM_HOME="$COCOAPODS_GEM_HOME" ruby scripts/integrate-share-extension.rb
else
    ruby scripts/integrate-share-extension.rb
fi

# ── Pods + assets ─────────────────────────────────────────────────
( cd ios/App && pod install )
pnpm assets:generate

echo "✓ cap add ios complete."
echo "  One-time manual step in Xcode: add 'App Groups' capability"
echo "  to BOTH the App and VanceShareExtension targets (Signing &"
echo "  Capabilities → + Capability → App Groups →"
echo "  group.de.mhus.vance.facelift)."
