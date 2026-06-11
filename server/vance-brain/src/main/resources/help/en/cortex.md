# Cortex

A unified workbench that brings **chat, documents, and execution**
together for one project. Open it from any chat with the *Open in
Cortex* button; the chat keeps its history, the project's file tree
appears on the left, and the same chat now sits in the right panel
beside the document you're working on.

## Layout

- **Left** — file tree, scoped to the chat's project.
- **Middle** — one or more open documents as tabs. The active tab
  renders with the right view for its kind: a list, a checklist, a
  diagram, a script editor, …
- **Right** — two tabs:
  - **Chat** — the agent conversation, same one you came from.
  - **Help** — this panel. Changes content per active document kind.

## Document tabs

Each open document gets a tab. Tabs remember themselves across
Cortex visits — open a chat in Cortex tomorrow and the same tabs
come back. Click *✕* to close one; the auto-save flushes any
unsaved edits first.

The header above each tab shows:

- **⟳** — reload from server (discards local edits, asks first).
- **path/of/file** — full path inside the project.
- **View / Edit** — toggle for typed documents (lists, charts,
  trees, …). *View* shows the rendered form; *Edit* drops to the raw
  source in a CodeEditor.
- **↗** — opens the full document properties page in a new tab
  (title, MIME, tags, RAG mode, archives, …).
- **●** — appears while there are unsaved edits.
- **[binding-id] mime-type** — debug pill showing which renderer
  matched. Hover for the full *(kind, mime)* attribution.

## Chat binding

One document at a time is *chat-bound* — the agent's edit tools
target it. The bound document is shown in the topbar as
*🔗 path/of/file*; click it to bind the current tab. *Open in
Cortex* auto-binds the first document the user opens.

The agent can:

- read the bound document (`cortex_read`),
- ask for the user's current selection (`cortex_get_selection`),
- replace exact strings (`cortex_edit`), append text
  (`cortex_append`), or rewrite the file (`cortex_write`).

All edits stage in the browser; auto-save persists them after a
2-second pause. While a tool is running the topbar shows *agent
editing…*; tools stay registered (no hard lock) but you should
avoid simultaneous keystrokes on the same range.

## Save

Auto-save kicks in two seconds after the last edit. Switching
tabs or closing the page flushes any pending writes
synchronously. The *●* dot disappears once everything is on disk.

## Run (when applicable)

Scripts (`.js` / `.mjs` / `.mjsh`) gain a **▶ Run** button next to
the path. Args is a single-line JSON object (default `{}`). The
log panel slides up under the editor and streams stdout / stderr
live; the *Cancel* button asks the worker to abort. The result
is shown beneath the log once the script finishes.

If you've just typed and hit Run, the editor flushes its buffer
to the server first so the executed code matches what you see.

## Tips

- Multiple tabs: the View/Edit toggle is per-tab and resets to
  *View* on every tab switch.
- The Help tab always reflects the **active** middle tab — flip
  between docs and the help follows.
- The chat survives a tab-switch to Help; switch back and your
  message buffer is still there.
