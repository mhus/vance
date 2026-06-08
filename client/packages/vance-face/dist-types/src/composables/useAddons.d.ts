import { type Ref } from 'vue';
import type { AddonInsightDto } from '@vance/generated';
/**
 * REST loader for the Addons insights endpoint. Returns one row per
 * addon in the system (enabled and disabled alike) combined with the
 * unpack state under {@code /shared/addons/}. Read-only; the Addons
 * tab is a debug view, not an admin control surface.
 */
export interface UseAddonInsights {
    addons: Ref<AddonInsightDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: () => Promise<void>;
    clear: () => void;
}
export declare function useAddonInsights(): UseAddonInsights;
//# sourceMappingURL=useAddons.d.ts.map