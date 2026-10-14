import { describe, expect, it } from 'vitest';
import { QuestionOutcome } from '@vance/generated';
import { parseQuestions } from './questionsCodec';
import { readableAnswers, setIdFor, toSetAnswer, type OutcomeLabels } from './questionsAnswers';

const BODY = `questions:
  - key: region
    text: Which region?
    kind: choice
    options: [eu-central-1, eu-west-1]
  - key: retention
    text: 10 years?
    kind: confirm
    proposal: 10 years
  - key: owner
    text: Who owns it?
`;

const LABELS: OutcomeLabels = {
  settled: 'good as it is',
  later: 'later',
  unknown: "don't know",
  confirmed: 'confirmed',
  open: '—',
};

describe('questionsAnswers', () => {
  const set = parseQuestions(BODY);

  it('derives a stable id for a set without one', () => {
    expect(setIdFor(set, BODY)).toBe(setIdFor(parseQuestions(BODY), BODY));
    expect(setIdFor({ ...set, id: 'q-1' }, BODY)).toBe('q-1');
  });

  it('sends a question left open as later — partial answers are valid', () => {
    const answers = {
      region: { key: 'region', outcome: QuestionOutcome.ANSWERED, value: 'eu-central-1' },
      retention: { key: 'retention', outcome: QuestionOutcome.SETTLED },
    };

    const setAnswer = toSetAnswer('q-1', set, answers);

    expect(setAnswer.setId).toBe('q-1');
    expect(setAnswer.answers.map((a) => a.outcome)).toEqual([
      QuestionOutcome.ANSWERED,
      QuestionOutcome.SETTLED,
      QuestionOutcome.LATER,
    ]);
  });

  it('renders the readable short form one line per question', () => {
    const text = readableAnswers(set, {
      region: { key: 'region', outcome: QuestionOutcome.ANSWERED, value: 'eu-central-1' },
      retention: { key: 'retention', outcome: QuestionOutcome.ANSWERED },
    }, LABELS);

    expect(text).toBe('- Which region? → eu-central-1\n- 10 years? → confirmed\n- Who owns it? → later');
  });
});
