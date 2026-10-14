// Parser + serialiser for `kind: questions` — several questions to the user
// at once, each answered separately. Twin of `QuestionsCodec.java`
// (vance-shared); the shared corpus `test-fixtures/kind-codecs/questions/`
// pins the agreement (`questionsCodec.parity.test.ts` here,
// `QuestionsCodecParityTest.java` there). Edit codec + corpus together.
//
// Spec: specification/public/doc-kind-questions.md

import {
  QuestionKind,
  QuestionWeight,
  type QuestionDto,
  type QuestionOptionDto,
  type QuestionSetDto,
} from '@vance/generated';
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, wrapJsonMeta } from '@vance/shared';

export const QUESTIONS_KIND = 'questions';

export class QuestionsCodecError extends Error {}

export function parseQuestions(body: string, mimeType?: string | null): QuestionSetDto {
  let root: Record<string, unknown>;
  try {
    root = mimeType === 'application/json' ? parseJson(body) : parseYamlBody(body);
  } catch (e) {
    if (e instanceof QuestionsCodecError) throw e;
    throw new QuestionsCodecError('Invalid body: ' + (e instanceof Error ? e.message : String(e)));
  }
  const list = root.questions;
  if (!Array.isArray(list) || list.length === 0) {
    throw new QuestionsCodecError('questions: at least one question is required');
  }
  const keys = new Set<string>();
  const questions = list.map((raw, i) => {
    const index = i + 1;
    if (!isObject(raw)) throw new QuestionsCodecError(`questions[${index}]: must be a mapping`);
    const q = question(raw, index);
    if (keys.has(q.key)) throw new QuestionsCodecError(`questions[${index}]: duplicate key '${q.key}'`);
    keys.add(q.key);
    return q;
  });
  return {
    id: str(root.id) ?? '',
    round: 0,
    intro: str(root.intro),
    questions,
  };
}

/** Stored-document form — `$meta` header with the kind, then the body. */
export function serializeQuestions(set: QuestionSetDto, mimeType?: string | null): string {
  if (mimeType === 'application/json') {
    return JSON.stringify(wrapJsonMeta(QUESTIONS_KIND, body(set)), null, 2) + '\n';
  }
  return dumpYamlBody(QUESTIONS_KIND, body(set));
}

function body(set: QuestionSetDto): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (set.id) out.id = set.id;
  if (set.intro) out.intro = set.intro;
  out.questions = set.questions.map((q) => {
    const m: Record<string, unknown> = { key: q.key, text: q.text, kind: q.kind.toLowerCase() };
    if (q.options.length > 0) {
      m.options = q.options.map((o) => (o.description ? { label: o.label, description: o.description } : o.label));
    }
    if (q.proposal) m.proposal = q.proposal;
    if (q.context) m.context = q.context;
    if (q.weight !== QuestionWeight.IMPORTANT) m.weight = q.weight.toLowerCase();
    return m;
  });
  return out;
}

function question(q: Record<string, unknown>, index: number): QuestionDto {
  const key = str(q.key);
  const text = str(q.text);
  if (!key) throw new QuestionsCodecError(`questions[${index}]: key is required`);
  if (!text) throw new QuestionsCodecError(`questions[${index}]: text is required`);
  const kind = kindOf(str(q.kind), index);
  const options = optionsOf(q.options, index);
  if (kind === QuestionKind.CHOICE && options.length < 2) {
    throw new QuestionsCodecError(`questions[${index}]: a choice needs at least two options`);
  }
  return {
    key,
    text,
    kind,
    options,
    proposal: str(q.proposal),
    context: str(q.context),
    weight: weightOf(str(q.weight)),
  };
}

function kindOf(raw: string | undefined, index: number): QuestionKind {
  if (!raw) return QuestionKind.TEXT;
  const upper = raw.toUpperCase();
  if (upper in QuestionKind) return QuestionKind[upper as keyof typeof QuestionKind];
  throw new QuestionsCodecError(`questions[${index}]: kind must be choice, text or confirm (got '${raw}')`);
}

function weightOf(raw: string | undefined): QuestionWeight {
  if (!raw) return QuestionWeight.IMPORTANT;
  const upper = raw.toUpperCase();
  return upper in QuestionWeight ? QuestionWeight[upper as keyof typeof QuestionWeight] : QuestionWeight.IMPORTANT;
}

function optionsOf(raw: unknown, index: number): QuestionOptionDto[] {
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw)) throw new QuestionsCodecError(`questions[${index}]: options must be a list`);
  const out: QuestionOptionDto[] = [];
  for (const o of raw) {
    if (isObject(o)) {
      const label = str(o.label);
      if (label) out.push({ label, description: str(o.description) });
    } else if (o !== null && o !== undefined && String(o).trim() !== '') {
      out.push({ label: String(o).trim() });
    }
  }
  return out;
}

function parseJson(body: string): Record<string, unknown> {
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new QuestionsCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)));
  }
  if (!isObject(parsed)) throw new QuestionsCodecError('Top-level JSON must be an object');
  return unwrapJsonMeta(parsed);
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function str(v: unknown): string | undefined {
  if (v === null || v === undefined) return undefined;
  const s = String(v).trim();
  return s === '' ? undefined : s;
}
