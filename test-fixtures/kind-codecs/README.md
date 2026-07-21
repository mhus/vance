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

### Currently recorded drifts

**None** — the harness is fully green on both sides. The drifts the rollout
originally surfaced have all been resolved:

- **records** `03-markdown-table-form` and **tree** `02-md-indented-mindmap`
  were structural gaps (a Markdown form the TS editor/LLM accepts that Java
  couldn't parse). **Resolved** by porting the parser to Java
  (`RecordsCodec` markdown-table, `TreeCodec` bullet-less indented /
  `root((X))`).
- **graph** `02` and **chart** `06` were cosmetic number-representation
  differences (Java `double` → `10.0` vs JS `10`, both the same number).
  **Resolved** by comparing numbers *by value* in the harness
  (`ParityJson.equivalent`: `10.0 ≡ 10`, but `"10" ≠ 10`), so an int-vs-double
  of the same value is not a drift while a type change still is.

## When you edit either codec

Edit the codec **and this corpus together**. Add/adjust fixtures for new
behaviour, re-run both tests, and only commit when both are green (or a
new divergence is deliberately captured via `knownJavaDrift`).
