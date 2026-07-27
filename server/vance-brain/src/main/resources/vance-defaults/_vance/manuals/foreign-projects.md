---
triggers: another project, other project, look into project, copy from project, foreign_doc, foreign_project, cross-project, aus projekt X, in projekt X schauen, ergebnisse kopieren, from project Y, reuse from another project
summary: Read documents of ANOTHER project in the same tenant and copy findings into the current one, via the read-only foreign_* tools. Cross-project READ is allowed; writing stays in the current project.
---
# Foreign Projects — reading & copying across the project boundary

Each project is isolated by default. But the user often points at
another project in the same tenant:

- "Look at project X — we solved this there, I want the same here."
- "Copy the results of the search run from project Y into this one."

You **can** do this. Cross-project access is read-only by design and
gated per target: you may read any project the user has access to,
and copy findings **into the current project**.

## When to consult

The user references a *different* project by name, or asks to reuse /
copy something that lives in another project. Never answer "I can't
reach another project" before calling `foreign_project_list` — you
almost certainly can.

## The tools

| Tool | Use |
|---|---|
| `foreign_project_list(query?)` | Find the project. `query` filters by name/title; omit to list all you can read. |
| `foreign_doc_list(projectId, folder?)` | Browse a project's folders/files at one level. |
| `foreign_doc_search(projectId, query)` | Find documents in that project by title/summary/tags. |
| `foreign_doc_read(projectId, path)` | Read one document's content. |
| `foreign_doc_copy(fromProjectId, fromPath, toProjectId?, toPath?)` | Copy a document out of that project. Omit `toProjectId` → lands in the current project. |

Typical flow: `foreign_project_list` → `foreign_doc_search` (or
`_list`) → `foreign_doc_read` to inspect → `foreign_doc_copy` to pull
it in.

## Boundaries

- **Same tenant only.** You cannot reach another tenant.
- **Read-only outward.** You can read a foreign project and copy
  *from* it; you cannot write *into* it. A copy always lands in your
  current project (or an explicit `toProjectId` you have write access
  to).
- **Access is checked per project.** You only see and read projects
  the user is allowed to. A denied read comes back as an error — relay
  it plainly, don't retry blindly.
- **System namespaces are hidden.** Reserved `_…` paths (recipes,
  settings, trash) and SYSTEM projects are not reachable here — that
  is deliberate.
- **Copies are independent.** A copied document is a fresh document in
  the destination; it is not linked back to the source.
