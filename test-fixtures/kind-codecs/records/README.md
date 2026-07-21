# records codec — Java ↔ JS parity corpus

A neutral, cross-tree fixture corpus that proves the two `records`
kind-codec implementations agree on the wire format:

- **Java** — `server/vance-shared/.../document/kind/RecordsCodec.java`
- **TS**   — `client/packages/vance-face/src/document/recordsCodec.ts`

Both of these tests read *exactly these files* by relative path:

- `RecordsCodecParityTest.java` (vance-shared) — resolves `../../test-fixtures/kind-codecs/records`
- `recordsCodec.parity.test.ts` (vance-face) — resolves `../../../test-fixtures/kind-codecs/records`

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

## When you edit either codec

Edit the codec **and this corpus together**. Add/adjust fixtures for new
behaviour, re-run both tests, and only commit when both are green (or a
new divergence is deliberately captured via `knownJavaDrift`).
