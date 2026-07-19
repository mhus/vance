/**
 * Pure `$output:` read/write for the inline `vance-compose` block.
 *
 * Mirrors `@vance/shared`'s `readComposeOutputs`/`writeComposeOutputs` — the
 * block-editor stays REST/`@vance/shared`-decoupled, so the (tiny, pure) YAML
 * logic is duplicated here rather than imported. Keep the two in sync.
 *
 * A successful run records its produced artifacts into the compose YAML under
 * a managed `$output:` block; on (re)load we read it back so the outputs
 * survive a refresh. The Damogran runner ignores `$`-prefixed keys, so this
 * never affects execution.
 */
import jsyaml from 'js-yaml';
import type { ComposeOutputView } from './VanceCompose';

const OUTPUT_KEY = '$output';
const GENERATED_COMMENT = '# generated — last run outputs (do not edit)';

function asString(v: unknown): string | undefined {
  return typeof v === 'string' && v.trim() !== '' ? v.trim() : undefined;
}

/** Read the `$output:` block a prior run wrote into the manifest. */
export function readComposeOutputs(doc: string): ComposeOutputView[] {
  let parsed: unknown;
  try {
    parsed = jsyaml.load(doc);
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
 * Return `doc` with its managed trailing `$output:` block replaced by
 * `outputs`; the hand-authored manifest above is preserved verbatim. An empty
 * list drops the block.
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
  const list = jsyaml
    .dump(items, { lineWidth: -1 })
    .trimEnd()
    .split('\n')
    .map((line) => `  ${line}`)
    .join('\n');
  return `${body}\n\n${GENERATED_COMMENT}\n${OUTPUT_KEY}:\n${list}\n`;
}

function stripOutputBlock(doc: string): string {
  const lines = doc.split('\n');
  let cut = lines.findIndex((line) => line.startsWith(`${OUTPUT_KEY}:`));
  if (cut < 0) return doc.replace(/\s+$/, '');
  while (cut > 0 && (lines[cut - 1].trim() === '' || lines[cut - 1].startsWith('# generated'))) {
    cut--;
  }
  return lines.slice(0, cut).join('\n').replace(/\s+$/, '');
}
