<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { collapseContext, computeLineDiff, diffStats, type DiffRow } from '@/util/lineDiff';
import { DIFF_ROW_PAGE, MAX_DIFF_LINES, diffDisplayLines, lineCount } from './archiveDiff';

/**
 * Unified line diff between two text bodies — an archived version (old)
 * and the current document (new). Unchanged stretches fold into gaps the
 * user can expand; line numbers of both sides stay visible.
 *
 * <p>Large bodies are bounded twice: above {@link MAX_DIFF_LINES} the diff
 * is not computed at all (it runs synchronously), and of the computed diff
 * only a page of rows is rendered, with "show more" for the rest.
 */
const props = defineProps<{
  oldText: string;
  newText: string;
}>();

const totalLines = computed(() => lineCount(props.oldText) + lineCount(props.newText));
const tooLarge = computed(() => totalLines.value > MAX_DIFF_LINES);

const rows = computed<DiffRow[]>(() =>
  tooLarge.value ? [] : computeLineDiff(props.oldText, props.newText));
const stats = computed(() => diffStats(rows.value));
const blocks = computed(() => collapseContext(rows.value));

// Indices of gaps the user expanded, and how many lines are rendered.
// Reset whenever the inputs change — block indices of a different diff
// mean different stretches.
const expanded = ref<Set<number>>(new Set());
const limit = ref(DIFF_ROW_PAGE);
watch(blocks, () => {
  expanded.value = new Set();
  limit.value = DIFF_ROW_PAGE;
});

const lines = computed(() => diffDisplayLines(blocks.value, expanded.value));
const visibleLines = computed(() => lines.value.slice(0, limit.value));
const hiddenCount = computed(() => Math.max(0, lines.value.length - limit.value));
const nextCount = computed(() => Math.min(hiddenCount.value, DIFF_ROW_PAGE));

function expand(index: number): void {
  expanded.value = new Set(expanded.value).add(index);
}

function showMore(): void {
  limit.value += DIFF_ROW_PAGE;
}

function sign(kind: 'same' | 'add' | 'del'): string {
  if (kind === 'add') return '+';
  if (kind === 'del') return '-';
  return ' ';
}
</script>

<template>
  <p v-if="tooLarge" class="text-sm text-(--v-label-secondary)">
    {{ $t('documents.archives.diffTooLarge', { lines: totalLines, max: MAX_DIFF_LINES }) }}
  </p>
  <div v-else class="flex flex-col gap-2 min-h-0">
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
          <template v-for="line in visibleLines" :key="line.key">
            <tr v-if="line.type === 'gap'" class="diff-gap">
              <td colspan="4" class="px-2">
                <button
                  type="button"
                  class="w-full text-left py-0.5 text-(--v-label-secondary) hover:underline cursor-pointer"
                  @click="expand(line.block)"
                >
                  {{ $t('documents.archives.diffShowUnchanged', { count: line.count }) }}
                </button>
              </td>
            </tr>
            <tr v-else :class="`diff-${line.row.kind}`">
              <td class="diff-no">{{ line.row.oldNo ?? '' }}</td>
              <td class="diff-no">{{ line.row.newNo ?? '' }}</td>
              <td class="diff-sign">{{ sign(line.row.kind) }}</td>
              <td class="diff-text">{{ line.row.text }}</td>
            </tr>
          </template>
          <tr v-if="hiddenCount > 0" class="diff-gap">
            <td colspan="4" class="px-2">
              <button
                type="button"
                class="w-full text-left py-0.5 text-(--v-label-secondary) hover:underline cursor-pointer"
                @click="showMore"
              >
                {{ $t('documents.archives.diffShowMore', { count: nextCount, total: hiddenCount }) }}
              </button>
            </td>
          </tr>
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
