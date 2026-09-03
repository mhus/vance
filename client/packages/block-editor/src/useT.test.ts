import { describe, expect, it } from 'vitest';
import { translatorFor } from './useT';

/**
 * The slash menu resolves its labels outside any component's setup, so it
 * reaches `$t` through the app context Tiptap copied onto the editor. These
 * are the two states that context can be in.
 */
describe('translatorFor', () => {
  it('calls the host translator when the app context carries one', () => {
    const t = translatorFor({
      config: { globalProperties: { $t: (key: string) => `translated:${key}` } },
    });

    expect(t('blockEditor.slash.image.title')).toBe('translated:blockEditor.slash.image.title');
  });

  it('forwards named values', () => {
    const t = translatorFor({
      config: {
        globalProperties: {
          $t: (key: string, named?: Record<string, unknown>) => `${key}/${named?.size}`,
        },
      },
    });

    expect(t('blockEditor.bubble.width', { size: 'half' })).toBe('blockEditor.bubble.width/half');
  });

  it('echoes the key when no i18n is installed', () => {
    // The visible key is the point: a silent English fallback would hide a
    // missing bundle registration until a German-speaking user finds it.
    expect(translatorFor(undefined)('blockEditor.slash.image.title'))
      .toBe('blockEditor.slash.image.title');
    expect(translatorFor({ config: {} })('x.y')).toBe('x.y');
  });

  it('echoes the key when the host returns a non-string', () => {
    const t = translatorFor({ config: { globalProperties: { $t: () => ({ not: 'a string' }) } } });

    expect(t('x.y')).toBe('x.y');
  });
});
