---
triggers: install tool, missing binary, sdkman, maven, JDK, java toolchain, gradle, self-install, PATH, env.sh, gh, github cli, tool not found, brew, homebrew, apt, install dependency, client machine
summary: Where exec_run commands actually run (container vs workstation, VANCE_EXEC_ENV marker), which tools are preinstalled in the container, and the self-install pattern per environment — sdkman into /app/data/tools inside the container, ask-first brew/apt on a workstation.
---
# Self-installed tools

Before installing anything, establish **where your commands run**. There
are two environments with two different patterns, and applying the wrong
one fails loudly (no `/app/data` on a laptop, no brew in the container) or
quietly (container rules on a workstation = wrong PATH advice, wrong
persistence promises).

## Step 1 — where do you run?

`work_target_get` tells you the active target:

- **CLIENT** → your commands run on the **user's machine** via foot.
  Workstation pattern, no exceptions.
- **WORK** → your commands run wherever this brain runs. One check:

```text
exec_run command="echo \"exec env: ${VANCE_EXEC_ENV:-workstation}\""
```

- `container` → the Docker image. Container pattern.
- `workstation` → a local dev brain on someone's machine. Workstation
  pattern.

## Container pattern

### What is already there

Available from the first turn, on PATH — check before installing:

- **gh** — GitHub CLI (needs auth, see below)
- **git**, **curl**, **unzip**, **tar**, **zip** — the installer set
- **java / javac** — GraalVM for JDK 25: a full current-LTS JDK. Most Java
  tasks need **nothing installed** — a missing Java is never a
  self-install reason
- **node / npm** (20), **python3 / pip** (3.12)

### The tools home and its lifetime

Everything beyond the base set goes into `/app/data/tools` — the one
agent-writable location with a disk budget. Write there with `exec_run`
shell commands; the `work_file_*` tools are RootDir-confined and cannot
reach `/app/data` (a confinement feature, not an obstacle).

**Expect wipes.** `/app/data` lives for the pod's lifetime — every rollout
starts a fresh pod and the tools are gone. That is by design: installs are
**repeatable, not persistent**. This manual carries the pinned commands,
so a wipe costs a re-run, not lost knowledge. Check what is actually
present before assuming (`ls /app/data/tools`).

`HOME` (`/app`) is worse than the tools home: it dies even on a container
restart AND every byte there counts toward node-level eviction. Keep every
big write — tools, checkouts, caches — out of `~`.

### env.sh — the boot-time PATH hook

`/app/data/tools/env.sh` is sourced by the container entrypoint at every
boot; its exports become part of the JVM environment and every `exec_run`
child inherits them. **Append-only**, one line per tool — the file is
syntax-checked at boot and a broken file is skipped wholesale with a
warning:

```text
exec_run command="mkdir -p /app/data/tools/bin"
exec_run command="echo 'export PATH=/app/data/tools/bin:$PATH' >> /app/data/tools/env.sh"
```

A new line applies to all calls after the next **container restart**. Until
then, set PATH in the call itself (`export PATH=… && <tool>`).

### Pattern A — JVM toolchains via sdkman

Maven, Gradle, Java versions beyond the shipped JDK 25:

```text
exec_run command="export SDKMAN_DIR=/app/data/tools/sdkman && curl -s https://get.sdkman.io | bash"
exec_run command="echo 'sdkman_auto_answer=true' >> /app/data/tools/sdkman/etc/config"
exec_run command="export SDKMAN_DIR=/app/data/tools/sdkman && . $SDKMAN_DIR/bin/sdkman-init.sh && sdk install maven 3.9.12"
exec_run command="printf 'export SDKMAN_DIR=/app/data/tools/sdkman\n. $SDKMAN_DIR/bin/sdkman-init.sh\nexport MAVEN_OPTS=-Dmaven.repo.local=/app/data/tools/m2\n' >> /app/data/tools/env.sh"
```

`MAVEN_OPTS` keeps the Maven repository cache out of `~/.m2` (HOME — the
worst place for it) and on the tools volume. Until the next container
restart, call Maven with the exports in the command:

```text
exec_run command="export SDKMAN_DIR=/app/data/tools/sdkman && . $SDKMAN_DIR/bin/sdkman-init.sh && mvn -v"
```

Pin exact versions, never `latest`. One install at a time — sdkman is not
concurrency-safe. Remove replaced versions — a JDK weighs 300–600 MB on a
volume shared with project workspaces and exec logs.

### Pattern B — single static binaries

Anything shipped as a release tarball:

```text
exec_run command="mkdir -p /app/data/tools/<name>/<version> && curl -fsSL <pinned-release-url> | tar xz -C /app/data/tools/<name>/<version> --strip-components=1"
exec_run command="ln -sf /app/data/tools/<name>/<version>/<binary> /app/data/tools/bin/<name>"
exec_run command="echo 'export PATH=/app/data/tools/bin:$PATH' >> /app/data/tools/env.sh"
```

### Caches

Point caches at the tools volume, never at HOME: Maven via `MAVEN_OPTS`
(above), pip via `PIP_CACHE_DIR=/app/data/tools/pip-cache` in env.sh or
per call. A cache in `~` fills toward eviction and vanishes on every
restart.

### gh authentication

Interactive `gh auth login` does not work here. Obtain a token from the
user (vault/setting secret), then per call:

```text
exec_run command="export GH_TOKEN=<token> && gh repo view <owner>/<repo>"
```

Preferred — the token never touches disk. Only persist
`export GH_TOKEN=…` in env.sh if the user explicitly accepts
plaintext-on-volume storage.

## Workstation pattern

Your commands run on a person's machine (local dev brain or foot CLIENT
target). That machine is **not** a throwaway container:

- **Ask before installing.** System-wide installs (brew, apt, sudo) touch
  the user's setup — say what you want to install and why. A denied
  install is a user decision, not an obstacle to route around. On the
  CLIENT target every `client_exec_run` already passes the user's
  permission gate (deny → allow → ask).
- **macOS:** `brew install <tool>` (user-space by nature). **Linux:**
  prefer user-space (`~/.local/bin` + PATH via the shell rc) over
  `sudo apt`; use apt only with the user's explicit OK.
- There is **no `/app`, no `/app/data`, no env.sh** — those exist only in
  the container image. The machine's normal shell PATH applies.
- Check what is already installed first (`which gh`, `which mvn`) — do not
  re-install what is present.
- Persistence inverts here: installing into `~` is **correct** on a
  workstation. It is still the user's disk — ask before large downloads.

## What NOT to do

- **Don't mix patterns.** No `/app/data` on a workstation; no brew/apt in
  the container (no root there, by design).
- **Don't install into `~` in the container** — dies per restart, counts
  toward eviction.
- **Don't rely on self-installed tools surviving a rollout** — re-run the
  pinned commands after a wipe.
- **Don't expect tools on other pods** — `/app/data` is per-pod.
- **Don't install `latest`** — pin exact versions.
- **Don't clean beyond what you created** — `/app/data` carries project
  data; a workstation is someone's home.
