import { type Ref } from 'vue';
import type { OAuthProviderAdminDto, OAuthProviderWriteRequest } from '@vance/generated';
/**
 * Admin-side CRUD for OAuth provider configurations. Talks to
 * {@code /brain/{tenant}/admin/oauth/providers}.
 *
 * <p>{@code clientSecret} handling follows the
 * {@link OAuthProviderWriteRequest} three-state contract:
 * {@code undefined}/{@code null} = leave alone, {@code ""} = remove,
 * any string = set.
 */
export declare function useAdminOAuthProviders(): {
    providers: Ref<OAuthProviderAdminDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    get: (providerId: string) => Promise<OAuthProviderAdminDto>;
    upsert: (providerId: string, body: OAuthProviderWriteRequest) => Promise<OAuthProviderAdminDto>;
    remove: (providerId: string) => Promise<void>;
};
//# sourceMappingURL=useAdminOAuthProviders.d.ts.map