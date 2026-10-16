import * as yaml from 'js-yaml';

/**
 * Codec for the `vance-server-tool` document — one server tool under
 * `_vance/server-tools/<name>.yaml` (spec `specification/public/server-tools.md`).
 * The server's `ServerToolLoader` is the authority on the grammar: `type`
 * and `description` are required, `name` (if present) must match the file
 * name, everything else has a default.
 *
 * The parsed model is the **whole YAML map**: the form owns the fields
 * below and every other key survives each round-trip. Writing mirrors the
 * server's `ServerToolConfigYamlWriter` — same key order, empty lists and
 * an empty parameter map are left out — so a document saved here diffs
 * like one the server wrote.
 */
export type ServerToolDoc = Record<string, unknown>;

/** Parse failure surfaced by {@link parseServerToolDoc}. */
export class ServerToolParseError extends Error {}

/** Path prefix of server-tool documents in every cascade tier. */
export const SERVER_TOOL_PATH_PREFIX = '_vance/server-tools/';

/** Tool names: snake_case, like the bundled tools. */
export const TOOL_NAME_PATTERN = /^[a-z0-9][a-z0-9_]*$/;

/** Tool type of an MCP server — gets the structured form and the login. */
export const MCP_SERVER_TYPE = 'mcp_server';

/** The fields the form owns, with the loader's defaults applied. */
export interface ServerToolFields {
  type: string;
  description: string;
  parameters: Record<string, unknown>;
  labels: string[];
  enabled: boolean;
  primary: boolean;
  disabledSubTools: string[];
  defaultDeferred: boolean;
  promptHint: string;
}

/** Parse a document body into the model. */
export function parseServerToolDoc(body: string): ServerToolDoc {
  // js-yaml throws on an empty document instead of returning undefined —
  // a blank body is an empty map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new ServerToolParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ServerToolParseError('a server-tool document must be a YAML mapping');
  }
  return parsed as ServerToolDoc;
}

/** Serialise the model back to YAML. */
export function serializeServerToolDoc(doc: ServerToolDoc): string {
  return yaml.dump(doc, { indent: 2, lineWidth: 120, noRefs: true, sortKeys: false });
}

/** The tool name a document path encodes, or `null` outside the tool tree. */
export function toolNameFromPath(path: string | null | undefined): string | null {
  if (!path || !path.startsWith(SERVER_TOOL_PATH_PREFIX) || !path.endsWith('.yaml')) return null;
  const stem = path.substring(SERVER_TOOL_PATH_PREFIX.length, path.length - '.yaml'.length);
  return stem.trim() === '' || stem.includes('/') ? null : stem;
}

/** Document path of the tool `name`. */
export function toolPath(name: string): string {
  return `${SERVER_TOOL_PATH_PREFIX}${name.trim().toLowerCase()}.yaml`;
}

/** The form's view of a model — loader defaults for anything unset. */
export function fieldsFromDoc(doc: ServerToolDoc): ServerToolFields {
  return {
    type: stringOf(doc.type),
    description: stringOf(doc.description),
    parameters: recordOf(doc.parameters),
    labels: stringList(doc.labels),
    enabled: doc.enabled !== false,
    primary: doc.primary === true,
    disabledSubTools: stringList(doc.disabledSubTools),
    defaultDeferred: doc.defaultDeferred === true,
    promptHint: stringOf(doc.promptHint),
  };
}

/**
 * Writes the form fields into the model. Keys the form does not own keep
 * their place; the owned keys follow the server writer's order, and empty
 * optional ones are dropped (the loader reads them as their default).
 */
export function applyFields(doc: ServerToolDoc, fields: ServerToolFields): ServerToolDoc {
  const owned = ownedEntries(fields);
  const out: ServerToolDoc = {};
  // `name` leads like in the server writer; it is identity, never edited here.
  if (doc.name !== undefined) out.name = doc.name;
  for (const [key, value] of owned) out[key] = value;
  for (const [key, value] of Object.entries(doc)) {
    if (key === 'name' || OWNED_KEYS.has(key)) continue;
    out[key] = value;
  }
  return out;
}

/** A complete tool document for `name` — used to create and to override. */
export function serverToolDocFor(name: string, fields: ServerToolFields): ServerToolDoc {
  return applyFields({ name }, fields);
}

const OWNED_KEYS = new Set([
  'type', 'description', 'parameters', 'labels', 'enabled', 'primary',
  'disabledSubTools', 'defaultDeferred', 'promptHint',
]);

function ownedEntries(fields: ServerToolFields): Array<[string, unknown]> {
  const out: Array<[string, unknown]> = [
    ['type', fields.type],
    ['description', fields.description],
  ];
  if (Object.keys(fields.parameters).length > 0) out.push(['parameters', fields.parameters]);
  if (fields.labels.length > 0) out.push(['labels', fields.labels]);
  out.push(['enabled', fields.enabled]);
  out.push(['primary', fields.primary]);
  if (fields.disabledSubTools.length > 0) out.push(['disabledSubTools', fields.disabledSubTools]);
  out.push(['defaultDeferred', fields.defaultDeferred]);
  if (fields.promptHint.trim() !== '') out.push(['promptHint', fields.promptHint]);
  return out;
}

function stringOf(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function recordOf(v: unknown): Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? { ...(v as Record<string, unknown>) } : {};
}

function stringList(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x): x is string => typeof x === 'string' && x.trim() !== '');
}
