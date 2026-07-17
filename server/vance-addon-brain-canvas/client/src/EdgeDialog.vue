<script setup lang="ts">
import { ref } from 'vue';
import { VButton, VCheckbox, VInput, VModal } from '@vance/components';

/**
 * Edit an edge: label + style (arrow ends, dashed, thick, colour).
 * Imperative API: `const v = await ref.value.open(initial)` →
 * the edited values on OK, or `null` on cancel.
 */
interface EdgeStyle {
  label: string;
  color: string; // hex or '' for default
  fromArrow: boolean;
  toArrow: boolean;
  dashed: boolean;
  thick: boolean;
}

const COLOR_OPTIONS = [
  { value: '', label: 'Standard' },
  { value: '#ef4444', label: 'Rot' },
  { value: '#f97316', label: 'Orange' },
  { value: '#eab308', label: 'Gelb' },
  { value: '#22c55e', label: 'Grün' },
  { value: '#3b82f6', label: 'Blau' },
  { value: '#8b5cf6', label: 'Lila' },
  { value: '#64748b', label: 'Grau' },
];

const open = ref(false);
const label = ref('');
const color = ref('');
const fromArrow = ref(false);
const toArrow = ref(true);
const dashed = ref(false);
const thick = ref(false);
let resolver: ((v: EdgeStyle | null) => void) | null = null;

function openDialog(initial: EdgeStyle): Promise<EdgeStyle | null> {
  label.value = initial.label;
  color.value = initial.color;
  fromArrow.value = initial.fromArrow;
  toArrow.value = initial.toArrow;
  dashed.value = initial.dashed;
  thick.value = initial.thick;
  open.value = true;
  return new Promise((res) => {
    resolver = res;
  });
}

function finish(v: EdgeStyle | null): void {
  open.value = false;
  const r = resolver;
  resolver = null;
  r?.(v);
}

function submit(): void {
  finish({
    label: label.value.trim(),
    color: color.value,
    fromArrow: fromArrow.value,
    toArrow: toArrow.value,
    dashed: dashed.value,
    thick: thick.value,
  });
}

function onToggle(v: boolean): void {
  if (!v && resolver) finish(null);
}

defineExpose({ open: openDialog });
</script>

<template>
  <VModal
    :model-value="open"
    title="Kante bearbeiten"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-3">
      <VInput v-model="label" label="Label" @keyup.enter="submit" />
      <div>
        <div class="mb-1 text-xs opacity-60">Farbe</div>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="c in COLOR_OPTIONS"
            :key="c.value"
            type="button"
            class="edge-swatch"
            :class="{ 'edge-swatch--active': color === c.value }"
            :style="c.value ? { background: c.value } : { background: '#ffffff' }"
            :title="c.label"
            @click="color = c.value"
          >{{ c.value ? '' : '×' }}</button>
        </div>
      </div>
      <div class="flex flex-wrap gap-4">
        <VCheckbox v-model="fromArrow" label="Pfeil am Start" />
        <VCheckbox v-model="toArrow" label="Pfeil am Ziel" />
        <VCheckbox v-model="dashed" label="Gestrichelt" />
        <VCheckbox v-model="thick" label="Fett" />
      </div>
      <div class="mt-1 flex justify-end gap-2">
        <VButton size="sm" variant="ghost" @click="finish(null)">Abbrechen</VButton>
        <VButton size="sm" variant="primary" @click="submit">OK</VButton>
      </div>
    </div>
  </VModal>
</template>

<style scoped>
.edge-swatch {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid #cbd5e1;
  cursor: pointer;
  font-size: 11px;
  line-height: 1;
  color: #64748b;
}
.edge-swatch--active {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
</style>
