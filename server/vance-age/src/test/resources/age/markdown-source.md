---
kind: workpage
title: Age Fixture
---

# Age Fixture Document

This plaintext was encrypted with the reference **Go `age` CLI** — the fixture
exists to prove that the Vance client decrypts real-world, externally produced
armored files, not just files written by `age-encryption` itself.

## What it exercises

- Front matter (`kind: workpage`) — after decryption, `readKindFromBody` must
  see a perfectly ordinary document body.
- UTF-8 round-trip: ä, ö, ü, ß, ✓, ✓✓, and an emoji 🗜.
- Long lines and short ones.

```text
vance.documents.* has nothing to do with this block — it is a plain fence.
```

| col | value |
|-----|-------|
| a   | 1     |
| b   | 2     |

Tail line.
