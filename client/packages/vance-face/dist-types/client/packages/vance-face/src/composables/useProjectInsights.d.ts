import { type Ref } from 'vue';
import type { EffectiveRecipeDto, EffectiveToolDto } from '@vance/generated';
/**
 * REST loaders for the project-level insight tabs (Recipes / Tools).
 * Both endpoints walk the cascade ({@code project → _vance →
 * built-in/bundled}) and tag each surviving entry with its
 * {@code source} attribute so the UI can render origin badges.
 *
 * One project at a time — the caller invokes {@code load(projectId)}
 * after the user has picked a project in the insights sidebar.
 */
export interface UseEffectiveRecipes {
    recipes: Ref<EffectiveRecipeDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (projectId: string) => Promise<void>;
    clear: () => void;
}
export interface UseEffectiveTools {
    tools: Ref<EffectiveToolDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (projectId: string) => Promise<void>;
    clear: () => void;
}
export declare function useEffectiveRecipes(): UseEffectiveRecipes;
export declare function useEffectiveTools(): UseEffectiveTools;
//# sourceMappingURL=useProjectInsights.d.ts.map