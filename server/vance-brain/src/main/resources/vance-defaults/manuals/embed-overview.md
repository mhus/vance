# Embedding — Overview

Routing index for showing visual / structured content to the user.
Pick the right sub-manual; do **not** answer "I can't show that"
without first reading one of these.

## What the Vance UI actually renders

The Web-UI parses your reply as Markdown and goes beyond text:

- Standard Markdown image syntax `![alt](url)` renders external
  `https://` images directly as `<img>` (e.g. results from
  `web_search`). No tool call needed for this.
- Fenced code blocks with a Vance kind tag (`mindmap`, `chart`,
  `youtube`, `tree`, `list`, `items`, `records`) render as
  interactive canvases.
- Markdown links / image-links with a `vance:` URI render the
  referenced project Document inline (image, PDF, audio, video,
  mindmap, …).
- The Foot CLI shows raw Markdown — your output stays readable
  in plain text either way.

## Which sub-manual to read

| User wants … | Read |
|---|---|
| To see a picture / photo / screenshot — external URL, project image, or `image_search` result | `manual_read('embed-images')` |
| A mindmap, chart, YouTube video, tree, table, list, records — anything you generate **right now** in chat | `manual_read('embed-fences')` |
| A slide deck / presentation — multi-slide artefact that lives as a Document | `manual_read('embed-documents')` |
| To open / reference an existing project Document (PDF, audio, big image, generated artifact) | `manual_read('embed-documents')` |
| A rounded mixed view of a topic, or unsure which search tool fits | `manual_read('search-tools')` |

## Hard rule — never claim it's impossible

If you catch yourself about to write *"I cannot show / display /
embed X"*, **stop**. Read the relevant manual above. The UI almost
certainly renders X. The Lisbon failure (2026-05-26) was Arthur
refusing to embed Pixabay image URLs that would have rendered
without any tool call. Don't repeat it.
