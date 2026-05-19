import { ref, type Ref } from 'vue';
import type {
  OAuthProviderAdminDto,
  OAuthProviderWriteRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Admin-side CRUD for OAuth provider configurations. Talks to
 * {@code /brain/{tenant}/admin/oauth/providers}.
 *
 * <p>{@code clientSecret} handling follows the
 * {@link OAuthProviderWriteRequest} three-state contract:
 * {@code undefined}/{@code null} = leave alone, {@code ""} = remove,
 * any string = set.
 */
export function useAdminOAuthProviders(): {
  providers: Ref<OAuthProviderAdminDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  get: (providerId: string) => Promise<OAuthProviderAdminDto>;
  upsert: (providerId: string, body: OAuthProviderWriteRequest) => Promise<OAuthProviderAdminDto>;
  remove: (providerId: string) => Promise<void>;
} {
  const providers = ref<OAuthProviderAdminDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      providers.value = await brainFetch<OAuthProviderAdminDto[]>(
        'GET',
        'admin/oauth/providers',
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load OAuth providers.';
      providers.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function get(providerId: string): Promise<OAuthProviderAdminDto> {
    return brainFetch<OAuthProviderAdminDto>(
      'GET',
      `admin/oauth/providers/${encodeURIComponent(providerId)}`,
    );
  }

  async function upsert(
    providerId: string,
    body: OAuthProviderWriteRequest,
  ): Promise<OAuthProviderAdminDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<OAuthProviderAdminDto>(
        'PUT',
        `admin/oauth/providers/${encodeURIComponent(providerId)}`,
        { body },
      );
      await reload();
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save OAuth provider.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(providerId: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `admin/oauth/providers/${encodeURIComponent(providerId)}`,
      );
      await reload();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete OAuth provider.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { providers, loading, busy, error, reload, get, upsert, remove };
}
