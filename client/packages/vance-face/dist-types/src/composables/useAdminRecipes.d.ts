import { type Ref } from 'vue';
import type { RecipeDto, RecipeWriteRequest } from '@vance/generated';
/**
 * Read + write recipes at tenant scope (when {@code projectId} is null
 * or empty) or project scope (when set). The "effective" list walks
 * Project → Tenant → Bundled and returns one entry per name with a
 * {@code source} field describing where the visible copy lives.
 */
export declare function useAdminRecipes(): {
    recipes: Ref<RecipeDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    loadEffective: (projectId: string | null) => Promise<void>;
    upsert: (projectId: string | null, name: string, body: RecipeWriteRequest) => Promise<RecipeDto>;
    remove: (projectId: string | null, name: string) => Promise<void>;
};
//# sourceMappingURL=useAdminRecipes.d.ts.map