<script setup lang="ts">
import { computed, ref } from 'vue';
import { VButton, VCheckbox, VInput, VModal } from '@vance/components';
import { useT } from './i18n';

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

const t = useT();

// Value → key, translated in a computed: the swatch titles have to follow a
// language switch, and a module-level array of literals cannot.
const COLOR_OPTIONS: { value: string; key: string }[] = [
  { value: '', key: 'default' },
  { value: '#ef4444', key: 'red' },
  { value: '#f97316', key: 'orange' },
  { value: '#eab308', key: 'yellow' },
  { value: '#22c55e', key: 'green' },
  { value: '#3b82f6', key: 'blue' },
  { value: '#8b5cf6', key: 'purple' },
  { value: '#64748b', key: 'grey' },
];

const colorOptions = computed(() =>
  COLOR_OPTIONS.map((c) => ({ value: c.value, label: t(`canvas.edge.colours.${c.key}`) })),
);

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
    :title="t('canvas.edge.title')"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-3">
      <VInput v-model="label" :label="t('canvas.edge.label')" @keyup.enter="submit" />
      <div>
        <div class="mb-1 text-xs opacity-60">{{ t('canvas.edge.colour') }}</div>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="c in colorOptions"
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
        <VCheckbox v-model="fromArrow" :label="t('canvas.edge.arrowStart')" />
        <VCheckbox v-model="toArrow" :label="t('canvas.edge.arrowEnd')" />
        <VCheckbox v-model="dashed" :label="t('canvas.edge.dashed')" />
        <VCheckbox v-model="thick" :label="t('canvas.edge.thick')" />
      </div>
      <div class="mt-1 flex justify-end gap-2">
        <VButton size="sm" variant="ghost" @click="finish(null)">{{ t('canvas.common.cancel') }}</VButton>
        <VButton size="sm" variant="primary" @click="submit">{{ t('canvas.common.ok') }}</VButton>
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
