# Provider sidecar

The `_provider.yaml` under `_vance/model/<provider>/` — the contact
record of a model vendor. It tells the brain **which protocol and which
endpoint** to use for this vendor's models; the individual models live
next to it as their own documents.

## Required fields

- **`wireType`** — the vendor's wire protocol (e.g. `openai`,
  `anthropic`, `google`, `ollama`, `cohere`, …). **Hard requirement**:
  if it is missing the catalog refuses every model of this provider —
  saving blocks with an error instead of silently swallowing it.
- **`baseUrl`** — the vendor's API endpoint.

## Credentials

API keys do **not** belong in this file — put them in the setting
forms (llm-provider-*) or the vault (`{{secret:vault:…}}`). A provider
sidecar with a plaintext key would not just sit in Git — it would sit
in plaintext in the brain.

## Cascade

Project overrides tenant: a local `_provider.yaml` replaces the
tenant's per field — empty fields inherit from the outer layer.
