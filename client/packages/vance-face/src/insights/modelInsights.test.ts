import { describe, expect, it } from 'vitest';
import type { CatalogModelInsightsDto, ModelAliasInsightsDto } from '@vance/generated';
import { ModelAliasScope, ModelCatalogLayer } from '@vance/generated';
import {
  aliasesTargeting,
  filterModels,
  groupCounts,
  isAutoLayer,
  kindGroupOf,
  sortModels,
  verdictOf,
} from './modelInsights';

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
    const hits = filterModels(models, { query: 'vision', group: '', usableOnly: false });
    expect(hits.map((m) => m.spec)).toEqual(['anthropic:claude-sonnet']);
    expect(filterModels(models, { query: 'CORTECS', group: '', usableOnly: false })).toHaveLength(2);
  });

  it('usableOnly drops refused chat models but keeps other kinds', () => {
    const hits = filterModels(models, { query: '', group: '', usableOnly: true });
    expect(hits.map((m) => m.spec)).toEqual(['anthropic:claude-sonnet', 'cortecs:flux']);
  });

  it('filters by type group', () => {
    expect(filterModels(models, { query: '', group: 'image', usableOnly: false }).map((m) => m.spec))
      .toEqual(['cortecs:flux']);
    const audio = [model('a:say', { kind: 'tts' }), model('a:hear', { kind: 'stt' }), model('a:chat')];
    expect(filterModels(audio, { query: '', group: 'audio', usableOnly: false }).map((m) => m.spec))
      .toEqual(['a:say', 'a:hear']);
  });
});

describe('kindGroupOf', () => {
  it('folds the fine kinds into the switch groups', () => {
    expect(['chat', 'embedding', 'tts', 'stt', 'au', 'music', 'image', 'decision'].map(kindGroupOf))
      .toEqual(['llm', 'llm', 'audio', 'audio', 'audio', 'audio', 'image', 'decision']);
  });

  it('keeps an unknown kind findable under other', () => {
    expect(kindGroupOf('video')).toBe('other');
  });
});

describe('groupCounts', () => {
  it('counts per group in switch order and leaves empty groups out', () => {
    const more = [...models, model('a:tts', { kind: 'tts' }), model('a:au', { kind: 'au' })];
    expect(groupCounts(more)).toEqual([
      { group: 'llm', count: 3 },
      { group: 'audio', count: 2 },
      { group: 'image', count: 1 },
    ]);
  });
});

describe('sortModels', () => {
  const priced = (spec: string, output?: number) =>
    model(spec, output === undefined
      ? {}
      : { pricing: { inputPerMTok: 1, outputPerMTok: output, currency: 'USD' } });

  it('sorts by model name, then provider', () => {
    const list = [priced('b:zeta'), priced('b:alpha'), priced('a:alpha')];
    expect(sortModels(list, 'name', 'asc').map((m) => m.spec)).toEqual(['a:alpha', 'b:alpha', 'b:zeta']);
    expect(sortModels(list, 'name', 'desc').map((m) => m.spec)).toEqual(['b:zeta', 'b:alpha', 'a:alpha']);
  });

  it('sorts by output price and keeps unpriced models last in both directions', () => {
    const list = [priced('a:mid', 5), priced('a:free'), priced('a:cheap', 0.5), priced('a:dear', 15)];
    expect(sortModels(list, 'price', 'asc').map((m) => m.spec)).toEqual(['a:cheap', 'a:mid', 'a:dear', 'a:free']);
    expect(sortModels(list, 'price', 'desc').map((m) => m.spec)).toEqual(['a:dear', 'a:mid', 'a:cheap', 'a:free']);
  });

  it('does not reorder the input', () => {
    const list = [priced('b:x'), priced('a:x')];
    sortModels(list, 'name', 'asc');
    expect(list.map((m) => m.spec)).toEqual(['b:x', 'a:x']);
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
