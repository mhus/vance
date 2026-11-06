/**
 * Which fields of a state the inspector gives a form control, per task type.
 * Everything else a state may carry stays reachable as a YAML fragment, so the
 * form never has to know every field the engine ever learns.
 *
 * Spec: {@code specification/public/workflows.md} §2.2, §3.
 */

export type FieldKind =
  /** One line of text. */
  | 'string'
  /** Several lines — a prompt, a command, a body. */
  | 'text'
  /** A whole number of at least 0. */
  | 'integer'
  /** One of {@link FieldSpec.options}. */
  | 'select'
  /** A list of words, typed comma-separated. */
  | 'list';

export interface FieldSpec {
  /** Names the label and help text in the message catalog. */
  key: string;
  /** Where the value sits inside the state's body. */
  path: string[];
  kind: FieldKind;
  options?: readonly string[];
  /** The engine refuses the state without it; the label says so. */
  required?: boolean;
  /** Shown only while another field of the state has this value. */
  onlyWhen?: { path: string[]; equals: string };
}

/** Top-level keys the inspector shows in its own places, never in the YAML fragment. */
const STRUCTURAL = ['type', 'description', 'on', 'catch', 'transitions'] as const;

const TIMEOUT: FieldSpec = { key: 'timeoutSeconds', path: ['timeoutSeconds'], kind: 'integer' };
const STORE_AS: FieldSpec = { key: 'storeAs', path: ['storeAs'], kind: 'string' };
const ENTER_COUNTER: FieldSpec = { key: 'enterCounter', path: ['enterCounter'], kind: 'string' };
const RESET_COUNTERS: FieldSpec = { key: 'resetCounters', path: ['resetCounters'], kind: 'list' };

/** Keys a type's own form owns although none of {@link SPECS} names them: the tool's parameters. */
const OWNED_BY_OTHER_FORMS: Record<string, string[]> = { tool_task: ['params'] };

/** What every state that runs something carries, after its own fields. */
const COMMON: FieldSpec[] = [TIMEOUT, STORE_AS, ENTER_COUNTER, RESET_COUNTERS];

const SPECS: Record<string, FieldSpec[]> = {
  agent_task: [
    { key: 'recipe', path: ['recipe'], kind: 'string', required: true },
    { key: 'prompt', path: ['params', 'prompt'], kind: 'text' },
    ...COMMON,
  ],
  shell_task: [
    { key: 'run', path: ['run'], kind: 'text', required: true },
    { key: 'dirName', path: ['dirName'], kind: 'string' },
    ...COMMON,
  ],
  script_task: [
    { key: 'source', path: ['source'], kind: 'select', options: ['document', 'workspace'] },
    { key: 'path', path: ['path'], kind: 'string', required: true },
    { key: 'dirName', path: ['dirName'], kind: 'string' },
    ...COMMON,
  ],
  tool_task: [
    { key: 'tool', path: ['tool'], kind: 'string', required: true },
    ...COMMON,
  ],
  gate_task: [
    { key: 'inboxKind', path: ['inbox', 'kind'], kind: 'select', options: ['APPROVAL', 'DECISION', 'FEEDBACK'] },
    { key: 'inboxTitle', path: ['inbox', 'title'], kind: 'string' },
    { key: 'inboxBody', path: ['inbox', 'body'], kind: 'text' },
    { key: 'inboxAssignedTo', path: ['inbox', 'assignedTo'], kind: 'string' },
    { key: 'inboxCriticality', path: ['inbox', 'criticality'], kind: 'select', options: ['LOW', 'NORMAL', 'CRITICAL'] },
    {
      key: 'inboxOptions',
      path: ['inbox', 'options'],
      kind: 'list',
      onlyWhen: { path: ['inbox', 'kind'], equals: 'DECISION' },
    },
    ...COMMON,
  ],
  timer_task: [
    { key: 'duration', path: ['duration'], kind: 'string', required: true },
    STORE_AS,
    ENTER_COUNTER,
    RESET_COUNTERS,
  ],
  condition_task: [ENTER_COUNTER, RESET_COUNTERS],
  workflow_task: [
    { key: 'workflow', path: ['workflow'], kind: 'string', required: true },
    { key: 'items', path: ['items'], kind: 'string' },
    { key: 'as', path: ['as'], kind: 'string' },
    { key: 'onItemError', path: ['onItemError'], kind: 'select', options: ['fail', 'continue'] },
    { key: 'maxItems', path: ['maxItems'], kind: 'integer' },
    ...COMMON,
  ],
  terminal: [{ key: 'outcome', path: ['outcome'], kind: 'select', options: ['success', 'failure'] }],
};

/** The form fields of a state of {@code type}; none for a type the engine does not know. */
export function fieldSpecsFor(type: string): FieldSpec[] {
  return SPECS[type] ?? [];
}

/**
 * The top-level keys of a state the inspector owns — the form's fields and the
 * structural ones. The YAML fragment holds every other key and may not set these.
 */
export function ownedKeys(type: string): Set<string> {
  return new Set([
    ...STRUCTURAL,
    ...fieldSpecsFor(type).map((spec) => spec.path[0]),
    ...(OWNED_BY_OTHER_FORMS[type] ?? []),
  ]);
}

/** The value at {@code path} of a state body, or {@code undefined}. */
export function valueAt(body: Record<string, unknown>, path: readonly string[]): unknown {
  let node: unknown = body;
  for (const key of path) {
    if (typeof node !== 'object' || node === null || Array.isArray(node)) return undefined;
    node = (node as Record<string, unknown>)[key];
  }
  return node;
}

/** What a form control shows for a stored value. */
export function toDraft(kind: FieldKind, value: unknown): string {
  if (value === undefined || value === null) return '';
  if (kind === 'list') return Array.isArray(value) ? value.join(', ') : String(value);
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/**
 * What a form control's text means as a stored value: {@code undefined} to
 * remove the field, the typed value to write it, or {@code 'invalid'} when the
 * text is not a legal value of the kind (nothing is written then).
 */
export function fromDraft(kind: FieldKind, text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === '') return undefined;
  switch (kind) {
    case 'integer': {
      const n = Number(trimmed);
      return Number.isInteger(n) && n >= 0 ? n : 'invalid';
    }
    case 'list': {
      const words = trimmed.split(',').map((w) => w.trim()).filter((w) => w !== '');
      return words.length > 0 ? words : undefined;
    }
    case 'text':
      // Keep the author's line breaks; only the outer whitespace of a block goes.
      return text.replace(/^\s*\n/, '').replace(/\s+$/, '');
    default:
      return trimmed;
  }
}
