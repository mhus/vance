/**
 * Damogran compose helpers shared by the Cortex `compose` View and (mirrored)
 * the Workbook inline block: run start/poll + the managed `$output:`/`$run:`
 * blocks the manifest carries between loads.
 *
 * A run's outputs live only in the caller's memory, and a named workspace is
 * **not exclusive** to one compose, so scanning the workspace cannot tell which
 * files *this* compose produced. Instead a successful run writes the produced
 * artifact list into the manifest under a managed `$output:` block; while a run
 * is still in flight (async, >30s) its `runId` is parked in a `$run:` block so a
 * page refresh can resume polling. The Damogran runner ignores `$`-prefixed
 * keys, so neither block affects execution. Content is loaded on demand from the
 * (transient) workspace via each artifact's `vance-workspace:` URI.
 */
import yaml from 'js-yaml';
import { brainFetch } from './rest/index';

/** A resolved compose output artifact, as consumed by the ComposeOutput view. */
export interface ComposeOutputView {
  path: string;
  uri: string;
  kind?: string;
  title?: string;
}

/** One task's result inside a run response. */
export interface ComposeTaskView {
  status: string;
  error?: string;
  log?: string;
  outputs?: ComposeOutputView[];
}

/** POST/GET compose-run response (async run: inline result or running + progress). */
export interface ComposeRunResponse {
  runId?: string;
  running: boolean;
  status: string; // running | success | failure
  workspace?: string;
  success?: boolean;
  error?: string;
  tasks?: ComposeTaskView[];
  currentTaskIndex?: number;
  currentTaskType?: string;
  tail?: string[];
}

/** Parked in-flight run marker (`$run:`), so a refresh can resume polling. */
export interface ComposeRunMarker {
  id: string;
  startedAt?: string;
}

/** Body for a compose run (one of composeYaml / composePath). */
export interface ComposeRunRequest {
  composeYaml?: string;
  composePath?: string;
  composeBasePath?: string;
  sessionId?: string | null;
  /** Workbook app identity → per-app chatless carrier; omit for a standalone
   *  compose file (server falls back to a per-user carrier). */
  appKey?: string;
}

const OUTPUT_KEY = '$output';
const RUN_KEY = '$run';
const MANAGED_KEYS = [OUTPUT_KEY, RUN_KEY];
const GENERATED_COMMENT = '# generated — compose run state (do not edit)';

function asString(v: unknown): string | undefined {
  return typeof v === 'string' && v.trim() !== '' ? v.trim() : undefined;
}

// ──────────────────── REST ────────────────────

/** Start a compose run (async on the server); returns inline result or a runId. */
export function postComposeRun(projectId: string, body: ComposeRunRequest): Promise<ComposeRunResponse> {
  return brainFetch<ComposeRunResponse>('POST', 'compose/run', { body: { ...body, projectId } });
}

/** Poll an in-flight run by id (status + current task + live tail). */
export function pollComposeRun(projectId: string, runId: string): Promise<ComposeRunResponse> {
  const query = new URLSearchParams({ projectId });
  return brainFetch<ComposeRunResponse>('GET', `compose/run/${encodeURIComponent(runId)}?${query}`);
}

/** Cancel an in-flight run (kills the current exec + halts before the next task). */
export function cancelComposeRun(projectId: string, runId: string): Promise<ComposeRunResponse> {
  const query = new URLSearchParams({ projectId });
  return brainFetch<ComposeRunResponse>('POST', `compose/run/${encodeURIComponent(runId)}/cancel?${query}`);
}

// ──────────────────── $output ────────────────────

/** Read the `$output:` block a prior successful run wrote into the manifest. */
export function readComposeOutputs(doc: string): ComposeOutputView[] {
  const root = parseRoot(doc);
  const raw = root?.[OUTPUT_KEY];
  if (!Array.isArray(raw)) return [];
  const outputs: ComposeOutputView[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const o = item as Record<string, unknown>;
    const path = asString(o.path);
    const uri = asString(o.uri);
    if (!path || !uri) continue;
    outputs.push({ path, uri, kind: asString(o.kind), title: asString(o.title) });
  }
  return outputs;
}

/**
 * Return `doc` with its managed block replaced by the `$output:` list (drops a
 * pending `$run:` — a completed run supersedes it). The hand-authored manifest
 * above the block is preserved verbatim. Empty list drops the block entirely.
 */
export function writeComposeOutputs(doc: string, outputs: ComposeOutputView[]): string {
  const body = stripManagedBlock(doc);
  if (outputs.length === 0) return body.length ? `${body}\n` : '';
  const items = outputs.map((o) => {
    const item: Record<string, string> = { path: o.path, uri: o.uri };
    if (o.kind) item.kind = o.kind;
    if (o.title) item.title = o.title;
    return item;
  });
  return appendBlock(body, OUTPUT_KEY, items);
}

// ──────────────────── $run ────────────────────

/** Read the parked in-flight `$run:` marker, or null. */
export function readComposeRun(doc: string): ComposeRunMarker | null {
  const raw = parseRoot(doc)?.[RUN_KEY];
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const id = asString(r.id);
  return id ? { id, startedAt: asString(r.startedAt) } : null;
}

/** Park an in-flight run's marker (replaces any managed block). */
export function writeComposeRun(doc: string, marker: ComposeRunMarker): string {
  const body = stripManagedBlock(doc);
  const obj: Record<string, string> = { id: marker.id };
  if (marker.startedAt) obj.startedAt = marker.startedAt;
  return appendBlock(body, RUN_KEY, obj);
}

/** Drop any managed block (`$run:`/`$output:`) — e.g. to clear a stale marker. */
export function clearComposeManaged(doc: string): string {
  const body = stripManagedBlock(doc);
  return body.length ? `${body}\n` : '';
}

// ──────────────────── managed-block plumbing ────────────────────

function parseRoot(doc: string): Record<string, unknown> | null {
  try {
    const parsed = yaml.load(doc);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Append a literally-keyed managed block (key never quoted) after `body`. */
function appendBlock(body: string, key: string, value: unknown): string {
  const nested = yaml
    .dump(value, { lineWidth: -1 })
    .trimEnd()
    .split('\n')
    .map((line) => `  ${line}`)
    .join('\n');
  return `${body}\n\n${GENERATED_COMMENT}\n${key}:\n${nested}\n`;
}

/** Drop the trailing managed block (`$output:` or `$run:` + its marker comment). */
function stripManagedBlock(doc: string): string {
  const lines = doc.split('\n');
  let cut = lines.findIndex((line) => MANAGED_KEYS.some((k) => line.startsWith(`${k}:`)));
  if (cut < 0) return doc.replace(/\s+$/, '');
  while (cut > 0 && (lines[cut - 1].trim() === '' || lines[cut - 1].startsWith('# generated'))) {
    cut--;
  }
  return lines.slice(0, cut).join('\n').replace(/\s+$/, '');
}
