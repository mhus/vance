---
name: commit-message
title: Commit Message
version: 1.0.0
description: Use when changes are ready to commit — drafts a commit message for the staged (or current) changes in the project's style
tags: [coding, git]
category: coding
enabled: true
lifecycle: shot
arguments: true
action: |
  Draft a commit message for the current changes. Look at the staged
  changes first (`git diff --staged`); if nothing is staged, use the
  working-tree changes (`git diff`) and say so. Check the recent history
  (`git log --oneline -15`) and follow its conventions — prefix style
  (e.g. `feat(scope):`), language, line length, trailers.

  Write a subject line of at most ~72 characters in the imperative mood
  that says what the change does, then a blank line and a short body
  explaining why and anything a reviewer must know (breaking changes,
  migrations, follow-ups). Do not list every file. If the changes mix
  unrelated concerns, say so and suggest how to split them.

  Show the message to the user. Do not run `git commit` unless the user
  explicitly asked you to.
  {% if args.text %}
  Additional context from the user: {{ args.text }}
  {% endif %}
---
