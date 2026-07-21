// Script-compose blocks: a compose manifest constrained to exactly ONE task of
// a fixed type, whose script (code / command) is edited in a dedicated pane
// next to the (still editable) settings YAML. Pure helpers — no Vue — so the
// normalisation is unit-testable and shared by the NodeView + registration.
//
// Storage model (chosen: "re-serialize"): the block's single source of truth is
// the manifest YAML. Editing the script re-dumps the manifest with the task
// forced to the fixed type; other top-level keys (title/description/unknown)
// keep their values (comments/formatting are not preserved on a script edit).

import jsyaml from 'js-yaml';
import { clearComposeManaged } from '../extensions/composeOutputs';

/** Task fields that carry the editable script — stripped before re-adding the
 * block's own {@code scriptField} so switching never leaves a stale twin. */
const SCRIPT_FIELDS = ['code', 'command', 'prompt'];

/** Static config for one `vance-compose-<lang>` block. */
export interface ScriptComposeKind {
  /** Fence info-string (registry key), e.g. `vance-compose-js`. */
  fence: string;
  /** Tiptap node name, e.g. `vanceComposeJs`. */
  nodeName: string;
  /** Fixed Damogran task type the block enforces. */
  taskType: string;
  /** Task field the script lands in (`code` for js/python, `command` for bash,
   * `prompt` for agent). */
  scriptField: string;
  /** Slash-menu label + hint. */
  label: string;
  hint: string;
  /** Editor placeholder for the empty script pane. */
  placeholder: string;
  /** `session:` keys always forced on (re-applied on every edit, win over the
   * user's settings pane) — e.g. `{ enabled: true }` for the agent block, whose
   * task needs a session process. */
  sessionForce?: Record<string, unknown>;
  /** `session:` keys filled in only when absent (a user-set value wins) — e.g.
   * `{ recipe: 'arthur' }` for the agent block's default agent. */
  sessionDefaults?: Record<string, unknown>;
}

export const SCRIPT_COMPOSE_KINDS: ScriptComposeKind[] = [
  {
    fence: 'vance-compose-js',
    nodeName: 'vanceComposeJs',
    taskType: 'js',
    scriptField: 'code',
    label: 'Compose JS',
    hint: 'Compose-Block mit einem JS-Task',
    placeholder: "vance.files.write('out.txt', 'hello')",
  },
  {
    fence: 'vance-compose-bash',
    nodeName: 'vanceComposeBash',
    taskType: 'exec',
    scriptField: 'command',
    label: 'Compose Bash',
    hint: 'Compose-Block mit einem Shell-Task',
    placeholder: 'echo hello > out.txt',
  },
  {
    fence: 'vance-compose-python',
    nodeName: 'vanceComposePython',
    taskType: 'python',
    scriptField: 'code',
    label: 'Compose Python',
    hint: 'Compose-Block mit einem Python-Task',
    placeholder: "print('hello')",
  },
  {
    fence: 'vance-compose-agent',
    nodeName: 'vanceComposeAgent',
    taskType: 'agent',
    scriptField: 'prompt',
    label: 'Compose Agent',
    hint: 'Compose-Block mit einem Agent-Task',
    placeholder: 'Analysiere die Dateien im Workspace und fasse zusammen.',
    // The prompt runs as a turn on the session process — force it enabled …
    sessionForce: { enabled: true },
    // … and default it to the arthur agent recipe (user can override in settings).
    sessionDefaults: { recipe: 'arthur' },
  },
];

export function kindByNodeName(nodeName: string): ScriptComposeKind | undefined {
  return SCRIPT_COMPOSE_KINDS.find((k) => k.nodeName === nodeName);
}

function parse(yaml: string): Record<string, unknown> {
  try {
    const p = jsyaml.load(yaml);
    return p && typeof p === 'object' && !Array.isArray(p) ? (p as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/**
 * The single task's script. Reads the first task's {@code scriptField}; falls
 * back to the other script field so switching language keeps the text.
 */
export function extractScript(yaml: string, scriptField: string): string {
  const m = parse(yaml);
  const tasks = Array.isArray(m.tasks) ? m.tasks : [];
  const first = tasks[0];
  if (first && typeof first === 'object') {
    const t = first as Record<string, unknown>;
    for (const key of [scriptField, ...SCRIPT_FIELDS]) {
      if (typeof t[key] === 'string') return t[key] as string;
    }
  }
  return '';
}

/**
 * Re-serialize the manifest with EXACTLY ONE task of {@code kind.taskType}
 * carrying {@code script} in {@code kind.scriptField}. Preserves other top-level
 * keys and the task's other params (e.g. `outputs`). Merges the `session:` block
 * so {@code kind.sessionForce} keys are always on and {@code kind.sessionDefaults}
 * fill gaps, while the user's own session fields (name, recipe, clean) survive.
 * Strips the stale managed {@code $output:}/{@code $run:} block first (a script
 * edit invalidates a prior run's outputs).
 */
export function applyScript(yaml: string, kind: ScriptComposeKind, script: string): string {
  const m = parse(clearComposeManaged(yaml));

  // Preserve the existing task's other params (outputs, …); only the type +
  // script-carrying fields are managed by the block.
  const tasks = Array.isArray(m.tasks) ? m.tasks : [];
  const first = tasks[0];
  const prev: Record<string, unknown> =
    first && typeof first === 'object' ? { ...(first as Record<string, unknown>) } : {};
  delete m.tasks;
  delete prev.type;
  for (const f of SCRIPT_FIELDS) delete prev[f];

  const task: Record<string, unknown> = { type: kind.taskType, ...prev };
  task[kind.scriptField] = script;

  // session: defaults (lowest) < user's existing fields < forced (highest).
  if (kind.sessionForce || kind.sessionDefaults) {
    const existing =
      m.session && typeof m.session === 'object' && !Array.isArray(m.session)
        ? (m.session as Record<string, unknown>)
        : {};
    m.session = { ...(kind.sessionDefaults ?? {}), ...existing, ...(kind.sessionForce ?? {}) };
  }

  const out: Record<string, unknown> = { ...m, tasks: [task] };
  return jsyaml.dump(out, { lineWidth: -1 }).trimEnd() + '\n';
}

/**
 * Normalise after a settings-YAML edit: force a single fixed-type task while
 * keeping whatever script the manifest already carried.
 */
export function normalizeManifest(yaml: string, kind: ScriptComposeKind): string {
  return applyScript(yaml, kind, extractScript(yaml, kind.scriptField));
}

/** Starter manifest for a freshly inserted block. */
export function initialManifest(kind: ScriptComposeKind): string {
  return applyScript(
    `title: ${kind.label}\nworkspace:\n  name: my-work\n  type: temp\n`,
    kind,
    kind.placeholder,
  );
}
