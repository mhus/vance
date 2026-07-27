// Java ↔ TS parity test for the `tree` kind-codec (TS side).
//
// Reads the SHARED fixture corpus at
// `<repo>/test-fixtures/kind-codecs/tree/*.json` — the very same
// files consumed by `TreeCodecParityTest.java` (vance-shared). For
// each fixture it asserts that
//   JSON.parse(serializeTree(parseTree(input, mime), "application/json"))
// deep-equals the fixture's `expected`. Since `expected` was authored
// from this codec, the TS side must pass every fixture — a failure here
// means the TS codec changed without the corpus being updated.
//
// See the corpus README for the drift-detection contract. Sibling test:
// server/vance-shared/.../document/kind/TreeCodecParityTest.java.
import { describe, it, expect } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseTree, serializeTree } from './treeItemsCodec';

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
const fixtureDir = path.resolve(here, '../../../../../test-fixtures/kind-codecs/tree');

const files = fs
  .readdirSync(fixtureDir)
  .filter((f) => f.endsWith('.json'))
  .sort();

describe('treeItemsCodec Java↔TS parity corpus', () => {
  it('has fixtures to run', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  for (const file of files) {
    const fixture: Fixture = JSON.parse(
      fs.readFileSync(path.join(fixtureDir, file), 'utf8'),
    );
    it(`${file} — ${fixture.description}`, () => {
      const actual = JSON.parse(
        serializeTree(
          parseTree(fixture.input, fixture.mime),
          'application/json',
        ),
      );
      expect(actual).toEqual(fixture.expected);
    });
  }
});
