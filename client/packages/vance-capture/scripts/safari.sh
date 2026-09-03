#!/usr/bin/env bash
# Generate the Xcode project that wraps the extension for Safari.
#
# Safari's difference from Chrome and Firefox is not in the manifest — it is
# that a web extension has to ship inside a native app. `dist/safari` is the
# same bundle as the other two; this turns it into something Safari can load.
#
# The project is GENERATED, not committed, the same arrangement
# facelift-bridge has for its `ios/` folder: a native project is an output of
# its source, and a committed one drifts from the manifest that produced it.
# Re-run this after changing the manifest or adding files; a plain code change
# needs only `pnpm build` plus a rebuild in Xcode, because the project
# REFERENCES dist/safari rather than copying it (no --copy-resources).
#
# Needs Xcode — not just the command line tools.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
extension="$here/dist/safari"
project="$here/safari"
pbxproj="$project/Vancetope Capture/Vancetope Capture.xcodeproj/project.pbxproj"
app_plist="$project/Vancetope Capture/Vancetope Capture/Info.plist"

# Store-relevant settings the converter has no flag for. They are applied
# below by patching the generated project — see the block for why that is
# the only lever, and why every patch is verified.
#
# The team id is not a secret (it is in every signed binary) and matches
# the one committed in facelift-bridge's iOS project. Override for a
# different Apple account:  VANCE_DEVELOPMENT_TEAM=XXXXXXXXXX pnpm safari
team="${VANCE_DEVELOPMENT_TEAM:-7YRVVQM79M}"
# The converter stamps whatever macOS SDK is installed as the minimum,
# which on a current Xcode means "runs on this year's macOS only". MV3 is
# what actually constrains us: Safari gained it in 16.4, and macOS 13 is
# the oldest release that carries a Safari new enough to be worth
# claiming. Not tested below the development machine's own version.
deployment_target='13.0'
# One version number, from package.json — the store shows this one.
marketing_version="$(node -p "require('$here/package.json').version")"
# Build number. Unique per upload within a marketing version; App Store
# Connect rejects a repeat. Bump for a second upload of the same version.
build_number="${VANCE_CAPTURE_BUILD:-1}"
copyright='© 2026 Mike Hummel'
# Mac App Store requires a category on the app record; declaring it in the
# bundle too keeps the two from disagreeing.
app_category='public.app-category.productivity'

[ -d "$extension" ] || { echo "No $extension — run 'pnpm build' first." >&2; exit 1; }
xcrun --find safari-web-extension-converter >/dev/null 2>&1 \
  || { echo "safari-web-extension-converter not found — install Xcode." >&2; exit 1; }

# Destructive, and worth saying so: this discards anything set in Xcode on the
# generated project. Everything a store build depends on is re-applied below,
# so a regeneration is safe in that respect — but a setting you changed by hand
# and did not add to this script is gone. Only run it when the manifest or the
# set of files changed. A plain code change needs `pnpm build` plus a rebuild in
# Xcode, because the project references dist/safari rather than copying it.
if [ -d "$project" ]; then
  echo "Replacing the existing project at $project (Xcode settings there are lost)."
fi
rm -rf "$project"
mkdir -p "$project"

# The identifier has to be the one the converter will derive for the APP, not
# the one we would pick for it. It builds the app id from the identifier's
# prefix plus the sanitised app name, and the extension id from the identifier
# plus ".Extension" — so passing "de.mhus.vance.capture" yields an app called
# de.mhus.vance.Vancetope-Capture with an extension called
# de.mhus.vance.capture.Extension, which is not prefixed by its parent and
# fails the build with "Embedded binary's bundle identifier is not prefixed".
# Passing the derived form makes both agree, which beats sed-ing a generated
# Xcode project afterwards.
#
# --macos-only: the capture surface is a toolbar popup next to a page you are
#   reading on a Mac. iOS Safari supports extensions, but the popup and the
#   "read the rendered DOM of the current tab" flow want a different design
#   there, and shipping an iOS target nobody tried would be a claim we cannot
#   back.
# --no-open/--no-prompt: this has to work from a script.
xcrun safari-web-extension-converter "$extension" \
  --project-location "$project" \
  --app-name "Vancetope Capture" \
  --bundle-identifier "de.mhus.vance.Vancetope-Capture" \
  --swift \
  --macos-only \
  --no-open \
  --no-prompt \
  --force

# ── Store settings ──────────────────────────────────────────────────────
#
# Everything below edits the generated project, which the bundle-identifier
# note above went out of its way to avoid. The difference is that the
# converter HAS a flag for the identifier and has none for a signing team,
# a deployment target or a version — so for these there is no earlier point
# to intervene. The alternative is setting them by hand in Xcode after every
# regeneration, which is how a build gets shipped signed by whatever was
# left over.
#
# Each patch states how many places it expects to change, and the script
# stops when the count is off. A converter release that renames or drops a
# setting then fails loudly here instead of silently producing an archive
# with the wrong signing identity.
patch_count() {
  local label="$1" expected="$2" pattern="$3"
  local actual
  actual="$(grep -c -- "$pattern" "$pbxproj" || true)"
  [ "$actual" = "$expected" ] && return 0
  echo "✗ $label: expected $expected occurrences, found $actual." >&2
  echo "  The generated project no longer looks the way this script" >&2
  echo "  assumes — check what safari-web-extension-converter emits." >&2
  exit 1
}

# Deployment target: project-level and the extension target both carry one.
sed -i '' "s|MACOSX_DEPLOYMENT_TARGET = [0-9.]*;|MACOSX_DEPLOYMENT_TARGET = $deployment_target;|g" "$pbxproj"
patch_count 'deployment target' 4 "MACOSX_DEPLOYMENT_TARGET = $deployment_target;"

# Versions: two targets × Debug/Release.
sed -i '' "s|MARKETING_VERSION = [0-9.]*;|MARKETING_VERSION = $marketing_version;|g" "$pbxproj"
patch_count 'marketing version' 4 "MARKETING_VERSION = $marketing_version;"
sed -i '' "s|CURRENT_PROJECT_VERSION = [0-9]*;|CURRENT_PROJECT_VERSION = $build_number;|g" "$pbxproj"
patch_count 'build number' 4 "CURRENT_PROJECT_VERSION = $build_number;"

# Copyright — the converter leaves the key present but empty.
sed -i '' "s|INFOPLIST_KEY_NSHumanReadableCopyright = \"\";|INFOPLIST_KEY_NSHumanReadableCopyright = \"$copyright\";|g" "$pbxproj"
patch_count 'copyright' 4 "INFOPLIST_KEY_NSHumanReadableCopyright = \"$copyright\";"

# Signing team, inserted next to the signing style it belongs with.
sed -i '' $'s|CODE_SIGN_STYLE = Automatic;|CODE_SIGN_STYLE = Automatic;\\\n\t\t\t\tDEVELOPMENT_TEAM = '"$team"$';|g' "$pbxproj"
patch_count 'development team' 4 "DEVELOPMENT_TEAM = $team;"

# Outgoing network for the EXTENSION target. The app target gets it from
# the converter; the appex does not, and the appex is where the popup and
# the options page run — every call to the brain is made from inside its
# sandbox, not the app's. Anchored on the extension's INFOPLIST_FILE
# because that line appears in the extension's two configs and nowhere
# else; ENABLE_APP_SANDBOX would also match the app target, and a second
# copy of a key the app already has makes the project file invalid. The
# expected count is 4, not 2: the app target's own two are already there.
sed -i '' $'s|INFOPLIST_FILE = "Vancetope Capture Extension/Info.plist";|INFOPLIST_FILE = "Vancetope Capture Extension/Info.plist";\\\n\t\t\t\tENABLE_OUTGOING_NETWORK_CONNECTIONS = YES;|g' "$pbxproj"
patch_count 'extension network entitlement' 4 'ENABLE_OUTGOING_NETWORK_CONNECTIONS = YES;'

# App category, on the app target only — NSMainStoryboardFile is set there
# and not on the extension.
sed -i '' $'s|INFOPLIST_KEY_NSMainStoryboardFile = Main;|INFOPLIST_KEY_LSApplicationCategoryType = '"$app_category"$';\\\n\t\t\t\tINFOPLIST_KEY_NSMainStoryboardFile = Main;|g' "$pbxproj"
patch_count 'app category' 2 "INFOPLIST_KEY_LSApplicationCategoryType = $app_category;"

# Export compliance. The extension talks HTTPS to a brain and does no
# cryptography of its own, which is the exemption this key declares.
# Without it every single upload stops on the same question in App Store
# Connect. GENERATE_INFOPLIST_FILE merges the generated keys over this
# file, so a key that has no INFOPLIST_KEY_ equivalent goes here.
plutil -replace ITSAppUsesNonExemptEncryption -bool false "$app_plist"

echo
echo "Xcode project: $project"
echo "  team $team · version $marketing_version ($build_number) · macOS $deployment_target+"
echo "Next: open it, Run once, then in Safari enable the extension"
echo "      (Settings -> Extensions) with Develop -> Allow Unsigned Extensions."
echo "For a store build see readme/vance-capture-app-store.md."
