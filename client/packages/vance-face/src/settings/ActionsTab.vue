<script setup lang="ts">
/**
 * "Aktionen" tab — the operator commands the backend serves through
 * the AdminAction SPI (catalog refresh, model discovery, model
 * check, research check, …). The tab is deliberately generic: it
 * lists whatever {@code GET admin/actions} returns, filtered by the
 * scope row (TENANT_ONLY actions only in the tenant scope), runs the
 * chosen action and renders the generic result — one row per item,
 * the same shape for every action. A new backend action appears here
 * without a line of client code; localized title/description come
 * from the descriptor ({@code Map<lang, text>}, universal `en`
 * fallback — the same convention wizard texts use).
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton } from '@/components';
import { brainFetch, RestError } from '@vance/shared';
import type {
  AdminActionDto,
  AdminActionRunResultDto,
} from '@vance/generated';
import { useProfile } from '@/composables/useProfile';
import type { SettingsScopeKind } from './settingsUrl';

const props = defineProps<{
  /** The selected scope row — filters which actions the tab offers. */
  scopeKind: SettingsScopeKind;
  /** Project id when (and only when) the scope is a project. */
  projectId: string | null;
}>();

const { t, locale } = useI18n();

const actions = ref<AdminActionDto[] | null>(null);
const loadError = ref<string | null>(null);
/** Action id currently running — one at a time, results arrive in order. */
const busyId = ref<string | null>(null);
/** Result of the last run, shown until the next one starts or the scope flips. */
const result = ref<AdminActionRunResultDto | null>(null);
const runError = ref<string | null>(null);

const loading = computed(() => actions.value === null && loadError.value === null);

/** `Map<lang, text>` → display string, universal `en` fallback (wizard convention). */
function localized(texts: Record<string, string> | null | undefined): string {
  if (!texts) return '';
  const lang = (locale.value || 'en').slice(0, 2);
  return texts[lang] ?? texts.en ?? Object.values(texts)[0] ?? '';
}

/** Scope filter: TENANT_ONLY actions are hidden on project rows. */
const visibleActions = computed<AdminActionDto[]>(() =>
  (actions.value ?? []).filter(
    (a) => props.scopeKind !== 'project' || a.scope === 'TENANT_AND_PROJECT',
  ),
);

async function load(): Promise<void> {
  actions.value = null;
  loadError.value = null;
  result.value = null;
  runError.value = null;
  try {
    actions.value = await brainFetch<AdminActionDto[]>('GET', 'admin/actions');
  } catch (err) {
    loadError.value = err instanceof RestError ? err.message : String(err);
  }
}

async function run(action: AdminActionDto): Promise<void> {
  busyId.value = action.id;
  runError.value = null;
  result.value = null;
  try {
    const params = new URLSearchParams();
    if (props.scopeKind === 'project' && props.projectId) {
      params.set('projectId', props.projectId);
    }
    const query = params.toString();
    result.value = await brainFetch<AdminActionRunResultDto>(
      'POST',
      `admin/actions/${encodeURIComponent(action.id)}${query ? `?${query}` : ''}`,
    );
  } catch (err) {
    runError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    busyId.value = null;
  }
}

onMounted(load);
watch(() => [props.scopeKind, props.projectId] as const, load);
void useProfile(); // tab renders inside an authenticated page; nothing more needed
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="loadError" variant="error">{{ loadError }}</VAlert>

    <div v-else-if="loading" class="text-sm opacity-60">{{ t('common.loading') }}</div>

    <template v-else>
      <VAlert v-if="runError" variant="error">{{ runError }}</VAlert>

      <!-- Result of the last run — generic shape, any action. -->
      <div
        v-if="result"
        class="border rounded p-3 text-sm"
        :class="result.ok ? 'border-base-300' : 'border-warning'"
      >
        <div class="font-semibold">
          {{ result.ok ? '✓' : '⚠' }} {{ result.summary }}
          <span class="opacity-50 font-normal">({{ Math.round(result.durationMs) }} ms)</span>
        </div>
        <table v-if="result.items?.length" class="mt-2 w-full text-xs">
          <tbody>
            <tr
              v-for="item in result.items"
              :key="item.key"
              class="border-t border-base-300 align-top"
            >
              <td class="py-1 pr-2 font-mono whitespace-nowrap">{{ item.key }}</td>
              <td class="py-1 pr-2 w-6 text-center">{{ item.ok ? '✓' : '✗' }}</td>
              <td class="py-1 opacity-80">{{ item.detail }}</td>
              <td v-if="item.durationMs" class="py-1 pl-2 text-right opacity-50 whitespace-nowrap">
                {{ item.durationMs }} ms
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- The action list — one card per descriptor. -->
      <div class="flex flex-col gap-2">
        <div
          v-for="action in visibleActions"
          :key="action.id"
          class="border border-base-300 rounded p-3 flex items-start justify-between gap-4"
        >
          <div class="min-w-0">
            <div class="font-semibold">{{ localized(action.title) }}</div>
            <div class="text-sm opacity-70">{{ localized(action.description) }}</div>
          </div>
          <VButton
            variant="primary"
            size="sm"
            :loading="busyId === action.id"
            :disabled="busyId !== null"
            @click="run(action)"
          >{{ t('settings.actions.run') }}</VButton>
        </div>

        <div v-if="!visibleActions.length" class="text-sm opacity-60">
          {{ t('settings.actions.empty') }}
        </div>
      </div>
    </template>
  </div>
</template>
