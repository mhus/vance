## Scribble (handwriting sheet)

- **Scribble / handwriting / handwritten notes / pen notes / sketch a page**
  → use the **`kind: scribble`** document: one fixed-size sheet of vector pen
  strokes, written by the user with the pen editor (stylus). Create it with a
  single **`scribble_create(path, title?)`** call — the sheet opens editable
  right away. There are **no stroke tools** (handwriting is user input, not
  LLM output), but if your model has vision you *can look at the ink*:
  **`scribble_sheet_image(path, dpi?, region?)`** renders the sheet and
  attaches it to your next turn. Structure checks:
  `scribble_validate(path)`; titles for content questions.

  For **several sheets grouped** as a notebook use `app: scribblebook`
  (**`scribblebook_app_create(folder, title?)`**). For a **spatial board** use
  `kind: canvas`; for typed rich text use `kind: workpage`. **Before the first
  scribble task in a session** read `manual_read('scribble')`.
