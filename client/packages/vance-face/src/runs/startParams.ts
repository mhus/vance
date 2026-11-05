/**
 * The parameters a run is started with: what the form shows, and how the
 * typed-in text becomes the values the server validates.
 *
 * <p>The form holds strings (and a flag for booleans) because that is what
 * inputs give. The declared type decides the conversion: integers are parsed,
 * objects and arrays are read as JSON, everything else stays text. An empty
 * optional field is left out, so the server applies the declared default
 * instead of receiving an empty string that overrides it.
 */

/** One declared parameter, whichever way it was read (definition document or REST). */
export interface StartParamSpec {
  name: string;
  /** Declared type: string / integer / boolean / object / array; anything else reads as text. */
  type: string;
  required: boolean;
  defaultValue?: unknown;
}

export type ParamFieldType = 'text' | 'integer' | 'boolean' | 'json';

/** What one form row holds. */
export interface ParamInput {
  text: string;
  checked: boolean;
}

export type ParamIssue =
  | { code: 'required'; key: string }
  | { code: 'notInteger'; key: string; raw: string }
  | { code: 'notJson'; key: string; error: string };

export function fieldTypeOf(spec: StartParamSpec): ParamFieldType {
  switch (spec.type) {
    case 'integer': return 'integer';
    case 'boolean': return 'boolean';
    case 'object':
    case 'array':
      return 'json';
    default:
      return 'text';
  }
}

/** The form's starting values: each declared default, rendered as the text a user would type. */
export function seedInputs(specs: StartParamSpec[]): Record<string, ParamInput> {
  const out: Record<string, ParamInput> = {};
  for (const spec of specs) {
    const dflt = spec.defaultValue;
    out[spec.name] = {
      checked: spec.type === 'boolean' && Boolean(dflt ?? false),
      text: dflt == null || spec.type === 'boolean'
        ? ''
        : typeof dflt === 'string' ? dflt : JSON.stringify(dflt),
    };
  }
  return out;
}

export type BuildResult =
  | { ok: true; params: Record<string, unknown> }
  | { ok: false; issue: ParamIssue };

/** Turns the form into request parameters, or names the first field that cannot be used. */
export function buildParams(
  specs: StartParamSpec[],
  inputs: Record<string, ParamInput>,
): BuildResult {
  const params: Record<string, unknown> = {};
  for (const spec of specs) {
    const input = inputs[spec.name] ?? { text: '', checked: false };
    if (spec.type === 'boolean') {
      params[spec.name] = input.checked;
      continue;
    }
    const raw = input.text.trim();
    if (raw === '') {
      // A declared default fills an empty required field on the server.
      if (spec.required && spec.defaultValue === undefined) {
        return { ok: false, issue: { code: 'required', key: spec.name } };
      }
      continue;
    }
    switch (fieldTypeOf(spec)) {
      case 'integer': {
        // Number() instead of parseInt: "12abc" must be refused, not read as 12.
        const n = Number(raw);
        if (!Number.isInteger(n)) {
          return { ok: false, issue: { code: 'notInteger', key: spec.name, raw } };
        }
        params[spec.name] = n;
        break;
      }
      case 'json':
        try {
          params[spec.name] = JSON.parse(raw);
        } catch (e) {
          return {
            ok: false,
            issue: {
              code: 'notJson',
              key: spec.name,
              error: e instanceof Error ? e.message : String(e),
            },
          };
        }
        break;
      default:
        params[spec.name] = raw;
    }
  }
  return { ok: true, params };
}
