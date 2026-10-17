import { describe, expect, it } from 'vitest';
import type { CatalogModelInsightsDto, ModelAliasInsightsDto } from '@vance/generated';
import { ModelAliasScope, ModelCatalogLayer } from '@vance/generated';
import { aliasesTargeting, filterModels, isAutoLayer, kindCounts, verdictOf } from './modelInsights';

function model(spec: string, over: Partial<CatalogModelInsightsDto> = {}): CatalogModelInsightsDto {
  const colon = spec.indexOf(':');
  return {
    spec,
    providerInstance: spec.slice(0, colon),
    modelName: spec.slice(colon + 1),
    kind: 'chat',
    capabilities: [],
    origin: ModelCatalogLayer.BUNDLED,
    discovered: false,
    instanceConfigured: true,
    chatAllowed: true,
    ...over,
  };
}

const models = [
  model('anthropic:claude-sonnet', { capabilities: ['thinking', 'vision'] }),
  model('openai:gpt-5', { instanceConfigured: false }),
  model('cortecs:glm-5', { chatAllowed: false }),
  model('cortecs:flux', { kind: 'image', instanceConfigured: undefined, chatAllowed: undefined }),
];

describe('verdictOf', () => {
  it('names the first reason a chat model is not offered', () => {
    expect(models.map(verdictOf)).toEqual(['usable', 'no-instance', 'not-allowed', 'other']);
  });

  it('a missing instance wins over a refused allowlist', () => {
    expect(verdictOf(model('x:y', { instanceConfigured: false, chatAllowed: false }))).toBe('no-instance');
  });
});

describe('filterModels', () => {
  it('matches spec and capability', () => {
    const hits = filterModels(models, { query: 'vision', kind: '', usableOnly: false });
    expect(hits.map((m) => m.spec)).toEqual(['anthropic:claude-sonnet']);
    expect(filterModels(models, { query: 'CORTECS', kind: '', usableOnly: false })).toHaveLength(2);
  });

  it('usableOnly drops refused chat models but keeps other kinds', () => {
    const hits = filterModels(models, { query: '', kind: '', usableOnly: true });
    expect(hits.map((m) => m.spec)).toEqual(['anthropic:claude-sonnet', 'cortecs:flux']);
  });

  it('filters by kind', () => {
    expect(filterModels(models, { query: '', kind: 'image', usableOnly: false }).map((m) => m.spec))
      .toEqual(['cortecs:flux']);
  });
});

describe('kindCounts', () => {
  it('puts chat first', () => {
    const more = [...models, model('a:tts', { kind: 'tts' }), model('a:au', { kind: 'au' })];
    expect(kindCounts(more)).toEqual([
      { kind: 'chat', count: 3 },
      { kind: 'au', count: 1 },
      { kind: 'image', count: 1 },
      { kind: 'tts', count: 1 },
    ]);
  });
});

describe('aliasesTargeting', () => {
  it('lists the aliases resolving to a spec', () => {
    const aliases: ModelAliasInsightsDto[] = [
      { alias: 'default:chat', target: 'default:fast', definedIn: ModelAliasScope.TENANT, resolved: 'cortecs:glm-5', chain: [], viaTenantDefault: false },
      { alias: 'default:fast', target: 'cortecs:glm-5', definedIn: ModelAliasScope.TENANT, resolved: 'cortecs:glm-5', chain: [], viaTenantDefault: false },
      { alias: 'cheap:x', target: 'nowhere:x', definedIn: ModelAliasScope.PROJECT, chain: [], viaTenantDefault: false, error: 'unknown' },
    ];
    expect(aliasesTargeting(aliases, 'cortecs:glm-5')).toEqual(['default:chat', 'default:fast']);
  });

  it('compares specs case-insensitively', () => {
    const aliases: ModelAliasInsightsDto[] = [
      { alias: 'default:chat', target: 'cortecs:GLM-5', definedIn: ModelAliasScope.PROJECT, resolved: 'cortecs:GLM-5', chain: [], viaTenantDefault: false },
    ];
    expect(aliasesTargeting(aliases, 'cortecs:glm-5')).toEqual(['default:chat']);
    expect(aliasesTargeting(aliases, 'CORTECS:glm-5')).toEqual(['default:chat']);
  });
});

describe('isAutoLayer', () => {
  it('knows the discovery layers', () => {
    expect(isAutoLayer(ModelCatalogLayer.TENANT_AUTO)).toBe(true);
    expect(isAutoLayer(ModelCatalogLayer.TENANT)).toBe(false);
  });
});
