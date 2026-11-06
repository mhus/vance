import type { ToolServiceDto, ToolSpec } from '@vance/generated';
import { parse, stringify } from 'yaml';

/**
 * The parameters of a tool as form fields, read from the tool's JSON Schema
 * ({@code ToolSpec.paramsSchema}). Only the top level is looked at: a scalar
 * becomes a text control, an enum a pick list, anything nested (an object, a
 * list, {@code anyOf}) a small YAML field. A value that is exactly a
 * placeholder ({@code ${state.x}}) is always allowed and kept as text, whatever
 * the schema says — the engine fills it in with its real type at run time.
 */

export type ParamKind = 'string' | 'integer' | 'number' | 'boolean' | 'enum' | 'yaml';

export interface ParamField {
  name: string;
  kind: ParamKind;
  required: boolean;
  description: string;
  /** The allowed values of an {@code enum} field. */
  options: string[];
  /** The schema's default, shown as a hint, never written. */
  defaultText: string;
}

/** The result of {@link fromParamDraft} when the text is no legal value. */
export const INVALID = Symbol('invalid');

const PLACEHOLDER = /^\$\{[^}]*\}$/;

function kindOf(schema: Record<string, unknown>): ParamKind {
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return 'enum';
  const raw = schema.type;
  const types = (Array.isArray(raw) ? raw : [raw]).filter((t) => t !== 'null' && t !== undefined);
  if (types.length !== 1) return 'yaml';
  switch (types[0]) {
    case 'string': return 'string';
    case 'integer': return 'integer';
    case 'number': return 'number';
    case 'boolean': return 'boolean';
    default: return 'yaml';
  }
}

/** The fields of a tool's parameter schema, required ones first, each group in schema order. */
export function paramFields(schema: Record<string, unknown> | undefined): ParamField[] {
  const properties = schema?.properties;
  if (typeof properties !== 'object' || properties === null || Array.isArray(properties)) return [];
  const required = new Set(Array.isArray(schema?.required) ? (schema.required as unknown[]).map(String) : []);
  const fields = Object.entries(properties as Record<string, unknown>).map(([name, raw]) => {
    const prop = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
    const kind = kindOf(prop);
    return {
      name,
      kind,
      required: required.has(name),
      description: typeof prop.description === 'string' ? prop.description : '',
      options: kind === 'enum' ? (prop.enum as unknown[]).map(String) : [],
      defaultText: prop.default === undefined ? '' : typeof prop.default === 'object' ? JSON.stringify(prop.default) : String(prop.default),
    } satisfies ParamField;
  });
  return [...fields.filter((f) => f.required), ...fields.filter((f) => !f.required)];
}

/** What a control shows for a stored value. */
export function toParamDraft(field: ParamField, value: unknown): string {
  if (value === undefined || value === null) return '';
  if (field.kind === 'yaml') return stringify(value).trimEnd();
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/**
 * What a control's text means as a stored value: {@code undefined} removes the
 * parameter, {@link INVALID} means nothing is written (the text is no legal
 * value, or no YAML).
 */
export function fromParamDraft(field: ParamField, text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === '') return undefined;
  if (PLACEHOLDER.test(trimmed)) return trimmed;
  switch (field.kind) {
    case 'integer': {
      const n = Number(trimmed);
      return Number.isInteger(n) ? n : INVALID;
    }
    case 'number': {
      const n = Number(trimmed);
      return Number.isFinite(n) ? n : INVALID;
    }
    case 'boolean':
      return trimmed === 'true' ? true : trimmed === 'false' ? false : INVALID;
    case 'yaml':
      try {
        const value: unknown = parse(text);
        return value === null || value === undefined ? undefined : value;
      } catch {
        return INVALID;
      }
    case 'string':
    case 'enum':
      // A string stays as typed: its spaces may matter to the service.
      return field.kind === 'string' ? text : trimmed;
  }
}

/** The tool named {@code name} among the services, with the service it came from. */
export function findTool(services: ToolServiceDto[], name: string): ToolSpec | undefined {
  for (const svc of services) {
    const hit = svc.tools.find((t) => t.name === name);
    if (hit) return hit;
  }
  return undefined;
}
