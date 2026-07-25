---
audience: eddie
triggers: kit, kit_install, kit_update, kit_status, kit installieren, install kit, kit update, vance-kits, prune kit, vault password, project kit, kit aus repo, kit branch, kit commit, kit lokal
summary: How Eddie installs, updates, and inspects project kits — default vance-kits.git source, branch/commit pinning, vault passphrases, and prune semantics.
---
# How I install a kit into a project

A **kit** is a bundle of skills, recipes, documents, settings and tool
definitions that brings a project into an equipped state. Instead of
creating each piece individually, I pull a kit from a Git repo (or a
local path) and let the `KitService` copy the contents into the
project — including the inherit chain, if the kit builds on another one.

Default source: <https://github.com/mhus/vance-kits.git>. That is where
the officially maintained kits live in their own subfolders; I point at
the right one via the `path` param.

## Prerequisite

I need a **target project** — a normal user project, not a system
project (`_vance` or `_user_<login>`). Without a `project` param I take
the currently active project (`project_current`).

Only **one** kit may be active per project. `kit_install` fails if one
is already installed — then `kit_update` is the right path. If I want to
switch entirely: first remove the manifest (`kit_apply` with an empty
manifest or the admin path), then install anew.

## Installation from the default repo

Standard case — pull a kit from `vance-kits`:

```
invoke_tool(
  name = "kit_install",
  params = {
    "url":     "https://github.com/mhus/vance-kits.git",
    "path":    "writing-essay",        // subfolder in the repo
    "branch":  "main",                  // optional, default main
    "project": "lissabon-erdbeben"      // optional, otherwise current
  }
)
```

`path` matters here — `vance-kits.git` contains several kits side by
side; without `path` I would take the repo root, which is usually not
what I want. When the user says "install the essay kit into the
project", the sub-path is clear to me.

## Installation from another Git repo

Exactly the same — different URL:

```
invoke_tool(
  name = "kit_install",
  params = {
    "url":    "https://github.com/some-org/internal-kits.git",
    "path":   "audit-pack",
    "token":  "ghp_…",                 // for private repos
    "commit": "abc1234"                 // optional, pin SHA
  }
)
```

`commit` beats `branch` — if I want to pin reproducibly, I set the
commit hash.

## Installation from a local path

File URLs and absolute paths work too. Useful when the user is building
a kit themselves and wants to test it locally:

```
invoke_tool(
  name = "kit_install",
  params = {
    "url":  "file:///Users/hummel/dev/my-kit",
    "path": "kit-root"                  // optional, if there are subfolders
  }
)
```

Or directly as an absolute path:

```
invoke_tool(
  name = "kit_install",
  params = { "url": "/Users/hummel/dev/my-kit" }
)
```

## Vault-protected settings

If the kit brings `PASSWORD` settings, I need a passphrase so that
`KitService` can store them encrypted:

```
params = {
  "url":            "...",
  "vault_password": "<the-passphrase-from-the-user>"
}
```

I ask the user for the passphrase directly — never guess.

## Looking at the status

Before I update or reinstall, I ask `kit_status` to see what is
currently in there:

```
invoke_tool(
  name   = "kit_status",
  params = { "project": "lissabon-erdbeben" }
)
```

I see: active kit name, source URL, pinned commit, list of artifacts
(documents, skills, recipes, settings, tools). When the user asks "what
have we installed?", that is my tool.

## Updating a kit

I call `kit_update` when the user wants a newer version or when upstream
has made changes:

```
invoke_tool(
  name = "kit_update",
  params = {
    "project": "lissabon-erdbeben"
    // url/path/branch/commit are taken from the existing manifest
    // unless I override them
  }
)
```

If I want to switch to a different branch or commit, I pass the
corresponding param:

```
params = {
  "branch": "v2",
  "prune":  true                       // removes orphaned artifacts
}
```

`prune=true` deletes artifacts that were in the old manifest but no
longer in the new one — otherwise they linger as dead entries. Default
is `false` (safe), but if the user says "clean it up", I turn it on.

## When I ask instead of installing

- When the user only says "install a kit" without a source — I name the
  default source (`vance-kits.git`) and list the available subfolders
  via web fetch, letting them choose.
- When the project already has a kit — I describe what is in it via
  `kit_status` and ask whether `kit_update` is enough or a replace is
  wanted.
- When a vault password is missing — I ask for it, instead of guessing
  or firing off the call with an empty string.

## What I don't do

- No `kit_install` into the `_user_<login>` project. That is my hub
  workspace, not meant for kits.
- No automatic update on start. The user decides when upstream changes
  come in.
- No building a custom kit on the fly. When the user says "build me a
  kit", that is a project of its own with `kit_export` at the end — not
  an Eddie action.
