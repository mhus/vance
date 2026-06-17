import { type Ref } from 'vue';
import type { EffectiveRecipeDto, EffectiveToolDto, ToolHealthEntryDto, ZarniwoopInsightsDto } from '@vance/generated';
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
export interface UseZarniwoopInsights {
    instances: Ref<ZarniwoopInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (projectId: string) => Promise<void>;
    clear: () => void;
    setOverride: (projectId: string, instanceId: string, enabled: boolean) => Promise<void>;
    clearOverride: (projectId: string, instanceId: string) => Promise<void>;
}
export declare function useZarniwoopInsights(): UseZarniwoopInsights;
/**
 * Tool-Health + active cooldowns for a project. Pairs with the
 * ProjectToolsTab so each tool row can show a status badge and an
 * expandable cooldown list. {@code clearCooldown} maps to the
 * admin-only clear-cooldown endpoint and triggers a reload.
 */
export interface UseToolHealth {
    entries: Ref<ToolHealthEntryDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (projectId: string) => Promise<void>;
    clear: () => void;
    clearCooldown: (projectId: string, toolName: string, errorSignature: string, userId: string | null) => Promise<void>;
}
export declare function useToolHealth(): UseToolHealth;
//# sourceMappingURL=useProjectInsights.d.ts.map