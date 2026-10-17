<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { CatalogModelInsightsDto, ModelDetailInsightsDto } from '@vance/generated';
import { ModelAliasScope, ModelCatalogLayer } from '@vance/generated';
import {
  VAlert,
  VBadge,
  VButton,
  VCheckbox,
  VEmptyState,
  VGroup,
  VGroupRow,
  VIcon,
  VInput,
  VModal,
  VSelect,
} from '@/components';
import { useModelInsights } from '@/composables/useProjectInsights';
import { formatContext, formatPrice } from '@/chat/modelPicker';
import { aliasesTargeting, filterModels, kindCounts, verdictOf, type ModelVerdict } from './modelInsights';

/**
 * Every model the project's merged catalog knows — all modalities, nothing
 * filtered — with the verdicts that decide whether the model picker offers
 * it, the `ai.alias.*` settings resolved in this project, and per model the
 * catalog layer each field comes from. The catalog differs per project
 * (project layers, alias settings, credentials), so everything here is for
 * the project selected in the sidebar.
 */
const props = defineProps<{ projectId: string | null }>();

const { t } = useI18n();
const state = useModelInsights();

function reload(): void {
  if (props.projectId) state.load(props.projectId);
}

// ─── Filter ────────────────────────────────────────────────────────────
const ALL = 'all';
const query = ref('');
const kind = ref<string>(ALL);
const usableOnly = ref(false);

const models = computed<CatalogModelInsightsDto[]>(() => state.data.value?.models ?? []);
const aliases = computed(() => state.data.value?.aliases ?? []);

const kindOptions = computed(() => [
  { value: ALL, label: t('insights.models.allKinds', { n: models.value.length }) },
  ...kindCounts(models.value).map((k) => ({
    value: k.kind,
    label: t('insights.models.kindOption', { kind: k.kind, n: k.count }),
  })),
]);

const shown = computed(() =>
  filterModels(models.value, {
    query: query.value,
    kind: kind.value === ALL ? '' : kind.value,
    usableOnly: usableOnly.value,
  }),
);

// ─── Labels ────────────────────────────────────────────────────────────
function layerLabel(layer: ModelCatalogLayer): string {
  return t(`insights.models.layer.${layer}`);
}

function layerVariant(layer: ModelCatalogLayer): 'primary' | 'neutral' {
  return layer === ModelCatalogLayer.PROJECT || layer === ModelCatalogLayer.PROJECT_AUTO ? 'primary' : 'neutral';
}

const VERDICT: Record<Exclude<ModelVerdict, 'other'>, {
  label: string;
  title: string;
  variant: 'success' | 'warning' | 'neutral';
}> = {
  usable: { label: 'statusUsable', title: 'statusUsableTitle', variant: 'success' },
  'no-instance': { label: 'statusNoInstance', title: 'statusNoInstanceTitle', variant: 'warning' },
  'not-allowed': { label: 'statusNotAllowed', title: 'statusNotAllowedTitle', variant: 'neutral' },
};

function verdict(m: CatalogModelInsightsDto) {
  const v = verdictOf(m);
  return v === 'other' ? null : VERDICT[v];
}

function contextOf(m: CatalogModelInsightsDto): string {
  return m.contextWindow ? formatContext(m.contextWindow) : '—';
}

// ─── Detail ────────────────────────────────────────────────────────────
const detailOpen = ref(false);
const detailSpec = ref<string | null>(null);
const detail = ref<ModelDetailInsightsDto | null>(null);
const detailError = ref<string | null>(null);

async function openDetail(m: CatalogModelInsightsDto): Promise<void> {
  if (!props.projectId) return;
  detailSpec.value = m.spec;
  detail.value = null;
  detailError.value = null;
  detailOpen.value = true;
  try {
    const out = await state.loadDetail(props.projectId, m.spec);
    if (detailSpec.value === m.spec) detail.value = out;
  } catch (e) {
    if (detailSpec.value === m.spec) detailError.value = e instanceof Error ? e.message : String(e);
  }
}

function closeDetail(): void {
  detailOpen.value = false;
  detailSpec.value = null;
}

const detailAliases = computed(() =>
  detailSpec.value ? aliasesTargeting(aliases.value, detailSpec.value) : [],
);

// Last in setup: `immediate` runs right away and touches the detail refs above.
watch(
  () => props.projectId,
  (next) => {
    closeDetail();
    if (next) state.load(next);
    else state.clear();
  },
  { immediate: true },
);
</script>

<template>
  <div class="flex flex-col gap-4 p-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.models.pickProject') }}
    </div>

    <div v-else-if="state.loading.value && !state.data.value" class="text-sm opacity-60">
      {{ $t('insights.models.loading') }}
    </div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.data.value">
      <!-- ─── Toolbar ─── -->
      <div class="flex flex-wrap items-end gap-3 text-sm">
        <!-- Widths on wrappers: VInput/VSelect carry `w-full` themselves. -->
        <div class="w-64">
          <VInput
            v-model="query"
            :label="$t('insights.models.search')"
            :placeholder="$t('insights.models.searchPlaceholder')"
          />
        </div>
        <div class="w-48">
          <VSelect v-model="kind" :label="$t('insights.models.kind')" :options="kindOptions" />
        </div>
        <div class="pb-2">
          <VCheckbox v-model="usableOnly" :label="$t('insights.models.usableOnly')" />
        </div>
        <div class="ml-auto flex items-center gap-3 pb-1">
          <span class="text-xs opacity-60">
            {{ $t('insights.models.count', { shown: shown.length, total: models.length }) }}
          </span>
          <VButton variant="secondary" size="sm" icon="refresh" :disabled="state.loading.value" @click="reload">
            {{ $t('insights.models.reload') }}
          </VButton>
        </div>
      </div>

      <!-- ─── Project facts ─── -->
      <VGroup :label="$t('insights.models.projectHeading')">
        <VGroupRow
          :label="$t('insights.models.defaultModel')"
          :description="state.data.value.defaultModelError ?? $t('insights.models.defaultModelHint')"
          :value="state.data.value.defaultModel ?? $t('insights.models.defaultMissing')"
          :danger="!state.data.value.defaultModel"
        />
        <VGroupRow
          :label="$t('insights.models.allowlist')"
          :description="$t('insights.models.allowlistHint')"
          :value="state.data.value.chatAllowlist"
        />
      </VGroup>

      <!-- ─── Aliases ─── -->
      <section class="flex flex-col gap-1">
        <h3 class="text-sm font-medium">{{ $t('insights.models.aliasesHeading') }}</h3>
        <p class="text-xs opacity-60">{{ $t('insights.models.aliasesHint') }}</p>
        <div v-if="aliases.length === 0" class="text-xs opacity-60 py-2">
          {{ $t('insights.models.noAliases') }}
        </div>
        <!-- Plain Tailwind, no DaisyUI `table` classes (web-ui.md §7.4). -->
        <table v-else class="w-full text-sm">
          <thead class="text-xs opacity-60">
            <tr class="text-left">
              <th class="font-normal px-3 py-1">{{ $t('insights.models.colAlias') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.models.colTarget') }}</th>
              <th class="font-normal px-3 py-1 w-24">{{ $t('insights.models.colDefinedIn') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.models.colResolves') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in aliases" :key="a.alias" class="border-t border-base-content/5 align-top">
              <td class="px-3 py-1 font-mono">{{ a.alias }}</td>
              <td class="px-3 py-1 font-mono text-xs opacity-80 break-all">{{ a.target }}</td>
              <td class="px-3 py-1 text-xs">
                <VBadge size="xs" :variant="a.definedIn === ModelAliasScope.PROJECT ? 'primary' : 'neutral'">
                  {{ $t(`insights.models.definedIn.${a.definedIn}`) }}
                </VBadge>
              </td>
              <td class="px-3 py-1 text-xs">
                <div v-if="a.resolved" class="flex flex-wrap items-center gap-1 font-mono">
                  <template v-for="step in a.chain.slice(1)" :key="step">
                    <span class="opacity-60">{{ step }}</span>
                    <VIcon name="arrow-right" class="opacity-40" />
                  </template>
                  <span>{{ a.resolved }}</span>
                  <span v-if="a.viaTenantDefault" class="font-sans opacity-60">
                    ({{ $t('insights.models.viaTenantDefault') }})
                  </span>
                </div>
                <div v-else class="text-(--v-danger) break-all">{{ a.error }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ─── Catalog ─── -->
      <section class="flex flex-col gap-1">
        <h3 class="text-sm font-medium">{{ $t('insights.models.modelsHeading') }}</h3>
        <p class="text-xs opacity-60">{{ $t('insights.models.modelsHint') }}</p>
        <VEmptyState
          v-if="models.length === 0"
          :headline="$t('insights.models.emptyHeadline')"
          :body="$t('insights.models.emptyBody')"
        />
        <table v-else class="w-full text-sm">
          <thead class="text-xs opacity-60">
            <tr class="text-left">
              <th class="font-normal px-3 py-1">{{ $t('insights.models.colModel') }}</th>
              <th class="font-normal px-3 py-1 w-16">{{ $t('insights.models.colKind') }}</th>
              <th class="font-normal px-3 py-1 w-16">{{ $t('insights.models.colSize') }}</th>
              <th class="font-normal px-3 py-1 w-20 text-right">{{ $t('insights.models.colContext') }}</th>
              <th class="font-normal px-3 py-1 w-36 text-right">{{ $t('insights.models.colPrice') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.models.colCapabilities') }}</th>
              <th class="font-normal px-3 py-1 w-36">{{ $t('insights.models.colOrigin') }}</th>
              <th class="font-normal px-3 py-1 w-28">{{ $t('insights.models.colStatus') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="shown.length === 0">
              <td colspan="8" class="opacity-60 text-center py-4">{{ $t('insights.models.noMatch') }}</td>
            </tr>
            <tr
              v-for="m in shown"
              :key="m.spec"
              class="model-row border-t border-base-content/5 cursor-pointer"
              tabindex="0"
              @click="openDetail(m)"
              @keydown.enter="openDetail(m)"
            >
              <td class="px-3 py-1">
                <span class="font-mono">
                  <span class="opacity-60">{{ m.providerInstance }}:</span>{{ m.modelName }}
                </span>
                <VBadge
                  v-if="m.discovered"
                  size="xs"
                  class="ml-1"
                  :title="$t('insights.models.discoveredTitle')"
                >{{ $t('insights.models.discovered') }}</VBadge>
              </td>
              <td class="px-3 py-1 text-xs opacity-80">{{ m.kind }}</td>
              <td class="px-3 py-1 text-xs opacity-80">{{ m.size?.toLowerCase() ?? '—' }}</td>
              <td class="px-3 py-1 text-xs text-right tabular-nums">{{ contextOf(m) }}</td>
              <td class="px-3 py-1 text-xs text-right tabular-nums">{{ formatPrice(m.pricing) ?? '—' }}</td>
              <td class="px-3 py-1 text-xs opacity-80">
                {{ m.capabilities.length ? m.capabilities.join(', ') : '—' }}
              </td>
              <td class="px-3 py-1 text-xs">
                <VBadge size="xs" :variant="layerVariant(m.origin)">{{ layerLabel(m.origin) }}</VBadge>
              </td>
              <td class="px-3 py-1 text-xs">
                <VBadge
                  v-if="verdict(m)"
                  size="xs"
                  :variant="verdict(m)!.variant"
                  :title="$t(`insights.models.${verdict(m)!.title}`)"
                >{{ $t(`insights.models.${verdict(m)!.label}`) }}</VBadge>
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <!-- ─── Detail ─── -->
    <VModal
      :model-value="detailOpen"
      size="lg"
      :title="$t('insights.models.detailTitle', { spec: detailSpec ?? '' })"
      :description="$t('insights.models.detailDescription')"
      @update:model-value="(open: boolean) => { if (!open) closeDetail(); }"
    >
      <VAlert v-if="detailError" variant="error">{{ detailError }}</VAlert>
      <div v-else-if="!detail" class="text-sm opacity-60">{{ $t('insights.models.detailLoading') }}</div>
      <div v-else class="flex flex-col gap-4">
        <div v-if="detailAliases.length" class="text-sm">
          <span class="opacity-60">{{ $t('insights.models.detailAliases') }}:</span>
          <span class="font-mono ml-1">{{ detailAliases.join(', ') }}</span>
        </div>

        <section class="flex flex-col gap-1">
          <h3 class="text-sm font-medium">{{ $t('insights.models.detailFields') }}</h3>
          <table class="w-full text-sm">
            <thead class="text-xs opacity-60">
              <tr class="text-left">
                <th class="font-normal px-3 py-1 w-48">{{ $t('insights.models.colField') }}</th>
                <th class="font-normal px-3 py-1">{{ $t('insights.models.colValue') }}</th>
                <th class="font-normal px-3 py-1 w-40">{{ $t('insights.models.colLayer') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in detail.fields" :key="f.name" class="border-t border-base-content/5 align-top">
                <td class="px-3 py-1 font-mono text-xs">{{ f.name }}</td>
                <td class="px-3 py-1 font-mono text-xs break-all">{{ f.value }}</td>
                <td class="px-3 py-1 text-xs">
                  <VBadge size="xs" :variant="layerVariant(f.layer)">{{ layerLabel(f.layer) }}</VBadge>
                </td>
              </tr>
            </tbody>
          </table>
        </section>

        <section class="flex flex-col gap-1">
          <h3 class="text-sm font-medium">
            {{ $t('insights.models.detailProvider', { instance: detail.model.providerInstance }) }}
          </h3>
          <div v-if="detail.providerFields.length === 0" class="text-xs opacity-60">
            {{ $t('insights.models.detailNoProvider') }}
          </div>
          <table v-else class="w-full text-sm">
            <tbody>
              <tr v-for="f in detail.providerFields" :key="f.name" class="border-t border-base-content/5 align-top">
                <td class="px-3 py-1 font-mono text-xs w-48">{{ f.name }}</td>
                <td class="px-3 py-1 font-mono text-xs break-all">{{ f.value }}</td>
                <td class="px-3 py-1 text-xs w-40">
                  <VBadge size="xs" :variant="layerVariant(f.layer)">{{ layerLabel(f.layer) }}</VBadge>
                </td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>

      <template #actions>
        <VButton variant="secondary" @click="closeDetail">{{ $t('insights.models.close') }}</VButton>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
.model-row:hover,
.model-row:focus-visible {
  background: var(--v-fill);
  outline: none;
}
</style>
