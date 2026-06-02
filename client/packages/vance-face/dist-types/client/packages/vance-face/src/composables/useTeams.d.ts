import { type Ref } from 'vue';
import type { TeamSummary } from '@vance/generated';
/**
 * Reactive wrapper around `GET /brain/{tenant}/teams` — returns
 * the teams the JWT-authenticated user is a member of.
 *
 * <p>Member-username lists ride along on each team summary, so the
 * inbox UI can compute "team inbox" filters (assignedTo IN
 * members ∖ self) without a follow-up round-trip.
 */
export declare function useTeams(): {
    teams: Ref<TeamSummary[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
};
//# sourceMappingURL=useTeams.d.ts.map