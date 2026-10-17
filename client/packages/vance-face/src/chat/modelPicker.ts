/**
 * Pure logic behind the chat's model picker (planning/model-picker.md):
 * filtering and grouping the selectable models, price formatting, and the
 * engine commands an "apply" turns into. The dialog itself only renders.
 */
import type { ChatModelDto, ChatModelPricingDto, ChatModelStateDto } from '@vance/generated';

/** The picker's "no override" choice — `//model clear`. */
export const RECIPE_DEFAULT = '' as const;

export interface ModelGroup {
  providerInstance: string;
  models: ChatModelDto[];
}

/**
 * Case-insensitive match of every whitespace-separated term against spec,
 * instance and model name — "cortecs glm" finds `cortecs:glm-5`.
 */
export function filterModels(models: ChatModelDto[], filter: string): ChatModelDto[] {
  const terms = filter.toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return models;
  return models.filter((m) => {
    const hay = `${m.spec} ${m.providerInstance} ${m.modelName}`.toLowerCase();
    return terms.every((t) => hay.includes(t));
  });
}

/** Groups by provider instance, keeping the server's order (instance, then name). */
export function groupModels(models: ChatModelDto[]): ModelGroup[] {
  const groups: ModelGroup[] = [];
  for (const m of models) {
    const last = groups[groups.length - 1];
    if (last && last.providerInstance === m.providerInstance) {
      last.models.push(m);
    } else {
      groups.push({ providerInstance: m.providerInstance, models: [m] });
    }
  }
  return groups;
}

/** `3.00 / 15.00 USD` per million tokens, input / output; `null` without a price. */
export function formatPrice(p: ChatModelPricingDto | undefined | null): string | null {
  if (!p) return null;
  return `${formatAmount(p.inputPerMTok)} / ${formatAmount(p.outputPerMTok)} ${p.currency}`;
}

function formatAmount(v: number): string {
  // Cheap models cost fractions of a cent per token; keep them readable.
  return v > 0 && v < 0.1 ? v.toFixed(3) : v.toFixed(2);
}

/** `128K` / `1M` — context window for the row description. */
export function formatContext(tokens: number): string {
  if (tokens >= 1_000_000) return `${+(tokens / 1_000_000).toFixed(1)}M`;
  if (tokens >= 1000) return `${Math.round(tokens / 1000)}K`;
  return String(tokens);
}

/** The picker's initial selection: the override, or the recipe default. */
export function initialSelection(state: ChatModelStateDto): string {
  return state.modelOverridden ? state.model : RECIPE_DEFAULT;
}

/**
 * Whether the reasoning level can be chosen for `selection`. Unknown models
 * (a recipe default outside the selectable list) stay enabled — the
 * provider downgrades silently if it has to.
 */
export function selectionSupportsThinking(
  selection: string,
  state: ChatModelStateDto,
  models: ChatModelDto[],
): boolean {
  const spec = selection === RECIPE_DEFAULT ? state.recipeModel : selection;
  if (!spec) return true;
  const m = models.find((x) => x.spec === spec);
  return m ? m.thinking : true;
}

/**
 * The engine commands (`verb`, `text`) that move the process from `state`
 * to the picked model and level. Unchanged values send nothing; the model
 * goes first so a level is never applied to the model being left.
 */
export function commandsFor(
  state: ChatModelStateDto,
  selection: string,
  thinking: string,
): { verb: string; text: string }[] {
  const out: { verb: string; text: string }[] = [];
  if (selection === RECIPE_DEFAULT) {
    if (state.modelOverridden) out.push({ verb: 'model', text: 'clear' });
  } else if (!state.modelOverridden || state.model !== selection) {
    out.push({ verb: 'model', text: selection });
  }
  if (thinking !== state.thinking) out.push({ verb: 'thinking', text: thinking });
  return out;
}

/** Short label of the effective model — `glm-5`, without the instance. */
export function shortModelName(spec: string): string {
  const colon = spec.indexOf(':');
  return colon < 0 ? spec : spec.slice(colon + 1);
}
