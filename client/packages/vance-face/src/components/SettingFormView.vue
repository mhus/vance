<script setup lang="ts">
import { VAlert, VButton, VCard } from '@vance/components';
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  AppliedSettingDto,
  ComputedSettingDto,
  FormFieldDto,
  SettingFormDto,
} from '@vance/generated';
import {
  applySettingForm,
  getSettingForm,
  resetSettingForm,
  RestError,
  validateSettingForm,
  type FormValue,
} from '@vance/shared';
import FormFields from './FormFields.vue';

/**
 * Wrapper around {@link FormFields} for a single Setting Form. Owns
 * the apply / validate / reset workflow and renders the live cascade
 * values for each direct-mapped field as a Card above the form.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code name} — form name to load (required)</li>
 *   <li>{@code projectId} — optional; passed through to the brain so
 *       the cascade and live values come from the right context</li>
 *   <li>{@code reloadKey} — bump to force a reload of the definition,
 *       e.g. after the user switched projects</li>
 * </ul>
 *
 * <p>Pebble templates never reach this component — the brain strips
 * them server-side before responding. Only the field schema +
 * {@code currentValue}/{@code currentSource} per direct-mapped field
 * and the {@code settings:} catalogue come over the wire.
 */

interface Props {
  name: string;
  projectId?: string;
  reloadKey?: string | number;
}

const props = withDefaults(defineProps<Props>(), {
  projectId: undefined,
  reloadKey: undefined,
});

const emit = defineEmits<{
  (e: 'applied', applied: AppliedSettingDto[]): void;
  (e: 'closed'): void;
}>();

const { t, locale } = useI18n();

const form = ref<SettingFormDto | null>(null);
const loading = ref(false);
const loadError = ref<string | null>(null);
const values = ref<Record<string, FormValue>>({});
const errors = ref<Record<string, string>>({});
const submitting = ref(false);
const submitError = ref<string | null>(null);

type PreviewState = { kind: 'apply' | 'validate' | 'reset'; entries: AppliedSettingDto[] } | null;
const preview = ref<PreviewState>(null);

async function loadForm(): Promise<void> {
  loading.value = true;
  loadError.value = null;
  preview.value = null;
  try {
    form.value = await getSettingForm(props.name, props.projectId);
    values.value = initialValuesFor(form.value.fields ?? []);
  } catch (err) {
    loadError.value = err instanceof RestError ? err.message : String(err);
    form.value = null;
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.name, props.projectId, props.reloadKey],
  () => {
    void loadForm();
  },
  { immediate: true },
);

function initialValuesFor(fields: FormFieldDto[]): Record<string, FormValue> {
  const out: Record<string, FormValue> = {};
  for (const f of fields) {
    // Passwords always start blank (PASSWORD-empty == "do not modify").
    if (f.type === 'password') {
      out[f.name] = '';
      continue;
    }
    // Live cascade value pre-fills direct-mapped fields when available.
    if (f.currentValue !== undefined && f.currentValue !== null && f.currentValue !== '') {
      out[f.name] = f.currentValue;
      continue;
    }
    if (f.type === 'multi_select') out[f.name] = [];
    else if (f.type === 'repeat') out[f.name] = [];
    else if (f.defaultValue !== undefined && f.defaultValue !== null) out[f.name] = f.defaultValue;
    else if (f.type === 'boolean') out[f.name] = 'false';
    else out[f.name] = '';
  }
  return out;
}

const directMappedFields = computed<FormFieldDto[]>(() => {
  if (!form.value) return [];
  return (form.value.fields ?? []).filter((f) => f.bindsTo);
});

const computedSettings = computed<ComputedSettingDto[]>(() => {
  return form.value?.settings ?? [];
});

async function onApply(): Promise<void> {
  if (!form.value) return;
  submitting.value = true;
  submitError.value = null;
  errors.value = {};
  try {
    const res = await applySettingForm(
      form.value.name,
      values.value,
      props.projectId,
      locale.value,
    );
    preview.value = { kind: 'apply', entries: res.applied ?? [] };
    emit('applied', res.applied ?? []);
    // Reload so the currentValue/currentSource indicators reflect the new state.
    await loadForm();
  } catch (err) {
    handleSubmitError(err);
  } finally {
    submitting.value = false;
  }
}

async function onValidate(): Promise<void> {
  if (!form.value) return;
  submitting.value = true;
  submitError.value = null;
  errors.value = {};
  try {
    const res = await validateSettingForm(
      form.value.name,
      values.value,
      props.projectId,
      locale.value,
    );
    preview.value = { kind: 'validate', entries: res.applied ?? [] };
  } catch (err) {
    handleSubmitError(err);
  } finally {
    submitting.value = false;
  }
}

async function onReset(): Promise<void> {
  if (!form.value) return;
  if (!confirm(t('settingForms.confirmReset'))) return;
  submitting.value = true;
  submitError.value = null;
  errors.value = {};
  try {
    const res = await resetSettingForm(form.value.name, props.projectId);
    preview.value = { kind: 'reset', entries: res.applied ?? [] };
    emit('applied', res.applied ?? []);
    await loadForm();
  } catch (err) {
    handleSubmitError(err);
  } finally {
    submitting.value = false;
  }
}

function handleSubmitError(err: unknown): void {
  if (err instanceof RestError) {
    submitError.value = err.message;
    const parsed = parseValidationMessage(err.message);
    if (parsed) errors.value = parsed;
  } else {
    submitError.value = String(err);
  }
}

function parseValidationMessage(msg: string): Record<string, string> | null {
  const idx = msg.indexOf('form validation failed: ');
  if (idx < 0) return null;
  const tail = msg.slice(idx + 'form validation failed: '.length);
  const out: Record<string, string> = {};
  for (const part of tail.split(';')) {
    const colon = part.indexOf(':');
    if (colon < 0) continue;
    const field = part.slice(0, colon).trim();
    const code = part.slice(colon + 1).trim();
    if (field && code) out[field] = code;
  }
  return Object.keys(out).length > 0 ? out : null;
}

function fieldDisplayValue(f: FormFieldDto): string {
  if (f.currentValue === undefined || f.currentValue === null) {
    return t('settingForms.unset');
  }
  return f.currentValue;
}

function sourceLabel(src: string | undefined | null): string {
  if (!src) return t('settingForms.unset');
  if (src === '_tenant') return t('settingForms.sourceTenant');
  if (src.startsWith('_user_')) return t('settingForms.sourceUser', { user: src.slice('_user_'.length) });
  return t('settingForms.sourceProject', { project: src });
}

function actionLabel(action: string): string {
  return t('settingForms.actions.' + action);
}
</script>

<template>
  <div class="flex flex-col gap-4 min-h-0">
    <VAlert v-if="loadError" variant="error">{{ loadError }}</VAlert>

    <div v-if="loading" class="text-sm opacity-70">{{ t('common.loading') }}</div>

    <template v-else-if="form">
      <!-- Header -->
      <div>
        <h2 class="text-lg font-semibold">{{ form.title }}</h2>
        <p class="text-sm opacity-70 mt-1">{{ form.description }}</p>
      </div>

      <!-- Live cascade values -->
      <VCard v-if="directMappedFields.length > 0">
        <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-2">
          {{ t('settingForms.currentValuesTitle') }}
        </div>
        <ul class="flex flex-col gap-1.5 text-sm">
          <li
            v-for="f in directMappedFields"
            :key="f.name"
            class="flex items-center justify-between gap-2 border-b border-base-300 last:border-0 pb-1.5 last:pb-0"
          >
            <div class="min-w-0 flex-1">
              <span class="font-mono text-xs opacity-70">{{ f.bindsTo?.key }}</span>
              <span class="opacity-50 mx-1">=</span>
              <span class="font-mono">{{ fieldDisplayValue(f) }}</span>
            </div>
            <span class="text-xs opacity-60 whitespace-nowrap">
              {{ sourceLabel(f.currentSource) }}
            </span>
          </li>
        </ul>
      </VCard>

      <!-- Computed settings catalog -->
      <VCard v-if="computedSettings.length > 0">
        <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-2">
          {{ t('settingForms.computedTitle') }}
        </div>
        <ul class="flex flex-col gap-1 text-sm">
          <li v-for="cs in computedSettings" :key="cs.key" class="font-mono text-xs">
            {{ cs.key }}
            <span v-if="cs.settingType" class="opacity-50">({{ cs.settingType }})</span>
            <span v-if="cs.conditional" class="opacity-60">— {{ t('settingForms.conditional') }}</span>
          </li>
        </ul>
      </VCard>

      <!-- Submit error -->
      <VAlert v-if="submitError" variant="error">{{ submitError }}</VAlert>

      <!-- Form -->
      <FormFields
        v-model="values"
        :fields="form.fields ?? []"
        :errors="errors"
        :disabled="submitting"
      />

      <!-- Action buttons -->
      <div class="flex gap-2 justify-end mt-2 flex-wrap">
        <VButton
          v-if="form.clearable"
          variant="ghost"
          size="sm"
          :disabled="submitting"
          @click="onReset"
        >
          {{ t('settingForms.reset') }}
        </VButton>
        <VButton variant="ghost" size="sm" :loading="submitting" @click="onValidate">
          {{ t('settingForms.preview') }}
        </VButton>
        <VButton variant="primary" size="sm" :loading="submitting" @click="onApply">
          {{ t('settingForms.apply') }}
        </VButton>
      </div>

      <!-- Preview / result -->
      <VCard v-if="preview">
        <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-2">
          {{
            preview.kind === 'apply'
              ? t('settingForms.appliedTitle')
              : preview.kind === 'reset'
                ? t('settingForms.resetTitle')
                : t('settingForms.previewTitle')
          }}
        </div>
        <ul v-if="preview.entries.length > 0" class="flex flex-col gap-1 text-sm">
          <li
            v-for="(a, idx) in preview.entries"
            :key="idx"
            class="flex items-center gap-2"
          >
            <span
              class="text-[10px] uppercase tracking-wide font-semibold px-1.5 py-0.5 rounded"
              :class="{
                'bg-success/20 text-success': a.action === 'write',
                'bg-warning/20 text-warning': a.action === 'delete',
                'bg-base-300 text-base-content/60': a.action === 'skip',
              }"
            >
              {{ actionLabel(a.action) }}
            </span>
            <span class="font-mono text-xs">{{ a.key }}</span>
            <span class="text-xs opacity-50">@ {{ a.scope }}</span>
            <span v-if="a.valueMasked" class="text-xs opacity-50">(***)</span>
          </li>
        </ul>
        <div v-else class="text-sm opacity-70">{{ t('settingForms.noChanges') }}</div>
      </VCard>
    </template>
  </div>
</template>
