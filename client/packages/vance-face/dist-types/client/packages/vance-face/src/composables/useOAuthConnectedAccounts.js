import { ref } from 'vue';
import { brainBaseUrl, brainFetch, getTenantId } from '@vance/shared';
/**
 * User-facing OAuth state — lists every provider configured for the
 * current tenant with a flag telling whether the calling user has
 * already connected. Connect navigates the browser to the init
 * endpoint (302-driven OAuth dance); disconnect deletes the user's
 * stored tokens.
 */
export function useOAuthConnectedAccounts() {
    const providers = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            providers.value = await brainFetch('GET', 'oauth/providers');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load OAuth providers.';
            providers.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function connect(providerId, returnTo) {
        // The init endpoint redirects the user-agent to the provider; we
        // can't follow that through fetch (CORS) — full-page navigation is
        // the right move.
        const tenant = getTenantId();
        if (!tenant) {
            error.value = 'No tenant configured — user is not logged in.';
            return;
        }
        const path = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/oauth/${encodeURIComponent(providerId)}/init`;
        const target = returnTo
            ? `${path}?returnTo=${encodeURIComponent(returnTo)}`
            : path;
        window.location.assign(target);
    }
    async function disconnect(providerId) {
        busy.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `oauth/${encodeURIComponent(providerId)}/connection`);
            await reload();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to disconnect provider.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { providers, loading, busy, error, reload, connect, disconnect };
}
//# sourceMappingURL=useOAuthConnectedAccounts.js.map