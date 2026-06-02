import { type Ref } from 'vue';
import type { ProfileDto, ProfileUpdateRequest } from '@vance/generated';
/**
 * REST loader / mutator for the self-service profile page. Talks to
 * {@code /brain/{tenant}/profile} — that endpoint always operates on
 * the caller's own user, so no username plumbing is needed here.
 *
 * <p>Every mutator updates the local {@link profile} ref with the
 * fresh server response so the UI stays in sync without a manual
 * reload.
 */
export declare function useProfile(): {
    profile: Ref<ProfileDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: () => Promise<void>;
    saveIdentity: (patch: ProfileUpdateRequest) => Promise<void>;
    saveSetting: (key: string, value: string | null) => Promise<void>;
    deleteSetting: (key: string) => Promise<void>;
};
//# sourceMappingURL=useProfile.d.ts.map