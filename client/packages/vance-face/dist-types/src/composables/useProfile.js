import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * REST loader / mutator for the self-service profile page. Talks to
 * {@code /brain/{tenant}/profile} — that endpoint always operates on
 * the caller's own user, so no username plumbing is needed here.
 *
 * <p>Every mutator updates the local {@link profile} ref with the
 * fresh server response so the UI stays in sync without a manual
 * reload.
 */
export function useProfile() {
    const profile = ref(null);
    const loading = ref(false);
    const error = ref(null);
    async function load() {
        loading.value = true;
        error.value = null;
        try {
            profile.value = await brainFetch('GET', 'profile');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load profile.';
            profile.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    async function saveIdentity(patch) {
        loading.value = true;
        error.value = null;
        try {
            profile.value = await brainFetch('PUT', 'profile', { body: patch });
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save profile.';
            throw e;
        }
        finally {
            loading.value = false;
        }
    }
    async function saveSetting(key, value) {
        loading.value = true;
        error.value = null;
        try {
            const body = { value: value ?? undefined };
            profile.value = await brainFetch('PUT', `profile/settings/${encodeURIComponent(key)}`, { body });
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save setting.';
            throw e;
        }
        finally {
            loading.value = false;
        }
    }
    async function deleteSetting(key) {
        loading.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `profile/settings/${encodeURIComponent(key)}`);
            // Reload so the {@code webUiSettings} map matches what the
            // server now thinks. Cheaper than reasoning about stale keys.
            await load();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete setting.';
            throw e;
        }
        finally {
            loading.value = false;
        }
    }
    return { profile, loading, error, load, saveIdentity, saveSetting, deleteSetting };
}
//# sourceMappingURL=useProfile.js.map