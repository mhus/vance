# Model document

One file under `_vance/model/<provider>/<slug>.yaml` — a concrete
model of the catalog: identity, limits, capabilities and prices. The
file name is the catalog key; a `:` in the name became the `wireName`
line in the body at creation time.

## Form

- **Identity** — `displayName` (shown name), `wireName` (what the
  vendor calls the model; required when the file name carries no
  structure), `size` (coarse class for model picking; "custom" when
  the standard classes don't fit).
- **Limits** — `contextWindowTokens` and `defaultMaxOutputTokens`
  control how much the brain packs into a window or a reply;
  `timeoutSeconds` bounds a single call.
- **Pricing** — `pricingInputPerMTok` / `pricingOutputPerMTok` (per
  million tokens, in `pricingCurrency`) feed quota accounting. Empty
  prices count as "free / not billed".

**Numbers are numbers:** the form writes numeric fields as YAML
numbers — a quoted string (`"131072"`) would silently fall back to
the default in the catalog.

## Cascade and partial coverage

Project overrides tenant **per field**: clearing a field drops the key
and inherits from the outer layer. Other keys in the YAML stay
untouched — the form only touches what it renders.
