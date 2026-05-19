import { ref, type Ref } from 'vue';
import type {
  ToolTemplateApplyRequestDto,
  ToolTemplateApplyResultDto,
  ToolTemplateCatalogDto,
  ToolTemplateCatalogEntry,
  ToolTemplateDescriptorDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

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
export function useToolTemplates(): {
  catalog: Ref<ToolTemplateCatalogEntry[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadCatalog: () => Promise<void>;
  describe: (name: string) => Promise<ToolTemplateDescriptorDto>;
  apply: (
    name: string,
    body: ToolTemplateApplyRequestDto,
  ) => Promise<ToolTemplateApplyResultDto>;
} {
  const catalog = ref<ToolTemplateCatalogEntry[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadCatalog(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const res = await brainFetch<ToolTemplateCatalogDto>(
        'GET',
        'admin/tool-templates/catalog',
      );
      catalog.value = (res.templates ?? []).slice();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load catalog.';
      catalog.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function describe(name: string): Promise<ToolTemplateDescriptorDto> {
    busy.value = true;
    error.value = null;
    try {
      return await brainFetch<ToolTemplateDescriptorDto>(
        'GET',
        `admin/tool-templates/${encodeURIComponent(name)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to describe template.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function apply(
    name: string,
    body: ToolTemplateApplyRequestDto,
  ): Promise<ToolTemplateApplyResultDto> {
    busy.value = true;
    error.value = null;
    try {
      return await brainFetch<ToolTemplateApplyResultDto>(
        'POST',
        `admin/tool-templates/${encodeURIComponent(name)}/apply`,
        { body },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Apply failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { catalog, loading, busy, error, loadCatalog, describe, apply };
}
