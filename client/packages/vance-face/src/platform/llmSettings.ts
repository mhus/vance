// Whether the tenant has a default LLM at all. A fresh tenant often has
// none yet, and every chat turn then fails with a resolver error the user
// cannot place — the landing page warns ahead of that.
//
// Reads the `_tenant` defaults through `/settings/cascade` (no projectId).
// The brain resolves a model without an explicit spec from exactly this
// pair (`AiModelResolver.tenantDefault`), so both must be set.
//
// Deliberately NOT built on `loadProjectCascadeSettings`: that swallows a
// failed request into `{}`, which here would read as "not configured" and
// warn on every network hiccup. A failed check is `null` — unknown, no
// warning.

import { brainFetch } from '@vance/shared';

const KEY_PROVIDER = 'ai.default.provider';
const KEY_MODEL = 'ai.default.model';

/** `true`/`false` when the check answered, `null` when it could not. */
export async function isTenantLlmConfigured(): Promise<boolean | null> {
  const params = new URLSearchParams();
  params.append('key', KEY_PROVIDER);
  params.append('key', KEY_MODEL);
  try {
    const values = await brainFetch<Record<string, string>>(
      'GET', `settings/cascade?${params.toString()}`);
    return !!values[KEY_PROVIDER]?.trim() && !!values[KEY_MODEL]?.trim();
  } catch (e) {
    console.warn('isTenantLlmConfigured failed', e);
    return null;
  }
}
