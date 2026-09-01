#!/usr/bin/env bash
# Regenerate the extension icons from the product's own favicon.
#
# The source is deliberately *not* copied into this package: the mark is
# defined once, in vance-face, and a second SVG here would drift the day
# somebody adjusts the glyph. The PNGs are committed because a build must not
# depend on a rasteriser being installed — this script is run by hand when the
# mark changes, the same arrangement facelift-bridge uses for its app icon.
#
# Why the favicon and not facelift-bridge's icon-source.svg: that one is the
# iOS app icon — solid blue, square, no rounding, because the App Store rejects
# transparency. A browser extension sits next to browser tabs, so it wears the
# browser mark.
#
# qlmanage is the repo's existing rasteriser (see facelift-bridge's
# `assets:regenerate-png`). Rendering once at 1024 and downscaling with sips
# gives cleaner small sizes than asking QuickLook for 16 pixels directly.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_svg="$here/../vance-face/public/favicon.svg"
target="$here/src/public/icons"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

[ -f "$source_svg" ] || { echo "No favicon at $source_svg" >&2; exit 1; }

qlmanage -t -s 1024 -o "$work" "$source_svg" >/dev/null 2>&1
rendered="$work/$(basename "$source_svg").png"
[ -f "$rendered" ] || { echo "qlmanage produced nothing — is QuickLook available?" >&2; exit 1; }

mkdir -p "$target"
# 16/32/48 are the toolbar and menu sizes, 128 is what Chrome shows in the
# extensions page and the store listing, 96 is Firefox's add-on manager at 2x.
for size in 16 32 48 96 128; do
  sips -z "$size" "$size" "$rendered" --out "$target/icon-$size.png" >/dev/null
done

echo "Wrote $(ls "$target" | wc -l | tr -d ' ') icons to $target"
