<script setup lang="ts">
/**
 * Editable form for a {@code vance-form} block. Given the edit-config
 * {@code vance:} URI, loads the field schema + the target data file's
 * current values from the workspace addon and renders {@link FormFields}
 * with explicit Save / Cancel (no auto-save).
 *
 * Provided to the block-editor as {@code vance:form-component} so the
 * editor stays decoupled from the form-engine + REST — mirrors
 * {@link VanceEmbedView}.
 *
 * Reactive-data, Schritt 3 (planning/workspace-reactive-data.md §8).
 * The {@code onSave} script run + soft-lock + design/work mode are
 * later steps; this view only loads + persists the data file.
 */
import { computed, onMounted, ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { FormFieldDto } from '@vance/generated';
import { FormFields, type FormValue } from './index';
import { parseVanceUri, VanceUriParseError } from '@/kindRenderers/parseVanceUri';
import { useDocumentRefStore } from '@/document/documentRefStore';

const props = defineProps<{ config: string }>();

interface FormResponse {
  fields: FormFieldDto[];
  values: Record<string, unknown>;
  target: string;
}

const store = useDocumentRefStore();

const fields = ref<FormFieldDto[]>([]);
const values = ref<Record<string, FormValue>>({});
const baseline = ref<Record<string, FormValue>>({});
const target = ref<string>('');

const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const savedAt = ref(false);

const dirty = computed(
  () => JSON.stringify(values.value) !== JSON.stringify(baseline.value),
);

/** Coerce raw JSON values into FormValue (string | string[] | object[]). */
function normalise(raw: Record<string, unknown>): Record<string, FormValue> {
  const out: Record<string, FormValue> = {};
  for (const [k, v] of Object.entries(raw ?? {})) {
    if (Array.isArray(v)) {
      if (v.length > 0 && typeof v[0] === 'object' && v[0] !== null) {
        // repeat: array of objects → coerce inner scalars to string
        out[k] = (v as Record<string, unknown>[]).map((row) => {
          const r: Record<string, string | string[]> = {};
          for (const [rk, rv] of Object.entries(row)) {
            r[rk] = Array.isArray(rv) ? rv.map(String) : String(rv ?? '');
          }
          return r;
        });
      } else {
        out[k] = (v as unknown[]).map(String); // multi_select
      }
    } else if (v != null && typeof v !== 'object') {
      out[k] = String(v);
    } else if (v != null) {
      // Unexpected object scalar — stringify defensively.
      out[k] = String(v);
    }
  }
  return out;
}

async function resolveProject(): Promise<{ projectId: string; path: string }> {
  const parsed = parseVanceUri(props.config, { text: '', imageStyle: false });
  const projectId = parsed.project ?? (await store.waitForCurrentProject());
  if (!projectId) throw new Error('No project context to resolve form config');
  return { projectId, path: parsed.path };
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const { projectId, path } = await resolveProject();
    const params = new URLSearchParams({ projectId, config: path });
    const resp = await brainFetch<FormResponse>('GET', `addon/workspace/form?${params}`);
    fields.value = resp.fields ?? [];
    target.value = resp.target ?? '';
    const v = normalise(resp.values ?? {});
    values.value = v;
    baseline.value = JSON.parse(JSON.stringify(v));
  } catch (e) {
    if (e instanceof VanceUriParseError) error.value = `Invalid form config URI: ${props.config}`;
    else error.value = e instanceof Error ? e.message : 'Failed to load form';
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  error.value = null;
  savedAt.value = false;
  try {
    const { projectId, path } = await resolveProject();
    const params = new URLSearchParams({ projectId, config: path });
    await brainFetch<void>('POST', `addon/workspace/form/save?${params}`, {
      body: { values: values.value },
    });
    baseline.value = JSON.parse(JSON.stringify(values.value));
    savedAt.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Save failed';
  } finally {
    saving.value = false;
  }
}

function cancel() {
  values.value = JSON.parse(JSON.stringify(baseline.value));
  savedAt.value = false;
}

onMounted(load);
</script>

<template>
  <div class="vance-form-view">
    <div v-if="loading" class="vance-form-view__status">Lade Formular…</div>
    <template v-else>
      <div v-if="error" class="vance-form-view__error">{{ error }}</div>
      <FormFields v-model="values" :fields="fields" :disabled="saving" />
      <div class="vance-form-view__footer">
        <span v-if="savedAt && !dirty" class="vance-form-view__saved">Gespeichert ✓</span>
        <span v-else-if="dirty" class="vance-form-view__dirty">Ungespeicherte Änderungen</span>
        <span class="vance-form-view__spacer" />
        <button
          type="button"
          class="vance-form-view__btn"
          :disabled="saving || !dirty"
          @click="cancel"
        >Cancel</button>
        <button
          type="button"
          class="vance-form-view__btn vance-form-view__btn--primary"
          :disabled="saving || !dirty"
          @click="save"
        >{{ saving ? 'Speichere…' : 'Save' }}</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.vance-form-view {
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.5rem;
  padding: 0.85rem 1rem;
  background: oklch(var(--b1));
}
.vance-form-view__status {
  color: oklch(var(--bc) / 0.65);
  font-size: 0.9rem;
  padding: 0.5rem 0;
}
.vance-form-view__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.85rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.25rem;
  margin-bottom: 0.75rem;
}
.vance-form-view__footer {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.85rem;
  padding-top: 0.75rem;
  border-top: 1px solid oklch(var(--bc) / 0.12);
}
.vance-form-view__spacer { flex: 1; }
.vance-form-view__saved { color: oklch(var(--su)); font-size: 0.8rem; }
.vance-form-view__dirty { color: oklch(var(--bc) / 0.6); font-size: 0.8rem; }
.vance-form-view__btn {
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.3rem;
  padding: 0.3rem 0.9rem;
  font-size: 0.85rem;
  background: oklch(var(--b1));
  color: oklch(var(--bc));
  cursor: pointer;
}
.vance-form-view__btn:disabled { opacity: 0.5; cursor: default; }
.vance-form-view__btn--primary {
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-color: oklch(var(--p));
}
</style>
