import { type Ref } from 'vue';
import type { SettingDto, SettingType } from '@vance/generated';
/**
 * Read + write settings on a single scope (e.g. tenant + name, or
 * project + name). Wraps the existing {@code AdminSettingsController}
 * endpoints.
 */
export declare function useScopeSettings(): {
    settings: Ref<SettingDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    load: (referenceType: string, referenceId: string) => Promise<void>;
    upsert: (referenceType: string, referenceId: string, key: string, value: string | null, type: SettingType, description?: string | null) => Promise<SettingDto>;
    remove: (referenceType: string, referenceId: string, key: string) => Promise<void>;
    clear: () => void;
};
//# sourceMappingURL=useScopeSettings.d.ts.map