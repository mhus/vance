---
audience: creator
triggers: vance sources, source code, sourcecode, quellcode, vance quellcode, source analysis, quellen analysieren, check out vance, vance sourcen, correct schema, verify schema, yaml schema nachschlagen, git_checkout, brain version, welche version laeuft, brain_info, vance internals, how does vance, wie funktioniert vance, verify against sources, source checkout, vance repo
summary: How I analyze Vance's own sources — brain_info for the running version and build commit, git_checkout of the public repo at exactly that revision, then file_grep / file_read inside the checkout to verify schemas, defaults and naming instead of guessing. Public repo only; the workspace is ephemeral — findings belong in the project.
requires-tools: git_checkout, file_grep, file_read
---
# How I analyze Vance's own sources

Vance's YAML schemas, settings keys and tool parameters are
Vance-specific — they do not match generic conventions. When the setup
must match how Vance really behaves, I do not guess: I check out the
sources of the running version and read the answer out of the code.

**Never claim a schema or key "probably" looks a certain way — grep the
sources first.**

## Step 1 — what is running

`brain_info` reports the running brain's `version`, `buildTime`, and —
when the jar was stamped at build time — `commit` and `branch` plus a
`dirty` flag (dirty = built from a locally modified checkout; treat
with the same honesty as an unknown commit).

## Step 2 — map version to a git ref

| brain_info says | Checkout ref | Exactness |
|---|---|---|
| `commit` is a SHA | `commit=<sha>` | exact — the sources the jar was built from |
| version has no `-SNAPSHOT` | `branch=refs/tags/v<version>` | exact — release tag |
| `-SNAPSHOT`, commit `unknown` | `branch=main` | approximate — say so when the answer may differ |

## Step 3 — check out

```
invoke_tool(
  name = "git_checkout",
  args = {
    "repoUrl": "https://github.com/mhus/vance.git",
    "commit": "<sha from brain_info>",       // or branch=… see above
    "depth": 1,                              // shallow — analysis only
    "label": "vance-sources",
    "asWorkingDir": true
  })
```

**Always pass `depth: 1` for analysis checkouts** — the snapshot is
all an analysis needs, and a full-history clone of the vance repo takes
minutes over the network where a depth-1 clone takes seconds. Shallow
checkouts are fine here because analysis RootDirs are ephemeral: nothing
is ever suspended or pushed from them.

`asWorkingDir: true` makes the checkout the process's WORK RootDir, so
the generic `file_*` tools operate inside it without any further
targeting. `work_target_get` confirms WORK if in doubt.

## Step 4 — analyze

| Question | Where to look |
|---|---|
| Bundled conventions — recipes, manuals, templates, setting forms | `server/vance-brain/src/main/resources/vance-defaults/_vance/` |
| A tool's real parameters | the Tool class's `paramsSchema` under `server/vance-brain/src/main/java/de/mhus/vance/brain/tools/<area>/` |
| Engine behavior (what a worker may do) | `server/vance-brain/src/main/java/de/mhus/vance/brain/<engine>/` |
| Addon wiring | `server/vance-addon-brain-*/src/main/resources/META-INF/spring/AutoConfiguration.imports` |
| Source-config documents (research sources, feeds, mounts) | `server/vance-brain/src/main/java/de/mhus/vance/brain/sourceconfig/` |
| Web-UI side of a surface | `client/packages/vance-face/src/` |

`file_grep pattern=<term>` searches the whole checkout (recursive,
WORK-relative paths); `file_read path=<file>` reads a single file;
`file_find name=<glob>` locates files first when the path is unclear.

## Honesty rules

- **Public repo only.** `github.com/mhus/vance.git` is the product
  code and bundled defaults — the right reference for how to *create*
  things in Vance. EE modules and internal specifications are not in
  it: do not promise their content, say that they are out of reach.
- **The workspace is ephemeral.** A checkout RootDir survives process
  suspend/recover at best and is not a place to keep results. Findings
  that matter for the project go into a document or note; then
  `workspace_delete dirName=<…>` cleans up.
- **Version differences are real.** If the running build is a
  `-SNAPSHOT` without a commit stamp, the checkout may be ahead of what
  is running — state that when quoting file contents.
