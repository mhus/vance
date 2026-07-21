# kind-codec Java ↔ JS parity corpus

A neutral, cross-tree fixture corpus that proves each mirrored
kind-codec has two implementations agreeing on the wire format:

- **Java** — `server/vance-shared/.../document/kind/<Kind>Codec.java`
- **TS**   — `client/packages/vance-face/src/document/<module>Codec.ts`

Every kind here has a directory of `*.json` fixtures read by **both**
sides:

- `<Kind>CodecParityTest.java` (vance-shared) resolves `../../test-fixtures/kind-codecs/<kind>`
- `<module>Codec.parity.test.ts` (vance-face) resolves `../../../../../test-fixtures/kind-codecs/<kind>`

## Covered kinds

| kind | TS codec | Java codec | wire formats exercised |
|------|----------|------------|------------------------|
| records   | `recordsCodec.ts`   | `RecordsCodec`   | markdown (doc-form + table), json, yaml |
| sheet     | `sheetCodec.ts`     | `SheetCodec`     | json, yaml (no markdown) |
| chart     | `chartCodec.ts`     | `ChartCodec`     | json, yaml (no markdown) |
| graph     | `graphCodec.ts`     | `GraphCodec`     | json, yaml (no markdown) |
| tree      | `treeItemsCodec.ts` | `TreeCodec`      | markdown, json, yaml |
| list      | `listItemsCodec.ts` | `ListCodec`      | markdown, json, yaml |
| checklist | `checklistCodec.ts` | `ChecklistCodec` | markdown, json, yaml |
| diagram   | `diagramCodec.ts`   | `DiagramCodec`   | markdown, json, yaml |

The `mime` field of each fixture selects the *input* format. The
harness always **serializes to `application/json`**, so `expected` is
always the codec's canonical JSON — markdown / yaml are only ever
parsed, never serialized, by these tests.

## Fixture shape

```json
{ "description": "...",
  "mime": "text/markdown | application/json | application/yaml",
  "input": "raw document body",
  "expected": { ... canonical JSON object ... },
  "knownJavaDrift": true,          // optional — see below
  "driftNote": "why Java diverges" // optional
}
```

`expected` is the canonical JSON of the codec:
`parse(input, mime)` → `serialize(doc, "application/json")` → `JSON.parse`.
Comparison is done on **parsed JSON structures**, so key order and
whitespace are irrelevant. `expected` was authored from the **TS**
codec — it is the reference the Java side must match.

## Drift detection

This is a **drift-detection harness**, not a rubber stamp. If Java does
not reproduce a fixture's `expected`, that is a real Java↔TS divergence.
Do **not** regenerate `expected` from Java or edit codec logic to force
a pass. Fixtures flagged `knownJavaDrift: true` are skipped by the Java
test (with the reason in `driftNote`) and asserted normally by the TS
test; their existence documents an open divergence for a human to
resolve.

### Currently recorded drifts (skipped on the Java side)

- **records** `03-markdown-table-form` — Java `RecordsCodec` originally
  had no markdown-table parsing; **since resolved** (ported to Java), so
  this fixture is no longer flagged.
- **tree** `02-md-indented-mindmap` — Java `TreeCodec` markdown parse
  supports only the bullet-list form, not the bullet-less indented /
  Mermaid-mindmap form (`root((X))` stripping). TS accepts it, Java
  yields zero items.
- **graph** `02-json-undirected-positions` — `GraphPosition` is a Java
  record of `double x/y`, so integer coordinates serialize as `10.0`;
  the TS codec emits `10`. Whole-number float vs int on disk.
- **chart** `06-yaml-candlestick` — a numeric literal with an explicit
  decimal point (`c: 2.0`) is preserved by Jackson as `2.0` in
  pass-through series data, while the TS/JS codec collapses it to `2`.

## When you edit either codec

Edit the codec **and this corpus together**. Add/adjust fixtures for new
behaviour, re-run both tests, and only commit when both are green (or a
new divergence is deliberately captured via `knownJavaDrift`).
