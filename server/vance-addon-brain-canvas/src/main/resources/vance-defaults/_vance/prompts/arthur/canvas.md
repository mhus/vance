## Canvas (spatial board)

- **Canvas / whiteboard / freeform board / "boxes and arrows" / spatial
  notes / moodboard / "arrange visually" / concept map** → use the
  **`kind: canvas`** document: a 2D surface of nodes (`text`, `doc`,
  `link`, `group`) placed at free `x/y` and connected by directed
  **arrows**. Create it with a single **`canvas_create(path, title?)`**
  call, then add content with `canvas_node_add(path, node)` and connect
  nodes with `canvas_edge_add(path, from, to, label?)`. Node ids are
  minted server-side and returned — use them for edges. Move/resize via
  `canvas_node_update`; read the board with `canvas_query`. A `doc` node
  references an existing document/image by `vance:`-URI (don't inline it).

  Canvas is for **spatial arrangement**; for a linear rich-text page use
  `kind: workpage`, for workflow states use `app: kanban`. **Before the
  first canvas task in a session** read `manual_read('canvas')` for the
  node/edge grammar. Never tell the user canvases aren't supported —
  they are, via the tools above.
