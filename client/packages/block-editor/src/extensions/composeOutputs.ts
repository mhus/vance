/**
 * Pure `$output:` / `$run:` read/write for the inline `vance-compose` block.
 *
 * Mirrors `@vance/shared`'s compose helpers — the block-editor stays
 * REST/`@vance/shared`-decoupled, so the (tiny, pure) YAML logic is duplicated
 * here rather than imported. Keep the two in sync.
 *
 * A successful run records its produced artifacts under a managed `$output:`
 * block; an in-flight async run parks its `runId` under `$run:` so a refresh can
 * resume polling. The Damogran runner ignores `$`-prefixed keys, so neither
 * affects execution.
 */
import jsyaml from 'js-yaml';
import type { ComposeOutputView } from './VanceCompose';

/** Parked in-flight run marker (`$run:`). */
export interface ComposeRunMarker {
  id: string;
  startedAt?: string;
}

const OUTPUT_KEY = '$output';
const RUN_KEY = '$run';
const MANAGED_KEYS = [OUTPUT_KEY, RUN_KEY];
const GENERATED_COMMENT = '# generated — compose run state (do not edit)';

function asString(v: unknown): string | undefined {
  return typeof v === 'string' && v.trim() !== '' ? v.trim() : undefined;
}

function parseRoot(doc: string): Record<string, unknown> | null {
  try {
    const parsed = jsyaml.load(doc);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function mapOutputList(raw: unknown): ComposeOutputView[] {
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

/** Read the `$output:` block a prior run wrote into the manifest. */
export function readComposeOutputs(doc: string): ComposeOutputView[] {
  return mapOutputList(parseRoot(doc)?.[OUTPUT_KEY]);
}

/**
 * A user-authored fixed `output:` list (top-level, no `$`) — a manual override
 * for when the run reports the wrong outputs or none. When present the run does
 * NOT write a `$output:` block; the UI shows this list instead. Runner ignores
 * the key (UI-only, like `showSource`/`autoRun`).
 */
export function readFixedOutputs(doc: string): ComposeOutputView[] {
  return mapOutputList(parseRoot(doc)?.output);
}

/** Replace the managed block with the `$output:` list (drops a pending `$run:`). */
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

/** Drop any managed block (`$run:`/`$output:`). */
export function clearComposeManaged(doc: string): string {
  const body = stripManagedBlock(doc);
  return body.length ? `${body}\n` : '';
}

function appendBlock(body: string, key: string, value: unknown): string {
  const nested = jsyaml
    .dump(value, { lineWidth: -1 })
    .trimEnd()
    .split('\n')
    .map((line) => `  ${line}`)
    .join('\n');
  return `${body}\n\n${GENERATED_COMMENT}\n${key}:\n${nested}\n`;
}

function stripManagedBlock(doc: string): string {
  const lines = doc.split('\n');
  let cut = lines.findIndex((line) => MANAGED_KEYS.some((k) => line.startsWith(`${k}:`)));
  if (cut < 0) return doc.replace(/\s+$/, '');
  while (cut > 0 && (lines[cut - 1].trim() === '' || lines[cut - 1].startsWith('# generated'))) {
    cut--;
  }
  return lines.slice(0, cut).join('\n').replace(/\s+$/, '');
}
