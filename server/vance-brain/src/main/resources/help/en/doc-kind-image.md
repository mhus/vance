# Image

A binary image document — PNG, JPEG, SVG, GIF, WebP. Unlike the typed
text kinds there is no body format to learn: the file *is* the
content, and the viewer just shows it.

Images arrive by upload (*New* → the upload tab, or drag onto the file
tree), from an agent that generated one, or from an import.

## What you can do here

- **View** at fit-to-width or full size.
- **Properties** (the ↗ button in the tab header) for title, tags, MIME
  type and the archive history.
- **Download** the original bytes.

There is no in-place editing. Replacing an image means uploading the
new version over the same path — the previous one stays retrievable
through the document's versions.

## Using an image in other documents

Reference it by document path rather than by an absolute URL, so the
link keeps working across environments:

```markdown
![Alt text](vance:/images/diagram.png)
```

In a workpage the same reference gets a width
preset through the alt text:

```markdown
![Alt text|medium](vance:/images/diagram.png)
```

## Naming

The file tree sorts alphabetically, and image names are what you will
scan later — `2026-08-invoice-flow.png` beats `Screenshot 2026-08-13 at
11.42.07.png`. Renaming happens in the Properties page.
