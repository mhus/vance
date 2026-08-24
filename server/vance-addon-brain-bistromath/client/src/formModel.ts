import type { FormFieldDto } from '@vance/generated';
import type { FormValue, FormValueObject } from '@vance/components';

/**
 * The seam between what a program keeps and what a form edits.
 *
 * <p>These are two different worlds and pretending otherwise is how a document
 * quietly acquires `paid: "true"`. The **program** holds whatever came out of a
 * YAML or JSON document: real booleans, real numbers, nested objects. The
 * **form engine** holds strings — that is the tool-template convention every
 * other consumer of `FormFieldDto` already follows, and changing it would touch
 * wizards, setting forms and document templates.
 *
 * <p>So the conversion lives here, once, in the one place that knows both
 * sides. The contract is a **round trip**: a value that goes out to the form
 * and comes back untouched must be identical, including its type. Everything
 * awkward below exists to keep that true.
 */

/** State → form. A field the record does not have shows as empty. */
export function toFormModel(
  record: Record<string, unknown> | null,
  fields: FormFieldDto[],
): Record<string, FormValue> {
  const out: Record<string, FormValue> = {};
  for (const field of fields) {
    out[field.name] = encodeField(record?.[field.name], field);
  }
  return out;
}

/**
 * Form → state, merged onto what was there.
 *
 * <p>Merged rather than rebuilt, and that is the load-bearing part: a record
 * usually carries keys no field mentions. Rebuilding from the field list would
 * delete them on the first keystroke — a data-loss bug that looks like the form
 * "not saving everything".
 */
export function fromFormModel(
  model: Record<string, FormValue>,
  fields: FormFieldDto[],
  base: Record<string, unknown> | null,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...(base ?? {}) };
  for (const field of fields) {
    const decoded = decodeField(model[field.name], field, out[field.name]);
    if (decoded === ABSENT) delete out[field.name];
    else out[field.name] = decoded;
  }
  return out;
}

/**
 * "Leave this key out entirely."
 *
 * <p>Needed because an empty form field and a missing key are the same thing to
 * a reader but not to a document. Without it, opening a form on a record and
 * closing it again would add `note: ""` to every record that had no note —
 * a diff on a document nobody edited.
 */
const ABSENT = Symbol('absent');

function encodeField(value: unknown, field: FormFieldDto): FormValue {
  switch (field.type) {
    case 'boolean':
      return value === true || value === 'true' || value === 1 ? 'true' : 'false';

    case 'multi_select':
      if (Array.isArray(value)) return value.map((v) => String(v));
      // A single value where a list belongs is a plausible hand-edit; showing
      // it selected beats showing nothing selected.
      return value === undefined || value === null || value === '' ? [] : [String(value)];

    case 'repeat': {
      if (!Array.isArray(value)) return [];
      const item = field.item ?? [];
      return value
        .filter((e): e is Record<string, unknown> => !!e && typeof e === 'object')
        .map((e) => toFormModel(e, item) as FormValueObject);
    }

    default:
      if (value === undefined || value === null) return '';
      if (typeof value === 'object') return JSON.stringify(value);
      return String(value);
  }
}

function decodeField(
  raw: FormValue | undefined,
  field: FormFieldDto,
  previous: unknown,
): unknown | typeof ABSENT {
  switch (field.type) {
    case 'boolean':
      return raw === 'true';

    case 'multi_select':
      return Array.isArray(raw) ? raw.map((v) => String(v)) : [];

    case 'repeat': {
      if (!Array.isArray(raw)) return [];
      const item = field.item ?? [];
      const before = Array.isArray(previous) ? (previous as unknown[]) : [];
      return raw.map((entry, i) => {
        const was = before[i];
        const baseEntry = was && typeof was === 'object' ? (was as Record<string, unknown>) : null;
        return fromFormModel(entry as Record<string, FormValue>, item, baseEntry);
      });
    }

    case 'integer': {
      const text = typeof raw === 'string' ? raw.trim() : '';
      if (text === '') return emptyMeans(previous);
      const n = Number(text);
      // A non-number in a number field is only reachable by a hand-edit or a
      // paste, and dropping what somebody typed is worse than a typed field
      // briefly holding a string: the program still sees it and can complain.
      return Number.isNaN(n) ? text : n;
    }

    default: {
      const text = typeof raw === 'string' ? raw : '';
      if (text === '') return emptyMeans(previous);
      return text;
    }
  }
}

/**
 * What an emptied field means, decided by what was there before.
 *
 * <p>If the key was absent or null, empty means "still absent" — leave the
 * document alone. If it held something, the reader cleared it on purpose, and
 * an empty string is that intent.
 */
function emptyMeans(previous: unknown): unknown | typeof ABSENT {
  if (previous === undefined) return ABSENT;
  if (previous === null) return null;
  return '';
}
