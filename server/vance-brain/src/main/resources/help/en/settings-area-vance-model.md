# Area: AI models

The model catalog under `_vance/model/` — one file per model plus a
`*_provider.yaml` sidecar per provider. This is where you configure
**which models the brain knows and how it talks to them**: context and
output limits, capabilities (vision, PDF, large), timeouts and prices
for quota accounting.

## Names carry structure

The add dialog takes the structure in the name:

- **`provider`** (no slash) — creates the provider sidecar
  `_provider.yaml`: how the brain talks to the vendor.
- **`provider/model`** — creates the model document. A `:` in the name
  becomes the `wireName` field, `/` nests directories.

Credentials and the provider type live in the **setting forms**
(llm-provider-*), not here — this area holds model properties.

## Cascade

Project overrides tenant, field by field: an **empty field falls back
to the outer value**, it does not inherit "inward". Unknown keys
survive each round trip — the form never hides anything.

Note: `_vance/model-auto/` is **discovery-owned** and deliberately not
listed here — model discovery writes there, never by hand.
