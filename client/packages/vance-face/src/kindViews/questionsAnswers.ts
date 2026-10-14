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

/** What the chat knows about the conversation after a bubble that carries a `questions` fence. */
export interface QuestionsChatContext {
  /** A user message follows the bubble. */
  laterUserMessage: boolean;
  /** A later message carries another `questions` fence — the set is superseded. */
  laterQuestions: boolean;
  /** Set ids a user message marked as answered (`meta.questionsAnswered`). */
  answeredSetIds: ReadonlySet<string>;
}

export const NO_QUESTIONS_CONTEXT: QuestionsChatContext = {
  laterUserMessage: false,
  laterQuestions: false,
  answeredSetIds: new Set<string>(),
};

/** The chat bubble tells the wizard where it stands in the conversation. */
export const QUESTIONS_CONTEXT_KEY: InjectionKey<Ref<QuestionsChatContext | undefined>> =
  Symbol('vance:questions-context');

/**
 * Whether the wizard may still send. A newer set supersedes it. A set with
 * an id is open until a user message is marked as its answer — a question
 * back to the engine leaves it open. Without an id any later user message
 * closes it.
 */
export function questionsActionable(ctx: QuestionsChatContext, set: QuestionSetDto): boolean {
  if (ctx.laterQuestions) return false;
  if (set.id) return !ctx.answeredSetIds.has(set.id);
  return !ctx.laterUserMessage;
}

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

/**
 * The message text of the answers. One question (an engine's ASK_USER): just
 * the answer, as if the user had typed it. Several: one line per question —
 * "text → answer". Questions left open are sent as "later".
 */
export function readableAnswers(
  set: QuestionSetDto,
  answers: Record<string, QuestionAnswerDto>,
  labels: OutcomeLabels,
): string {
  const only = set.questions.length === 1 ? set.questions[0] : undefined;
  if (only) {
    return answerLabel(set, answers[only.key] ?? { key: only.key, outcome: QuestionOutcome.LATER }, labels);
  }
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
