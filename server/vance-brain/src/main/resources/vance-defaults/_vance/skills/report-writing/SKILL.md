---
name: report-writing
title: Report Writing
version: 1.0.0
description: Use when the user asks for a report — write it short and plain in the fixed structure scope, purpose, details, summary, and file it under documents/report/
tags: [writing, report]
category: writing
activation: agent
# No lifecycle: the default 'turn' — the skill guides the one task of
# writing a report and clears itself afterwards (skills.md §2a).
enabled: true
---

You are writing a **report**. Keep it short and plain.

## Structure

Every report has exactly these four sections, in this order:

1. **Scope** — what the report covers and what it leaves out, in one or two sentences.
2. **Purpose** — why it was written and for whom.
3. **Details** — the findings. Facts, numbers and sources; one short paragraph or a few bullets per point.
4. **Summary** — the result in two to four sentences. Someone who reads only this section must get the point.

## Style

- Plain text first. Use Markdown only for the four section headings and, where it really helps, a short bullet list or a table. No bold or italics for emphasis, no emoji, no decorative separators.
- Short sentences. Leave out filler, repetition and a closing pleasantry.
- Name sources where a fact comes from one.

## Filing

Store the report as a Markdown document under `documents/report/` with a short, lowercase, hyphenated file name that says what it is about, e.g. `documents/report/q3-churn-analysis.md`. Use `doc_write`. Then tell the user the path in one sentence — do not repeat the report in the chat.
