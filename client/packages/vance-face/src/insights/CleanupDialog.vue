<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { StorageBreakdownDto, StorageCleanupResultDto } from '@vance/generated';
import { VAlert, VBadge, VButton, VInput, VModal, useConfirm } from '@/components';
import { useStorageCleanup } from '@/composables/useStorageCleanup';

/**
 * Confirmation dialog for the "Thin archives" action on the Quotas tab.
 *
 * <p>One slider, one preview line, one confirm button. The slider picks the
 * number of versions to keep per lineage (newest first); the preview is a
 * server-side projection — but rather than call the server twice, we derive
 * the projection locally from the {@code topArchives} list in the
 * breakdown. The server clamps out-of-range values; the UI keeps the input
 * in range so the confirm button never offers an action the server would
 * silently change.
 *
 * <p>{@code keepLatest === 0} gets a louder confirmation — keeping nothing
 * is the kind of decision where a one-button click can lose years of
 * version history.
 */
const { t } = useI18n();
const confirmAction = useConfirm();
const cleanup = useStorageCleanup();

interface Props {
  open: boolean;
  /** Tenant-wide when {@code null}; project-scoped otherwise. */
  projectId: string | null;
  /** Breakdown already loaded by the parent — drives the preview and the cap. */
  breakdown: StorageBreakdownDto | null;
}
const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'done', result: StorageCleanupResultDto): void;
}>();

const MIN = 0;
const MAX = 50;
const DEFAULT_KEEP = 5;

const keepLatest = ref<number>(DEFAULT_KEEP);
const submitting = ref(false);
const error = ref<string | null>(null);

watch(() => props.open, (open) => {
  if (open) {
    keepLatest.value = DEFAULT_KEEP;
    error.value = null;
  }
});

function clamp(n: number): number {
  if (!Number.isFinite(n)) return DEFAULT_KEEP;
  return Math.max(MIN, Math.min(MAX, Math.floor(n)));
}

function setKeepLatest(value: string | number): void {
  const n = typeof value === 'string' ? Number.parseInt(value, 10) : value;
  keepLatest.value = clamp(Number.isNaN(n) ? DEFAULT_KEEP : n);
}

const archiveCount = computed<number>(() => props.breakdown?.archiveCount ?? 0);

/**
 * Estimate how many entries the server will delete. The breakdown does not
 * carry the per-lineage version counts beyond the top-N list, so we cannot
 * be exact — but the worst case is "delete every entry except the newest
 * one per lineage", which for {@code keepLatest === 0} is the entire
 * {@code archiveCount}. We surface that as the upper bound and qualify it
 * in the hint so the admin does not take the number for a guarantee.
 */
const projectedDeleted = computed<number>(() => {
  const total = archiveCount.value;
  if (total === 0) return 0;
  if (keepLatest.value <= 0) return total;
  // Lower bound: assume every lineage has at least keepLatest+1 entries.
  // The true number is "at least this, at most total - 1 per lineage".
  // The UI hint says "between N and M".
  return Math.max(0, total - keepLatest.value);
});

const isAggressive = computed<boolean>(() => keepLatest.value === 0);
const confirmLabel = computed<string>(() => {
  if (submitting.value) return t('insights.cleanup.dialog.submitting');
  if (isAggressive.value) return t('insights.cleanup.dialog.confirmZero');
  return t('insights.cleanup.dialog.confirmKeep');
});

async function onSubmit(): Promise<void> {
  if (submitting.value) return;
  // Loud confirm for keepLatest=0 — admin clicks once, history is gone.
  if (isAggressive.value && !await confirmAction({
    message: t('insights.cleanup.dialog.zeroWarning'),
    confirmLabel: t('insights.cleanup.dialog.confirmZero'),
    danger: true,
  })) {
    return;
  }
  submitting.value = true;
  error.value = null;
  try {
    const result = await cleanup.thinArchives(props.projectId, keepLatest.value);
    emit('done', result);
    emit('close');
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('insights.cleanup.dialog.errorGeneric');
  } finally {
    submitting.value = false;
  }
}

function onCancel(): void {
  if (submitting.value) return;
  emit('close');
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="t('insights.cleanup.dialog.title')"
    :description="t('insights.cleanup.dialog.description')"
    size="sm"
    @update:model-value="(v) => { if (!v) onCancel() }"
  >
    <form id="cleanup-dialog-form" @submit.prevent="onSubmit">
      <VAlert v-if="error" variant="error" class="mb-3">
        <span>{{ error }}</span>
      </VAlert>

      <div class="cleanup-dialog__field">
        <label class="cleanup-dialog__label" for="cleanup-keep">
          {{ $t('insights.cleanup.dialog.keepLabel') }}
        </label>
        <VInput
          id="cleanup-keep"
          type="number"
          :model-value="String(keepLatest)"
          :min="MIN"
          :max="MAX"
          size="md"
          @update:model-value="setKeepLatest"
        />
        <input
          type="range"
          class="cleanup-dialog__slider"
          :min="MIN"
          :max="MAX"
          :value="keepLatest"
          :aria-label="$t('insights.cleanup.dialog.keepLabel')"
          @input="(e) => setKeepLatest((e.target as HTMLInputElement).valueAsNumber)"
        />
        <p class="cleanup-dialog__hint">
          {{ $t('insights.cleanup.dialog.keepHint', { min: MIN, max: MAX }) }}
        </p>
      </div>

      <div class="cleanup-dialog__preview">
        <div class="cleanup-dialog__preview-row">
          <span class="cleanup-dialog__preview-label">
            {{ $t('insights.cleanup.dialog.previewScope') }}
          </span>
          <span class="cleanup-dialog__preview-value">
            {{ projectId ? projectId : $t('insights.cleanup.dialog.scopeTenant') }}
          </span>
        </div>
        <div class="cleanup-dialog__preview-row">
          <span class="cleanup-dialog__preview-label">
            {{ $t('insights.cleanup.dialog.previewArchives') }}
          </span>
          <span class="cleanup-dialog__preview-value">
            {{ archiveCount }}
          </span>
        </div>
        <div class="cleanup-dialog__preview-row">
          <span class="cleanup-dialog__preview-label">
            {{ $t('insights.cleanup.dialog.previewDelete') }}
          </span>
          <span class="cleanup-dialog__preview-value">
            {{ projectedDeleted }}
            <VBadge v-if="isAggressive" variant="error" size="sm">
              {{ $t('insights.cleanup.dialog.dangerBadge') }}
            </VBadge>
          </span>
        </div>
        <p class="cleanup-dialog__hint">
          {{ $t('insights.cleanup.dialog.previewHint') }}
        </p>
      </div>
    </form>

    <template #actions>
      <VButton variant="secondary" size="md" :disabled="submitting" @click="onCancel">
        {{ $t('common.cancel') }}
      </VButton>
      <VButton
        form="cleanup-dialog-form"
        type="submit"
        :variant="isAggressive ? 'danger' : 'primary'"
        size="md"
        :loading="submitting"
        :disabled="submitting"
      >
        {{ confirmLabel }}
      </VButton>
    </template>
  </VModal>
</template>

<style scoped>
.cleanup-dialog__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}
.cleanup-dialog__label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--v-label);
}
.cleanup-dialog__slider {
  width: 100%;
  accent-color: var(--v-accent);
}
.cleanup-dialog__hint {
  font-size: 0.75rem;
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
  margin: 0;
}
.cleanup-dialog__preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: color-mix(in oklab, var(--color-base-content) 4%, transparent);
  border-radius: var(--v-radius-group);
}
.cleanup-dialog__preview-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 0.875rem;
}
.cleanup-dialog__preview-label {
  color: color-mix(in oklab, var(--color-base-content) 70%, transparent);
}
.cleanup-dialog__preview-value {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}
</style>
