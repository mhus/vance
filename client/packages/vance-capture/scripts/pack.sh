#!/usr/bin/env bash
# Package the built extension for the Chrome Web Store and addons.mozilla.org,
# plus the source archive AMO requires.
#
# Why a source archive at all: the uploaded package is bundled and minified by
# Vite, and AMO asks for reproducible source whenever it is. The archive is
# `git archive` of the whole repository rather than a trimmed copy of this
# package — the extension imports `@vance/shared`, and the root lockfile
# describes every workspace member, so a trimmed tree could not be installed
# with `--frozen-lockfile`. Verified: building from the archive reproduces
# `dist/firefox` byte for byte. The instructions the reviewer follows are
# committed as AMO-BUILD.md and therefore ship inside the archive.
#
# Uncommitted work is refused rather than silently packaged: `git archive`
# reads HEAD, so a dirty tree would ship source that does not match the
# uploaded bundle — the one failure mode that gets an add-on rejected for a
# reason nobody can see in the diff.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"
version="$(node -p "require('$here/package.json').version")"
out="$here/dist/upload"

for target in chrome firefox; do
  [ -f "$here/dist/$target/manifest.json" ] || {
    echo "No build at dist/$target — run 'pnpm build' first." >&2
    exit 1
  }
done

if [ -n "$(git -C "$repo" status --porcelain)" ]; then
  echo "Working tree is dirty. The source archive comes from HEAD and would" >&2
  echo "not match the packaged bundle. Commit first." >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"

# zip from inside the directory so manifest.json is the top-level entry —
# a store upload with the folder as the root is rejected without explanation.
for target in chrome firefox; do
  (cd "$here/dist/$target" && zip -q -r -X "$out/vancetope-capture-$target-$version.zip" .)
done

git -C "$repo" archive --format=tar.gz --prefix="vancetope-capture-$version/" HEAD \
  > "$out/vancetope-capture-source-$version.tar.gz"

echo "Packaged version $version from $(git -C "$repo" rev-parse --short HEAD):"
ls -lh "$out" | tail -n +2 | awk '{printf "  %-52s %s\n", $NF, $5}'
