import type { CatalogModelInsightsDto, ModelAliasInsightsDto } from '@vance/generated';
import { ModelCatalogLayer } from '@vance/generated';

/**
 * Pure helpers behind the Insights "Models" tab — filtering, sorting,
 * verdicts and the alias lookup, kept out of the component so they are testable. Token
 * and price formatting is the model picker's (`chat/modelPicker.ts`).
 */

/** Why a chat model is (not) offered by the model picker; `other` for non-chat kinds. */
export type ModelVerdict = 'usable' | 'no-instance' | 'not-allowed' | 'other';

export function verdictOf(m: CatalogModelInsightsDto): ModelVerdict {
  if (m.kind !== 'chat') return 'other';
  if (m.instanceConfigured === false) return 'no-instance';
  if (m.chatAllowed === false) return 'not-allowed';
  return 'usable';
}

/**
 * The type switch above the catalog. The catalog's `kind` is finer than what
 * a reader filters by: speech in, speech out, audio understanding and music
 * are all "audio". A kind that fits none of the groups lands in `other`, which
 * the tab only offers when such a model exists — a new kind stays findable.
 */
export type KindGroup = 'llm' | 'audio' | 'image' | 'decision' | 'other';

const KIND_GROUPS: Record<string, KindGroup> = {
  chat: 'llm',
  embedding: 'llm',
  tts: 'audio',
  stt: 'audio',
  au: 'audio',
  music: 'audio',
  image: 'image',
  decision: 'decision',
};

export function kindGroupOf(kind: string): KindGroup {
  return KIND_GROUPS[kind] ?? 'other';
}

const GROUP_ORDER: KindGroup[] = ['llm', 'audio', 'image', 'decision', 'other'];

/** Groups present in the catalog with their counts, in switch order; empty groups are left out. */
export function groupCounts(models: CatalogModelInsightsDto[]): Array<{ group: KindGroup; count: number }> {
  const counts = new Map<KindGroup, number>();
  for (const m of models) {
    const g = kindGroupOf(m.kind);
    counts.set(g, (counts.get(g) ?? 0) + 1);
  }
  return GROUP_ORDER.filter((g) => counts.has(g)).map((g) => ({ group: g, count: counts.get(g)! }));
}

export interface ModelFilter {
  query: string;
  /** `''` = every group. */
  group: KindGroup | '';
  /** Hide chat models the picker would not offer (non-chat kinds stay). */
  usableOnly: boolean;
}

export function filterModels(
  models: CatalogModelInsightsDto[],
  filter: ModelFilter,
): CatalogModelInsightsDto[] {
  const q = filter.query.trim().toLowerCase();
  return models.filter((m) => {
    if (filter.group && kindGroupOf(m.kind) !== filter.group) return false;
    if (filter.usableOnly) {
      const v = verdictOf(m);
      if (v === 'no-instance' || v === 'not-allowed') return false;
    }
    if (!q) return true;
    return m.spec.toLowerCase().includes(q)
      || m.capabilities.some((c) => c.toLowerCase().includes(q));
  });
}

export type ModelSortKey = 'name' | 'price';
export type SortDirection = 'asc' | 'desc';

/**
 * Sorted copy. `name` compares the model name first and the provider instance
 * second, so the same model from several providers sits together — which is
 * where a price comparison happens. `price` is the output price per million
 * tokens; a model without a price (most non-chat kinds) goes last in either
 * direction, since "unknown" is neither cheap nor expensive.
 */
export function sortModels(
  models: CatalogModelInsightsDto[],
  key: ModelSortKey,
  direction: SortDirection,
): CatalogModelInsightsDto[] {
  const sign = direction === 'asc' ? 1 : -1;
  const byName = (a: CatalogModelInsightsDto, b: CatalogModelInsightsDto): number =>
    a.modelName.localeCompare(b.modelName) || a.providerInstance.localeCompare(b.providerInstance);
  return [...models].sort((a, b) => {
    if (key === 'name') return sign * byName(a, b);
    const pa = a.pricing?.outputPerMTok;
    const pb = b.pricing?.outputPerMTok;
    if (pa == null && pb == null) return byName(a, b);
    if (pa == null) return 1;
    if (pb == null) return -1;
    return sign * (pa - pb) || byName(a, b);
  });
}

/**
 * Aliases that end up at `spec` in this project. Compared case-insensitively,
 * so an alias whose setting spells the model `cortecs:GLM-5` still shows up
 * at the catalog's `cortecs:glm-5`.
 */
export function aliasesTargeting(aliases: ModelAliasInsightsDto[], spec: string): string[] {
  const wanted = spec.toLowerCase();
  return aliases.filter((a) => a.resolved?.toLowerCase() === wanted).map((a) => a.alias);
}

/** Discovery-written layer — the inventory observed at the provider, not set by hand. */
export function isAutoLayer(layer: ModelCatalogLayer): boolean {
  return layer === ModelCatalogLayer.SYSTEM_AUTO
    || layer === ModelCatalogLayer.TENANT_AUTO
    || layer === ModelCatalogLayer.PROJECT_AUTO;
}
