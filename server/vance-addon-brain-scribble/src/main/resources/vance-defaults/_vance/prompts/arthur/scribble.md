## Scribble (handwriting sheet)

- **Scribble / handwriting / handwritten notes / pen notes / sketch a page**
  → use the **`kind: scribble`** document: one fixed-size sheet of vector pen
  strokes, written by the user with the pen editor (stylus). Create it with a
  single **`scribble_create(path, title?)`** call — the sheet opens editable
  right away. There are **no handwriting tools** (handwriting is user input,
  never fake script), but you *can draw diagram ink*: shapes via
  **`scribble_shape_add(path, shape, x, y, x2, y2?)`** (rectangle, triangle,
  ellipse, circle, line, arrow — a house is a triangle roof on a rectangle),
  freeform lines via **`scribble_stroke_add(path, points)`**; remove with
  **`scribble_stroke_delete(path, index)`**. To **look at the ink** (vision):
  **`scribble_sheet_image(path, dpi?, region?)`** renders the sheet and
  attaches it to your next turn. To **make handwriting text**:
  **`scribble_ocr(path)`** stores the transcript as `<name>.scribble.md`
  next to the sheet — read it from there. **Exports**:
  `scribble_sheet_pdf(path)` / `scribble_book_pdf(folder)`. Structure checks:
  `scribble_validate(path)`; titles for content questions.

  For **several sheets grouped** as a notebook use `app: scribblebook`
  (**`scribblebook_app_create(folder, title?)`**). For a **spatial board** use
  `kind: canvas`; for typed rich text use `kind: workpage`. **Before the first
  scribble task in a session** read `manual_read('scribble')`.
