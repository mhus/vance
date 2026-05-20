import { type Ref } from 'vue';
import type { ToolTemplateAppliedStateDto, ToolTemplateApplyRequestDto, ToolTemplateApplyResultDto, ToolTemplateCatalogEntry, ToolTemplateDescriptorDto } from '@vance/generated';
/**
 * State + actions for the tool-templates wizard:
 *
 * - {@link loadCatalog}  — tenant-wide catalog from `_tenant/config/tool-templates.yaml`
 * - {@link describe}     — resolves one template (clones the kit + parses
 *                          `template.yaml`); call this when the user picks
 *                          a row before showing the form
 * - {@link loadApplied}  — last applied state for (template, projectId);
 *                          returns `null` when the template has never been
 *                          applied (so the wizard can fall back to the
 *                          template's declared defaults). PASSWORD inputs
 *                          are structurally absent from the response.
 * - {@link apply}        — POST inputs, kit is applied, returns the
 *                          installer stats + postInstall hook
 */
export declare function useToolTemplates(): {
    catalog: Ref<ToolTemplateCatalogEntry[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    loadCatalog: () => Promise<void>;
    describe: (name: string) => Promise<ToolTemplateDescriptorDto>;
    loadApplied: (name: string, projectId: string) => Promise<ToolTemplateAppliedStateDto | null>;
    apply: (name: string, body: ToolTemplateApplyRequestDto) => Promise<ToolTemplateApplyResultDto>;
};
//# sourceMappingURL=useToolTemplates.d.ts.map