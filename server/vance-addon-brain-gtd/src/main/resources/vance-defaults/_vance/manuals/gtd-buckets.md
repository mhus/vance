--- 
title: GTD Buckets — derivation rules
trigger: Before moving or creating a GTD action, or when the user asks why an action is in a bucket.
---

# GTD bucket derivation

A GTD action's bucket is a **pure function** of its `when` attribute, an
optional `deadline`, its folder (`inbox/` / `trash/` or neither) and **today's
date**. It is recomputed on every view/rebuild — there are no bucket folders
apart from those two.

Rules, first match wins:

1. file under `trash/` → **Trash** (put away; not on any work list).
2. file under `inbox/` → **Inbox** (regardless of `when`).
3. `deadline` on or before today → **Today** (a hard due date pulls it forward).
4. `when: someday` → **Someday**.
5. `when: today` → **Today**.
6. `when` is a date → future: **Upcoming**; today or past (overdue): **Today**.
7. no `when` (or unparseable) → **Anytime**.

Consequences the model must respect:

- **Moving buckets = setting `when`.** Today → `when: today`; Anytime → clear
  `when`; Someday → `when: someday`; Upcoming → `when: <future ISO date>`.
- **Inbox and Trash are the exceptions**: they *are* folders, and they are
  reached with `gtd_action_update(bucket: "inbox" | "trash")`, not with `when`.
  Moving an action **out** of the trash restores the folder it came from —
  including a `projects/<name>/` membership.
- **Re-filing into a project is the other move** — `gtd_action_update(project:)`
  relocates the file between `actions/` and `projects/<name>/` and leaves `when`
  (and therefore the bucket) alone. These two are independent: an action can be
  in Today *and* in a project.
- A `when`-dated action in the future **automatically** appears in Today on its
  date — you do not need to touch it.
- **`done: true` does not move or hide anything.** A completed action keeps its
  bucket, shown struck through, until `app_rebuild` sweeps every completed
  action into `trash/`. Do not "clean up" by hand after completing something,
  and do not tell the user their item is gone — it is ticked off, in place.

Never tell the user "move it to the Today folder" — that folder does not exist.
