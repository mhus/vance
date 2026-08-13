# Checklist

A flat list where every entry carries a **status**. Same shape as a
list, one field richer — use it whenever "is this
done?" is part of the content rather than something you keep in your
head.

## Statuses

In markdown the status is the character between the brackets:

| Char | Status | |
|---|---|---|
| (space) | `open` | not started |
| `x` | `done` | finished |
| `~` | `in_progress` | being worked on |
| `/` | `review` | done, awaiting a look |
| `!` | `blocked` | something is in the way |
| `?` | `needs_info` | can't proceed, question open |
| `-` | `deferred` | consciously postponed |
| `>` | `delegated` | someone else has it |
| `<` | `waiting` | waiting on someone else |

The last seven are the reason this is its own kind rather than a
boolean: *blocked* and *waiting* say different things about the same
unfinished item, and a plain checkbox flattens both to "not done".

## On disk

```markdown
---
kind: checklist
---
- [ ] open task
- [x] done task
- [~] in progress task
- [!] blocked task
```

```yaml
$meta:
  kind: checklist
items:
  - text: open task
  - text: done task
    status: done
  - text: in progress task
    status: in_progress
```

In YAML and JSON the status is spelled out; a missing `status` means
`open`.

## In the editor

*View* lets you click a status to cycle it, edit text inline, reorder
by dragging and delete. Unknown per-item fields survive editing.
