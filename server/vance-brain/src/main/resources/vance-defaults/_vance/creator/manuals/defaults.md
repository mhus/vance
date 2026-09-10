---
audience: creator
triggers: defaults_list, defaults_read, bundled defaults, vance-defaults, gebündelte konfiguration, canonical recipe, vorlage recipe, provider sidecar vorlage, _vance defaults lesen, wie sieht ein recipe aus, recipe struktur vorlage, manual vorlage, template vorlage
summary: How I read the bundled Vance configuration as a structural reference — defaults_list to enumerate everything under vance-defaults/ (recipes, provider sidecars, manuals, prompts, templates, wizards, setting forms, skills), defaults_read to fetch one as raw text. Canonical shipped defaults only, never tenant overrides.
requires-tools: defaults_list, defaults_read
---
# How I read bundled defaults as a reference

When I need to know how a piece of Vance configuration is **shaped** —
the canonical structure of a recipe, a provider sidecar, a manual, a
template — I read the **bundled defaults**: the classpath resources
under `vance-defaults/` that ship with Vance and keep every tenant
working out of the box.

These tools return **only the classpath layer**. That is the point: the
name "defaults" promises the shipped canonical form, not whatever a
tenant may have overridden. For the *effective* configuration (with
overrides applied) I use the dedicated surfaces instead:

- `recipe_describe` — the effective recipe as Vance resolves it.
- `doc_read` / `doc_list` — documents a tenant or project actually
  stored in the database.
- `manual_read` — manuals through the cascade (includes overrides).

## defaults_list — enumerate the bundled tree

```
defaults_list(path?)  →  { root, prefix, count, entries: [{path, size}] }
```

`path` is a prefix relative to `vance-defaults/`. Empty lists everything.
A bare folder name is treated as a folder prefix. Typical narrowing:

```
defaults_list('_vance/recipes/')          → all 60-odd bundled recipes
defaults_list('_vance/model/openai/')     → OpenAI provider sidecar + model docs
defaults_list('_vance/creator/manuals/')  → this manual and its siblings
```

Each entry's `path` is the input for `defaults_read`.

## defaults_read — fetch one bundled resource

```
defaults_read(path)  →  { path, contentLength, truncated, content }
```

`path` is relative to `vance-defaults/`, exactly as returned by
`defaults_list`. Content over 512 KB is truncated (with a `note`); that
has never been a real limit for any single shipped resource.

```
defaults_read('_vance/recipes/creator.yaml')   → this recipe's canonical YAML
defaults_read('_vance/model/openai/_provider.yaml')  → the OpenAI sidecar shape
```

Paths with `..` or an absolute form are refused — the tool never leaves
the bundled tree.

## When I use which

- "How is a recipe structured?" → `defaults_read('_vance/recipes/<name>.yaml')`.
- "What does a provider sidecar look like?" → `defaults_list('_vance/model/')`
  then `defaults_read` the `_provider.yaml`.
- "What manuals ship with Vance?" → `defaults_list('_vance/manuals/')`.
- "What does the effective recipe resolve to here?" → `recipe_describe`,
  not these tools.

## What is NOT here

No secrets: bundled resources carry metadata, prompts and schemas,
never API keys (those are settings). No mutations: these tools are
read-only — to *write* configuration I use `doc_write` / `doc_edit`
(for project documents) or `setting_set` (for settings).

## Related

- `manual_read('ai-model')` — writing model definitions (the sidecar
  these tools can show as a template).
- `manual_read('settings-tools')` — writing the settings a setup needs.
- Spec: `specification/public/llm-resource-management.md` §Model Catalog
  for how the model tree is loaded and cached.
