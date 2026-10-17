import { describe, expect, it } from 'vitest';
import type { ChatModelDto, ChatModelStateDto } from '@vance/generated';
import {
  RECIPE_DEFAULT,
  commandsFor,
  filterModels,
  formatContext,
  formatPrice,
  groupModels,
  initialSelection,
  selectionSupportsThinking,
  shortModelName,
} from './modelPicker';

function model(spec: string, thinking = false): ChatModelDto {
  const [providerInstance, modelName] = spec.split(':');
  return { spec, providerInstance, modelName, size: 'LARGE', thinking, contextWindow: 128000 };
}

const models = [model('anthropic:claude-sonnet', true), model('cortecs:deepseek-v4'), model('cortecs:glm-5', true)];

function state(over: Partial<ChatModelStateDto> = {}): ChatModelStateDto {
  return {
    model: 'anthropic:claude-sonnet',
    modelOverridden: false,
    recipeModel: 'anthropic:claude-sonnet',
    thinking: 'off',
    thinkingOverridden: false,
    modelSupportsThinking: true,
    ...over,
  };
}

describe('filterModels', () => {
  it('matches every term case-insensitively', () => {
    expect(filterModels(models, 'Cortecs GLM').map((m) => m.spec)).toEqual(['cortecs:glm-5']);
  });
  it('returns everything for a blank filter', () => {
    expect(filterModels(models, '  ')).toHaveLength(3);
  });
});

describe('groupModels', () => {
  it('groups consecutive models by provider instance', () => {
    const groups = groupModels(models);
    expect(groups.map((g) => g.providerInstance)).toEqual(['anthropic', 'cortecs']);
    expect(groups[1].models).toHaveLength(2);
  });
});

describe('formatting', () => {
  it('formats input / output price per million tokens', () => {
    expect(formatPrice({ currency: 'USD', inputPerMTok: 3, outputPerMTok: 15 })).toBe('3.00 / 15.00 USD');
    expect(formatPrice({ currency: 'EUR', inputPerMTok: 0.05, outputPerMTok: 0.4 })).toBe('0.050 / 0.40 EUR');
    expect(formatPrice(undefined)).toBeNull();
  });
  it('shortens context windows', () => {
    expect(formatContext(128000)).toBe('128K');
    expect(formatContext(1_000_000)).toBe('1M');
    expect(formatContext(1_048_576)).toBe('1M');
  });
  it('drops the instance from the short name', () => {
    expect(shortModelName('cortecs:glm-5')).toBe('glm-5');
  });
});

describe('selection', () => {
  it('starts on the recipe default without an override', () => {
    expect(initialSelection(state())).toBe(RECIPE_DEFAULT);
    expect(initialSelection(state({ model: 'cortecs:glm-5', modelOverridden: true }))).toBe('cortecs:glm-5');
  });
  it('knows whether the selected model can reason', () => {
    expect(selectionSupportsThinking('cortecs:deepseek-v4', state(), models)).toBe(false);
    expect(selectionSupportsThinking('cortecs:glm-5', state(), models)).toBe(true);
    expect(selectionSupportsThinking(RECIPE_DEFAULT, state({ recipeModel: 'x:unknown' }), models)).toBe(true);
  });
});

describe('commandsFor', () => {
  it('sends nothing when nothing changed', () => {
    expect(commandsFor(state(), RECIPE_DEFAULT, 'off')).toEqual([]);
  });
  it('sets a new model, then the level', () => {
    expect(commandsFor(state(), 'cortecs:glm-5', 'high')).toEqual([
      { verb: 'model', text: 'cortecs:glm-5' },
      { verb: 'thinking', text: 'high' },
    ]);
  });
  it('clears an override when going back to the recipe default', () => {
    expect(commandsFor(state({ model: 'cortecs:glm-5', modelOverridden: true }), RECIPE_DEFAULT, 'off')).toEqual([
      { verb: 'model', text: 'clear' },
    ]);
  });
  it('pins the recipe model explicitly when picked from the list', () => {
    expect(commandsFor(state(), 'anthropic:claude-sonnet', 'off')).toEqual([
      { verb: 'model', text: 'anthropic:claude-sonnet' },
    ]);
  });
});
