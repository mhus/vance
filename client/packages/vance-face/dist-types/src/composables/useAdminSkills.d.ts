import { type Ref } from 'vue';
import type { SkillDto, SkillWriteRequest } from '@vance/generated';
/**
 * Read + write skills at one of three persistence scopes — pick by
 * setting exactly one of {@code projectId} / {@code userId}, or both
 * to {@code null} for tenant scope. The "effective" list overlays the
 * cascade BUNDLED → TENANT → PROJECT? → USER? and returns one entry
 * per name with a {@code scope} field.
 */
export declare function useAdminSkills(): {
    skills: Ref<SkillDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    loadEffective: (projectId: string | null, userId: string | null) => Promise<void>;
    upsert: (projectId: string | null, userId: string | null, name: string, body: SkillWriteRequest) => Promise<SkillDto>;
    remove: (projectId: string | null, userId: string | null, name: string) => Promise<void>;
};
//# sourceMappingURL=useAdminSkills.d.ts.map