# QR Code

A scannable QR code rendered from the document's payload — usually a
URL. The code is generated in your browser when the document opens;
nothing is sent to an external service.

## On disk

The body **is** the payload; flat front-matter keys style the render.
Markdown is the recommended form — the payload stays readable in the
raw editor.

```markdown
---
kind: qrcode
label: Team invite
size: 512
ecc: high
---
https://example.com/invite
```

YAML and JSON are equivalent storage forms — there the payload lives
in the `content` key:

```yaml
$meta:
  kind: qrcode
content: https://example.com/invite
label: Team invite
```

A document without front matter works too: the whole body encodes.

## Options

| Key | Meaning | Default |
|---|---|---|
| `label` | Caption under the symbol | — |
| `size` | Canvas size in px (64–2048) | `320` |
| `margin` | Quiet zone in modules (0–16) | `4` |
| `ecc` | Error correction: `low`, `medium`, `quartile`, `high` | `medium` |
| `dark` / `light` | Module colours, HTML hex | `#000000` / `#ffffff` |

Unknown keys and malformed values fall back to their defaults — a QR
code document always renders if the payload fits (QR tops out at
roughly 3 kB).

## In the editor

*View* shows the symbol, the payload text and a **Download PNG**
button. *Edit* is the raw source — write the URL, flip back to see
the code. Read-only by design: everything is derived from the text.
