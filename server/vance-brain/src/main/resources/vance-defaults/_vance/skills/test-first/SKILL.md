---
name: test-first
title: Test-First Development
version: 1.0.0
description: Use when adding or changing behaviour in code that has (or should have) automated tests — write the failing test first, then the minimal code to pass it
tags: [coding, testing]
category: coding
activation: agent
enabled: true
---

You are working **test-first**. Every behaviour change starts as a failing test.

## The loop

1. **Red.** Write one test that describes the next small piece of behaviour. Run it and watch it fail — and check it fails for the *right* reason (an assertion, not a compile error or a missing fixture). A test you never saw fail proves nothing.
2. **Green.** Write the least code that makes it pass. Hard-coding is fine at this step; the next test forces the generalisation.
3. **Refactor.** With the test green, clean up names, duplication and structure — in both production and test code. Run the tests again after.

Repeat in small steps. If a step takes more than a few minutes to get green, the step was too big — revert to green and split it.

## Before the first test

- Find how the project runs its tests (build file, `README`, CI config) and run the existing suite once, so you know what "green" looks like before you touch anything.
- Look at a neighbouring test file and follow its conventions: framework, naming, fixture style, assertion library.

## What a good test looks like

- Tests **behaviour through the public surface**, not private helpers or call order.
- One reason to fail. The name says what is expected: `rejectsExpiredToken`, not `test3`.
- Arrange / act / assert, visibly separated. No logic (loops, conditionals) in the test itself.
- Deterministic: no wall clock, randomness, network or shared state unless the test controls it.
- Cover the edges the change touches: empty, null/missing, boundary values, the error path.

## When fixing a bug

Write the test that reproduces the bug **first**. It must fail against the current code; then fix. That test is the proof the fix works and the guard against regression.

## Don't

- Don't write the implementation first and back-fill tests that merely mirror it.
- Don't weaken or delete an existing test to get green — find out why it fails.
- Don't mock what you own when the real object is cheap; mock at system boundaries.
