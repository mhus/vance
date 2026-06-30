<script setup lang="ts">
/**
 * Editable form for a {@code vance-form} block. The block's {@code config}
 * is a {@code vance:} URI to a DATA document (`kind: records`) that carries
 * its own typed schema in {@code $meta.form.fields} plus an optional
 * {@code $meta.onSave} recompute hook (one-file model, §13). Data lives in
 * the document's {@code items}.
 *
 * Two work-mode shapes, driven by {@code $meta.form.single}:
 * - **single**: one record, rendered as a single form.
 * - **records** (default): a collection, rendered as cards with Add/Remove.
 *
 * Design mode (driven by the injected {@code vance:page-mode} ref) shows a
 * field builder + a "single" toggle; Save persists the schema back into
 * {@code $meta.form}.
 */
import { computed, inject, onMounted, ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { FormFieldDto, FormChoiceDto } from '@vance/generated';
import { FormFields, type FormValue } from './index';
import { parseVanceUri, VanceUriParseError } from '@/kindRenderers/parseVanceUri';
import { useDocumentRefStore } from '@/document/documentRefStore';

const props = defineProps<{ config: string }>();

interface FormResponse {
  fields: FormFieldDto[];
  single: boolean;
  records: Record<string, unknown>[];
  target: string;
}

const pageMode = inject<Ref<'design' | 'work'>>('vance:page-mode', ref('work'));

const store = useDocumentRefStore();

const fields = ref<FormFieldDto[]>([]);
const single = ref(false);
const records = ref<Record<string, FormValue>[]>([]);
const baselineRecords = ref<Record<string, FormValue>[]>([]);
const target = ref<string>('');

const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const savedAt = ref(false);

const dirty = computed(
  () => JSON.stringify(records.value) !== JSON.stringify(baselineRecords.value),
);

// Single mode edits records[0]; bridge it as one form value object.
const singleRecord = computed<Record<string, FormValue>>({
  get: () => records.value[0] ?? {},
  set: (v) => { records.value = [v]; },
});

function addRecord() { records.value.push({}); }
function removeRecord(i: number) { records.value.splice(i, 1); }

// ── Design builder state ──────────────────────────────────────────
const FIELD_TYPES = ['string', 'textarea', 'integer', 'boolean', 'select', 'multi_select'];

interface DesignField {
  name: string;
  type: string;
  labelText: string;
  required: boolean;
  choicesText: string;
}

const designFields = ref<DesignField[]>([]);
const designSingle = ref(false);
const schemaSaving = ref(false);
const schemaSavedAt = ref(false);

function toDesign(f: FormFieldDto): DesignField {
  const label = f.label ?? {};
  const choices = f.choices ?? [];
  return {
    name: f.name ?? '',
    type: f.type ?? 'string',
    labelText: label.en ?? Object.values(label)[0] ?? '',
    required: !!f.required,
    choicesText: choices
      .map((c) => (c.label?.en && c.label.en !== c.value ? `${c.value}|${c.label.en}` : c.value))
      .join('\n'),
  };
}

function fromDesign(d: DesignField): FormFieldDto {
  const hasChoices = d.type === 'select' || d.type === 'multi_select';
  const choices: FormChoiceDto[] = hasChoices
    ? d.choicesText
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean)
        .map((line) => {
          const pipe = line.indexOf('|');
          const value = pipe >= 0 ? line.slice(0, pipe).trim() : line;
          const lbl = pipe >= 0 ? line.slice(pipe + 1).trim() : line;
          return { value, label: { en: lbl }, defaultSelected: false };
        })
    : [];
  return {
    name: d.name.trim(),
    type: d.type,
    label: { en: d.labelText.trim() || d.name.trim() },
    required: d.required,
    choices,
  };
}

function syncDesignFromFields() {
  designFields.value = fields.value.map(toDesign);
  designSingle.value = single.value;
}

function addField() {
  designFields.value.push({
    name: `field${designFields.value.length + 1}`,
    type: 'string',
    labelText: '',
    required: false,
    choicesText: '',
  });
}
function removeField(i: number) { designFields.value.splice(i, 1); }
function moveField(i: number, delta: number) {
  const j = i + delta;
  if (j < 0 || j >= designFields.value.length) return;
  const arr = designFields.value;
  [arr[i], arr[j]] = [arr[j], arr[i]];
}

// ── Load / save ───────────────────────────────────────────────────
async function resolveProject(): Promise<{ projectId: string; path: string }> {
  const parsed = parseVanceUri(props.config, { text: '', imageStyle: false });
  const projectId = parsed.project ?? (await store.waitForCurrentProject());
  if (!projectId) throw new Error('No project context to resolve form document');
  return { projectId, path: parsed.path };
}

function normalise(raw: Record<string, unknown>): Record<string, FormValue> {
  const out: Record<string, FormValue> = {};
  for (const [k, v] of Object.entries(raw ?? {})) {
    if (Array.isArray(v)) {
      if (v.length > 0 && typeof v[0] === 'object' && v[0] !== null) {
        out[k] = (v as Record<string, unknown>[]).map((row) => {
          const r: Record<string, string | string[]> = {};
          for (const [rk, rv] of Object.entries(row)) {
            r[rk] = Array.isArray(rv) ? rv.map(String) : String(rv ?? '');
          }
          return r;
        });
      } else {
        out[k] = (v as unknown[]).map(String);
      }
    } else if (v != null) {
      out[k] = String(v);
    }
  }
  return out;
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const { projectId, path } = await resolveProject();
    const params = new URLSearchParams({ projectId, doc: path });
    const resp = await brainFetch<FormResponse>('GET', `addon/workspace/form?${params}`);
    fields.value = resp.fields ?? [];
    single.value = !!resp.single;
    target.value = resp.target ?? '';
    const recs = (resp.records ?? []).map((r) => normalise(r));
    if (single.value && recs.length === 0) recs.push({});
    records.value = recs;
    baselineRecords.value = JSON.parse(JSON.stringify(recs));
    syncDesignFromFields();
  } catch (e) {
    if (e instanceof VanceUriParseError) error.value = `Invalid form document URI: ${props.config}`;
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
    const params = new URLSearchParams({ projectId, doc: path });
    await brainFetch<void>('POST', `addon/workspace/form/save?${params}`, {
      body: { records: records.value },
    });
    baselineRecords.value = JSON.parse(JSON.stringify(records.value));
    savedAt.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Save failed';
  } finally {
    saving.value = false;
  }
}

function cancel() {
  records.value = JSON.parse(JSON.stringify(baselineRecords.value));
  if (single.value && records.value.length === 0) records.value = [{}];
  savedAt.value = false;
}

async function saveSchema() {
  schemaSaving.value = true;
  error.value = null;
  schemaSavedAt.value = false;
  try {
    const { projectId, path } = await resolveProject();
    const params = new URLSearchParams({ projectId, doc: path });
    const built = designFields.value.filter((d) => d.name.trim()).map(fromDesign);
    await brainFetch<void>('POST', `addon/workspace/form/schema?${params}`, {
      body: { fields: built, single: designSingle.value },
    });
    schemaSavedAt.value = true;
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Schema save failed';
  } finally {
    schemaSaving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="vance-form-view">
    <div v-if="loading" class="vance-form-view__status">Lade Formular…</div>
    <template v-else>
      <div v-if="error" class="vance-form-view__error">{{ error }}</div>

      <!-- DESIGN MODE: field builder -->
      <template v-if="pageMode === 'design'">
        <div class="vance-form-view__design-hint">
          Design-Modus — Felder bearbeiten. Dokument: <code>{{ target }}</code>
        </div>
        <div class="vance-form-view__mode-row">
          <label class="vance-form-view__req">
            <input v-model="designSingle" type="checkbox" />
            Single record (eine Form statt Karten-Liste)
          </label>
        </div>
        <div
          v-for="(f, i) in designFields"
          :key="i"
          class="vance-form-view__field-row"
        >
          <div class="vance-form-view__field-main">
            <input
              v-model="f.name"
              class="vance-form-view__inp"
              placeholder="name"
              style="width: 9rem"
            />
            <select v-model="f.type" class="vance-form-view__inp">
              <option v-for="t in FIELD_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
            <input
              v-model="f.labelText"
              class="vance-form-view__inp"
              placeholder="Label"
              style="flex: 1"
            />
            <label class="vance-form-view__req">
              <input v-model="f.required" type="checkbox" /> req
            </label>
            <button class="vance-form-view__mini" title="Move up" @click="moveField(i, -1)">↑</button>
            <button class="vance-form-view__mini" title="Move down" @click="moveField(i, 1)">↓</button>
            <button class="vance-form-view__mini" title="Remove" @click="removeField(i)">✕</button>
          </div>
          <textarea
            v-if="f.type === 'select' || f.type === 'multi_select'"
            v-model="f.choicesText"
            class="vance-form-view__choices"
            placeholder="One choice per line — value or value|Label"
            rows="2"
          />
        </div>
        <div class="vance-form-view__footer">
          <button class="vance-form-view__btn" @click="addField">+ Add field</button>
          <span class="vance-form-view__spacer" />
          <span v-if="schemaSavedAt" class="vance-form-view__saved">Schema gespeichert ✓</span>
          <button
            class="vance-form-view__btn vance-form-view__btn--primary"
            :disabled="schemaSaving"
            @click="saveSchema"
          >{{ schemaSaving ? 'Speichere…' : 'Save fields' }}</button>
        </div>
      </template>

      <!-- WORK MODE: data entry -->
      <template v-else>
        <template v-if="single">
          <FormFields v-model="singleRecord" :fields="fields" :disabled="saving" />
        </template>
        <template v-else>
          <div
            v-for="(rec, i) in records"
            :key="i"
            class="vance-form-view__record-card"
          >
            <div class="vance-form-view__record-head">
              <span class="vance-form-view__record-idx">#{{ i + 1 }}</span>
              <button
                type="button"
                class="vance-form-view__mini"
                title="Remove record"
                :disabled="saving"
                @click="removeRecord(i)"
              >✕</button>
            </div>
            <FormFields
              :model-value="rec"
              :fields="fields"
              :disabled="saving"
              @update:model-value="(v) => (records[i] = v)"
            />
          </div>
          <button
            type="button"
            class="vance-form-view__btn"
            :disabled="saving"
            @click="addRecord"
          >+ Add record</button>
        </template>
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
.vance-form-view__design-hint {
  font-size: 0.8rem;
  color: oklch(var(--bc) / 0.6);
  margin-bottom: 0.6rem;
}
.vance-form-view__design-hint code {
  font-family: monospace;
  background: oklch(var(--bc) / 0.1);
  padding: 0 0.25em;
  border-radius: 0.2em;
}
.vance-form-view__mode-row {
  display: flex;
  align-items: center;
  gap: 0.9rem;
  margin-bottom: 0.7rem;
  padding-bottom: 0.6rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.12);
  font-size: 0.82rem;
}
.vance-form-view__record-card {
  border: 1px solid oklch(var(--bc) / 0.15);
  border-radius: 0.4rem;
  padding: 0.6rem 0.75rem;
  margin-bottom: 0.6rem;
}
.vance-form-view__record-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.4rem;
}
.vance-form-view__record-idx {
  font-size: 0.75rem;
  font-weight: 600;
  color: oklch(var(--bc) / 0.55);
}
.vance-form-view__field-row {
  border: 1px solid oklch(var(--bc) / 0.12);
  border-radius: 0.35rem;
  padding: 0.4rem 0.5rem;
  margin-bottom: 0.45rem;
}
.vance-form-view__field-main {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.vance-form-view__inp {
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.25rem 0.4rem;
  font-size: 0.85rem;
  background: oklch(var(--b1));
}
.vance-form-view__req {
  font-size: 0.78rem;
  color: oklch(var(--bc) / 0.7);
  display: flex;
  align-items: center;
  gap: 0.2rem;
  white-space: nowrap;
}
.vance-form-view__mini {
  border: 1px solid oklch(var(--bc) / 0.2);
  background: oklch(var(--b1));
  border-radius: 0.25rem;
  width: 1.6rem;
  height: 1.6rem;
  cursor: pointer;
  font-size: 0.8rem;
  color: oklch(var(--bc) / 0.7);
}
.vance-form-view__mini:hover { background: oklch(var(--bc) / 0.08); }
.vance-form-view__choices {
  width: 100%;
  margin-top: 0.35rem;
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.3rem 0.4rem;
  font-size: 0.8rem;
  font-family: monospace;
  box-sizing: border-box;
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
