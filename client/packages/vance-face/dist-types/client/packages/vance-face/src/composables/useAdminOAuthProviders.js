import { ref } from 'vue';
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
export function useAdminOAuthProviders() {
    const providers = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            providers.value = await brainFetch('GET', 'admin/oauth/providers');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load OAuth providers.';
            providers.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function get(providerId) {
        return brainFetch('GET', `admin/oauth/providers/${encodeURIComponent(providerId)}`);
    }
    async function upsert(providerId, body) {
        busy.value = true;
        error.value = null;
        try {
            const saved = await brainFetch('PUT', `admin/oauth/providers/${encodeURIComponent(providerId)}`, { body });
            await reload();
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save OAuth provider.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function remove(providerId) {
        busy.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `admin/oauth/providers/${encodeURIComponent(providerId)}`);
            await reload();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete OAuth provider.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { providers, loading, busy, error, reload, get, upsert, remove };
}
//# sourceMappingURL=useAdminOAuthProviders.js.map