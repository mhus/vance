import type { CatalogModelInsightsDto, ModelAliasInsightsDto } from '@vance/generated';
import { ModelCatalogLayer } from '@vance/generated';

/**
 * Pure helpers behind the Insights "Models" tab — filtering, verdicts and
 * the alias lookup, kept out of the component so they are testable. Token
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

export interface ModelFilter {
  query: string;
  /** `''` = every kind. */
  kind: string;
  /** Hide chat models the picker would not offer (non-chat kinds stay). */
  usableOnly: boolean;
}

export function filterModels(
  models: CatalogModelInsightsDto[],
  filter: ModelFilter,
): CatalogModelInsightsDto[] {
  const q = filter.query.trim().toLowerCase();
  return models.filter((m) => {
    if (filter.kind && m.kind !== filter.kind) return false;
    if (filter.usableOnly) {
      const v = verdictOf(m);
      if (v === 'no-instance' || v === 'not-allowed') return false;
    }
    if (!q) return true;
    return m.spec.toLowerCase().includes(q)
      || m.capabilities.some((c) => c.toLowerCase().includes(q));
  });
}

/** Kinds present in the catalog with their counts — `chat` first, then alphabetical. */
export function kindCounts(models: CatalogModelInsightsDto[]): Array<{ kind: string; count: number }> {
  const counts = new Map<string, number>();
  for (const m of models) counts.set(m.kind, (counts.get(m.kind) ?? 0) + 1);
  return [...counts.entries()]
    .map(([kind, count]) => ({ kind, count }))
    .sort((a, b) => {
      if (a.kind === 'chat') return -1;
      if (b.kind === 'chat') return 1;
      return a.kind.localeCompare(b.kind);
    });
}

/** Aliases that end up at `spec` in this project. */
export function aliasesTargeting(aliases: ModelAliasInsightsDto[], spec: string): string[] {
  return aliases.filter((a) => a.resolved === spec).map((a) => a.alias);
}

/** Discovery-written layer — the inventory observed at the provider, not set by hand. */
export function isAutoLayer(layer: ModelCatalogLayer): boolean {
  return layer === ModelCatalogLayer.SYSTEM_AUTO
    || layer === ModelCatalogLayer.TENANT_AUTO
    || layer === ModelCatalogLayer.PROJECT_AUTO;
}
