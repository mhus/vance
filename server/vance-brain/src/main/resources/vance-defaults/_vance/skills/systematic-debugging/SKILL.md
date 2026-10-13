---
name: systematic-debugging
title: Systematic Debugging
version: 1.0.0
description: Use when something fails, crashes or behaves unexpectedly and the cause is not obvious — reproduce, isolate and prove the root cause before changing code
tags: [coding, debugging]
category: coding
activation: agent
enabled: true
triggers:
  - type: PATTERN
    pattern: "(?i)\\b(debug|debuggen|stack ?trace|root cause)\\b"
---

You are **debugging systematically**. No fix before the cause is proven.

## 1. Reproduce

Get a reliable, ideally minimal, reproduction: the exact command, input and environment that shows the failure. Capture the full error output — the first error, not just the last line. If you cannot reproduce it, say so and gather more information instead of guessing.

## 2. Read the evidence

- Read the whole stack trace or log: where it was thrown, and which of *our* frames led there.
- Note what is actually observed versus what was expected. Write both down in one sentence each.
- Check what changed recently (`git log`, `git diff`) — new failures usually live in new code.

## 3. Isolate

Narrow the search space before reading code at random:

- Bisect: halve the input, the code path or the commit range until the failure appears or disappears.
- Find the last point where the state is still correct and the first where it is wrong.
- Add temporary logging or assertions at those points rather than reasoning in the abstract.

## 4. Hypothesise and test

State one hypothesis that explains *all* observations. Predict what a specific experiment will show, run it, compare. A hypothesis that explains only some symptoms is wrong or incomplete. Change one thing at a time.

## 5. Fix the cause

- Fix the root cause, not the symptom where it surfaced. A null check at the crash site often hides the real bug upstream.
- Add a test that fails without the fix and passes with it.
- Remove the temporary logging.
- Run the wider test suite — a fix can break a neighbour.

## Report

Say what the cause was, how you proved it, what you changed and how it is now guarded. If you stopped before proving the cause, say that plainly and list what you ruled out.
