// Java ↔ TS parity test for the `checklist` kind-codec (TS side).
//
// Reads the SHARED fixture corpus at
// `<repo>/test-fixtures/kind-codecs/checklist/*.json` — the very same
// files consumed by `ChecklistCodecParityTest.java` (vance-shared). For
// each fixture it asserts that
//   JSON.parse(serializeChecklist(parseChecklist(input, mime), "application/json"))
// deep-equals the fixture's `expected`. Since `expected` was authored
// from this codec, the TS side must pass every fixture — a failure here
// means the TS codec changed without the corpus being updated.
//
// See the corpus README for the drift-detection contract. Sibling test:
// server/vance-shared/.../document/kind/ChecklistCodecParityTest.java.
import { describe, it, expect } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseChecklist, serializeChecklist } from './checklistCodec';

interface Fixture {
  description: string;
  mime: string;
  input: string;
  expected: unknown;
  knownJavaDrift?: boolean;
  driftNote?: string;
}

// From client/packages/vance-face/src/document the repo root is ../../../../..
const here = path.dirname(fileURLToPath(import.meta.url));
const fixtureDir = path.resolve(here, '../../../../../test-fixtures/kind-codecs/checklist');

const files = fs
  .readdirSync(fixtureDir)
  .filter((f) => f.endsWith('.json'))
  .sort();

describe('checklistCodec Java↔TS parity corpus', () => {
  it('has fixtures to run', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  for (const file of files) {
    const fixture: Fixture = JSON.parse(
      fs.readFileSync(path.join(fixtureDir, file), 'utf8'),
    );
    it(`${file} — ${fixture.description}`, () => {
      const actual = JSON.parse(
        serializeChecklist(
          parseChecklist(fixture.input, fixture.mime),
          'application/json',
        ),
      );
      expect(actual).toEqual(fixture.expected);
    });
  }
});
