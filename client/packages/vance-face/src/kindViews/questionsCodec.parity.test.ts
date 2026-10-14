// Java ↔ TS parity test for the `questions` kind-codec (TS side), over the
// shared corpus `test-fixtures/kind-codecs/questions/` that
// `QuestionsCodecParityTest.java` reads too. Success fixtures compare the
// canonical JSON; `expectedError` fixtures must be rejected with a message
// containing the given text.
import { describe, it, expect } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseQuestions, serializeQuestions } from './questionsCodec';

interface Fixture {
  description: string;
  mime: string;
  input: string;
  expected?: unknown;
  expectedError?: string;
}

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtureDir = path.resolve(here, '../../../../../test-fixtures/kind-codecs/questions');
const files = fs.readdirSync(fixtureDir).filter((f) => f.endsWith('.json')).sort();

describe('questionsCodec Java↔TS parity corpus', () => {
  it('has fixtures to run', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  for (const file of files) {
    const fixture: Fixture = JSON.parse(fs.readFileSync(path.join(fixtureDir, file), 'utf8'));
    it(`${file} — ${fixture.description}`, () => {
      if (fixture.expectedError) {
        expect(() => parseQuestions(fixture.input, fixture.mime)).toThrow(fixture.expectedError);
        return;
      }
      const actual = JSON.parse(serializeQuestions(parseQuestions(fixture.input, fixture.mime), 'application/json'));
      expect(actual).toEqual(fixture.expected);
    });
  }
});
