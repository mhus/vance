import type { InjectionKey, Ref } from 'vue';
import type { FormFieldDto } from '@vance/generated';
import type { ViewNode } from './generated/bistromath/ViewNode';
import type { ViewOption } from './generated/bistromath/ViewOption';

/**
 * Runtime changes a program made to the rendered view.
 *
 * <p><b>Why an API and not more schema.</b> The declarative route would be
 * `optionsFrom:`, `labelFrom:`, `hiddenIf:`, `disabledIf:` — one key per thing
 * an author might want to vary, each needing a name, a default and a place in
 * the parser. That is a configuration language growing to meet requirements
 * nobody has written down yet. A framework does not know what its programmer
 * needs, so the honest move is to hand over the tree instead of guessing which
 * of its properties deserve a binding.
 *
 * <p><b>Why beside the tree and not in it.</b> A patch never touches the parsed
 * `ViewNode` the server sent. Mutating that object would make a patch outlive a
 * re-fetch, so "reload" would stop meaning "what the document says" — and the
 * one reliable way out of a confusing runtime state would be gone. Kept apart,
 * `reset()` is a map clear and a view switch is a fresh start.
 *
 * <p><b>What a patch may change: appearance and presence, never behaviour.</b>
 * A label, a text, a widget's visibility, a `select`'s choices, a form's
 * fields. Not `on:` handlers, not `from:` bindings — a program that could
 * rewire which function a button calls, or which key a widget reads, would make
 * the document stop describing the app. The document stays the map; a patch
 * moves furniture.
 */
export interface FieldPatch {
  label?: string;
  help?: string;
  hide?: boolean;
  required?: boolean;
  options?: (string | ViewOption)[];
}

export interface WidgetPatch {
  label?: string;
  text?: string;
  hide?: boolean;
  options?: (string | ViewOption)[];
  /** Per-field changes, for a `form` or `details`. Keyed by field name. */
  fields?: Record<string, FieldPatch>;
}

export type PatchMap = Record<string, WidgetPatch>;

/**
 * Injected rather than threaded through as a prop: every node in the tree needs
 * it, and `WidgetNode` already carries five props through its own recursion.
 */
export const PATCHES: InjectionKey<Ref<PatchMap>> = Symbol('bistromath:patches');

/** Merge a change into what a widget already had. A later call adds to an earlier one. */
export function applyPatch(map: PatchMap, id: string, change: WidgetPatch): PatchMap {
  const before = map[id] ?? {};
  const fields = { ...(before.fields ?? {}) };
  for (const [name, patch] of Object.entries(change.fields ?? {})) {
    fields[name] = { ...(fields[name] ?? {}), ...patch };
  }
  const merged: WidgetPatch = { ...before, ...change };
  if (Object.keys(fields).length > 0) merged.fields = fields;
  return { ...map, [id]: merged };
}

/** Normalise the two spellings a program may use for a choice. */
export function toOptions(raw: (string | ViewOption)[]): ViewOption[] {
  return raw.map((o) =>
    typeof o === 'string'
      ? { value: o, label: o }
      : { value: o.value, label: o.label || o.value },
  );
}

/**
 * The node as it should render: the document's node with its patch applied.
 *
 * <p>Returns the original object when there is nothing to apply, so an
 * unpatched view costs no allocations and no reactivity churn.
 */
export function patched(node: ViewNode, map: PatchMap): ViewNode {
  const patch = node.id ? map[node.id] : undefined;
  if (!patch) return node;
  const out: ViewNode = { ...node };
  if (patch.label !== undefined) out.label = patch.label;
  if (patch.text !== undefined) out.text = patch.text;
  if (patch.options !== undefined) out.options = toOptions(patch.options);
  if (patch.fields) {
    out.fields = node.fields
      .filter((f) => !patch.fields?.[f.name]?.hide)
      .map((f): FormFieldDto => {
        const fp = patch.fields?.[f.name];
        if (!fp) return f;
        const out: FormFieldDto = { ...f };
        // A label is a localised map on the DTO; a patch is one string, so it
        // replaces every language. A program that knows better can still put the
        // right text in — what it cannot do is accidentally show German to an
        // English reader, because it replaced *all* of them, visibly.
        if (fp.label !== undefined) out.label = { en: fp.label };
        if (fp.help !== undefined) out.help = { en: fp.help };
        if (fp.required !== undefined) out.required = fp.required;
        if (fp.options !== undefined) {
          out.choices = toOptions(fp.options).map((o) => ({
            value: o.value,
            label: { en: o.label },
            // The DTO carries it; a patched choice is never pre-selected, because
            // "what is selected" is a *value* and lives in state, not in the
            // list of what may be selected.
            defaultSelected: false,
          }));
        }
        return out;
      });
  }
  return out;
}

/** Whether a patch says this widget is not shown at all. */
export function patchHides(node: ViewNode, map: PatchMap): boolean {
  return Boolean(node.id && map[node.id]?.hide);
}
