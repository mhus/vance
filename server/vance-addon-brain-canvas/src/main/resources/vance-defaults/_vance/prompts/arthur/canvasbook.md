## Canvasbook (canvas app)

- **"Canvas app" / whiteboard app / several canvases grouped / a board of
  canvases** → use the **`app: canvasbook`** pattern: a folder that
  contains multiple spatial `kind: canvas` boards. Create it with a
  single **`canvasbook_app_create(folder, title?, pages?)`** call (writes
  the manifest, seeds optional boards, builds `_index.md`); add more
  boards with `canvasbook_page_create(folder, title)`. Fill each board
  with `canvas_node_add` / `canvas_edge_add`. Do **not** hand-write
  `_app.yaml`.

  For a **single** board, skip the app and use `canvas_create` directly.
  **Before the first canvasbook task in a session** read
  `manual_read('app-canvasbook')` (and `manual_read('canvas')` for the
  node/edge grammar).
