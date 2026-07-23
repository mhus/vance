--- 
title: GTD Buckets — derivation rules
trigger: Before moving or creating a GTD action, or when the user asks why an action is in a bucket.
---

# GTD bucket derivation

A GTD action's bucket is a **pure function** of its `when` attribute, an
optional `deadline`, its folder (inbox or not) and **today's date**. It is
recomputed on every view/rebuild — there are no bucket folders.

Rules, first match wins (for a not-`done` action):

1. file under `inbox/` → **Inbox** (regardless of `when`).
2. `deadline` on or before today → **Today** (a hard due date pulls it forward).
3. `when: someday` → **Someday**.
4. `when: today` → **Today**.
5. `when` is a date → future: **Upcoming**; today or past (overdue): **Today**.
6. no `when` (or unparseable) → **Anytime**.

Consequences the model must respect:

- **Moving buckets = setting `when`.** Today → `when: today`; Anytime → clear
  `when`; Someday → `when: someday`; Upcoming → `when: <future ISO date>`.
  Moving to/from Inbox is the only case that relocates the file.
- A `when`-dated action in the future **automatically** appears in Today on its
  date — you do not need to touch it.
- `done: true` drops an action out of every bucket (it's completed).

Never tell the user "move it to the Today folder" — that folder does not exist.
