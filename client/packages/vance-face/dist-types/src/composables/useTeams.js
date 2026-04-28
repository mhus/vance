import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Reactive wrapper around `GET /brain/{tenant}/teams` — returns
 * the teams the JWT-authenticated user is a member of.
 *
 * <p>Member-username lists ride along on each team summary, so the
 * inbox UI can compute "team inbox" filters (assignedTo IN
 * members ∖ self) without a follow-up round-trip.
 */
export function useTeams() {
    const teams = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            const data = await brainFetch('GET', 'teams');
            teams.value = data.teams ?? [];
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load teams.';
            teams.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { teams, loading, error, reload };
}
//# sourceMappingURL=useTeams.js.map