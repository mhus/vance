<script setup lang="ts">
import VButton from './VButton.vue';
import VCheckbox from './VCheckbox.vue';
import VInput from './VInput.vue';
import VSelect from './VSelect.vue';
import VTextarea from './VTextarea.vue';
import { pickLocalized } from '@vance/shared';
import { computed, ref } from 'vue';
import type { FormChoiceDto, FormFieldDto } from '@vance/generated';

/**
 * Universal form renderer driven by {@link FormFieldDto} schemas.
 *
 * <p>Used by Prompt-Wizards, Kit-Tool-Templates, Setting-Forms, Document-
 * Templates and the Bistromath runtime — which is why it lives here and not in
 * {@code vance-face}: an addon bundle cannot import the host's components.
 *
 * <p><b>Strings come in as props.</b> The ones it shows are props with English
 * defaults and the label language is a prop too, so a caller stays in control
 * of the wording of its own form. What this package must not do is import
 * vue-i18n — that would make it unusable in exactly the bundles that need it
 * most. Where a component of this package needs a translation of its own, it
 * goes through {@link useT}, which reaches the host's {@code $t} through the
 * app context without a vue-i18n dependency.
 *
 * <p>Field types: {@code string}, {@code textarea}, {@code password},
 * {@code integer}, {@code boolean}, {@code select}, {@code multi_select},
 * {@code repeat}.
 *
 * <p>Value encoding follows the tool-template convention: booleans /
 * integers / selects are stored as strings inside the modelValue
 * map. Multi-selects are {@code string[]}. Repeat fields are
 * {@code Array<Record<fieldName, string | string[]>>} — nested
 * repeats are intentionally <em>not</em> supported in v1.
 */
export type FormValue = string | string[] | FormValueObject[];
export type FormValueObject = Record<string, string | string[]>;

interface Props {
  fields: FormFieldDto[];
  modelValue: Record<string, FormValue>;
  /** Map of field-path → error code (e.g. "members[2].name" → "required"). */
  errors?: Record<string, string>;
  /** Language for label resolution. Falls back to English inside `pickLocalized`. */
  preferredLang?: string;
  /** Path prefix for nested error keys (used by repeat-recursion). */
  pathPrefix?: string;
  disabled?: boolean;
  /** Placeholder of the filter box a long choice list gets. */
  filterLabel?: string;
  /** Shown when the filter matches none of the choices. */
  noMatchesLabel?: string;
  /**
   * How many are selected — a function, not a template string, because this
   * package has no message formatter to substitute a placeholder with.
   */
  selectedCountLabel?: (count: number) => string;
  /** The button that empties a multi-select. */
  clearSelectionLabel?: string;
}

const props = withDefaults(defineProps<Props>(), {
  errors: () => ({}),
  preferredLang: undefined,
  pathPrefix: '',
  disabled: false,
  filterLabel: 'Filter…',
  noMatchesLabel: 'No matches',
  selectedCountLabel: () => (n: number) => `${n} selected`,
  clearSelectionLabel: 'Clear',
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, FormValue>): void;
}>();

const activeLang = computed(() => props.preferredLang ?? 'en');

function resolveLocalized(map: Record<string, string> | undefined): string {
  return pickLocalized(map, activeLang.value);
}

function labelOf(field: FormFieldDto): string {
  const text = resolveLocalized(field.label);
  return field.required ? `${text} *` : text;
}

function helpOf(field: FormFieldDto): string | undefined {
  const text = resolveLocalized(field.help);
  return text || undefined;
}

function pathOf(name: string, index?: number): string {
  const base = props.pathPrefix ? `${props.pathPrefix}.${name}` : name;
  return index !== undefined ? `${props.pathPrefix}[${index}].${name}` : base;
}

function errorOf(field: FormFieldDto): string | undefined {
  return props.errors[pathOf(field.name)];
}

function setField(name: string, value: FormValue): void {
  emit('update:modelValue', { ...props.modelValue, [name]: value });
}

function stringValue(name: string): string {
  const v = props.modelValue[name];
  return typeof v === 'string' ? v : '';
}

function boolValue(name: string): boolean {
  const v = props.modelValue[name];
  return v === 'true' || v === '1' || v === 'yes';
}

function setBool(name: string, v: boolean): void {
  setField(name, v ? 'true' : 'false');
}

function multiSelectedSet(name: string): Set<string> {
  const v = props.modelValue[name];
  if (Array.isArray(v)) {
    return new Set(v.filter((x): x is string => typeof x === 'string'));
  }
  return new Set();
}

function multiSelectIsChecked(name: string, value: string): boolean {
  return multiSelectedSet(name).has(value);
}

function toggleMultiSelect(field: FormFieldDto, value: string, checked: boolean): void {
  const set = multiSelectedSet(field.name);
  if (checked) set.add(value);
  else set.delete(value);
  // Preserve declaration order.
  const ordered: string[] = [];
  for (const c of field.choices ?? []) {
    if (set.has(c.value)) ordered.push(c.value);
  }
  setField(field.name, ordered);
}

function clearMultiSelect(field: FormFieldDto): void {
  setField(field.name, []);
}

function selectedCount(name: string): number {
  return multiSelectedSet(name).size;
}

/**
 * Above this many options a checkbox column gets a filter box and a
 * bounded height. The number lives here rather than in the schema: how
 * many rows fit is a property of the renderer, not of the form's meaning,
 * and a form author cannot know how many options a dynamic
 * {@code choices} list will carry at render time.
 */
const LONG_CHOICE_LIST = 8;

/** Per-field filter text, keyed by field name. */
const choiceFilters = ref<Record<string, string>>({});

function isLongChoiceList(field: FormFieldDto): boolean {
  return (field.choices ?? []).length > LONG_CHOICE_LIST;
}

function visibleChoices(field: FormFieldDto): FormChoiceDto[] {
  const all = field.choices ?? [];
  const needle = (choiceFilters.value[field.name] ?? '').trim().toLowerCase();
  if (!needle) return all;
  return all.filter((c) => {
    const label = resolveLocalized(c.label) || c.value;
    return label.toLowerCase().includes(needle)
      || c.value.toLowerCase().includes(needle);
  });
}

function selectOptionsOf(field: FormFieldDto): { value: string; label: string }[] {
  return (field.choices ?? []).map((c: FormChoiceDto) => ({
    value: c.value,
    label: resolveLocalized(c.label) || c.value,
  }));
}

// ─────────────────── Repeat helpers ───────────────────

function repeatItems(name: string): FormValueObject[] {
  const v = props.modelValue[name];
  if (Array.isArray(v)) {
    return v.filter(
      (x): x is FormValueObject => typeof x === 'object' && x !== null && !Array.isArray(x),
    );
  }
  return [];
}

function addRepeatItem(field: FormFieldDto): void {
  const current = repeatItems(field.name);
  if (field.max !== undefined && current.length >= field.max) return;
  const blank: FormValueObject = {};
  for (const item of field.item ?? []) {
    blank[item.name] = item.type === 'multi_select' ? [] : '';
  }
  setField(field.name, [...current, blank]);
}

function removeRepeatItem(field: FormFieldDto, index: number): void {
  const current = repeatItems(field.name);
  if (field.min !== undefined && current.length <= field.min) {
    // Block removal below min; the user gets visual feedback via the
    // disabled remove button.
    return;
  }
  setField(
    field.name,
    current.filter((_, i) => i !== index),
  );
}

function updateRepeatItem(field: FormFieldDto, index: number, sub: FormValueObject): void {
  const current = repeatItems(field.name);
  const next = [...current];
  next[index] = sub;
  setField(field.name, next);
}

function canAdd(field: FormFieldDto): boolean {
  if (field.max === undefined) return true;
  return repeatItems(field.name).length < field.max;
}

function canRemove(field: FormFieldDto): boolean {
  if (field.min === undefined) return true;
  return repeatItems(field.name).length > field.min;
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <!--
      One wrapper per field, tagged with the same path the error map uses
      (`members[2].name` for repeat-nested entries). Hosts scroll a failed
      field into view via [data-form-field="<path>"] — a long form
      otherwise reports errors somewhere off-screen.
    -->
    <div
      v-for="field in fields"
      :key="field.name"
      :data-form-field="pathOf(field.name)"
    >
      <!-- ── string ── -->
      <VInput
        v-if="field.type === 'string'"
        :model-value="stringValue(field.name)"
        :label="labelOf(field)"
        :placeholder="field.defaultValue ?? ''"
        :help="helpOf(field)"
        :error="errorOf(field)"
        :required="field.required"
        :disabled="disabled"
        @update:model-value="(v: string) => setField(field.name, v)"
      />

      <!-- ── password ── -->
      <VInput
        v-else-if="field.type === 'password'"
        :model-value="stringValue(field.name)"
        type="password"
        :label="labelOf(field)"
        :help="helpOf(field)"
        :error="errorOf(field)"
        :required="field.required"
        :disabled="disabled"
        autocomplete="new-password"
        @update:model-value="(v: string) => setField(field.name, v)"
      />

      <!-- ── integer ── -->
      <VInput
        v-else-if="field.type === 'integer'"
        :model-value="stringValue(field.name)"
        type="number"
        :label="labelOf(field)"
        :placeholder="field.defaultValue ?? ''"
        :help="helpOf(field)"
        :error="errorOf(field)"
        :required="field.required"
        :disabled="disabled"
        @update:model-value="(v: string) => setField(field.name, v)"
      />

      <!-- ── textarea ── -->
      <VTextarea
        v-else-if="field.type === 'textarea'"
        :model-value="stringValue(field.name)"
        :label="labelOf(field)"
        :placeholder="field.defaultValue ?? ''"
        :help="helpOf(field)"
        :error="errorOf(field)"
        :rows="field.rows ?? 3"
        :required="field.required"
        :disabled="disabled"
        @update:model-value="(v: string) => setField(field.name, v)"
      />

      <!-- ── boolean ── -->
      <VCheckbox
        v-else-if="field.type === 'boolean'"
        :model-value="boolValue(field.name)"
        :label="labelOf(field)"
        :help="helpOf(field)"
        :disabled="disabled"
        @update:model-value="(v: boolean) => setBool(field.name, v)"
      />

      <!-- ── select ── -->
      <VSelect
        v-else-if="field.type === 'select'"
        :model-value="stringValue(field.name) || null"
        :label="labelOf(field)"
        :options="selectOptionsOf(field)"
        :help="helpOf(field)"
        :error="errorOf(field)"
        :placeholder="field.required ? undefined : '—'"
        :disabled="disabled"
        @update:model-value="(v: string | null) => setField(field.name, v ?? '')"
      />

      <!-- ── multi_select ── -->
      <div
        v-else-if="field.type === 'multi_select'"
        class="flex flex-col gap-1"
      >
        <label class="text-sm">{{ labelOf(field) }}</label>
        <!-- Above the threshold a flat checkbox column stops being a
             list and becomes a wall: it pushes the rest of the form
             (and the submit button) off-screen. Same control, given a
             filter and a bounded height. -->
        <VInput
          v-if="isLongChoiceList(field)"
          :model-value="choiceFilters[field.name] ?? ''"
          :placeholder="filterLabel"
          :disabled="disabled"
          @update:model-value="(v: string) => (choiceFilters[field.name] = v)"
        />
        <div
          class="flex flex-col gap-1 pl-1"
          :class="isLongChoiceList(field) ? 'max-h-52 overflow-y-auto' : ''"
        >
          <VCheckbox
            v-for="choice in visibleChoices(field)"
            :key="choice.value"
            :model-value="multiSelectIsChecked(field.name, choice.value)"
            :label="resolveLocalized(choice.label) || choice.value"
            :disabled="disabled"
            @update:model-value="(v: boolean) => toggleMultiSelect(field, choice.value, v)"
          />
          <span
            v-if="visibleChoices(field).length === 0"
            class="text-xs opacity-60 italic"
          >{{ noMatchesLabel }}</span>
        </div>
        <!-- The filter hides rows but never a selection — so say how many
             are picked, or a filtered-away choice would look unselected. -->
        <div
          v-if="isLongChoiceList(field) && selectedCount(field.name) > 0"
          class="flex items-center gap-2 text-xs"
        >
          <span class="opacity-70">
            {{ selectedCountLabel(selectedCount(field.name)) }}
          </span>
          <button
            type="button"
            class="underline opacity-70 hover:opacity-100 disabled:no-underline"
            :disabled="disabled"
            @click="clearMultiSelect(field)"
          >{{ clearSelectionLabel }}</button>
        </div>
        <span v-if="errorOf(field)" class="text-xs text-error">
          {{ errorOf(field) }}
        </span>
        <span v-else-if="helpOf(field)" class="text-xs opacity-70">
          {{ helpOf(field) }}
        </span>
      </div>

      <!-- ── repeat ── -->
      <fieldset
        v-else-if="field.type === 'repeat'"
        class="border border-base-300 rounded-lg p-3 flex flex-col gap-3"
      >
        <legend class="px-2 text-sm font-semibold">
          {{ labelOf(field) }}
        </legend>
        <span v-if="helpOf(field)" class="text-xs opacity-70 -mt-1">
          {{ helpOf(field) }}
        </span>
        <span v-if="errorOf(field)" class="text-xs text-error -mt-1">
          {{ errorOf(field) }}
        </span>

        <div
          v-for="(entry, idx) in repeatItems(field.name)"
          :key="idx"
          class="border border-base-200 rounded p-3 flex flex-col gap-3 bg-base-50"
        >
          <div class="flex justify-between items-center -mb-1">
            <span class="text-xs uppercase tracking-wide opacity-60">#{{ idx + 1 }}</span>
            <VButton
              variant="ghost"
              size="sm"
              :disabled="disabled || !canRemove(field)"
              @click="removeRepeatItem(field, idx)"
            >
              ✕
            </VButton>
          </div>
          <FormFields
            :fields="field.item ?? []"
            :model-value="entry as Record<string, FormValue>"
            :errors="errors"
            :preferred-lang="preferredLang"
            :path-prefix="`${field.name}[${idx}]`"
            :disabled="disabled"
            :filter-label="filterLabel"
            :no-matches-label="noMatchesLabel"
            :selected-count-label="selectedCountLabel"
            :clear-selection-label="clearSelectionLabel"
            @update:model-value="(sub: Record<string, FormValue>) =>
              updateRepeatItem(field, idx, sub as FormValueObject)"
          />
        </div>

        <VButton
          variant="ghost"
          size="sm"
          :disabled="disabled || !canAdd(field)"
          @click="addRepeatItem(field)"
        >
          + {{ labelOf(field) }}
        </VButton>
      </fieldset>

      <!-- ── unknown ── -->
      <div v-else class="text-xs text-error">
        Unknown field type: <code class="font-mono">{{ field.type }}</code>
      </div>
    </div>
  </div>
</template>
