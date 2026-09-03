<script setup lang="ts">
import { computed, watch } from 'vue';
import type { ZarniwoopInsightsDto } from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useToolHealth, useZarniwoopInsights } from '@/composables/useProjectInsights';
import { useI18n } from 'vue-i18n';

const props = defineProps<{ projectId: string | null }>();

const state = useZarniwoopInsights();
// Only used for its clearCooldown writer — the cooldown shown per
// instance lives in a tool-health record, and clearing it is the one
// action this tab could previously only describe. The full Health tab
// is the place to browse those records.
const health = useToolHealth();

watch(
  () => props.projectId,
  (next) => {
    if (next) state.load(next);
    else state.clear();
  },
  { immediate: true },
);

/** Forces the server past its provider cache — see useZarniwoopInsights.load. */
function reload(): void {
  if (props.projectId) state.load(props.projectId, true);
}

async function toggleInstance(inst: ZarniwoopInsightsDto): Promise<void> {
  if (!props.projectId) return;
  // Effective state right now (after gate resolution) drives the flip;
  // forcing the opposite value is what the operator means.
  await state.setOverride(props.projectId, inst.id, !inst.effectivelyEnabled);
}

async function resetOverride(inst: ZarniwoopInsightsDto): Promise<void> {
  if (!props.projectId) return;
  await state.clearOverride(props.projectId, inst.id);
}

/**
 * Lift the cooldown gating this instance. Needs the subject the record
 * sits on (`research:<id>:<MODALITY>`) plus the signature — cooldowns
 * are kept per error kind, so a 404 and a timeout are two records under
 * the same subject and only the named one is cleared.
 */
async function clearCooldown(inst: ZarniwoopInsightsDto): Promise<void> {
  if (!props.projectId) return;
  if (!inst.activeCooldownSubject || !inst.activeCooldownSignature) return;
  await health.clearCooldown(
    props.projectId,
    inst.activeCooldownSubject,
    inst.activeCooldownSignature,
    null,
  );
  // The instance row derives its availability from the cooldown lookup,
  // so it has to be re-assembled. No provider-cache drop needed.
  await state.load(props.projectId);
}

function availabilityClass(availability: string): string {
  switch (availability) {
    case 'READY':
      return 'badge badge--ok';
    case 'NO_CREDENTIALS':
      return 'badge badge--warning';
    case 'QUOTA_EXHAUSTED':
    case 'COOLDOWN':
      return 'badge badge--error';
    case 'DISABLED':
    default:
      return 'badge badge--muted';
  }
}

function formatTimestamp(iso: string | undefined): string {
  if (!iso) return '—';
  // `ms`, not `t`: the translator is called `t` in this file now.
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return iso;
  return new Date(ms).toLocaleString();
}

function formatDuration(now: number, iso: string | undefined): string {
  if (!iso) return '';
  const target = Date.parse(iso);
  if (Number.isNaN(target)) return '';
  const ms = target - now;
  if (ms <= 0) return t('insights.zarniwoop.duration.elapsed');
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return t('insights.zarniwoop.duration.minutes', { n: minutes });
  const hours = Math.round(minutes / 60);
  if (hours < 24) return t('insights.zarniwoop.duration.hours', { n: hours });
  return t('insights.zarniwoop.duration.days', { n: Math.round(hours / 24) });
}

const sorted = computed<ZarniwoopInsightsDto[]>(() => {
  const out = [...state.instances.value];
  out.sort((a, b) => a.id.localeCompare(b.id));
  return out;
});

const totals = computed(() => {
  let calls = 0;
  let ok = 0;
  let errors = 0;
  for (const inst of state.instances.value) {
    calls += inst.callCount;
    ok += inst.okCount;
    errors += inst.errorCount;
  }
  return { calls, ok, errors };
});

const { t } = useI18n();

const now = Date.now();
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <!-- Stands outside the load-state chain below: a failed cooldown
         clear must not hide the table it was triggered from. -->
    <VAlert v-if="health.error.value" variant="error">
      {{ health.error.value }}
    </VAlert>

    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.zarniwoop.pickProject') }}
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">
      {{ $t('insights.zarniwoop.loading') }}
    </div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.instances.value.length === 0">
      <VEmptyState
        :headline="$t('insights.zarniwoop.emptyHeadline')"
        :body="$t('insights.zarniwoop.emptyBody')"
      >
        <template #action>
          <VButton variant="secondary" size="sm" @click="reload" :disabled="state.loading.value">
            {{ $t('insights.zarniwoop.reload') }}
          </VButton>
        </template>
      </VEmptyState>
    </template>

    <template v-else>
      <div class="flex items-end gap-4 text-sm">
        <div class="opacity-70">
          {{ $t('insights.zarniwoop.instances', { count: sorted.length }) }}
        </div>
        <div class="opacity-70">
          · {{ $t('insights.zarniwoop.calls', { n: totals.calls }, totals.calls) }}
          {{ $t('insights.zarniwoop.callsBreakdown', { ok: totals.ok, errors: totals.errors }) }}
        </div>
        <VButton
          variant="neutral"
          size="xs"
          class="ml-auto"
          @click="reload"
          :disabled="state.loading.value"
        >
          {{ $t('insights.zarniwoop.reload') }}
        </VButton>
      </div>

      <table class="table table-sm">
        <thead>
          <tr>
            <th class="w-32">{{ $t('insights.zarniwoop.colEnabled') }}</th>
            <th class="w-40">{{ $t('insights.zarniwoop.colInstance') }}</th>
            <th class="w-24">{{ $t('insights.zarniwoop.colProtocol') }}</th>
            <th>{{ $t('insights.zarniwoop.colModalities') }}</th>
            <th class="w-32">{{ $t('insights.zarniwoop.colAvailability') }}</th>
            <th>{{ $t('insights.zarniwoop.colStatus') }}</th>
            <th class="w-32 text-right">{{ $t('insights.zarniwoop.colCalls') }}</th>
            <th class="w-40">{{ $t('insights.zarniwoop.colLastUsed') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="inst in sorted" :key="inst.id">
            <td class="text-xs">
              <label class="flex items-center gap-2">
                <input
                  type="checkbox"
                  class="checkbox checkbox-sm"
                  :checked="inst.effectivelyEnabled"
                  :disabled="state.loading.value"
                  @change="toggleInstance(inst)"
                />
                <span
                  v-if="inst.manualOverride"
                  class="badge badge--tag"
                  :title="$t('insights.zarniwoop.overrideTitle', {
                    mode: inst.manualOverride,
                    fallback: inst.defaultEnabled
                      ? $t('insights.zarniwoop.enabled')
                      : $t('insights.zarniwoop.disabled'),
                  })"
                >{{ $t('insights.zarniwoop.override') }}</span>
                <span
                  v-else-if="!inst.defaultEnabled"
                  class="opacity-60 text-xs"
                  :title="$t('insights.zarniwoop.offByDefaultTitle')"
                >{{ $t('insights.zarniwoop.offByDefault') }}</span>
              </label>
              <VButton
                v-if="inst.manualOverride"
                variant="link"
                size="xs"
                class="mt-1"
                @click="resetOverride(inst)"
                :disabled="state.loading.value"
              >{{ $t('insights.zarniwoop.reset') }}</VButton>
            </td>
            <td class="font-mono">
              {{ inst.id }}
              <div class="text-xs opacity-60">{{ inst.displayName }}</div>
            </td>
            <td class="font-mono opacity-80">{{ inst.protocol }}</td>
            <td class="text-xs">
              <span
                v-for="m in inst.modalities"
                :key="m"
                class="badge badge--tag mr-1"
              >{{ m.toLowerCase() }}</span>
            </td>
            <td>
              <span :class="availabilityClass(inst.availability)">
                {{ inst.availability }}
              </span>
              <div
                v-if="inst.activeCooldownUntil"
                class="text-xs opacity-60 mt-1"
                :title="inst.activeCooldownSignature ?? ''"
              >
                {{ $t('insights.zarniwoop.cooldownUntil', {
                  when: formatTimestamp(inst.activeCooldownUntil),
                }) }}
                {{ formatDuration(now, inst.activeCooldownUntil) }}
                <div v-if="inst.activeCooldownSubject" class="font-mono opacity-70">
                  {{ inst.activeCooldownSubject }} · {{ inst.activeCooldownSignature }}
                </div>
                <VButton
                  v-if="inst.activeCooldownSubject && inst.activeCooldownSignature"
                  variant="neutral"
                  size="xs"
                  :outline="true"
                  class="mt-1"
                  :disabled="state.loading.value"
                  :title="$t('insights.zarniwoop.clearCooldownTitle')"
                  @click="clearCooldown(inst)"
                >
                  {{ $t('insights.zarniwoop.clearCooldown') }}
                </VButton>
              </div>
            </td>
            <td class="text-xs opacity-80">
              <span v-if="inst.statusText">{{ inst.statusText }}</span>
              <span v-else class="opacity-50">—</span>
              <div
                v-if="inst.lastErrorMessage"
                class="text-xs text-error mt-1"
                :title="inst.lastErrorAt ?? ''"
              >
                {{ $t('insights.zarniwoop.lastError', { message: inst.lastErrorMessage }) }}
              </div>
            </td>
            <td class="text-right text-xs">
              {{ inst.callCount }}
              <span class="opacity-60">({{ inst.okCount }} / {{ inst.errorCount }})</span>
            </td>
            <td class="text-xs opacity-80">
              {{ formatTimestamp(inst.lastUsedAt) }}
            </td>
          </tr>
        </tbody>
      </table>

      <div class="text-xs opacity-50">
        {{ $t('insights.zarniwoop.footnotePre') }}
        <span class="font-mono">research.endpoint.&lt;id&gt;.enabled</span>{{
          $t('insights.zarniwoop.footnoteMid') }}
        <span class="font-mono">_vance/logs/research/</span>.
      </div>
    </template>
  </div>
</template>
