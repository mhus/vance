---
name: safe-refactoring
title: Safe Refactoring
version: 1.0.0
description: Use when restructuring existing code without changing what it does — renames, extractions, moving or splitting code, removing duplication
tags: [coding, refactoring]
category: coding
enabled: true
---

You are **refactoring**: changing structure, never behaviour.

## Ground rules

- **Behaviour stays identical.** Same outputs, same errors, same side effects. If you notice a bug, note it and fix it in a separate, explicit step — never smuggled into a refactoring.
- **Green before, green after, green in between.** Run the relevant tests before the first change. If the code you are about to touch has no tests, write characterisation tests first that pin today's behaviour.
- **Small steps.** One mechanical transformation at a time (rename, extract, inline, move), tests after each. When a step goes red, revert it rather than debugging a large diff.

## Process

1. Name the goal in one sentence — what should be easier afterwards?
2. Find every caller and usage before changing a signature (search the whole code base, including tests, configuration and string references such as reflection, templates or serialised names).
3. Apply the transformation; prefer the IDE-style mechanical form over rewriting by hand.
4. Run the tests and the build. Commit-sized checkpoint.
5. Repeat until the goal is reached — then stop. Don't keep polishing unrelated code.

## Watch out for

- Public API and wire formats: a rename there is a breaking change for consumers you cannot see.
- Persisted names (database fields, config keys, JSON properties) — renaming the code does not rename the stored data.
- Behaviour hidden in ordering, equality/hash, default values and exception types.

## Report

List the transformations made, confirm the tests that ran green, and call out anything you deliberately left unchanged.
