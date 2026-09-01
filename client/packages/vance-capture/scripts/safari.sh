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

[ -d "$extension" ] || { echo "No $extension — run 'pnpm build' first." >&2; exit 1; }
xcrun --find safari-web-extension-converter >/dev/null 2>&1 \
  || { echo "safari-web-extension-converter not found — install Xcode." >&2; exit 1; }

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

echo
echo "Xcode project: $project"
echo "Next: open it, Run once, then in Safari enable the extension"
echo "      (Settings -> Extensions) with Develop -> Allow Unsigned Extensions."
