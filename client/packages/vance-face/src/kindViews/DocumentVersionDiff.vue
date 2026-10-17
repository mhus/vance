<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { collapseContext, computeLineDiff, diffStats } from '@/util/lineDiff';

/**
 * Unified line diff between two text bodies — an archived version (old)
 * and the current document (new). Unchanged stretches fold into gaps the
 * user can expand; line numbers of both sides stay visible.
 */
const props = defineProps<{
  oldText: string;
  newText: string;
}>();

const rows = computed(() => computeLineDiff(props.oldText, props.newText));
const stats = computed(() => diffStats(rows.value));
const blocks = computed(() => collapseContext(rows.value));

// Indices of gaps the user expanded. Reset whenever the inputs change —
// block indices of a different diff mean different stretches.
const expanded = ref<Set<number>>(new Set());
watch(blocks, () => {
  expanded.value = new Set();
});

function expand(index: number): void {
  expanded.value = new Set(expanded.value).add(index);
}

function sign(kind: 'same' | 'add' | 'del'): string {
  if (kind === 'add') return '+';
  if (kind === 'del') return '-';
  return ' ';
}
</script>

<template>
  <div class="flex flex-col gap-2 min-h-0">
    <div class="flex items-center gap-3 text-xs">
      <span class="diff-stat-add font-mono">+{{ stats.added }}</span>
      <span class="diff-stat-del font-mono">-{{ stats.removed }}</span>
      <span v-if="stats.added === 0 && stats.removed === 0" class="text-(--v-label-secondary)">
        {{ $t('documents.archives.diffIdentical') }}
      </span>
    </div>

    <div
      class="overflow-auto border border-(--v-separator) rounded-(--v-radius-control)
             font-mono text-xs leading-5"
    >
      <table class="diff-table w-full border-collapse">
        <tbody>
          <template v-for="(block, bi) in blocks" :key="bi">
            <tr v-if="block.type === 'gap' && !expanded.has(bi)" class="diff-gap">
              <td colspan="4" class="px-2">
                <button
                  type="button"
                  class="w-full text-left py-0.5 text-(--v-label-secondary) hover:underline cursor-pointer"
                  @click="expand(bi)"
                >
                  {{ $t('documents.archives.diffShowUnchanged', { count: block.rows.length }) }}
                </button>
              </td>
            </tr>
            <template v-else>
              <tr v-for="(row, ri) in block.rows" :key="`${bi}-${ri}`" :class="`diff-${row.kind}`">
                <td class="diff-no">{{ row.oldNo ?? '' }}</td>
                <td class="diff-no">{{ row.newNo ?? '' }}</td>
                <td class="diff-sign">{{ sign(row.kind) }}</td>
                <td class="diff-text">{{ row.text }}</td>
              </tr>
            </template>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.diff-table td {
  vertical-align: top;
}
.diff-no {
  width: 1%;
  padding: 0 0.5rem;
  text-align: right;
  white-space: nowrap;
  user-select: none;
  color: var(--v-label-tertiary);
  border-right: 1px solid var(--v-separator);
}
.diff-sign {
  width: 1%;
  padding: 0 0.25rem 0 0.5rem;
  user-select: none;
}
.diff-text {
  padding-right: 0.5rem;
  white-space: pre-wrap;
  word-break: break-word;
}
.diff-add {
  background: color-mix(in srgb, var(--v-success) 14%, transparent);
}
.diff-add .diff-sign {
  color: var(--v-success);
}
.diff-del {
  background: color-mix(in srgb, var(--v-danger) 14%, transparent);
}
.diff-del .diff-sign {
  color: var(--v-danger);
}
.diff-gap {
  background: var(--v-fill);
}
.diff-stat-add {
  color: var(--v-success);
}
.diff-stat-del {
  color: var(--v-danger);
}
</style>
