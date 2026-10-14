// The chat side of a `questions` fence: the provide/inject key a chat bubble
// uses to tell the wizard whether it may still send, the DOM event the wizard
// dispatches with its answers, and the readable short form that travels as the
// message text. Spec: specification/public/doc-kind-questions.md §4.
import type { InjectionKey, Ref } from 'vue';
import {
  QuestionKind,
  QuestionOutcome,
  type QuestionAnswerDto,
  type QuestionSetAnswerDto,
  type QuestionSetDto,
} from '@vance/generated';

/** `true` while the question set is the conversation's open end (no later user message). */
export const QUESTIONS_ACTIONABLE_KEY: InjectionKey<Ref<boolean>> = Symbol('vance:questions-actionable');

/** Bubbling DOM event the wizard dispatches; the chat view turns it into a process-steer. */
export const QUESTION_ANSWERS_EVENT = 'vance-question-answers';

export interface QuestionAnswersDetail {
  answers: QuestionSetAnswerDto;
  /** Readable short form — the message text every engine understands. */
  content: string;
}

/** Labels for the standard exits, passed in so the text follows the UI language. */
export interface OutcomeLabels {
  settled: string;
  later: string;
  unknown: string;
  confirmed: string;
  open: string;
}

/**
 * Stable id for a set that has none: the same fence yields the same id on
 * every render, so an answer still matches after a reload.
 */
export function setIdFor(set: QuestionSetDto, fenceBody: string): string {
  if (set.id) return set.id;
  let h = 5381;
  for (let i = 0; i < fenceBody.length; i++) h = ((h << 5) + h + fenceBody.charCodeAt(i)) | 0;
  return 'q-' + (h >>> 0).toString(36);
}

export function answerLabel(
  set: QuestionSetDto,
  answer: QuestionAnswerDto | undefined,
  labels: OutcomeLabels,
): string {
  if (!answer) return labels.open;
  switch (answer.outcome) {
    case QuestionOutcome.SETTLED:
      return labels.settled;
    case QuestionOutcome.LATER:
      return labels.later;
    case QuestionOutcome.UNKNOWN:
      return labels.unknown;
    default: {
      const q = set.questions.find((x) => x.key === answer.key);
      if (q?.kind === QuestionKind.CONFIRM && !answer.value) return labels.confirmed;
      return answer.value ?? labels.confirmed;
    }
  }
}

/** One line per question — "text → answer". Questions left open are sent as "later". */
export function readableAnswers(
  set: QuestionSetDto,
  answers: Record<string, QuestionAnswerDto>,
  labels: OutcomeLabels,
): string {
  return set.questions
    .map((q) => `- ${q.text} → ${answerLabel(set, answers[q.key] ?? { key: q.key, outcome: QuestionOutcome.LATER }, labels)}`)
    .join('\n');
}

export function toSetAnswer(
  setId: string,
  set: QuestionSetDto,
  answers: Record<string, QuestionAnswerDto>,
): QuestionSetAnswerDto {
  return {
    setId,
    answers: set.questions.map((q) => answers[q.key] ?? { key: q.key, outcome: QuestionOutcome.LATER }),
  };
}
