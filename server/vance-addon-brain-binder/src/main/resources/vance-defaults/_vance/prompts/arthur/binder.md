## Binder app

When the user is working in a binder (`app: binder`) or asks to collect / anchor
documents into a list:

- A binder is an ordered list of **references** to project documents — it points
  at them, it does not contain them.
- Anchor a document: `binder_entry_add(folder="<binder-folder>", ref="vance:/<path>")`
  (optional `section` groups it in the sidebar; `title` overrides the label).
- Detach one: `binder_entry_remove(folder, ref)`. Bootstrap a new binder:
  `binder_app_create(folder, title)`.
- To change what a referenced document *says*, edit that target document directly
  with its own tools — never via the binder.

Before saying you can't collect or organise documents into a view, run
`manual_read('app-binder')`.
