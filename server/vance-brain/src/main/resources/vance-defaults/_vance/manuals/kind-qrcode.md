---
triggers: QR code, QR-Code, qrcode, QR, scan code, scannable link, Scan-Link, share link as code, create QR, generate QR
summary: Render a URL or any short text as a scannable QR code, either inline in chat or as a stored document.
---
# Document kind — `qrcode`

A QR code document encodes a payload — almost always a URL — as a
scannable symbol. The Web-UI renders it client-side in the Cortex
editor, inline in chat, and in embedded document references; a
Download button exports the symbol as PNG.

## Two storage forms — pick by intent

| Did the user ask for a saved file / document? | Use form |
|---|---|
| YES — "save this as a QR code doc", "create a QR for the wiki URL" | **Stored** (below) |
| NO — "show me a QR code for this link", "give me a scannable code" | **Inline** (further below) |

### Inline in chat — fence-wrapped, no tool call

When the user wants to *see* the code right now, **emit a single
```` ```qrcode ```` fence in the chat message**. The fence body IS the
payload:

````
```qrcode
https://example.com/invite
```
````

The reply must CONTAIN this fence verbatim — narrating "Here is your
QR code…" without the fenced block leaves the user with no render.
Optional render options go into the fence language, comma-separated:

````
```qrcode size=512,ecc=high,label=Invite
https://example.com/invite
```
````

### Stored document — `doc_write` with kind `qrcode`

To save the code, write a Markdown document and pass the payload as
the body, the render options as flat front-matter keys:

```markdown
---
kind: qrcode
label: Team invite
size: 512
ecc: high
---
https://example.com/invite
```

`doc_write(kind="qrcode", path="invite.md", content=<the body above>)` —
the `.md` extension sets the markdown mime type; no explicit
`mimeType` needed. YAML and JSON are equivalent storage forms
(`content` key carries the payload) — Markdown is the recommended
one because the payload stays readable in the raw editor.

## Options

| Key | Meaning | Default |
|---|---|---|
| `label` | Caption rendered under the symbol | — |
| `size` | Canvas size in px (64–2048) | `320` |
| `margin` | Quiet zone in modules (0–16) | `4` |
| `ecc` | Error correction: `low`, `medium`, `quartile`, `high` | `medium` |
| `dark` / `light` | Module colours, HTML hex | `#000000` / `#ffffff` |

Unknown keys and malformed values are ignored silently — the render
falls back to the default, never to an error banner.

## When to use this

- "Make a QR code for this link" — inline fence.
- "Save a QR code for the guest wifi in the project" — stored document.
- The payload is a URL, a wifi string, a vCard, a plain text snippet.

## Anti-patterns

- **Huge payloads.** A QR symbol tops out at ~3 kB; long URLs fail
  with a render error. Shorten the link first.
- **`content:` in the Markdown form.** In Markdown the payload is the
  *body*; a `content:` front-matter key is ignored there. The
  `content` key only exists in the YAML/JSON forms.
- **Unquoted front-matter values with `: ` in them.**
  `label: Invite: Team` breaks the whole front matter — quote it:
  `label: "Invite: Team"`.
- **`ecc: high` reflexively.** Higher correction means denser modules;
  a scanned-from-screen or printed-large code renders fine at
  `medium`. Use `high` for small print or partially covered codes.
- **Dark-on-dark styling.** A `light: "#111111"` background with dark
  modules is unscannable by every reader — keep the contrast high.
