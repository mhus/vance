import { describe, expect, it } from 'vitest';
import { createI18n } from 'vue-i18n';
import en from './en';

/**
 * Every English message of the run view and the workflow drawing must compile in
 * vue-i18n. A bare `{…}` is read as a placeholder and a bare `@` as a link, and either
 * breaks the message at run time — neither `vue-tsc` nor a lookup test sees it. The
 * walk covers new messages in these namespaces without being told about them.
 *
 * Other namespaces are left alone on purpose: a few older messages elsewhere carry a
 * bare `@` that this check would flag (`chat.autoAi.*`,
 * `scopes.kit.dialog.commitMessageHelp`), and changing them would mark their
 * translations stale.
 */
const CHECKED_PREFIXES = ['runs.', 'documents.workflowView.', 'insights.workflows.'];

function collect(node: unknown, prefix: string, out: Array<[string, string]>): void {
  if (typeof node === 'string') {
    out.push([prefix, node]);
  } else if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
      collect(value, prefix ? `${prefix}.${key}` : key, out);
    }
  }
}

describe('English messages', () => {
  it('all compile in vue-i18n', () => {
    const messages: Array<[string, string]> = [];
    collect(en, '', messages);
    const checked = messages.filter(([key]) => CHECKED_PREFIXES.some((prefix) => key.startsWith(prefix)));
    expect(checked.length).toBeGreaterThan(60);

    const errors: string[] = [];
    const i18n = createI18n({
      legacy: false,
      locale: 'en',
      messages: { en: en as never },
      missingWarn: false,
      fallbackWarn: false,
      warnHtmlMessage: false,
      onWarn: () => {},
      missing: () => '',
    });
    const t = i18n.global.t as (key: string, named?: Record<string, unknown>) => string;
    for (const [key] of checked) {
      try {
        t(key, new Proxy({}, { get: () => 'x' }) as Record<string, unknown>);
      } catch (e) {
        errors.push(`${key}: ${e instanceof Error ? e.message : String(e)}`);
      }
    }
    expect(errors).toEqual([]);
  });
});
