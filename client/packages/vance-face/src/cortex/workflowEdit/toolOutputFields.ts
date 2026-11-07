/**
 * What a tool returns, as the fields a later state can read. Taken from the tool's JSON Schema of its result
 * ({@code ToolSpec.outputSchema}): the envelope the workflow sees — for a REST call {@code status}, {@code ok},
 * {@code headers}, {@code body}, {@code json}, for an MCP tool {@code structuredContent} and {@code isError} — with
 * the schema of the answer below it. Each field becomes a path that goes after the state's variable:
 * {@code ${state.out.json.messages[0].ts}}.
 *
 * Spec: {@code specification/public/workflows.md} §2.4 (dataflow), §3.3.
 */

export interface OutputField {
  /** Dotted path from the result, with {@code [0]} for the first element of a list: {@code json.messages[0].ts}. */
  path: string;
  /** {@code string}, {@code integer}, {@code object}, {@code list of object}, … */
  type: string;
  description: string;
}

export interface OutputFields {
  fields: OutputField[];
  /** There were more fields than were kept. */
  truncated: boolean;
}

/** How many levels below the result are followed; a schema can nest without end. */
const MAX_DEPTH = 5;

type Schema = Record<string, unknown>;

const isSchema = (value: unknown): value is Schema =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** The one type a schema names, ignoring {@code null}; {@code undefined} when it names none or several. */
function typeOf(schema: Schema): string | undefined {
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return 'enum';
  const raw = schema.type;
  const types = (Array.isArray(raw) ? raw : [raw]).filter((t) => typeof t === 'string' && t !== 'null') as string[];
  if (types.length === 1) return types[0];
  return isSchema(schema.properties) ? 'object' : undefined;
}

function label(schema: Schema): string {
  const type = typeOf(schema);
  if (type === 'array') {
    const items = isSchema(schema.items) ? typeOf(schema.items) : undefined;
    return `list of ${items ?? 'any'}`;
  }
  return type ?? 'any';
}

/** The fields of a result schema, in schema order, depth first; empty when there is no schema to read. */
export function outputFields(schema: Schema | undefined, limit = 400): OutputFields {
  const fields: OutputField[] = [];
  let truncated = false;

  function visit(node: Schema, prefix: string, depth: number): void {
    if (!isSchema(node.properties)) return;
    for (const [name, raw] of Object.entries(node.properties)) {
      if (fields.length >= limit) {
        truncated = true;
        return;
      }
      const child = isSchema(raw) ? raw : {};
      const path = prefix === '' ? name : `${prefix}.${name}`;
      fields.push({
        path,
        type: label(child),
        description: typeof child.description === 'string' ? child.description : '',
      });
      if (depth >= MAX_DEPTH) continue;
      if (typeOf(child) === 'array' && isSchema(child.items)) visit(child.items, `${path}[0]`, depth + 1);
      else visit(child, path, depth + 1);
    }
  }

  if (schema) visit(schema, '', 1);
  return { fields, truncated };
}

/** The placeholder that reads {@code path} of what the state {@code storeAs} kept. */
export function placeholderFor(storeAs: string, path: string): string {
  return '${state.' + storeAs + '.' + path + '}';
}

/** The fields whose path or description contains {@code query}, case-insensitively; all of them for an empty query. */
export function filterFields(fields: readonly OutputField[], query: string): OutputField[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return [...fields];
  return fields.filter((f) => `${f.path} ${f.description}`.toLowerCase().includes(needle));
}
