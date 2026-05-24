<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormChoiceDto, FormFieldDto } from '@vance/generated';
import VButton from './VButton.vue';
import VCheckbox from './VCheckbox.vue';
import VInput from './VInput.vue';
import VSelect from './VSelect.vue';
import VTextarea from './VTextarea.vue';

/**
 * Universal form renderer driven by {@link FormFieldDto} schemas.
 *
 * <p>Used by Prompt-Wizards (chat editor) and Kit-Tool-Templates —
 * everything UI primitive lives in {@code src/components/} so editor
 * code only ever sees this composite.
 *
 * <p>Field types: {@code string}, {@code textarea}, {@code password},
 * {@code integer}, {@code boolean}, {@code select}, {@code multi_select},
 * {@code repeat}. Localized labels resolve against the active
 * {@code useI18n().locale} (or the {@code preferredLang} prop when
 * the host wants to force a different language).
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
  /** Override the active i18n locale for label resolution. */
  preferredLang?: string;
  /** Path prefix for nested error keys (used by repeat-recursion). */
  pathPrefix?: string;
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  errors: () => ({}),
  preferredLang: undefined,
  pathPrefix: '',
  disabled: false,
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, FormValue>): void;
}>();

const { locale } = useI18n();
const activeLang = computed(() => props.preferredLang ?? locale.value);

function resolveLocalized(map: Record<string, string> | undefined): string {
  if (!map) return '';
  const preferred = activeLang.value;
  if (map[preferred] && map[preferred].trim()) return map[preferred];
  if (map.en && map.en.trim()) return map.en;
  for (const v of Object.values(map)) {
    if (v && v.trim()) return v;
  }
  return '';
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
    <template v-for="field in fields" :key="field.name">
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
        <div class="flex flex-col gap-1 pl-1">
          <VCheckbox
            v-for="choice in (field.choices ?? [])"
            :key="choice.value"
            :model-value="multiSelectIsChecked(field.name, choice.value)"
            :label="resolveLocalized(choice.label) || choice.value"
            :disabled="disabled"
            @update:model-value="(v: boolean) => toggleMultiSelect(field, choice.value, v)"
          />
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
    </template>
  </div>
</template>
