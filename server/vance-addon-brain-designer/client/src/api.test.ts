import { beforeEach, describe, expect, it } from 'vitest';
import { configurePlatform, StorageKeys } from '@vance/shared';
import { designContentUrl } from './api';

/**
 * The URL builder is security-relevant surface: the token must end up
 * as a path segment (never a query parameter — the first relative
 * sub-resource URL would drop it), and every caller-controlled segment
 * must be encoded per segment.
 */
describe('designContentUrl', () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    store.set(StorageKeys.identityTenantId, 'acme');
    configurePlatform({
      storage: {
        secureStore: {
          get: () => null,
          set: () => undefined,
          remove: () => undefined,
        },
        prefsStore: {
          get: (k: string) => store.get(k) ?? null,
          set: (k: string, v: string) => void store.set(k, v),
          remove: (k: string) => void store.delete(k),
        },
      },
      rest: {
        baseUrl: 'https://brain.example',
        authMode: 'cookie',
        refreshAccess: async () => false,
        onUnauthorized: () => undefined,
      },
    });
  });

  it('builds the path-token content route with a trailing design slash', () => {
    const url = designContentUrl('doc 1', 'eyJhbGci.x', 'landing', '');
    expect(url).toBe(
      'https://brain.example/brain/acme/addon/designer/content/doc%201/eyJhbGci.x/landing/',
    );
  });

  it('appends the inner path per-segment encoded', () => {
    const url = designContentUrl('doc1', 'tok', 'landing', 'assets/my file.css');
    expect(url).toBe(
      'https://brain.example/brain/acme/addon/designer/content/doc1/tok/landing/assets/my%20file.css',
    );
  });

  it('returns empty without a tenant', () => {
    // The builder must never emit a partial URL an iframe would load
    // and fail on opaquely — the component shows its error state instead.
    configurePlatform({
      storage: {
        secureStore: {
          get: () => null,
          set: () => undefined,
          remove: () => undefined,
        },
        prefsStore: {
          get: () => null,
          set: () => undefined,
          remove: () => undefined,
        },
      },
      rest: {
        baseUrl: '',
        authMode: 'cookie',
        refreshAccess: async () => false,
        onUnauthorized: () => undefined,
      },
    });
    expect(designContentUrl('doc1', 'tok', 'landing', '')).toBe('');
  });
});
