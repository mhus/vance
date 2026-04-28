import { ref, type Ref } from 'vue';
import type { TeamListResponse, TeamSummary } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Reactive wrapper around `GET /brain/{tenant}/teams` — returns
 * the teams the JWT-authenticated user is a member of.
 *
 * <p>Member-username lists ride along on each team summary, so the
 * inbox UI can compute "team inbox" filters (assignedTo IN
 * members ∖ self) without a follow-up round-trip.
 */
export function useTeams(): {
  teams: Ref<TeamSummary[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
} {
  const teams = ref<TeamSummary[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const data = await brainFetch<TeamListResponse>('GET', 'teams');
      teams.value = data.teams ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load teams.';
      teams.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { teams, loading, error, reload };
}
