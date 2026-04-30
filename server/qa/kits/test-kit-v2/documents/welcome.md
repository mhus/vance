# Welcome to Test Kit v2

This document is unique to **v2** of `test-kit`. The v1 fixture has
`intro.md`; v2 drops `intro.md` and adds this `welcome.md` instead.

The KitImportTest uses these two versions to exercise the UPDATE path:

- **Without prune** (step2): re-import v2 on top of v1's manifest.
  `welcome.md` is ADDED, `intro.md` stays in the project (UPDATE
  without prune does not delete documents). The manifest itself is
  rewritten as v2-only — orphans stay in Mongo but stop being tracked.
- **With prune** (step3): the test re-installs v1 first to push the
  manifest back to the v1 shape, then re-imports v2 with `prune=true`.
  `intro.md` is now in the previous-manifest-but-not-in-new-source
  diff and gets removed.

Marker: `test-kit-welcome-v2`.
