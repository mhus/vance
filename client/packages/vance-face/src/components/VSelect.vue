<script setup lang="ts" generic="T extends string | number">
interface Option<TValue> {
  value: TValue;
  label: string;
  /** Optional group name. Adjacent options with the same group are rendered under one <optgroup>. */
  group?: string;
  disabled?: boolean;
}

interface Props {
  modelValue: T | null;
  options: Option<T>[];
  label?: string;
  placeholder?: string;
  help?: string;
  error?: string;
  disabled?: boolean;
}

withDefaults(defineProps<Props>(), { disabled: false });

const emit = defineEmits<{ (e: 'update:modelValue', value: T | null): void }>();

function onChange(event: Event): void {
  const raw = (event.target as HTMLSelectElement).value;
  if (raw === '') {
    emit('update:modelValue', null);
    return;
  }
  // Number-coerce when the option type narrows to number; we detect via the
  // first non-null option's typeof.
  emit('update:modelValue', raw as unknown as T);
}

interface Section { group: string | null; options: Option<T>[]; }

function groupedOptions(options: Option<T>[]): Section[] {
  const sections: Section[] = [];
  for (const opt of options) {
    const group = opt.group ?? null;
    const last = sections[sections.length - 1];
    if (last && last.group === group) {
      last.options.push(opt);
    } else {
      sections.push({ group, options: [opt] });
    }
  }
  return sections;
}
</script>

<template>
  <label class="form-control w-full">
    <div v-if="label" class="label">
      <span class="label-text">{{ label }}</span>
    </div>
    <select
      :value="modelValue ?? ''"
      :disabled="disabled"
      :class="['select', 'select-bordered', 'w-full', { 'select-error': !!error }]"
      @change="onChange"
    >
      <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
      <template v-for="(section, idx) in groupedOptions(options)" :key="idx">
        <optgroup v-if="section.group" :label="section.group">
          <option
            v-for="opt in section.options"
            :key="String(opt.value)"
            :value="opt.value"
            :disabled="opt.disabled"
          >
            {{ opt.label }}
          </option>
        </optgroup>
        <option
          v-for="opt in (section.group ? [] : section.options)"
          :key="String(opt.value)"
          :value="opt.value"
          :disabled="opt.disabled"
        >
          {{ opt.label }}
        </option>
      </template>
    </select>
    <div v-if="error || help" class="label">
      <span :class="['label-text-alt', error ? 'text-error' : 'opacity-70']">
        {{ error || help }}
      </span>
    </div>
  </label>
</template>
