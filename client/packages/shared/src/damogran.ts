/**
 * Damogran compose output persistence — pure YAML helpers shared by the Cortex
 * `compose` View and (mirrored) the Workbook inline block.
 *
 * A run's outputs live only in the caller's memory, and a named workspace is
 * **not exclusive** to one compose (many composes fire against it in sequence),
 * so scanning the workspace cannot tell which files *this* compose produced.
 * Instead a successful run writes the produced artifact list into the manifest
 * itself under a managed `$output:` block; on (re)load we read it back. The
 * Damogran runner ignores `$`-prefixed keys, so `$output` never affects
 * execution — it is a pure last-run cache. Content is still loaded on demand
 * from the (transient) workspace via each artifact's `vance-workspace:` URI;
 * true persistence comes from the compose's `export` step.
 */
import yaml from 'js-yaml';

/** A resolved compose output artifact, as consumed by the ComposeOutput view. */
export interface ComposeOutputView {
  path: string;
  uri: string;
  kind?: string;
  title?: string;
}

const OUTPUT_KEY = '$output';
const GENERATED_COMMENT = '# generated — last run outputs (do not edit)';

function asString(v: unknown): string | undefined {
  return typeof v === 'string' && v.trim() !== '' ? v.trim() : undefined;
}

/**
 * Read the `$output:` block a prior successful run wrote into the manifest.
 * Returns `[]` for invalid YAML or a missing/malformed block.
 */
export function readComposeOutputs(doc: string): ComposeOutputView[] {
  let parsed: unknown;
  try {
    parsed = yaml.load(doc);
  } catch {
    return [];
  }
  if (!parsed || typeof parsed !== 'object') return [];
  const raw = (parsed as Record<string, unknown>)[OUTPUT_KEY];
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
 * Return `doc` with its managed `$output:` block replaced by `outputs`. The
 * block is always the trailing region (after a generated marker comment); the
 * hand-authored manifest above it is preserved **verbatim** — no YAML
 * round-trip, no comment loss. An empty list drops the block entirely.
 */
export function writeComposeOutputs(doc: string, outputs: ComposeOutputView[]): string {
  const body = stripOutputBlock(doc);
  if (outputs.length === 0) return body.length ? `${body}\n` : '';

  const items = outputs.map((o) => {
    const item: Record<string, string> = { path: o.path, uri: o.uri };
    if (o.kind) item.kind = o.kind;
    if (o.title) item.title = o.title;
    return item;
  });
  // Dump the list, then nest it under a literally-written `$output:` key so
  // the key is never quoted (keeps stripOutputBlock's line match reliable).
  const list = yaml
    .dump(items, { lineWidth: -1 })
    .trimEnd()
    .split('\n')
    .map((line) => `  ${line}`)
    .join('\n');
  return `${body}\n\n${GENERATED_COMMENT}\n${OUTPUT_KEY}:\n${list}\n`;
}

/** Drop the trailing `$output:` block (and its generated marker), if present. */
function stripOutputBlock(doc: string): string {
  const lines = doc.split('\n');
  let cut = lines.findIndex((line) => line.startsWith(`${OUTPUT_KEY}:`));
  if (cut < 0) return doc.replace(/\s+$/, '');
  while (cut > 0 && (lines[cut - 1].trim() === '' || lines[cut - 1].startsWith('# generated'))) {
    cut--;
  }
  return lines.slice(0, cut).join('\n').replace(/\s+$/, '');
}
