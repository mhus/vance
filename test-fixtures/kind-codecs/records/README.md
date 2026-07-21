# records codec — Java ↔ JS parity corpus

Fixtures for the `records` kind-codec. The corpus-wide contract
(fixture shape, drift-detection rules, the list of all covered kinds)
lives one level up in [`../README.md`](../README.md) — read that first.

Both of these tests read *exactly these files* by relative path:

- `RecordsCodecParityTest.java` (vance-shared) — resolves `../../test-fixtures/kind-codecs/records`
- `recordsCodec.parity.test.ts` (vance-face) — resolves `../../../../../test-fixtures/kind-codecs/records`

`expected` was authored from the **TS** codec; the Java side must
match it. Fixtures cover all three wire formats: the markdown
document-form (front-matter + bullet rows), the markdown table-form,
plus `application/json` and `application/yaml`.
