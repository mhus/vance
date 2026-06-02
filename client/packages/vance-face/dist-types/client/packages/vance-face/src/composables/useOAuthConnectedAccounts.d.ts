import { type Ref } from 'vue';
import type { OAuthProviderListEntry } from '@vance/generated';
/**
 * User-facing OAuth state — lists every provider configured for the
 * current tenant with a flag telling whether the calling user has
 * already connected. Connect navigates the browser to the init
 * endpoint (302-driven OAuth dance); disconnect deletes the user's
 * stored tokens.
 */
export declare function useOAuthConnectedAccounts(): {
    providers: Ref<OAuthProviderListEntry[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    connect: (providerId: string, returnTo?: string) => void;
    disconnect: (providerId: string) => Promise<void>;
};
//# sourceMappingURL=useOAuthConnectedAccounts.d.ts.map