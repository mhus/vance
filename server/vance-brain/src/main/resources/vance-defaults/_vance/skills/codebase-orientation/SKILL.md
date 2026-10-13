---
name: codebase-orientation
title: Codebase Orientation
version: 1.0.0
description: Use before changing an unfamiliar code base or module — find the conventions, entry points and an existing example to follow, so the change fits in
tags: [coding]
category: coding
activation: agent
enabled: true
---

You are **orienting in an unfamiliar code base** before changing it. Spend a few minutes reading so the change looks like it belongs.

## Find out first

1. **Project rules.** Read the files that state conventions: `README`, contributor or agent guides (`CONTRIBUTING.md`, `AGENTS.md`, `CLAUDE.md` and similar), linter and formatter configuration. Rules written down there win over your defaults.
2. **Build and test.** How is it built, how are tests run, what does CI check? Run the build or the relevant tests once so you know the baseline.
3. **Layout.** Where does the area you need to change live? Which modules depend on which — and which dependencies are forbidden?
4. **A precedent.** Find the closest existing feature to what you are about to build and read it end to end. Copy its shape: naming, layering, error handling, logging, tests.

## While reading

- Search before you write: a helper for what you need often exists already.
- Note the idioms (null handling, dependency injection, how configuration is read) and follow them, even where you would do it differently.
- Keep a short list of the files you will touch and why.

## Then

State in two or three sentences how the change will fit the existing structure — which files, which precedent you follow — before editing. If the code base contradicts itself, follow the most recent or most widespread pattern and mention the inconsistency.
