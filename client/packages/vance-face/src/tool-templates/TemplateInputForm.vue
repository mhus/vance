<script setup lang="ts">
import { computed } from 'vue';
import type { ToolTemplateInputDto } from '@vance/generated';
import { VCheckbox, VInput, VSelect } from '@/components';

interface Props {
  inputs: ToolTemplateInputDto[];
  modelValue: Record<string, string>;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, string>): void;
}>();

function setField(name: string, value: string): void {
  emit('update:modelValue', { ...props.modelValue, [name]: value });
}

function setBool(name: string, value: boolean): void {
  setField(name, value ? 'true' : 'false');
}

function boolValue(name: string): boolean {
  const v = props.modelValue[name];
  return v === 'true' || v === '1' || v === 'yes';
}

const selectOptionsByName = computed<Record<string, { value: string; label: string }[]>>(() => {
  const out: Record<string, { value: string; label: string }[]> = {};
  for (const i of props.inputs) {
    if (i.type === 'select') {
      out[i.name] = (i.choices ?? []).map((c: string) => ({ value: c, label: c }));
    }
  }
  return out;
});

function helpFor(input: ToolTemplateInputDto): string | undefined {
  if (input.help) return input.help;
  if (input.target === 'setting') {
    return 'Wird verschlüsselt in den Settings gespeichert.';
  }
  return undefined;
}

function labelFor(input: ToolTemplateInputDto): string {
  return input.required ? `${input.label} *` : input.label;
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <template v-for="input in inputs" :key="input.name">
      <VInput
        v-if="input.type === 'string'"
        :model-value="modelValue[input.name] ?? ''"
        :label="labelFor(input)"
        :placeholder="input.defaultValue ?? ''"
        :help="helpFor(input)"
        :required="input.required"
        @update:model-value="(v: string) => setField(input.name, v)"
      />

      <VInput
        v-else-if="input.type === 'password'"
        :model-value="modelValue[input.name] ?? ''"
        type="password"
        :label="labelFor(input)"
        :help="helpFor(input)"
        :required="input.required"
        autocomplete="new-password"
        @update:model-value="(v: string) => setField(input.name, v)"
      />

      <VInput
        v-else-if="input.type === 'integer'"
        :model-value="modelValue[input.name] ?? ''"
        type="number"
        :label="labelFor(input)"
        :placeholder="input.defaultValue ?? ''"
        :help="helpFor(input)"
        :required="input.required"
        @update:model-value="(v: string) => setField(input.name, v)"
      />

      <VCheckbox
        v-else-if="input.type === 'boolean'"
        :model-value="boolValue(input.name)"
        :label="labelFor(input)"
        :help="helpFor(input)"
        @update:model-value="(v: boolean) => setBool(input.name, v)"
      />

      <VSelect
        v-else-if="input.type === 'select'"
        :model-value="modelValue[input.name] ?? null"
        :label="labelFor(input)"
        :options="selectOptionsByName[input.name] ?? []"
        :help="helpFor(input)"
        :placeholder="input.required ? undefined : '—'"
        @update:model-value="(v: string | null) => setField(input.name, v ?? '')"
      />

      <div v-else class="text-xs text-error">
        Unknown input type: <code class="font-mono">{{ input.type }}</code>
      </div>
    </template>
  </div>
</template>
