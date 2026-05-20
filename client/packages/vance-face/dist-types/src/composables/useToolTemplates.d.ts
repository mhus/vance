import { type Ref } from 'vue';
import type { ToolTemplateApplyRequestDto, ToolTemplateApplyResultDto, ToolTemplateCatalogEntry, ToolTemplateDescriptorDto } from '@vance/generated';
/**
 * State + actions for the tool-templates wizard. Three calls:
 *
 * - {@link loadCatalog}      — tenant-wide catalog from `_tenant/config/tool-templates.yaml`
 * - {@link describe}         — resolves one template (clones the kit + parses
 *                              `template.yaml`); call this when the user picks
 *                              a row before showing the form
 * - {@link apply}             — POST inputs, kit is applied, returns the
 *                              installer stats + postInstall hook
 */
export declare function useToolTemplates(): {
    catalog: Ref<ToolTemplateCatalogEntry[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    loadCatalog: () => Promise<void>;
    describe: (name: string) => Promise<ToolTemplateDescriptorDto>;
    apply: (name: string, body: ToolTemplateApplyRequestDto) => Promise<ToolTemplateApplyResultDto>;
};
//# sourceMappingURL=useToolTemplates.d.ts.map