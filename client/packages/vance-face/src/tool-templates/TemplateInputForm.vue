<script setup lang="ts">
import { computed } from 'vue';
import type { ToolTemplateChoiceDto, ToolTemplateInputDto } from '@vance/generated';
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

// ── Single-select ──
const selectOptionsByName = computed<Record<string, { value: string; label: string }[]>>(() => {
  const out: Record<string, { value: string; label: string }[]> = {};
  for (const i of props.inputs) {
    if (i.type === 'select') {
      out[i.name] = (i.choices ?? []).map((c: ToolTemplateChoiceDto) => ({
        value: c.value,
        label: c.label ?? c.value,
      }));
    }
  }
  return out;
});

// ── Multi-select ──
// Decode the JSON-array form stored in modelValue back to a Set for easy
// per-checkbox toggling. Empty / malformed → empty set.
function multiSelectedSet(name: string): Set<string> {
  const raw = props.modelValue[name];
  if (!raw) return new Set();
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return new Set(parsed.filter((v): v is string => typeof v === 'string'));
  } catch {
    /* fall through to empty */
  }
  return new Set();
}

function multiSelectIsChecked(name: string, value: string): boolean {
  return multiSelectedSet(name).has(value);
}

function toggleMultiSelect(name: string, value: string, checked: boolean): void {
  const set = multiSelectedSet(name);
  if (checked) set.add(value);
  else set.delete(value);
  // Preserve declaration order by walking the input's choices.
  const input = props.inputs.find((i) => i.name === name);
  const ordered: string[] = [];
  if (input) {
    for (const c of input.choices ?? []) {
      if (set.has(c.value)) ordered.push(c.value);
    }
  }
  setField(name, JSON.stringify(ordered));
}

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

      <div v-else-if="input.type === 'multi_select' || input.type === 'multiselect'"
           class="flex flex-col gap-1">
        <label class="text-sm">{{ labelFor(input) }}</label>
        <div class="flex flex-col gap-1 pl-1">
          <VCheckbox
            v-for="choice in (input.choices ?? [])"
            :key="choice.value"
            :model-value="multiSelectIsChecked(input.name, choice.value)"
            :label="choice.label ?? choice.value"
            @update:model-value="(v: boolean) => toggleMultiSelect(input.name, choice.value, v)"
          />
        </div>
        <span v-if="helpFor(input)" class="text-xs opacity-70">{{ helpFor(input) }}</span>
      </div>

      <div v-else class="text-xs text-error">
        Unknown input type: <code class="font-mono">{{ input.type }}</code>
      </div>
    </template>
  </div>
</template>
