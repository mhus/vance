# Records

A flat table: one schema declared once, then one record per row with a
value per field. The list with named columns
instead of a single text.

Use it for anything uniform and tabular — people, inventory, lookup
tables, structured notes with the same shape per entry. For free 2D
cells with addresses and formulas use a sheet;
for one text per entry, a list.

Deliberately flat: no nested records. The schema holds for every row,
and sub-records would break that promise.

## On disk

The schema is a top-level field; in markdown the record values are
positional, in YAML and JSON they are named.

```markdown
---
kind: records
schema: name, email, role
---
- Alice, alice@example.com, admin
- Bob, bob@example.com, user
```

```yaml
$meta:
  kind: records
schema: [name, email, role]
items:
  - name: Alice
    email: alice@example.com
    role: admin
```

## Resilience

The format forgives incomplete data on purpose — a half-filled table
is a normal working state, not an error:

- Fewer values than fields → the missing ones read as empty.
- More values than the schema has fields → kept, and flagged as a
  warning by the validation rather than dropped.
- Unknown named fields in YAML/JSON → passed through untouched.

Values are strings in v1. There is no type system, no required-field
validation, no per-field indexing.

## In the editor

*View* is a table: edit cells inline, add and delete rows, reorder by
dragging. Changing the schema itself (renaming or adding a column)
happens in the *Edit* tab on the `schema` line.
