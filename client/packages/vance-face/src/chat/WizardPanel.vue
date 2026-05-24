<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  FormFieldDto,
  WizardDto,
  WizardSummaryDto,
} from '@vance/generated';
import {
  getWizard,
  listWizards,
  renderWizard,
  RestError,
  type FormValue,
} from '@vance/shared';
import { FormFields, VAlert, VButton, VEmptyState } from '@components/index';

interface Props {
  projectId?: string;
  /** Active session — only used to decide when to refresh the listing. */
  sessionKey?: string;
}

const props = withDefaults(defineProps<Props>(), {
  projectId: undefined,
  sessionKey: undefined,
});

const emit = defineEmits<{
  /** Fired when the user submits a wizard — host writes the prompt into the chat input. */
  (e: 'promptReady', prompt: string): void;
}>();

const { t } = useI18n();

type Mode = 'list' | 'form';
const mode = ref<Mode>('list');

const listing = ref<WizardSummaryDto[]>([]);
const listLoading = ref(false);
const listError = ref<string | null>(null);

const active = ref<WizardDto | null>(null);
const formLoading = ref(false);
const formValues = ref<Record<string, FormValue>>({});
const errors = ref<Record<string, string>>({});
const submitting = ref(false);
const formError = ref<string | null>(null);

async function refreshListing(): Promise<void> {
  listLoading.value = true;
  listError.value = null;
  try {
    const res = await listWizards(props.projectId);
    listing.value = res.wizards ?? [];
  } catch (err) {
    listError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    listLoading.value = false;
  }
}

const groupedByCategory = computed(() => {
  const groups = new Map<string, WizardSummaryDto[]>();
  for (const w of listing.value) {
    const cat = w.category ?? '';
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(w);
  }
  for (const list of groups.values()) {
    list.sort((a, b) => a.title.localeCompare(b.title));
  }
  return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});

async function openWizard(
  name: string,
  prefill?: Record<string, string>,
): Promise<void> {
  formLoading.value = true;
  formError.value = null;
  errors.value = {};
  try {
    active.value = await getWizard(name, props.projectId);
    const initial = initialValuesFor(active.value.fields ?? []);
    if (prefill) {
      mergePrefill(initial, prefill, active.value.fields ?? []);
    }
    formValues.value = initial;
    mode.value = 'form';
  } catch (err) {
    formError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    formLoading.value = false;
  }
}

/**
 * Apply prefill values from a {@code vance:/wizards/<name>?...} URI
 * into the freshly-initialized form. Unknown keys are ignored
 * (rather than throwing) so renames in the target wizard don't
 * break in-flight follow-up links.
 */
function mergePrefill(
  target: Record<string, FormValue>,
  prefill: Record<string, string>,
  fields: FormFieldDto[],
): void {
  const fieldByName = new Map(fields.map((f) => [f.name, f]));
  for (const [key, value] of Object.entries(prefill)) {
    const field = fieldByName.get(key);
    if (!field) continue;
    if (field.type === 'multi_select') {
      // Wire form: comma-separated. Skip when empty.
      target[key] = value ? value.split(',').map((s) => s.trim()) : [];
    } else if (field.type === 'repeat') {
      // Repeat prefill via URL is not supported in v1 — the link size
      // would explode and the encoding is fragile. Skip silently.
      continue;
    } else {
      target[key] = value;
    }
  }
}

function initialValuesFor(fields: FormFieldDto[]): Record<string, FormValue> {
  const out: Record<string, FormValue> = {};
  for (const f of fields) {
    if (f.type === 'multi_select') {
      out[f.name] = [];
    } else if (f.type === 'repeat') {
      out[f.name] = [];
    } else if (f.defaultValue !== undefined && f.defaultValue !== null) {
      out[f.name] = f.defaultValue;
    } else if (f.type === 'boolean') {
      out[f.name] = 'false';
    } else {
      out[f.name] = '';
    }
  }
  return out;
}

function backToList(): void {
  mode.value = 'list';
  active.value = null;
  formValues.value = {};
  errors.value = {};
  formError.value = null;
}

async function submit(): Promise<void> {
  if (!active.value) return;
  submitting.value = true;
  formError.value = null;
  errors.value = {};
  try {
    const res = await renderWizard(active.value.name, formValues.value, props.projectId);
    emit('promptReady', res.prompt);
    backToList();
  } catch (err) {
    if (err instanceof RestError) {
      formError.value = err.message;
      // Light heuristic: the BAD_REQUEST message ends with a list of
      // "field: error" pairs joined by "; ". Parse what we can so the
      // form can highlight at least the first offending field.
      const parsed = parseValidationMessage(err.message);
      if (parsed) errors.value = parsed;
    } else {
      formError.value = String(err);
    }
  } finally {
    submitting.value = false;
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

onMounted(refreshListing);
// Re-list when the session changes (different project may be active).
watch(() => props.sessionKey, refreshListing);
watch(() => props.projectId, refreshListing);

// Imperative handle for the host editor: ChatView's vance:-wizard click
// handler calls {@code wizardPanelRef.value?.openWizard(name, prefill)}
// after switching the side-panel tab.
defineExpose({ openWizard });
</script>

<template>
  <div class="p-3 flex flex-col gap-3 min-h-0">
    <!-- ─── List mode ─── -->
    <template v-if="mode === 'list'">
      <div class="flex items-center justify-between px-1">
        <div class="text-xs uppercase tracking-wide opacity-60 font-semibold">
          {{ t('chat.wizards.title') }}
        </div>
        <VButton
          variant="ghost"
          size="sm"
          :loading="listLoading"
          @click="refreshListing"
        >
          ⟳
        </VButton>
      </div>

      <VAlert v-if="listError" variant="error">
        {{ listError }}
      </VAlert>

      <VEmptyState
        v-else-if="!listLoading && listing.length === 0"
        :headline="t('chat.wizards.emptyHeadline')"
        :body="t('chat.wizards.emptyBody')"
      />

      <div v-for="[cat, group] in groupedByCategory" :key="cat" class="flex flex-col gap-1.5">
        <div
          v-if="cat"
          class="text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1"
        >
          {{ cat }}
        </div>
        <button
          v-for="w in group"
          :key="w.name"
          type="button"
          class="bg-base-200 hover:bg-base-300 rounded px-2.5 py-2 text-left text-sm transition-colors"
          @click="openWizard(w.name)"
        >
          <div class="flex items-center gap-1.5">
            <span v-if="w.icon" class="text-xs opacity-60 font-mono">{{ w.icon }}</span>
            <span class="font-semibold truncate">{{ w.title }}</span>
          </div>
          <div class="text-xs opacity-70 mt-0.5 line-clamp-2">{{ w.description }}</div>
        </button>
      </div>
    </template>

    <!-- ─── Form mode ─── -->
    <template v-else-if="mode === 'form' && active">
      <div class="flex items-center gap-2 px-1">
        <VButton variant="ghost" size="sm" @click="backToList">← </VButton>
        <div class="flex-1 min-w-0">
          <div class="text-sm font-semibold truncate">
            {{ active.title?.[$i18n.locale] ?? active.title?.en ?? active.name }}
          </div>
          <div class="text-xs opacity-70 line-clamp-2">
            {{ active.description?.[$i18n.locale] ?? active.description?.en ?? '' }}
          </div>
        </div>
      </div>

      <VAlert v-if="formError" variant="error">
        {{ formError }}
      </VAlert>

      <FormFields
        v-if="!formLoading"
        v-model="formValues"
        :fields="active.fields ?? []"
        :errors="errors"
        :disabled="submitting"
      />

      <div class="flex gap-2 justify-end mt-2">
        <VButton variant="ghost" size="sm" :disabled="submitting" @click="backToList">
          {{ t('common.cancel') }}
        </VButton>
        <VButton variant="primary" size="sm" :loading="submitting" @click="submit">
          {{ t('chat.wizards.submit') }}
        </VButton>
      </div>
    </template>
  </div>
</template>
