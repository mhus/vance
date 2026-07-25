---
triggers: "import URL, doc_import_url, save a web page, keep a page, save this article, save this URL as a document, download and keep, archive a page, URL to document, save PDF from URL, import article, persist a webpage, Webseite speichern, Artikel speichern als Dokument, Seite als Dokument behalten, URL importieren"
summary: How to save the content at a URL as a persistent project Document (doc_import_url) — as opposed to reading a page once (web_fetch).
---
# Importing a URL as a Document

When the user wants to **keep** the content at a URL — an article, a
doc page, a PDF, an image — as a project Document they can find and
refer to later, use `doc_import_url`. It fetches the URL and persists
the body as a Document under `documents/`.

## When to use this — vs. `web_fetch`

- **Keep / refer to the page** → `doc_import_url`. Persists the content
  as a project Document: it survives the turn, is findable via
  `doc_find`, and can be embedded in chat via a `vance:`-URI link.
- **Read the page once** (extract a fact, summarise, quote it now) →
  use `web_fetch` instead. It returns the body inline and persists
  nothing.

Symptoms that say "use `doc_import_url`":

- "save this article / page / PDF so I can find it later"
- "import `<url>` into the project"
- "keep a copy of this page"
- the user will want to reference the imported artifact afterwards.

## How to call it

    doc_import_url(url="https://…", path?, title?, tags?, summary?, ifExists?)

- `url` (required) — absolute `http(s)://` URL to fetch and import.
- `path` — optional target path, e.g. `documents/imports/apollo-13.html`.
  Omit → auto-generated under `documents/` from the URL's last segment
  (or a title slug). Must be unique per project.
- `title` — optional human title (defaults to the URL).
- `tags` — optional; `imported` is added automatically.
- `summary` — optional caption stored on the document; useful for
  binary content (image, PDF) where the auto-summary scheduler
  doesn't run.
- `ifExists` — `reuse` (default: idempotent, returns the existing doc
  without re-fetching), `update` (re-fetch and replace the body), or
  `error`.

Returns `{ path, title, tags, … }` — a real project Document path.

## After importing

To surface the imported Document to the user in chat, reference it with
a `vance:`-URI Markdown link → `manual_read('embed-documents')`.

## Do NOT

- Don't use `doc_import_url` for a one-shot read — that's `web_fetch`.
- Don't pipe a `web_fetch` result into `doc_create` by hand when the
  user wants the original page kept — import it directly so the source
  URL, mime-type and idempotency are handled for you.
