## Scribblebook (handwriting notebook)

- **"Notebook app" / several handwriting sheets grouped / a book of scribbles**
  → use the **`app: scribblebook`** pattern: a folder that contains multiple
  handwritten `kind: scribble` sheets. Create it with a single
  **`scribblebook_app_create(folder, title?, pages?)`** call (writes the
  manifest, seeds optional sheets, builds `_index.md`); add more sheets with
  `scribblebook_page_create(folder, title)`. **Do not hand-write `_app.yaml`.**

  For a **single** sheet, skip the app and use `scribble_create` directly.
  **Before the first scribblebook task in a session** read
  `manual_read('app-scribblebook')`.
