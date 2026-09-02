/**
 * Translating a document, or the reader's selection inside it.
 *
 * One single-shot call to `POST /brain/{tenant}/light-llm/{project}` with the
 * bundled `translate` recipe — no process is spawned, nothing is queued. The
 * recipe carries the model, the temperature and every rule about what must
 * survive translation (structure, code, links, front-matter keys); this module
 * carries the parts a prompt cannot decide: which documents may be offered,
 * what the result file is called, and when the answer is not to be trusted.
 *
 * <p><b>Two gates, not one</b>, because the two modes risk different things.
 * Translating a whole document *writes a file*, so it is offered for prose
 * only ({@link isTranslatableDocument}). Translating a selection writes
 * nothing — the result lands in a dialog — so it is offered wherever text can
 * be marked at all ({@link canTranslateSelection}), including YAML, JSON and
 * source code.
 *
 * <p><b>The limit worth knowing about.</b> One call translates as much as the
 * model is willing to emit, and a model that stops early stops silently — the
 * reply simply ends, and a document written from it looks complete. Two
 * defences, neither of them a fix: {@link TRANSLATE_MAX_CHARS} refuses input
 * that is unlikely to come back whole, and {@link looksTruncated} flags a
 * result far shorter than its source. Chunking a document into sections and
 * reassembling it is the real answer and is not implemented.
 */

import { brainFetch } from '@vance/shared';
import { isBinaryDoc } from './stores/cortexStore';

/**
 * Longest source text accepted for one call, in characters.
 *
 * Chosen against output-token ceilings rather than context windows: the input
 * is rarely the binding constraint, the reply is. Roughly 5–6k tokens of
 * output for European languages, which every model behind `default:analyze`
 * can emit in one go. Above it the call is refused with a message that says
 * so, because a truncated document is worse than no document.
 */
export const TRANSLATE_MAX_CHARS = 20000;

/**
 * A result shorter than this fraction of its source is reported as possibly
 * incomplete. Translations do change length — German runs longer than
 * English, Chinese much shorter — so this sits low enough to leave normal
 * variation alone and only catch a reply that stopped.
 */
const TRUNCATION_RATIO = 0.55;

/** Below this length the ratio says nothing useful, so it is not applied. */
const TRUNCATION_MIN_SOURCE = 400;

/** MIME types whose body is prose we can hand to a translator. */
const PROSE_MIMES = [
  'text/markdown',
  'text/x-markdown',
  'text/plain',
];

/** Extensions consulted only when the server reports no MIME type. */
const PROSE_EXTS = ['.md', '.markdown', '.txt', '.text'];

/**
 * Document kinds that are still prose. Empty is the ordinary case — a plain
 * Markdown file has no kind at all.
 *
 * <p>Everything else is excluded on purpose: a `list`, `records` or `sheet`
 * document is YAML in a fence, a `workpage` is a block structure, and running
 * either through a translator would translate the keys along with the values
 * and leave a file its own codec can no longer read.
 */
const PROSE_KINDS = ['', 'text', 'markdown'];

/** The languages offered in the dialog, plus a free-text escape beside them. */
export const TRANSLATE_LANGUAGES: readonly string[] = [
  'en', 'de', 'fr', 'es', 'it', 'pt', 'nl', 'pl', 'cs', 'sv', 'da', 'fi',
  'tr', 'ru', 'uk', 'ar', 'zh', 'ja', 'ko',
];

/**
 * Whether the **whole document** may be translated into a new one.
 *
 * Narrow on purpose, and narrower than "is this text": a `.yaml` config, a
 * `.py` script and a `list` document are all text, and none of them survives
 * being rewritten in another language — the result would be a file its own
 * parser, interpreter or codec can no longer read.
 */
export function isTranslatableDocument(doc: {
  mimeType?: string | null;
  path: string;
  kind?: string | null;
}): boolean {
  const kind = (doc.kind ?? '').trim().toLowerCase();
  if (!PROSE_KINDS.includes(kind)) return false;
  const mime = (doc.mimeType ?? '').trim().toLowerCase();
  if (mime) return PROSE_MIMES.some((m) => mime === m || mime.startsWith(`${m};`));
  const path = doc.path.toLowerCase();
  return PROSE_EXTS.some((ext) => path.endsWith(ext));
}

/**
 * Whether a **selection** inside this document may be translated.
 *
 * Wider, and for a reason that is not laxness: translating a selection writes
 * nothing. The result goes into a dialog with a copy button, so the question
 * is only whether there is text to mark — a comment in a `.py` file, a
 * `description:` in a YAML config, a string literal in JSON. The narrow rule
 * above exists to protect a *file* being rewritten; there is no file here to
 * protect.
 *
 * <p>The gate is therefore the same one that decides whether the code editor
 * shows the document at all: anything not binary. A document with no text
 * surface (a rendered table, a canvas) simply never produces a selection, so
 * the entry stays disabled without needing to be excluded by name.
 */
export function canTranslateSelection(doc: {
  mimeType?: string | null;
  path: string;
}): boolean {
  return !isBinaryDoc(doc);
}

/**
 * The file name to propose for the translation: the source name with the
 * language pushed in front of the extension — `manual.md` + `de` →
 * `manual.de.md`.
 *
 * <p>Always derived from the *source* name, never from the current field
 * value, so switching the language twice cannot produce `manual.de.en.md`.
 * A name without an extension just gets the suffix appended.
 */
export function suggestTranslatedName(sourceName: string, languageCode: string): string {
  const code = languageCode.trim().toLowerCase().replace(/[^a-z0-9-]/g, '');
  if (!code) return sourceName;
  const dot = sourceName.lastIndexOf('.');
  // A leading dot is the whole name of a dotfile, not an extension.
  if (dot <= 0) return `${sourceName}.${code}`;
  return `${sourceName.slice(0, dot)}.${code}${sourceName.slice(dot)}`;
}

/**
 * A human-readable name for a language tag, in the reader's own locale.
 * Falls back to the tag itself where `Intl.DisplayNames` has no entry.
 */
export function languageLabel(code: string, locale: string): string {
  try {
    return new Intl.DisplayNames([locale], { type: 'language' }).of(code) ?? code;
  } catch {
    return code;
  }
}

/**
 * What the recipe is told to translate into. The English name where we know
 * it, so the instruction reads as a language rather than as a code — with the
 * tag kept beside it for the regional variants where the name alone is
 * ambiguous.
 */
export function languageInstruction(code: string): string {
  const english = languageLabel(code, 'en');
  return english === code ? code : `${english} (${code})`;
}

/**
 * Whether a result should be treated as possibly cut short. Advisory: the
 * caller warns, it does not discard — a short answer may simply be a short
 * translation, and throwing away a good one is the worse mistake.
 */
export function looksTruncated(source: string, result: string): boolean {
  if (source.length < TRUNCATION_MIN_SOURCE) return false;
  return result.length < source.length * TRUNCATION_RATIO;
}

/**
 * Drop a code fence that wraps the *entire* reply.
 *
 * Models like to hand back a Markdown document inside ```markdown … ```, and
 * written to a file that fence is part of the document. Only removed when the
 * whole reply is one fence — a document that legitimately contains fences has
 * more than two fence lines and is left alone.
 */
export function stripWrappingFence(text: string): string {
  const trimmed = text.trim();
  const lines = trimmed.split('\n');
  if (lines.length < 2) return text;
  const fences = lines.filter((line) => /^\s*```/.test(line)).length;
  if (fences !== 2) return text;
  if (!/^\s*```/.test(lines[0]) || !/^\s*```\s*$/.test(lines[lines.length - 1])) return text;
  return lines.slice(1, -1).join('\n');
}

export interface TranslateRequest {
  projectId: string;
  /** Target language: a tag from {@link TRANSLATE_LANGUAGES} or free text. */
  language: string;
  /** The text to translate — a whole document body or a selection. */
  text: string;
  /** Source file name, passed to the recipe as a format hint. Optional. */
  sourceName?: string | null;
}

interface LightLlmCallResponse {
  recipe: string;
  text: string;
}

/**
 * Run one translation. Throws when the input is over the ceiling — before the
 * call, so a refusal never costs tokens — and propagates whatever the brain
 * says otherwise (a missing or un-released recipe is a 404/403 with a message
 * naming the fix).
 */
export async function translate(req: TranslateRequest): Promise<string> {
  const text = req.text;
  if (!text.trim()) throw new Error('Nothing to translate.');
  if (text.length > TRANSLATE_MAX_CHARS) {
    throw new Error(
      `Too long for one translation: ${text.length} characters, limit ${TRANSLATE_MAX_CHARS}.`,
    );
  }
  const response = await brainFetch<LightLlmCallResponse>(
    'POST',
    `light-llm/${encodeURIComponent(req.projectId)}`,
    {
      body: {
        recipe: 'translate',
        prompt: text,
        vars: {
          language: req.language,
          sourceName: req.sourceName ?? null,
        },
      },
    },
  );
  return stripWrappingFence(response.text ?? '');
}
